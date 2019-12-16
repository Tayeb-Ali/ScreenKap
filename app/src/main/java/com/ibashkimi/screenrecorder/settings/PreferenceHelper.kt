/*
 * Copyright (C) 2019 Indrit Bashkimi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibashkimi.screenrecorder.settings

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.preference.PreferenceManager
import com.ibashkimi.screenrecorder.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.*

@ExperimentalCoroutinesApi
class PreferenceHelper(private val context: Context,
                       val sharedPreferences: SharedPreferences =
                               PreferenceManager.getDefaultSharedPreferences(context)) {

    @Suppress("unused")
    val nightModeLiveData: LiveData<String> by lazy {
        createPreferenceLiveData(sharedPreferences, context.getString(R.string.pref_key_dark_theme)) { _, _ ->
            nightMode
        }
    }

    var isFirstTime: Boolean
        get() = sharedPreferences.getBoolean("is_first_time", true)
        set(value) {
            sharedPreferences.edit { putBoolean("is_first_time", value) }
        }

    val displayMetrics: DisplayMetrics by lazy {
        val metrics = DisplayMetrics()
        val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        window.defaultDisplay.getRealMetrics(metrics)
        metrics
    }

    var videoEncoder: Int
        get() {
            return when (getString(R.string.pref_key_video_encoder) ?: "default") {
                "default" -> MediaRecorder.VideoEncoder.DEFAULT
                "H264" -> MediaRecorder.VideoEncoder.H264
                "HEVC" -> when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> MediaRecorder.VideoEncoder.HEVC
                    else -> {
                        // Fallback to default value to avoid crash
                        putString(R.string.pref_key_video_encoder, "default")
                        throw IllegalArgumentException("HEVC codec requires Android N.")
                    }
                }
                "VP8" -> MediaRecorder.VideoEncoder.VP8
                else -> MediaRecorder.VideoEncoder.H264
            }
        }
        set(value) {
            putString(R.string.pref_key_video_encoder, value)
        }

    var videoBitrate: Int
        get() = getString(R.string.pref_key_video_bitrate)?.toIntOrNull() ?: 8388608
        set(value) {
            putString(
                    R.string.pref_key_video_bitrate, value)
        }
    var fps: Int
        get() = getString(R.string.pref_key_fps)?.toIntOrNull() ?: 30
        set(value) {
            putString(R.string.pref_key_fps, value)
        }

    var videoWidth: Int
        get() {
            val deviceWidth = displayMetrics.widthPixels
            return getString(R.string.pref_key_resolution)?.toIntOrNull() ?: deviceWidth
        }
        set(value) {
            putString(R.string.pref_key_resolution, value)
        }

    private fun initResolutionPreference() {
        val realDisplayMetrics = DisplayMetrics()
        val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        window.defaultDisplay.getRealMetrics(realDisplayMetrics)
        val resolutionValues = context.resources.getStringArray(R.array.resolution_values)
                .filter { it.toInt() <= realDisplayMetrics.widthPixels }
        videoWidth = resolutionValues.lastOrNull()?.toInt() ?: displayMetrics.widthPixels
    }

    val resolution: Pair<Int, Int>
        get() {
            val width = videoWidth
            val height: Int = (width * getAspectRatio(displayMetrics)).toInt()
            val orientationPrefs = sharedPreferences.getString(context.getString(R.string.pref_key_orientation), "auto")
            val screenOrientation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val (finalWidth, finalHeight) = when (orientationPrefs) {
                "auto" -> {
                    if (screenOrientation == Surface.ROTATION_0 || screenOrientation == Surface.ROTATION_180) {
                        Pair(width, height)
                    } else {
                        Pair(height, width)
                    }
                }
                "portrait" -> Pair(width, height)
                "landscape" -> Pair(height, width)
                else -> Pair(width, height)
            }
            return Pair(finalWidth, finalHeight)
        }

    var baseFilename: String
        get() {
            return getString(R.string.pref_key_filename) ?: "yyyyMMdd_hhmmss"
        }
        set(value) {
            putString(R.string.pref_key_filename, value)
        }

    var prefixFilename: String
        get() {
            return getString(R.string.pref_key_file_prefix) ?: "REC"
        }
        set(value) {
            putString(R.string.pref_key_file_prefix, value)
        }

    val filename: String
        get() {
            val formatter = SimpleDateFormat(baseFilename, Locale.getDefault())
            val userPrefix = prefixFilename
            val prefix = when {
                userPrefix.isBlank() -> ""
                userPrefix.endsWith("_") -> userPrefix
                else -> userPrefix + "_"
            }
            return prefix + formatter.format(Calendar.getInstance().time)
        }

    val saveLocation: SaveUri?
        get() {
            getString(R.string.pref_key_save_location)?.let { Uri.parse(it) }?.let { uri ->
                getString(R.string.pref_key_save_location_type)?.let { type ->
                    val uriType = when (type) {
                        "media_store" -> UriType.MEDIA_STORE
                        "saf" -> UriType.SAF
                        else -> throw IllegalArgumentException("Unknown uri type $type.")
                    }
                    return SaveUri(uri, uriType)
                }
            } ?: return null
        }

    val saveLocationLiveData: LiveData<SaveUri?> by lazy {
        PreferenceLiveData(sharedPreferences, context.getString(R.string.pref_key_save_location), true) {
            saveLocation
        }
    }

    val saveLocationFlow: Flow<SaveUri?> =
            preferenceFlow(context.getString(R.string.pref_key_save_location), true) {
                saveLocation
            }

    fun setSaveLocation(uri: Uri?, uriType: UriType?) {
        sharedPreferences.edit()
                .putString(context.getString(R.string.pref_key_save_location), uri.toString())
                .putString(context.getString(R.string.pref_key_save_location_type), uriType?.name?.toLowerCase(Locale.ENGLISH))
                .apply()
    }

    fun resetSaveLocation() {
        if (Build.VERSION.SDK_INT < 29) {
            setSaveLocation(null, null)
        } else {
            setSaveLocation(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, UriType.MEDIA_STORE)
        }
    }

    var nightMode: String
        get() = getString(R.string.pref_key_dark_theme) ?: "system_default"
        set(value) {
            putString(R.string.pref_key_dark_theme, value)
        }

    var sortBy: SortBy
        get() {
            val sortBy = sharedPreferences.getString(KEY_SORT_BY, null) ?: SortBy.DATE.name
            return SortBy.valueOf(sortBy)
        }
        set(value) {
            sharedPreferences.edit().putString(KEY_SORT_BY, value.name).apply()
        }

    var orderBy: OrderBy
        get() {
            val orderBy = sharedPreferences.getString(KEY_ORDER_BY, null) ?: OrderBy.DESCENDING.name
            return OrderBy.valueOf(orderBy)
        }
        set(value) {
            sharedPreferences.edit().putString(KEY_ORDER_BY, value.name).apply()
        }

    val sortOrderOptionsFlow: Flow<SortOrderOptions>
        get() = callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                android.util.Log.d("PreferenceHelper", "preferences changed")
                offer(SortOrderOptions(sortBy, orderBy))
            }
            offer(SortOrderOptions(sortBy, orderBy))
            android.util.Log.d("PreferenceHelper", "offering options")
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
            awaitClose {
                android.util.Log.d("PreferenceHelper", "unregistering listener")
                sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }


    var recordAudio: Boolean
        get() = getBoolean(R.string.pref_key_audio)
        set(value) {
            sharedPreferences.edit {
                putBoolean(getString(R.string.pref_key_audio), value)
            }
        }

    var audioBitrate: Int
        get() = getString(R.string.pref_key_audio_bit_rate)?.toIntOrNull() ?: 1280000
        set(value) {
            putString(R.string.pref_key_audio_bit_rate, value)
        }

    var audioSamplingRate: Int
        get() = getString(R.string.pref_key_audio_sampling_rate)?.toIntOrNull() ?: 44100
        set(value) {
            putString(R.string.pref_key_audio_sampling_rate, value.toString())
        }

    var audioEncoder: Int
        get() {
            return when (getString(R.string.pref_key_audio_encoder) ?: "default") {
                "default" -> MediaRecorder.AudioEncoder.DEFAULT
                "aac" -> MediaRecorder.AudioEncoder.AAC
                "opus" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.AudioEncoder.OPUS
                } else {
                    // Fallback to default value to avoid crash
                    putString(R.string.pref_key_audio_encoder, "default")
                    throw java.lang.IllegalArgumentException("Opus codec requires Android Q.")
                }
                "vorbis" -> MediaRecorder.AudioEncoder.VORBIS
                else -> MediaRecorder.AudioEncoder.AAC
            }
        }
        set(value) {
            putString(R.string.pref_key_audio_encoder, value)
        }

    fun initIfFirstTimeAnd(doAlso: () -> Unit = {}) {
        if (isFirstTime) {
            initResolutionPreference()
            resetSaveLocation()
            doAlso()
            isFirstTime = false
        }
    }

    private fun getAspectRatio(metrics: DisplayMetrics): Float {
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        return if (width > height) {
            width / height
        } else {
            height / width
        }
    }

    private fun getString(stringRes: Int): String? {
        return sharedPreferences.getString(context.getString(stringRes), null)
    }

    private fun putString(stringRes: Int, value: Int) {
        putString(stringRes, value.toString())
    }

    private fun putString(stringRes: Int, value: String?) {
        sharedPreferences.edit().putString(context.getString(stringRes), value).apply()
    }

    private fun getBoolean(stringRes: Int, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(context.getString(stringRes), defaultValue)
    }

    enum class SortBy {
        NAME, DATE, DURATION, SIZE
    }

    enum class OrderBy {
        ASCENDING, DESCENDING
    }

    data class SortOrderOptions(val sortBy: SortBy, val orderBy: OrderBy)

    data class SaveUri(val uri: Uri, val type: UriType)

    enum class UriType {
        MEDIA_STORE, SAF
    }

    companion object {
        const val KEY_SORT_BY = "sort_by"
        const val KEY_ORDER_BY = "order_by"
    }
}
