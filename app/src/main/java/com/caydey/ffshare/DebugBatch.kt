package com.caydey.ffshare

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

// test hook for debug builds: every photo, video and audio file in one folder, so a batch can be started
// from adb in one command. adb grants the read-media permissions the storage tracker declares
object DebugBatch {
    fun urisIn(context: Context, relativePath: String): List<Uri> {
        val uris = ArrayList<Uri>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        context.contentResolver.query(collection, projection, "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath), MediaStore.Files.FileColumns.DISPLAY_NAME)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val base = when (cursor.getInt(1)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> null
                } ?: continue
                uris.add(ContentUris.withAppendedId(base, id))
            }
        }
        return uris
    }
}
