package com.caydey.ffshare.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import timber.log.Timber

// how much space photos, videos and audio take, from the media library (sizes only, nothing is opened)
class StorageScanner(private val context: Context) {

    enum class Kind { PHOTO, VIDEO, AUDIO }

    class Total(var bytes: Long = 0, var count: Int = 0)
    class Folder(val path: String, var bytes: Long = 0, var count: Int = 0)
    class Item(val uri: Uri, val name: String, val folder: String, val bytes: Long, val kind: Kind)

    class Report(
        val totals: Map<Kind, Total>,
        val folders: List<Folder>,     // biggest first
        val biggest: List<Item>,       // biggest first
        val deviceTotalBytes: Long,
        val deviceFreeBytes: Long,
        val compressedFolderBytes: Long, // the app's save folder, -1 when none is set
        val compressedFolderCount: Int
    ) {
        val mediaBytes: Long get() = totals.values.sumOf { it.bytes }
    }

    // call off the main thread, a phone with tens of thousands of files takes a moment
    // compressedFolder: relative path of the save folder ("Pictures/Compressed"), to show what the copies take
    fun scan(compressedFolder: String?): Report {
        val totals = Kind.values().associateWith { Total() }
        val folders = HashMap<String, Folder>()
        val items = ArrayList<Item>()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
        )
        try {
            context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val (kind, base) = when (cursor.getInt(1)) {
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Kind.PHOTO to MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Kind.VIDEO to MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else -> Kind.AUDIO to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }
                        val bytes = cursor.getLong(2)
                        val path = cursor.getString(3)?.trimEnd('/') ?: ""
                        totals.getValue(kind).apply { this.bytes += bytes; count++ }
                        folders.getOrPut(path) { Folder(path) }.apply { this.bytes += bytes; count++ }
                        items.add(Item(ContentUris.withAppendedId(base, cursor.getLong(0)), cursor.getString(4) ?: "?", path, bytes, kind))
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Unable to read the media library")
        }

        val inCompressed = compressedFolder?.trimEnd('/')?.let { folder -> folders.values.filter { it.path == folder || it.path.startsWith("$folder/") } }
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return Report(
            totals,
            folders.values.sortedByDescending { it.bytes }.take(MAX_FOLDERS),
            items.sortedByDescending { it.bytes }.take(MAX_BIGGEST),
            stat.totalBytes,
            stat.availableBytes,
            inCompressed?.sumOf { it.bytes } ?: -1,
            inCompressed?.sumOf { it.count } ?: 0
        )
    }

    companion object {
        private const val MAX_FOLDERS = 12
        private const val MAX_BIGGEST = 25
    }
}
