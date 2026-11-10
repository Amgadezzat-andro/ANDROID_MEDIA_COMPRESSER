package com.caydey.ffshare.utils

import android.content.Context
import androidx.core.app.ActivityCompat
import com.caydey.ffshare.extensions.mediaCacheDir
import java.io.File
import java.util.*
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.caydey.ffshare.BuildConfig
import com.caydey.ffshare.R
import timber.log.Timber
import java.io.InputStream
import java.text.SimpleDateFormat
import kotlin.math.ln
import kotlin.math.pow


const val MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1000
// MediaStore.PickerMediaColumns.DATE_TAKEN, a constant only from android 13 but the column is there on every picker
private const val PICKER_DATE_TAKEN = "datetaken"
class Utils(private val context: Context) {
    private val settings: Settings by lazy { Settings(context) }

    fun getCacheOutputFile(uri: Uri, mediaType: MediaType): Pair<File, MediaType> {
        val fileExtension = getOutputFileMediaType(mediaType) // some files are converted to different formats e.g. mkv to mp4

        var filename = getRandomFilename(fileExtension); // UUID
        if (settings.compressedMediaName == Settings.CompressedMediaNameOpts.ORIGINAL) {
            // if getFilenameFromUri returns null default to randomFilename
            filename = getFilenameFromUri(uri) ?: getRandomFilename(fileExtension)
            filename = filename.substringBeforeLast(".")+".${fileExtension.name.lowercase()}"
        } else if (settings.compressedMediaName == Settings.CompressedMediaNameOpts.CUSTOM) {
           filename = "${settings.compressedMediaCustomName}.${fileExtension.name.lowercase()}"
        }
        Timber.d("Created output file '%s'", filename)
        val outputFile = File(makeCacheUUIDFolder(), filename)
        return Pair(outputFile, fileExtension)
    }
    private fun getOutputFileMediaType(inputFileMediaType: MediaType): MediaType {
        var outputMediaType: MediaType = MediaType.UNKNOWN
        if (isVideo(inputFileMediaType)) {
            outputMediaType = settings.videoConversionOutput;
        } else if (isImage(inputFileMediaType)) {
            if (settings.treatGifsAsVideos && inputFileMediaType == MediaType.GIF) {
                outputMediaType = settings.videoConversionOutput
            } else {
                outputMediaType = settings.imageConversionOutput
            }
        } else if (isAudio(inputFileMediaType)) {
            outputMediaType = settings.audioConversionOutput
        }

        if (outputMediaType == MediaType.UNKNOWN) {
            outputMediaType = inputFileMediaType;
        }
        return outputMediaType
    }
    private fun makeCacheUUIDFolder(): File {
        val cacheUUIDFolder = File(context.mediaCacheDir, UUID.randomUUID().toString())
        cacheUUIDFolder.mkdirs()
        return cacheUUIDFolder
    }

    private fun getRandomFilename(mediaType: MediaType): String {
        return "${UUID.randomUUID()}.${mediaType.name.lowercase()}"
    }
    fun getFilenameFromUri(uri: Uri): String? {
        var filename: String? = null
        // used when reporting failures, so it must not throw itself
        val queryCursor = try {
            context.contentResolver.query(uri, null, null, null, null)
        } catch (e: Exception) {
            null
        }
        queryCursor?.let { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    filename = cursor.getString(nameIndex)
                }
            }
            cursor.close()
        }
        if (isPhotoPickerUri(uri)) {
            // the photo picker hides real names ("65.mp4"), a camera style name from the date taken is far
            // more useful in the save folder than the picker's number
            filename = pickerFilename(uri, filename) ?: filename
        }
        return filename
    }

    fun isPhotoPickerUri(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull()?.startsWith("picker") == true

    // IMG_20240314_101500.jpg / VID_... from the picker's date taken, null when it has none
    private fun pickerFilename(uri: Uri, pickerName: String?): String? {
        val dateTaken = try {
            context.contentResolver.query(uri, arrayOf(PICKER_DATE_TAKEN, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        if (dateTaken <= 0) return null
        val prefix = if (context.contentResolver.getType(uri)?.startsWith("video/") == true) "VID" else "IMG"
        val extension = pickerName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        return prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(dateTaken)) + extension
    }
    fun getFileSizeFromUri(uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Timber.d("Unable to get size of '%s'", uri)
        }
        return 0
    }

    val isReadPermissionGranted: Boolean
        get() {
            // android 13+ has no such permission any more (always "denied"), shared and picked files come
            // with their own access. asking anyway opens an empty permission screen that restarts ours
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) return true
            val check = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            return (check == PackageManager.PERMISSION_GRANTED)
        }
    fun requestReadPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE)
    }


    private fun getFileHexSignature(inputStream: InputStream): String {
        val arr = ByteArray(16)
        inputStream.read(arr)
        val builder = StringBuilder()
        for (byte in arr) { // foreach byte in head
            val hex = "%02X".format(byte) // format as hex
            builder.append(hex)
        }
        return builder.toString()
    }

    fun getMediaType(uri: Uri): MediaType {
        val filename: String? = getFilenameFromUri(uri)
        var mediaType: MediaType = MediaType.UNKNOWN

        // get type from file extension
        if (filename != null) {
            val extensionsToMediaType = mapOf(
                // images
                "jpg" to MediaType.JPEG,
                "jpeg" to MediaType.JPEG,
                "png" to MediaType.PNG,
                "gif" to MediaType.GIF,
                "webp" to MediaType.WEBP,
                // videos
                "mp4" to MediaType.MP4,
                "mkv" to MediaType.MKV,
                "webm" to MediaType.WEBM,
                "avi" to MediaType.AVI,
                "mov" to MediaType.MP4,
                // audios
                "mp3" to MediaType.MP3,
                "ogg" to MediaType.OGG,
                "aac" to MediaType.AAC,
                "wav" to MediaType.WAV,
                "opus" to MediaType.OPUS,
                "m4a" to MediaType.M4A
            )

            val extension = filename.substringAfterLast('.').lowercase()
            mediaType = extensionsToMediaType[extension] ?: MediaType.UNKNOWN
        }
        // unable to get filetype from filename extension, using signature detection
        // https://en.wikipedia.org/wiki/List_of_file_signatures
        if (mediaType == MediaType.UNKNOWN) {
            Timber.d("unable to find filetype from extension, trying file signature")
            val inputStream = context.contentResolver.openInputStream(uri)
            val signature = getFileHexSignature(inputStream!!)
            inputStream.close()

            if (signature.startsWith("FFD8FF")) {
                mediaType = MediaType.JPEG
            } else if (signature.startsWith("89504E470D0A1A0A")) {
                mediaType = MediaType.PNG
            } else if (signature.startsWith("47494638")) {
                mediaType = MediaType.GIF
            } else if (signature.startsWith("52494646") && signature.drop(16).startsWith("57454250")) { // 52 49 46 46 ?? ?? ?? ?? 57 45 42 50
                mediaType = MediaType.WEBP
            } else if (signature.drop(8).startsWith("6674797069736F6D")) { // ** ** ** ** 66 74 79 70 69 73 6F 6D
                mediaType = MediaType.MP4
            } else if (signature.startsWith("1A45DFA3")) { // or webm, but assume mkv, also not that big a deal as only happens when filename is not found
                mediaType = MediaType.MKV
            } else if (signature.startsWith("52494646") && signature.drop(16).startsWith("41564920")) { // 52 49 46 46 ** ** ** ** 41 56 49 20
                mediaType = MediaType.AVI
            } else if (signature.startsWith("494433") || signature.startsWith("FFFB")
                || signature.startsWith("FFF3") || signature.startsWith("FFF2")) {
                mediaType = MediaType.MP3
            } else if (signature.startsWith("4F676753")) {
                mediaType = MediaType.OGG
            } else if (signature.startsWith("52494646") && signature.drop(16).startsWith("57415645")) { // 52 49 46 46 ** ** ** ** 57 41 56 45
                mediaType = MediaType.WAV
            }
            if (mediaType == MediaType.UNKNOWN) {
                Timber.d("Unable to find filetype from signature")
            } else {
                Timber.d("Found Filetype from signature $mediaType")
            }
        } else {
            Timber.d("Found Filetype from extension $mediaType")
        }

        return mediaType
    }
    // the size as shown on screen: ffmpeg turns photos and videos upright before scaling, so a portrait
    // photo stored sideways (4000x3000 + "rotate 90") is 3000x4000 here
    fun getMediaResolution(mediaUri: Uri, mediaType: MediaType): Pair<Int,Int> {
        // image
        if (isImage(mediaType)) {
            // only read the header, decoding the whole bitmap is slow and memory heavy
            val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(mediaUri)?.use { BitmapFactory.decodeStream(it, null, bitmapOptions) }
            val width = maxOf(bitmapOptions.outWidth, 0)
            val height = maxOf(bitmapOptions.outHeight, 0)
            val rotated = try {
                context.contentResolver.openInputStream(mediaUri)?.use { ExifInterface(it).rotationDegrees % 180 != 0 } ?: false
            } catch (e: Exception) {
                false
            }
            return if (rotated) Pair(height, width) else Pair(width, height)
        }
        // video
        // attempt to get video resolution
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, mediaUri)
        } catch (e: java.lang.RuntimeException) {
            return Pair(0,0) // unable to read video width/height, usually happens with avi files
        }
        // read video resolution
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        retriever.release()
        return if (rotation % 180 != 0) Pair(height, width) else Pair(width, height)
    }

    fun bytesToHuman(bytes: Long): String {
        // warning will break for files over 1 petabyte in size
        // decimal units, same as the phone's own storage screen
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val byte = 1000f
        if (bytes >= byte) {
            val e = (ln(bytes.toDouble()) / ln(byte)).toInt()
            val humanUnits = bytes / byte.pow(e)
            return String.format("%.01f %s", humanUnits, units[e])
        }
        return "$bytes ${units[0]}"
    }
    // "About 25 min left", rounded the way people say it rather than exact
    fun formatTimeLeft(millis: Long): String {
        val minutes = (millis + 59_999) / 60_000 // round up, "about 1 min" beats "0 min"
        return when {
            millis < 60_000 -> context.getString(R.string.eta_under_minute)
            minutes < 60 -> context.resources.getQuantityString(R.plurals.eta_minutes, minutes.toInt(), minutes.toInt())
            else -> context.getString(R.string.eta_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }
    fun millisToMicrowaveTime(millis: Int): String {
        var remainder = millis / 1_000
        var time = ""
        for (i in 0..2) {
            val unit = remainder % 60
            remainder /= 60
            if (!(i == 2 && unit == 0)) { // exclude hours if there are none
                time = String.format("%02d:%s", unit, time)
            }
        }
        return time.dropLast(1)
    }

    enum class MediaType {
        MP4, MKV, WEBM, AVI, // videos
        JPEG, JPG, PNG, GIF, WEBP, // images
        MP3, OGG, AAC, WAV, OPUS, M4A, // audios (m4a: voice recorder and messenger audio)
        UNKNOWN
    }
    fun isSupportedMediaType(type: MediaType): Boolean {
        // obviously unsupported if MediaType isn't even known
        return type != MediaType.UNKNOWN
    }
    fun isImage(type: MediaType): Boolean {
        return type == MediaType.JPEG || type == MediaType.PNG || type == MediaType.GIF
                || type == MediaType.WEBP
    }
    fun isVideo(type: MediaType): Boolean {
        return type == MediaType.MP4 || type == MediaType.MKV || type == MediaType.WEBM
                || type == MediaType.AVI
    }
    fun isAudio(type: MediaType): Boolean {
        return type == MediaType.MP3 || type == MediaType.OGG || type == MediaType.AAC
                || type == MediaType.WAV || type == MediaType.OPUS || type == MediaType.M4A
    }

    fun getAllowedMimes(): Array<String> {
        return arrayOf("audio/*", "image/*", "video/*")
    }

}