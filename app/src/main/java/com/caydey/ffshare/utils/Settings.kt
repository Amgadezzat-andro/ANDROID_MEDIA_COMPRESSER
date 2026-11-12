package com.caydey.ffshare.utils

import android.content.Context
import android.content.SharedPreferences

import androidx.preference.PreferenceManager

class Settings(private val context: Context) {
    enum class CompressedMediaNameOpts {
        ORIGINAL,
        UUID,
        CUSTOM
    }

    enum class VideoCodecOpts(val raw: String) {
        COPY("copy"),
        DEFAULT(""),
        H264("libx264"),
        H265("libx265"),
        VP9("libvpx-vp9"),
        MPEG4("mpeg4");
        companion object {
            fun parseCodec(codec: String?): VideoCodecOpts {
                return when (codec) {
                    "h264" -> H264
                    "hevc" -> H265
                    "vp9" -> VP9
                    "mpeg4" -> MPEG4
                    else -> DEFAULT
                }
            }
        }
    }

    enum class AudioCodecOpts(val raw: String) {
        COPY("copy"),
        DEFAULT(""),
        AAC("aac"),
        AC3("ac3"),
        OPUS("libopus"),
        FLAC("flac");
        companion object {
            fun parseCodec(codec: String?): AudioCodecOpts {
                return when (codec) {
                    "aac" -> AAC
                    "ac3" -> AC3
                    "opus" -> OPUS
                    "flac" -> FLAC
                    else -> DEFAULT
                }
            }
        }
    }

    // one-tap presets, each one overwrites the compression settings below. the photo format (webp or
    // the original's) is a choice of its own, so every preset sets both a webp and a jpeg quality
    enum class QualityPreset(val values: Map<String, String>) {
        ULTRA(mapOf(
            VIDEO_CODEC to VideoCodecOpts.H265.name,
            VIDEO_CRF to "38",
            COMPRESSION_PRESET to "slow",
            MAX_VIDEO_RESOLUTION to "480",
            WEBP_QUALITY to "40",
            JPEG_QSCALE to "20",
            MAX_IMAGE_RESOLUTION to "720"
        )),
        MAX_COMPRESSION(mapOf(
            VIDEO_CODEC to VideoCodecOpts.H265.name,
            VIDEO_CRF to "32",
            COMPRESSION_PRESET to "slow",
            MAX_VIDEO_RESOLUTION to "720",
            WEBP_QUALITY to "60",
            JPEG_QSCALE to "15",
            MAX_IMAGE_RESOLUTION to "1080"
        )),
        BALANCED(mapOf(
            VIDEO_CODEC to VideoCodecOpts.H265.name,
            VIDEO_CRF to "28",
            COMPRESSION_PRESET to "medium",
            MAX_VIDEO_RESOLUTION to "1080",
            WEBP_QUALITY to "75",
            JPEG_QSCALE to "6",
            MAX_IMAGE_RESOLUTION to "1440"
        )),
        HIGH_QUALITY(mapOf(
            VIDEO_CODEC to VideoCodecOpts.H265.name,
            VIDEO_CRF to "23",
            COMPRESSION_PRESET to "medium",
            MAX_VIDEO_RESOLUTION to "0",
            WEBP_QUALITY to "90",
            JPEG_QSCALE to "2",
            MAX_IMAGE_RESOLUTION to "0"
        )),
        CUSTOM(emptyMap())
    }

    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    // the look of the app, see AppTheme
    val appTheme: com.caydey.ffshare.AppTheme
        get() = try {
            com.caydey.ffshare.AppTheme.valueOf(preferences.getString(APP_THEME, null) ?: com.caydey.ffshare.AppTheme.NOTHING.name)
        } catch (e: IllegalArgumentException) {
            com.caydey.ffshare.AppTheme.NOTHING
        }

    var qualityPreset: QualityPreset
        get() = QualityPreset.valueOf(preferences.getString(QUALITY_PRESET, QualityPreset.CUSTOM.name)!!)
        set(value) = setPreference(QUALITY_PRESET, value.name)

    val isQualityPresetSet: Boolean
        get() = preferences.contains(QUALITY_PRESET)

    fun applyQualityPreset(preset: QualityPreset) {
        val editor = preferences.edit()
        preset.values.forEach { (key, value) -> editor.putString(key, value) }
        editor.putString(QUALITY_PRESET, preset.name)
        editor.apply()
    }

    // SAF tree uri of the folder compressed files are saved to, empty means share instead
    var saveFolderUri: String
        get() = preferences.getString(SAVE_FOLDER_URI, "")!!
        set(value) = setPreference(SAVE_FOLDER_URI, value)

    // don't save a "compressed" file that came out bigger, the original stays where it is
    var keepOriginalIfBigger: Boolean
        get() = preferences.getBoolean(KEEP_ORIGINAL_IF_BIGGER, true)
        set(value) = setPreference(KEEP_ORIGINAL_IF_BIGGER, value)

    var onboardingDone: Boolean
        get() = preferences.getBoolean(ONBOARDING_DONE, false)
        set(value) = setPreference(ONBOARDING_DONE, value)

    // asked once for photo locations before the first batch, either answer is final
    var mediaLocationAsked: Boolean
        get() = preferences.getBoolean(MEDIA_LOCATION_ASKED, false)
        set(value) = setPreference(MEDIA_LOCATION_ASKED, value)

    // settings screen shows only the basics until this is turned on
    var showAdvancedSettings: Boolean
        get() = preferences.getBoolean(SHOW_ADVANCED_SETTINGS, false)
        set(value) = setPreference(SHOW_ADVANCED_SETTINGS, value)

    // use the phone's video encoder, falls back to x264/x265 automatically if the phone can't
    var hardwareVideo: Boolean
        get() = preferences.getBoolean(HARDWARE_VIDEO, true)
        set(value) = setPreference(HARDWARE_VIDEO, value)

    // lifetime savings shown on the main screen
    val statsFiles: Int
        get() = preferences.getInt(STATS_FILES, 0)
    val statsInputBytes: Long
        get() = preferences.getLong(STATS_INPUT_BYTES, 0)
    val statsOutputBytes: Long
        get() = preferences.getLong(STATS_OUTPUT_BYTES, 0)

    fun addToStats(inputBytes: Long, outputBytes: Long) {
        preferences.edit()
            .putInt(STATS_FILES, statsFiles + 1)
            .putLong(STATS_INPUT_BYTES, statsInputBytes + inputBytes)
            .putLong(STATS_OUTPUT_BYTES, statsOutputBytes + outputBytes)
            .apply()
    }

    var webpQuality: Int
        get() = preferences.getString(WEBP_QUALITY, "75")!!.toInt()
        set(value) = setPreference(WEBP_QUALITY, value.toString())

    var compressedMediaName: CompressedMediaNameOpts
        get() {
            // 2nd param is the default value
            val preferencesString = preferences.getString(COMPRESSED_MEDIA_NAME, CompressedMediaNameOpts.ORIGINAL.name)!!
            return CompressedMediaNameOpts.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(COMPRESSED_MEDIA_NAME, value.toString())

    var compressedMediaCustomName: String
        get() = preferences.getString(COMPRESSED_MEDIA_CUSTOM_NAME, "")!!
        set(value) = setPreference(COMPRESSED_MEDIA_CUSTOM_NAME, value)

    var videoConversionOutput: Utils.MediaType // UNKNOWN for original
        get() {
            val preferencesString = preferences.getString(CONVERT_VIDEO_OUTPUT, Utils.MediaType.UNKNOWN.name)!!
            return Utils.MediaType.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(COMPRESSED_MEDIA_NAME, value.toString())
    var imageConversionOutput: Utils.MediaType // UNKNOWN keeps the original format, webp unless the user picks that
        get() {
            val preferencesString = preferences.getString(CONVERT_IMAGE_OUTPUT, Utils.MediaType.WEBP.name)!!
            return Utils.MediaType.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(CONVERT_IMAGE_OUTPUT, value.toString())
    var audioConversionOutput: Utils.MediaType // UNKNOWN for original
        get() {
            val preferencesString = preferences.getString(CONVERT_AUDIO_OUTPUT, Utils.MediaType.UNKNOWN.name)!!
            return Utils.MediaType.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(CONVERT_AUDIO_OUTPUT, value.toString())

    var treatGifsAsVideos: Boolean
        get() = preferences.getBoolean(TREAT_GIFS_AS_VIDEOS, false)
        set(value) = setPreference(TREAT_GIFS_AS_VIDEOS, value)

    var showStatusMessages: Boolean
        get() = preferences.getBoolean(SHOW_STATUS_MESSAGES, true)
        set(value) = setPreference(SHOW_STATUS_MESSAGES, value)

    var videoCrf: Int
        get() = preferences.getString(VIDEO_CRF, "23")!!.toInt()
        set(value) = setPreference(VIDEO_CRF, value.toString())

    var jpegQscale: Int
        get() = preferences.getString(JPEG_QSCALE, "10")!!.toInt()
        set(value) = setPreference(JPEG_QSCALE, value.toString())

    var videoMaxFileSize: Int
        get() = preferences.getString(VIDEO_MAX_FILE_SIZE, "0")!!.toInt()
        set(value) = setPreference(VIDEO_MAX_FILE_SIZE, value.toString())

    var maxVideoResolution: Int
        get() = preferences.getString(MAX_VIDEO_RESOLUTION, "1080")!!.toInt()
        set(value) = setPreference(MAX_VIDEO_RESOLUTION, value.toString())

    var maxImageResolution: Int
        get() = preferences.getString(MAX_IMAGE_RESOLUTION, "2160")!!.toInt()
        set(value) = setPreference(MAX_IMAGE_RESOLUTION, value.toString())

    var copyExifTags: Boolean
        get() = preferences.getBoolean(COPY_EXIF_TAGS, true) // keeps date taken, otherwise galleries sort compressed photos as "today"
        set(value) = setPreference(COPY_EXIF_TAGS, value)

    var lastVersion: Int
        get() = preferences.getInt(LAST_VERSION, 0)
        set(value) = setPreference(LAST_VERSION, value)

    var saveLogs: Boolean
        get() = preferences.getBoolean(SAVE_LOGS, false) // matches the switch, logs of thousands of files waste storage
        set(value) = setPreference(SAVE_LOGS, value)

    var compressionPreset: String
        get() = preferences.getString(COMPRESSION_PRESET, "medium")!!
        set(value) = setPreference(COMPRESSION_PRESET, value)

    var videoCodec: VideoCodecOpts
        get() {
            val videoCodecString = preferences.getString(VIDEO_CODEC, VideoCodecOpts.H264.name)!!
            return VideoCodecOpts.valueOf(videoCodecString) // convert string to enum
        }
        set(value) = setPreference(VIDEO_CODEC, value.toString())

    var audioCodec: AudioCodecOpts
        get() {
            val audioCodecString = preferences.getString(AUDIO_CODEC, AudioCodecOpts.COPY.name)!!
            return AudioCodecOpts.valueOf(audioCodecString) // convert string to enum
        }
        set(value) = setPreference(AUDIO_CODEC, value.toString())

    var customVideoParams: String
        get() = preferences.getString(CUSTOM_VIDEO_PARAMS, "")!!
        set(value) = setPreference(CUSTOM_VIDEO_PARAMS, value)

    var customImageParams: String
        get() = preferences.getString(CUSTOM_IMAGE_PARAMS, "")!!
        set(value) = setPreference(CUSTOM_IMAGE_PARAMS, value)

    var customAudioParams: String
        get() = preferences.getString(CUSTOM_AUDIO_PARAMS, "")!!
        set(value) = setPreference(CUSTOM_AUDIO_PARAMS, value)

    private fun setPreference(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
    private fun setPreference(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
    private fun setPreference(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    // static variables
    companion object {
        const val COMPRESSED_MEDIA_NAME = "pref_compressed_media_name"
        const val COMPRESSED_MEDIA_CUSTOM_NAME = "pref_compressed_media_custom_name"
        const val CONVERT_VIDEO_OUTPUT = "pref_convert_video_output"
        const val CONVERT_IMAGE_OUTPUT = "pref_convert_image_output"
        const val CONVERT_AUDIO_OUTPUT = "pref_convert_audio_output"
        const val TREAT_GIFS_AS_VIDEOS = "pref_treat_gifs_as_videos"
        const val SHOW_STATUS_MESSAGES = "pref_show_status_messages"
        const val VIDEO_CRF = "pref_video_crf"
        const val JPEG_QSCALE = "pref_jpeg_qscale"
        const val VIDEO_MAX_FILE_SIZE = "pref_video_max_file_size"
        const val MAX_VIDEO_RESOLUTION = "pref_max_video_resolution"
        const val MAX_IMAGE_RESOLUTION = "pref_max_image_resolution"
        const val COPY_EXIF_TAGS = "pref_copy_exif_tags"
        const val LAST_VERSION = "pref_last_version"
        const val SAVE_LOGS = "pref_save_logs"
        const val COMPRESSION_PRESET = "pref_compression_preset"
        const val VIDEO_CODEC = "pref_video_codec"
        const val AUDIO_CODEC = "pref_audio_codec"
        const val CUSTOM_VIDEO_PARAMS = "pref_custom_video_params"
        const val CUSTOM_IMAGE_PARAMS = "pref_custom_image_params"
        const val CUSTOM_AUDIO_PARAMS = "pref_custom_audio_params"
        const val QUALITY_PRESET = "pref_quality_preset"
        const val SAVE_FOLDER_URI = "pref_save_folder_uri"
        const val WEBP_QUALITY = "pref_webp_quality"
        const val KEEP_ORIGINAL_IF_BIGGER = "pref_keep_original_if_bigger"
        const val HARDWARE_VIDEO = "pref_hardware_video"
        const val ONBOARDING_DONE = "onboarding_done"
        const val APP_THEME = "pref_app_theme"
        const val MEDIA_LOCATION_ASKED = "media_location_asked"
        const val SHOW_ADVANCED_SETTINGS = "pref_show_advanced"
        const val STATS_FILES = "stats_files"
        const val STATS_INPUT_BYTES = "stats_input_bytes"
        const val STATS_OUTPUT_BYTES = "stats_output_bytes"
    }
}