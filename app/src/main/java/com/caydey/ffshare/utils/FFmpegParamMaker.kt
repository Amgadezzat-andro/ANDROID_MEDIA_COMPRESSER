package com.caydey.ffshare.utils

import android.net.Uri
import com.arthenica.ffmpegkit.MediaInformation
import timber.log.Timber
import java.util.StringJoiner
import kotlin.math.ceil
import kotlin.math.pow

class FFmpegParamMaker(val settings: Settings, val utils: Utils) {
    fun create(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType, useHardware: Boolean = false): String {
        // custom params
        if (utils.isImage(outputMediaType) && settings.customImageParams.isNotEmpty()) return settings.customImageParams
        if (utils.isVideo(outputMediaType) && settings.customVideoParams.isNotEmpty()) return settings.customVideoParams
        if (utils.isAudio(outputMediaType) && settings.customAudioParams.isNotEmpty()) return settings.customAudioParams

        val params = StringJoiner(" ")
        val videoFormatParams = StringJoiner(",")

//        val (inputVideoCodec, inputAudioCodec) = getMediaCodecs(mediaInformation)

        // the phone's own video encoder, many times faster than x264/x265 but has no crf or preset
        val hardwareEncoder = if (useHardware && utils.isVideo(outputMediaType) && outputMediaType != Utils.MediaType.WEBM) {
            when (settings.videoCodec) {
                Settings.VideoCodecOpts.H265 -> HEVC_HARDWARE_ENCODER
                Settings.VideoCodecOpts.H264 -> H264_HARDWARE_ENCODER
                else -> null
            }
        } else null

        // preset, webp and hardware encoders do not support this
        if (outputMediaType != Utils.MediaType.WEBP && hardwareEncoder == null) {
            params.add("-preset ${settings.compressionPreset}")
        }

        if (utils.isAudio(mediaType) || utils.isVideo(mediaType)) {
            // add audio codec
            if (settings.audioCodec != Settings.AudioCodecOpts.DEFAULT) {
                params.add("-c:a ${settings.audioCodec.raw}")
            }
        }

        var videoScaleApplied = false
        // output size, the hardware bitrate is based on it
        var outputWidth = 0
        var outputHeight = 0
        // videos and images max resolution
        if (utils.isVideo(mediaType) || utils.isImage(mediaType)) {
            val (resolutionWidth, resolutionHeight) = utils.getMediaResolution(inputFile, mediaType)
            outputWidth = resolutionWidth
            outputHeight = resolutionHeight
            val isPortrait = resolutionHeight > resolutionWidth
            val resolution = if (isPortrait) resolutionWidth else resolutionHeight
            val maxResolution = if (utils.isImage(mediaType)) settings.maxImageResolution else settings.maxVideoResolution
            // only reduce resolution
            if (resolution > maxResolution && maxResolution != 0) {
                videoScaleApplied = true
                if (isPortrait) {
                    outputHeight = resolutionHeight * maxResolution / resolutionWidth
                    outputWidth = maxResolution
                } else {
                    outputWidth = resolutionWidth * maxResolution / resolutionHeight
                    outputHeight = maxResolution
                }
                var pixelRounding = "-1"
                // H.26x videos require dimensions to be divisible by 2
                if (utils.isVideo(outputMediaType) && settings.videoCodec in setOf(Settings.VideoCodecOpts.H264, Settings.VideoCodecOpts.H265)) {
                    pixelRounding = "-2"
                }
                if (isPortrait) {
                    videoFormatParams.add("scale=$maxResolution:$pixelRounding,setsar=1")
                } else {
                    videoFormatParams.add("scale=$pixelRounding:$maxResolution,setsar=1")
                }
            }
        }

        // video
        if (utils.isVideo(outputMediaType)) { // check outputMediaType not mediaType because conversions
            // pixel format
            videoFormatParams.add("format=yuv420p")

            // video codec
            if (hardwareEncoder != null) {
                params.add("-c:v $hardwareEncoder")
            } else {
                params.add("-crf ${settings.videoCrf}")
                if (settings.videoCodec != Settings.VideoCodecOpts.DEFAULT) {
                    params.add("-c:v ${settings.videoCodec.raw}")
                }
            }
            var maxVideoBitrate = Int.MAX_VALUE

            // hvc1 tag so H.265 mp4s play in galleries and on iOS (default hev1 tag often does not)
            if (settings.videoCodec == Settings.VideoCodecOpts.H265 && outputMediaType == Utils.MediaType.MP4) {
                params.add("-tag:v hvc1")
            }

            if (hardwareEncoder != null) {
                // hardware encoders work in 16px blocks. for other sizes (1080 = 67.5 blocks) ffmpeg adds an
                // hevc_metadata crop step that fails on qualcomm's empty first packet ("No NAL unit found in
                // packet"), so trim to a multiple of 16 up front: 1080 -> 1072, 4px off top and bottom
                videoFormatParams.add("crop=trunc(iw/16)*16:trunc(ih/16)*16")
            } else if (!videoScaleApplied) {
                // H.26x requires dimensions to be divisible by 2, video scaling will account for this if applied
                val stream = mediaInformation.getStreams()[0]
                if (stream.getWidth()?.rem(2) != 0L || stream.getHeight()?.rem(2) != 0L) {
                    videoFormatParams.add("crop=trunc(iw/2)*2:trunc(ih/2)*2")
                    // could also use "pad=ceil(iw/2)*2:ceil(ih/2)*2" to add column/row of black pixels
                }
            }

            //  max file size (limit the bitrate to achieve this)
            if (settings.videoMaxFileSize != 0) {
                // ffmpeg does not like -maxrate & -bufsize params when the output file is webm
                if (outputMediaType != Utils.MediaType.WEBM) {
                    // Calculate maximum bitrate in kbps.
                    // Ceil duration to ensure the maximum is strict. toInt to floor result, ffmpeg takes ints.
                    val maxBitrate = (settings.videoMaxFileSize * 8 / ceil(mediaInformation.getDuration()?.toFloat() ?: 0f)).toInt()
                    Timber.d("Maximum bitrate for targeted filesize (%dK): %dk", settings.videoMaxFileSize, maxBitrate)

                    // audio can have at most one third of the total bitrate
                    val audioSplit = maxBitrate / 3
                    // round audio bitrate down to 192,128,96,64,32,24
                    val audioBitrate = if (audioSplit > 192) 192 // maximum audio bitrate is 192k
                    else if (audioSplit > 128) 128
                    else if (audioSplit > 96) 96
                    else if (audioSplit > 64) 64
                    else if (audioSplit > 32) 32
                    else 24 // minimum audio bitrate is 24k

                    // set audio bitrate
                    params.add("-b:a ${audioBitrate}k")

                    // set max video bitrate
                    val videoBitrate = (maxBitrate - audioBitrate)
                    maxVideoBitrate = videoBitrate
                    if (hardwareEncoder == null) {
                        params.add("-maxrate ${videoBitrate}k -bufsize ${videoBitrate}k")
                    }
                }
            }

            if (hardwareEncoder != null) {
                val bitrate = minOf(hardwareBitrateKbps(outputWidth, outputHeight, mediaInformation), maxVideoBitrate.toLong())
                params.add("-b:v ${bitrate}k")
                // a keyframe every 2 seconds instead of mediacodec's default 1, fewer keyframes = smaller file
                params.add("-g ${(videoFps(mediaInformation) * 2).toInt()}")
            }
        }

        if (videoFormatParams.length() > 0) {
            params.add("-vf \"$videoFormatParams\"")
        }

        // jpeg quality
        if (outputMediaType == Utils.MediaType.JPEG || outputMediaType == Utils.MediaType.JPG) {
            params.add("-qscale:v ${settings.jpegQscale}")
        }

        // webp quality
        if (outputMediaType == Utils.MediaType.WEBP) {
            params.add("-quality ${settings.webpQuality}")
        }

        return params.toString()
    }

    // hardware encoders have no crf, so turn the crf setting into a bitrate for the output size.
    // bits per pixel roughly matches x265 at crf 23 and halves every +6 crf, the same way crf behaves
    private fun hardwareBitrateKbps(width: Int, height: Int, mediaInformation: MediaInformation): Long {
        val stream = mediaInformation.getStreams().firstOrNull { it.getType()?.lowercase() == "video" }
        val fps = videoFps(mediaInformation)
        val pixels = if (width > 0 && height > 0) width.toLong() * height else 1920L * 1080
        var bitsPerPixel = 0.12 * 2.0.pow((23 - settings.videoCrf) / 6.0)
        if (settings.videoCodec == Settings.VideoCodecOpts.H264) {
            bitsPerPixel *= 1.5 // H.264 needs about 50% more bits than H.265 for the same quality
        }
        var kbps = (pixels * fps * bitsPerPixel / 1000).toLong()

        // never ask for more than the original has, the "compressed" file would only get bigger
        val sourceKbps = (stream?.getBitrate() ?: mediaInformation.getBitrate())?.toLongOrNull()?.div(1000)
        if (sourceKbps != null && sourceKbps > 0) {
            kbps = minOf(kbps, sourceKbps * 85 / 100)
        }
        return maxOf(kbps, 300L)
    }

    private fun videoFps(mediaInformation: MediaInformation): Double {
        val stream = mediaInformation.getStreams().firstOrNull { it.getType()?.lowercase() == "video" }
        return parseFrameRate(stream?.getAverageFrameRate())?.coerceIn(1.0, 120.0) ?: 30.0
    }

    // ffprobe gives frame rates as fractions like "30000/1001"
    private fun parseFrameRate(frameRate: String?): Double? {
        if (frameRate == null) return null
        val parts = frameRate.split("/")
        val numerator = parts[0].toDoubleOrNull() ?: return null
        val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (denominator > 0 && numerator > 0) numerator / denominator else null
    }

    companion object {
        const val HEVC_HARDWARE_ENCODER = "hevc_mediacodec"
        const val H264_HARDWARE_ENCODER = "h264_mediacodec"
    }

    private fun getMediaCodecs(mediaInformation: MediaInformation): Pair<Settings.VideoCodecOpts?, Settings.AudioCodecOpts?> {
        var audioCodec: Settings.AudioCodecOpts? = null
        var videoCodec: Settings.VideoCodecOpts? = null
        for (stream in mediaInformation.getStreams()) {
            if (stream.getType()?.lowercase() == "video") {
                videoCodec = Settings.VideoCodecOpts.parseCodec(stream.getCodec());
            } else if (stream.getType()?.lowercase() == "audio") {
                audioCodec = Settings.AudioCodecOpts.parseCodec((stream.getCodec()))
            }
        }
        return Pair(videoCodec, audioCodec)
    }
}
