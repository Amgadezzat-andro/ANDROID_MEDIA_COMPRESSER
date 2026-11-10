package com.caydey.ffshare.utils


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageView
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.caydey.ffshare.R
import com.caydey.ffshare.extensions.mediaInputDir
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import com.google.android.material.progressindicator.LinearProgressIndicator
import timber.log.Timber
import java.io.File
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread


class MediaCompressor(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper by lazy { LogsDbHelper(context) }

    private val ffmpegParamMaker = FFmpegParamMaker(settings, utils)
    private val mainHandler = Handler(Looper.getMainLooper())

    // set by the cancel button, stops the batch instead of skipping to the next file
    @Volatile private var canceled = false
    // turned off after the first hardware encoder failure so the rest of the batch goes straight to software
    @Volatile private var hardwareVideoWorks = true

    fun cancelAllOperations() {
        Timber.d("Canceling all ffmpeg operations")
        FFmpegKit.cancel()
    }

    fun cancel() {
        canceled = true
        cancelAllOperations()
    }

    // progress of the video being encoded (0..1), only used when the batch is a single file
    @Volatile private var fileProgressListener: ((Float) -> Unit)? = null

    @SuppressLint("SetTextI18n")
    fun compressSingleFile(
        activity: Activity,
        inputFileUri: Uri,
        successHandler: (uri: Uri, outputFile: File, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: (reason: String) -> Unit, // reason is shown on the results page
        useHardware: Boolean = settings.hardwareVideo && hardwareVideoWorks
    ) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)
        val processedTableRow: TableRow = activity.findViewById(R.id.processedTableRow)

        if (canceled) {
            failureHandler(context.getString(R.string.ffmpeg_canceled))
            return
        }

        val mediaType = utils.getMediaType(inputFileUri)
        if (!utils.isSupportedMediaType(mediaType)) { // not supported
            failureHandler(context.getString(R.string.error_unknown_filetype))
            return
        }

        // don't show progress when compressing images (not possible)
        val showProgress = !utils.isImage(mediaType)
        mainHandler.post {
            processedTableRow.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE
        }

        val inputFileName = utils.getFilenameFromUri(inputFileUri) ?: "unknown"

        // get output file, (random uuid, custom name, original name)
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(inputFileUri, mediaType)

        // get Uri from File, needs to be this way not Uri.fromFile(...) to go through security
        val outputFileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName+".fileprovider", outputFile)

        // ffmpeg-kit's saf: parameters share one table between sessions that breaks when files run in
        // parallel ("SAF fd not found"). only images run in parallel, so they're read from a private
        // copy (a few MB, deleted when done). videos run one at a time and keep using saf
        val localInput = if (utils.isImage(mediaType)) copyToCache(inputFileUri) else null
        if (utils.isImage(mediaType) && localInput == null) {
            failureHandler(context.getString(R.string.error_invalid_file))
            return
        }
        fun inputParameter() = localInput?.absolutePath ?: FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)

        // need to create new saf param as they are one-use
        val mediaInformation = FFprobeKit.getMediaInformation(inputParameter()).getMediaInformation()

        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            localInput?.delete()
            failureHandler(context.getString(R.string.error_invalid_file))
            return
        }

        val inputFileSize = mediaInformation.getSize()?.toLong() ?: 0 // get input file size

        var duration = 0 // default duration for image
        if (showProgress) {
            // invalid video file if ffprobe cant parse duration and size
            if (mediaInformation.getDuration() == null || mediaInformation.getSize() == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                localInput?.delete()
                failureHandler(context.getString(R.string.error_invalid_file))
                return
            }
            duration = ((mediaInformation.getDuration()?.toFloat() ?: 0f) * 1_000).toInt()
        }

        val params = ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType, useHardware)
        val usedHardware = params.contains(FFmpegParamMaker.HEVC_HARDWARE_ENCODER) || params.contains(FFmpegParamMaker.H264_HARDWARE_ENCODER)
        // the output is our own cache file, so ffmpeg writes it directly instead of through saf
        val input = localInput?.let { "\"${it.absolutePath}\"" } ?: inputParameter()
        val command = "-y -i $input $params \"${outputFile.absolutePath}\""
        val prettyCommand = "ffmpeg -y -i $inputFileName $params ${outputFile.name}"

        // set TextViews
        mainHandler.post {
            txtFfmpegCommand.text = prettyCommand
            txtInputFile.text = inputFileName
            txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
            txtOutputFile.text = outputFile.name
            txtOutputFileSize.text = utils.bytesToHuman(0)
            txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(duration)
            txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
        }

        Timber.d("Executing ffmpeg command: 'ffmpeg %s'", command)
        FFmpegKit.executeAsync(command, { session ->
            // completed
            localInput?.delete()
            if (session.getReturnCode()?.isValueSuccess() == false) { // failed
                if (session.getReturnCode()?.isValueCancel() == true) {
                    failureHandler(context.getString(R.string.ffmpeg_canceled)) // releases the worker waiting on this file
                    return@executeAsync
                }
                logsDbHelper.addLog(Log(
                    prettyCommand,
                    inputFileName,
                    outputFile.name,
                    false,
                    session.getOutput(),
                    inputFileSize,
                    -1
                ))
                if (usedHardware) {
                    // phone's encoder refused this video, redo it in software and stop trying hardware
                    Timber.d("hardware encoder failed, retrying with software")
                    hardwareVideoWorks = false
                    outputFile.parentFile?.deleteRecursively()
                    compressSingleFile(activity, inputFileUri, successHandler, failureHandler, useHardware = false)
                    return@executeAsync
                }
                Timber.d("ffmpeg command failed")
                outputFile.parentFile?.deleteRecursively()
                failureHandler(context.getString(R.string.ffmpeg_error))
            } else { // success
                Timber.d("ffmpeg command executed successfully")
                if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                    Timber.d("copying exif tags")
                    try {
                        context.contentResolver.openInputStream(inputFileUri)?.use { ExifTools.copyExif(it, outputFile) }
                    } catch (e: Exception) {
                        // a missing date isn't worth losing the compressed file over
                        Timber.e(e, "Unable to copy exif tags")
                    }
                }
                val outputFileCurrentSize = outputFile.length()
                // """Only the original thread that created a view hierarchy can touch its views."""
                mainHandler.post {
                    // update TextViews to their final values 97.8% -> 100.0%
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(duration)
                    if (outputFileCurrentSize > 0) {
                        txtOutputFileSize.text = utils.bytesToHuman(outputFileCurrentSize)
                    }
                }

                logsDbHelper.addLog(Log(
                    prettyCommand,
                    inputFileName,
                    outputFile.name,
                    true,
                    session.getOutput(),
                    inputFileSize,
                    outputFileCurrentSize
                ))
                // callback
                successHandler(outputFileUri, outputFile, inputFileSize, outputFileCurrentSize)
            }
        }, { /* logs */ }, { statistics ->
            // update TextViews with stats
            mainHandler.post {
                if (showProgress && duration > 0) { // only show time processed if video
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, (statistics.time.toFloat() / duration) * 100)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(statistics.time.toInt())
                }
                txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
            }
            if (showProgress && duration > 0) {
                fileProgressListener?.invoke((statistics.time.toFloat() / duration).coerceIn(0f, 1f))
            }
        })
    }

    // everything that happened in a batch, used for the results screen
    class BatchResult(val savedToFolder: Boolean) {
        val sharedUris = ArrayList<Uri>()
        // original -> bytes saved, only originals whose smaller copy is safely in the save folder
        val replaceableOriginals = LinkedHashMap<Uri, Long>()
        var inputBytes = 0L
        var outputBytes = 0L
        var compressedCount = 0
        var keptOriginalCount = 0
        var failedCount = 0
        var canceled = false
        // per file, for the "biggest savings" list and "see which files failed"
        val savings = ArrayList<FileSaving>()
        val failures = ArrayList<FileFailure>()
        val savedBytes: Long
            get() = inputBytes - outputBytes
    }
    class FileSaving(val name: String, val inputBytes: Long, val outputBytes: Long)
    class FileFailure(val name: String, val reason: String)

    // progressCallback and callback are called from worker threads
    fun compressFiles(
        activity: Activity,
        inputFilesUri: ArrayList<Uri>,
        saveToFolder: Boolean, // false shares the compressed files instead
        progressCallback: (done: Int, total: Int, savedBytes: Long, timeLeft: String?) -> Unit,
        callback: (result: BatchResult) -> Unit
    ) {
        val txtCommandNumber: TextView = activity.findViewById(R.id.txtCommandNumber)
        val txtSavings: TextView = activity.findViewById(R.id.txtSavings)
        val txtEta: TextView = activity.findViewById(R.id.txtEta)
        val progressBar: LinearProgressIndicator = activity.findViewById(R.id.progressBar)
        val imgThumbnail: ImageView = activity.findViewById(R.id.imgThumbnail)

        val inputFilesCount = inputFilesUri.size

        val saveFolder = if (saveToFolder) getSaveFolder() else null
        val result = BatchResult(saveFolder != null)
        canceled = false
        hardwareVideoWorks = true

        activity.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
            cancel()
        }

        // size of the whole batch, for the "about X when finished" and time left estimates
        val batchTotalBytes = AtomicLong(0)
        thread { batchTotalBytes.set(inputFilesUri.sumOf { utils.getFileSizeFromUri(it) }) }
        val startTime = SystemClock.elapsedRealtime()
        val lastThumbnail = AtomicLong(0)

        // guarded by result
        var processedInputBytes = 0L // includes kept originals so the estimate isn't too optimistic
        var doneCount = 0

        // time left from how fast the batch has gone so far, by bytes since one video outweighs many photos
        fun timeLeft(fractionDone: Double): String {
            val elapsed = SystemClock.elapsedRealtime() - startTime
            if (fractionDone <= 0.0 || elapsed < MIN_ETA_ELAPSED) return context.getString(R.string.eta_estimating)
            return utils.formatTimeLeft((elapsed * (1 - fractionDone) / fractionDone).toLong())
        }

        fun showProgress() {
            val (done, saved, processed) = synchronized(result) { Triple(doneCount, result.savedBytes, processedInputBytes) }
            val savedSoFar = utils.bytesToHuman(maxOf(saved, 0L))
            val totalBytes = batchTotalBytes.get()
            val text = if (inputFilesCount > 1 && totalBytes > 0 && processed > 0) {
                val estimate = (saved.toDouble() / processed * totalBytes).toLong()
                context.getString(R.string.savings_live_estimate, savedSoFar, utils.bytesToHuman(maxOf(estimate, 0L)))
            } else {
                context.getString(R.string.savings_live, savedSoFar)
            }
            val fractionDone = if (totalBytes > 0) processed.toDouble() / totalBytes else done.toDouble() / inputFilesCount
            val eta = if (inputFilesCount > 1 && done < inputFilesCount) timeLeft(fractionDone) else null
            mainHandler.post {
                txtSavings.text = text
                if (inputFilesCount > 1) {
                    txtCommandNumber.text = context.getString(R.string.progress_x_of_y, done, inputFilesCount)
                    progressBar.setProgressCompat(done * 1000 / inputFilesCount, true)
                    txtEta.text = eta ?: ""
                }
            }
            progressCallback(done, inputFilesCount, saved, eta)
        }

        // shows what's being worked on, throttled since parallel images change faster than anyone can see
        fun showThumbnail(uri: Uri) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastThumbnail.get() < THUMBNAIL_INTERVAL) return
            lastThumbnail.set(now)
            val thumbnail = try {
                context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
            } catch (e: Exception) {
                videoFrame(uri) // file picker videos often have no ready-made thumbnail
            }
            if (thumbnail == null) {
                Timber.d("No thumbnail for '%s'", uri)
                return
            }
            mainHandler.post { imgThumbnail.setImageBitmap(thumbnail) }
        }

        // the single file of a one file batch drives the bar itself
        fileProgressListener = if (inputFilesCount == 1) { fraction ->
            val eta = timeLeft(fraction.toDouble())
            mainHandler.post {
                progressBar.setProgressCompat((fraction * 1000).toInt(), true)
                txtEta.text = eta
            }
            progressCallback(0, 1, 0, eta)
        } else null
        mainHandler.post {
            txtCommandNumber.text = if (inputFilesCount == 1) context.getString(R.string.progress_single)
                else context.getString(R.string.progress_x_of_y, 0, inputFilesCount)
            // a batch, or a single video once it reports progress, gets a real bar
            if (inputFilesCount > 1) progressBar.isIndeterminate = false
            txtEta.setText(R.string.eta_estimating) // something to read before the first file finishes
        }

        fun onFailed(originalUri: Uri, reason: String) {
            val name = utils.getFilenameFromUri(originalUri) ?: originalUri.lastPathSegment ?: "?"
            synchronized(result) {
                if (!canceled) { // files stopped by cancel didn't fail
                    result.failedCount++
                    result.failures.add(FileFailure(name, reason))
                }
                doneCount++
            }
        }

        fun onCompressed(originalUri: Uri, uri: Uri, outputFile: File, inputFileSize: Long, outputFileSize: Long) {
            // file io outside the lock so parallel workers don't wait on each other
            val keepOriginal = saveFolder != null && settings.keepOriginalIfBigger && outputFileSize >= inputFileSize
            val saved = saveFolder != null && !keepOriginal && saveToFolder(saveFolder, outputFile)
            if (keepOriginal) {
                // nothing to gain, leave the original alone
                outputFile.parentFile?.deleteRecursively()
            }
            if (saveFolder != null && !keepOriginal && !saved) {
                onFailed(originalUri, context.getString(R.string.result_save_failed))
                return
            }
            val name = utils.getFilenameFromUri(originalUri) ?: outputFile.name
            synchronized(result) {
                doneCount++
                processedInputBytes += inputFileSize
                when {
                    keepOriginal -> result.keptOriginalCount++
                    saveFolder == null -> {
                        result.sharedUris.add(uri)
                        countCompressed(result, name, inputFileSize, outputFileSize)
                    }
                    else -> {
                        countCompressed(result, name, inputFileSize, outputFileSize)
                        result.replaceableOriginals[originalUri] = inputFileSize - outputFileSize
                    }
                }
            }
        }

        // ffmpeg encodes an image on roughly one core, so images run several at a time.
        // videos already use every core (or the hardware encoder), so they run one at a time
        // off the main thread, checking thousands of files takes a moment
        thread(name = "compress-start") {
            val hasVideos = inputFilesUri.any { context.contentResolver.getType(it)?.startsWith("video/") == true }
            val workerCount = if (hasVideos || inputFilesCount == 1) 1 else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            val nextIndex = AtomicInteger(0)
            val runningWorkers = AtomicInteger(workerCount)
            Timber.d("Compressing %d files with %d workers", inputFilesCount, workerCount)

            repeat(workerCount) { workerNumber ->
                thread(name = "compress-worker-$workerNumber") {
                    while (!canceled) {
                        val i = nextIndex.getAndIncrement()
                        if (i >= inputFilesCount) break
                        Timber.d("Processing %d of %d files", i+1, inputFilesCount)

                        val originalUri = inputFilesUri[i]
                        showThumbnail(originalUri)
                        val fileDone = CountDownLatch(1)
                        // one unreadable file (revoked permission, cloud-only, deleted) must not crash the
                        // whole app from a worker thread, it just counts as failed
                        try {
                            compressSingleFile(activity, originalUri, failureHandler = { reason ->
                                onFailed(originalUri, reason)
                                fileDone.countDown()
                            }, successHandler = { uri, outputFile, inputFileSize, outputFileSize ->
                                try {
                                    onCompressed(originalUri, uri, outputFile, inputFileSize, outputFileSize)
                                } catch (e: Exception) {
                                    Timber.e(e, "Unable to finish '%s'", originalUri)
                                    onFailed(originalUri, context.getString(R.string.result_save_failed))
                                }
                                fileDone.countDown()
                            })
                        } catch (e: Exception) {
                            Timber.e(e, "Unable to compress '%s'", originalUri)
                            onFailed(originalUri, context.getString(R.string.error_cannot_read))
                            fileDone.countDown()
                        }
                        fileDone.await()
                        showProgress()
                    }
                    // last worker out reports the batch
                    if (runningWorkers.decrementAndGet() == 0) {
                        fileProgressListener = null
                        result.canceled = canceled
                        callback(result)
                    }
                }
            }
        }
    }

    // must be called while holding the result lock
    private fun countCompressed(result: BatchResult, name: String, inputFileSize: Long, outputFileSize: Long) {
        result.compressedCount++
        result.inputBytes += inputFileSize
        result.outputBytes += outputFileSize
        result.savings.add(FileSaving(name, inputFileSize, outputFileSize))
        settings.addToStats(inputFileSize, outputFileSize)
    }

    companion object {
        private const val THUMBNAIL_INTERVAL = 400L
        private const val THUMBNAIL_SIZE = 400
        // the first seconds are too noisy to estimate time left from
        private const val MIN_ETA_ELAPSED = 5_000L
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    // private copy of an image for ffmpeg to read, the caller deletes it when done
    private fun copyToCache(uri: Uri): File? {
        val name = utils.getFilenameFromUri(uri) ?: "input"
        val copy = File(context.mediaInputDir, "${UUID.randomUUID()}.${name.substringAfterLast('.', "img")}")
        return try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                copy.outputStream().use { input.copyTo(it) }
            }
            copy
        } catch (e: Exception) {
            Timber.e(e, "Unable to copy '%s'", uri)
            copy.delete()
            null
        }
    }

    private fun getSaveFolder(): DocumentFile? {
        if (settings.saveFolderUri.isEmpty()) return null
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.saveFolderUri))
        // folder was deleted or permission revoked, fall back to sharing
        return if (folder != null && folder.canWrite()) folder else null
    }

    // copy the compressed file out of the cache into the save folder, then free the cache space
    private fun saveToFolder(folder: DocumentFile, file: File): Boolean {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
        val target = folder.createFile(mime, file.name) ?: return false
        return try {
            context.contentResolver.openOutputStream(target.uri)!!.use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Unable to save '%s' to folder", file.name)
            target.delete()
            false
        } finally {
            file.parentFile?.deleteRecursively() // uuid cache folder
        }
    }

}
