package com.caydey.ffshare

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.caydey.ffshare.extensions.parcelable
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
import com.caydey.ffshare.utils.MediaCompressor
import com.caydey.ffshare.utils.SaveFolder
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import timber.log.Timber


class HandleMediaActivity : AppCompatActivity() {
    // by lazy means load when variable is used, lazy-loading helps performance
    // also without it there is a null error for applicationContext
    private val mediaCompressor: MediaCompressor by lazy { MediaCompressor(applicationContext) }
    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }

    // true while a batch is compressing, back then sends it to the background instead of closing
    @Volatile private var running = false
    // share-mode batch that finished while the user was elsewhere, shared when they come back
    private var pendingShare: MediaCompressor.BatchResult? = null

    // originals offered for the trash on the results page, kept for undo
    private var trashUris: List<Uri> = emptyList()
    private var trashFreedBytes = 0L
    private var restoring = false
    private val trashRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) {
            onTrashChanged()
        }
    }

    // "Compress & save" with no folder chosen yet asks for one first
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            Toast.makeText(this, getString(R.string.choose_folder_first), Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        SaveFolder.set(this, uri)
        onMediaReceive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
            // the batch this notification was for is gone (app was restarted), show the main screen instead
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_handle_media)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (running) {
                    // keep compressing, progress stays in the notification
                    moveTaskToBack(true)
                } else {
                    finish()
                }
            }
        })
        findViewById<Button>(R.id.btnBackground).setOnClickListener { moveTaskToBack(true) }

        val detailsContainer = findViewById<View>(R.id.detailsContainer)
        val detailsToggle = findViewById<TextView>(R.id.lblDetailsToggle)
        detailsToggle.setOnClickListener {
            val show = detailsContainer.visibility != View.VISIBLE
            detailsContainer.visibility = if (show) View.VISIBLE else View.GONE
            detailsToggle.setText(if (show) R.string.progress_details_hide else R.string.progress_details_show)
        }

        if (utils.isReadPermissionGranted) {
            onMediaReceive()
        } else {
            Timber.d("Requesting read permissions")
            utils.requestReadPermissions(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // opening from the notification just brings this screen back
        if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) return
        if (running) {
            // a new share has to wait for the current batch
            Toast.makeText(this, getString(R.string.already_compressing), Toast.LENGTH_LONG).show()
        } else {
            // this screen is only showing an old batch's results, start fresh with the new files
            setIntent(intent)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        pendingShare?.let {
            pendingShare = null
            finishShare(it)
        }
    }

    override fun finish() {
        // only this screen's own batch, a stale screen opened from the notification must not cancel anything
        if (running) {
            mediaCompressor.cancel()
        }
        scheduleCacheCleanup()
        super.finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // first time running app user is requested to allow app to read external storage,
        // after clicking "allow" the app will continue handling media it was shared
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            Timber.d("Read permissions granted, continuing...")
            onMediaReceive()
        }
    }

    // which share menu entry opened this: "Compress & save", "Compress & share", or the app's own button
    private fun wantsSaveToFolder(): Boolean {
        val entry = intent.component?.className ?: ""
        return when {
            entry.endsWith(".CompressAndSave") -> true
            entry.endsWith(".CompressAndShare") -> false
            else -> SaveFolder.isUsable(this) // "Select files": save if a folder is set, otherwise share
        }
    }

    private fun onMediaReceive() {
        // "intent" variable is the shared item
        val receivedMedia =
            when (intent.action) {
                Intent.ACTION_SEND -> intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let { arrayListOf(it) } ?: ArrayList()
                Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM) ?: ArrayList()
                else -> ArrayList<Uri>()
            }

        // unable to get file from intent
        if (receivedMedia.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_uri_intent), Toast.LENGTH_LONG).show()
            Timber.d("No files found in shared intent")
            finish()
            return
        }

        val saveToFolder = wantsSaveToFolder()
        if (saveToFolder && !SaveFolder.isUsable(this)) {
            Toast.makeText(this, getString(R.string.choose_folder_first), Toast.LENGTH_LONG).show()
            folderPickerLauncher.launch(SaveFolder.PICKER_START)
            return
        }

        running = true
        CompressionService.start(this)
        mediaCompressor.compressFiles(this, receivedMedia, saveToFolder, progressCallback = { done, total, savedBytes, timeLeft ->
            CompressionService.updateProgress(applicationContext, done, total, savedBytes, timeLeft)
        }) { result ->
            running = false
            CompressionService.stop(applicationContext)
            runOnUiThread { onBatchFinished(result) }
        }
    }

    private fun onBatchFinished(result: MediaCompressor.BatchResult) {
        val inForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!inForeground && !result.canceled) {
            notifyDone(result, openResults = !isDestroyed)
        }
        if (isDestroyed) return // system closed this screen, the notification is all that's left

        if (result.savedToFolder) {
            showResult(result)
        } else if (inForeground) {
            finishShare(result)
        } else {
            // android blocks opening the share menu from the background
            pendingShare = result
        }
    }

    private fun notifyDone(result: MediaCompressor.BatchResult, openResults: Boolean) {
        val title = getString(R.string.notification_done_title, utils.bytesToHuman(maxOf(result.savedBytes, 0L)))
        val text = if (result.savedToFolder) {
            resources.getQuantityString(R.plurals.notification_done_text, result.compressedCount, result.compressedCount)
        } else {
            getString(R.string.notification_done_share)
        }
        CompressionService.showDone(applicationContext, title, text, openResults)
    }

    private fun finishShare(result: MediaCompressor.BatchResult) {
        showShareToast(result)
        if (result.sharedUris.isNotEmpty()) {
            shareMedia(result.sharedUris)
        }
        finish()
    }

    private fun showShareToast(result: MediaCompressor.BatchResult) {
        if (result.canceled) return
        // nothing worked, say why instead of silently closing
        if (result.compressedCount == 0 && result.failures.isNotEmpty()) {
            Toast.makeText(applicationContext, result.failures.first().reason, Toast.LENGTH_LONG).show()
            return
        }
        if (!settings.showStatusMessages || result.inputBytes == 0L) return
        val compressionPercentage = (1 - (result.outputBytes.toDouble() / result.inputBytes)) * 100
        var toastMessage = getString(R.string.media_reduction_message, utils.bytesToHuman(result.outputBytes), compressionPercentage)
        if (result.failedCount > 0) {
            toastMessage += "\n" + resources.getQuantityString(R.plurals.files_failed_message, result.failedCount, result.failedCount)
        }
        Toast.makeText(applicationContext, toastMessage, Toast.LENGTH_LONG).show()
    }

    // results page for batches saved to a folder, offers to replace the originals
    private fun showResult(result: MediaCompressor.BatchResult) {
        // canceled before anything finished, nothing to report
        if (result.compressedCount + result.keptOriginalCount + result.failedCount == 0) {
            finish()
            return
        }
        findViewById<View>(R.id.progressContainer).visibility = View.GONE
        findViewById<View>(R.id.resultContainer).visibility = View.VISIBLE

        findViewById<TextView>(R.id.txtResultTitle).text = when {
            result.compressedCount > 0 -> getString(R.string.result_title, utils.bytesToHuman(maxOf(result.savedBytes, 0L)))
            result.keptOriginalCount > 0 && result.failedCount == 0 -> getString(R.string.result_title_already_small)
            else -> getString(R.string.result_title_nothing) // "Done! 0 B smaller" reads like a joke
        }

        // summary and before/after bars
        val summary = findViewById<TextView>(R.id.txtResultSummary)
        val barViews = listOf(R.id.lblBefore, R.id.barBefore, R.id.lblAfter, R.id.barAfter).map { findViewById<View>(it) }
        if (result.compressedCount > 0 && result.inputBytes > 0) {
            val percentage = (1 - (result.outputBytes.toDouble() / result.inputBytes)) * 100
            summary.text = resources.getQuantityString(R.plurals.result_summary, result.compressedCount, result.compressedCount, percentage)
            findViewById<TextView>(R.id.lblBefore).text = getString(R.string.result_before, utils.bytesToHuman(result.inputBytes))
            findViewById<TextView>(R.id.lblAfter).text = getString(R.string.result_after, utils.bytesToHuman(result.outputBytes))
            findViewById<LinearProgressIndicator>(R.id.barAfter).progress =
                (result.outputBytes * 1000 / result.inputBytes).toInt().coerceIn(0, 1000)
        } else {
            summary.visibility = View.GONE
            barViews.forEach { it.visibility = View.GONE }
        }

        // kept originals and failures
        val notes = ArrayList<String>()
        if (result.keptOriginalCount > 0) {
            notes.add(resources.getQuantityString(R.plurals.result_kept_original, result.keptOriginalCount, result.keptOriginalCount))
        }
        if (result.failedCount > 0) {
            notes.add(resources.getQuantityString(R.plurals.files_failed_message, result.failedCount, result.failedCount))
        }
        val notesView = findViewById<TextView>(R.id.txtResultNotes)
        notesView.text = notes.joinToString("\n")
        notesView.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        val btnShowFailed = findViewById<Button>(R.id.btnShowFailed)
        if (result.failures.isNotEmpty()) {
            btnShowFailed.visibility = View.VISIBLE
            btnShowFailed.setOnClickListener { showFailures(result) }
        }

        // biggest savings, the files worth knowing about
        val biggest = result.savings.sortedByDescending { it.inputBytes - it.outputBytes }.take(BIGGEST_SAVINGS_COUNT)
        if (biggest.isEmpty()) {
            findViewById<View>(R.id.lblBiggestSavings).visibility = View.GONE
            findViewById<View>(R.id.txtBiggestSavings).visibility = View.GONE
        } else {
            findViewById<TextView>(R.id.txtBiggestSavings).text = biggest.joinToString("\n") {
                getString(R.string.result_saving_line, it.name, utils.bytesToHuman(it.inputBytes), utils.bytesToHuman(it.outputBytes))
            }
        }

        setUpTrash(result)

        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener {
            if (!SaveFolder.open(this)) {
                Toast.makeText(this, getString(R.string.result_open_folder_failed), Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
    }

    private fun setUpTrash(result: MediaCompressor.BatchResult) {
        val trashInfo = findViewById<TextView>(R.id.txtTrashInfo)
        val btnTrash = findViewById<Button>(R.id.btnTrash)
        btnTrash.visibility = View.GONE

        // originals can only be trashed through MediaStore (Android 11+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || result.replaceableOriginals.isEmpty()) {
            trashInfo.visibility = View.GONE
            return
        }
        val trashable = result.replaceableOriginals.mapNotNull { (uri, saved) -> toMediaStoreUri(uri)?.let { it to saved } }
        val notTrashable = result.replaceableOriginals.size - trashable.size
        val info = ArrayList<String>()
        if (trashable.isNotEmpty()) {
            trashUris = trashable.map { it.first }
            trashFreedBytes = trashable.sumOf { it.second }
            info.add(getString(R.string.result_trash_explain, utils.bytesToHuman(trashFreedBytes)))
            btnTrash.text = getString(R.string.result_trash_button, utils.bytesToHuman(trashFreedBytes))
            btnTrash.visibility = View.VISIBLE
            btnTrash.setOnClickListener { requestTrash(restore = false) }
            findViewById<Button>(R.id.btnUndoTrash).setOnClickListener { requestTrash(restore = true) }
        }
        if (notTrashable > 0) {
            info.add(getString(R.string.result_not_trashable, notTrashable))
        }
        trashInfo.text = info.joinToString("\n")
    }

    // android asks the user to confirm both ways, the trash keeps files for 30 days
    private fun requestTrash(restore: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || trashUris.isEmpty()) return
        restoring = restore
        try {
            val request = MediaStore.createTrashRequest(contentResolver, trashUris, !restore)
            trashRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            // never crash over this, the compressed files are already safe in the folder
            Timber.e(e, "Trash request refused")
            Toast.makeText(this, getString(R.string.result_trash_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun onTrashChanged() {
        val trashInfo = findViewById<TextView>(R.id.txtTrashInfo)
        val btnTrash = findViewById<Button>(R.id.btnTrash)
        val btnUndo = findViewById<Button>(R.id.btnUndoTrash)
        if (restoring) {
            trashInfo.setText(R.string.result_restored)
            btnUndo.visibility = View.GONE
            btnTrash.visibility = View.VISIBLE
        } else {
            trashInfo.text = getString(R.string.result_trashed, trashUris.size, utils.bytesToHuman(trashFreedBytes))
            btnTrash.visibility = View.GONE
            btnUndo.visibility = View.VISIBLE
        }
    }

    private fun showFailures(result: MediaCompressor.BatchResult) {
        val list = result.failures.joinToString("\n\n") { getString(R.string.result_failed_line, it.name, it.reason) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.result_failed_title)
            .setMessage(list)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // trash requests only take images/video/audio collection uris. gallery shares already are those,
    // picker/document uris convert to the generic "files" table which the trash refuses
    @RequiresApi(Build.VERSION_CODES.R)
    private fun toMediaStoreUri(uri: Uri): Uri? {
        val mediaUri = if (uri.authority == MediaStore.AUTHORITY) {
            uri
        } else {
            try {
                MediaStore.getMediaUri(this, uri)
            } catch (e: Exception) {
                null
            }
        }
        if (mediaUri == null) {
            Timber.d("No MediaStore uri for '%s'", uri)
            return null
        }
        val id = try { ContentUris.parseId(mediaUri) } catch (e: Exception) { -1L }
        val mime = contentResolver.getType(uri) ?: contentResolver.getType(mediaUri)
        val collection = when {
            id < 0 || mime == null -> null
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> null
        } ?: return null
        return ContentUris.withAppendedId(collection, id)
    }

    private fun shareMedia(mediaUris: ArrayList<Uri>) {
        val shareIntent = Intent()

        // temp permissions for other app to view file
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // add compressed media files
        if (mediaUris.size == 1) {
            Timber.d("Creating share intent for single item")
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUris[0])
        } else {
            Timber.d("Creating share intent for multiple items")
            shareIntent.action = Intent.ACTION_SEND_MULTIPLE
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUris)
        }

        // set mime for each file
        mediaUris.forEach { mediaUri ->
            shareIntent.setDataAndType(mediaUri, contentResolver.getType(mediaUri))
        }

        val chooser = Intent.createChooser(shareIntent, "media")
        startActivity(chooser)
    }

    private fun scheduleCacheCleanup() {
        Timber.d("Scheduling cleanup alarm")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, CacheCleanUpReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

        // every 12 hours clear cache
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime(),
            AlarmManager.INTERVAL_HALF_DAY,
            pendingIntent
        )
    }

    companion object {
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
        private const val BIGGEST_SAVINGS_COUNT = 5
    }
}
