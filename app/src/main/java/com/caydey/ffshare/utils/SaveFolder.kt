package com.caydey.ffshare.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber

// the folder compressed files are saved to, shared by the main screen, setup and "Compress & save"
object SaveFolder {
    private const val PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // the folder picker opens in Pictures instead of the top of the storage
    val PICKER_START: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Pictures")

    // keep access to the picked folder after the app or phone restarts
    fun set(context: Context, uri: Uri) {
        release(context)
        context.contentResolver.takePersistableUriPermission(uri, PERMISSION_FLAGS)
        Settings(context).saveFolderUri = uri.toString()
    }

    fun clear(context: Context) {
        release(context)
        Settings(context).saveFolderUri = ""
    }

    // folder is chosen and still exists and is writable
    fun isUsable(context: Context): Boolean {
        val folderUri = Settings(context).saveFolderUri
        if (folderUri.isEmpty()) return false
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
        return folder != null && folder.canWrite()
    }

    // "Pictures/Compressed" instead of the raw content uri
    fun displayName(context: Context): String? {
        val folderUri = Settings(context).saveFolderUri
        if (folderUri.isEmpty()) return null
        val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(folderUri)) // looks like "primary:Pictures/Compressed"
        return treeId.substringAfter(':').ifEmpty { treeId }
    }

    // opens the folder in the phone's files app, false if no app can show it
    fun open(context: Context): Boolean {
        val folderUri = Settings(context).saveFolderUri
        if (folderUri.isEmpty()) return false
        val treeUri = Uri.parse(folderUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Timber.d("No app can open the save folder")
            false
        }
    }

    private fun release(context: Context) {
        val folderUri = Settings(context).saveFolderUri
        if (folderUri.isEmpty()) return
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folderUri), PERMISSION_FLAGS)
        } catch (e: SecurityException) {
            // permission was already gone
        }
    }
}
