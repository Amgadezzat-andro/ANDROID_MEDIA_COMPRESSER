package com.caydey.ffshare.extensions

import android.content.Context
import java.io.File

val Context.mediaCacheDir: File
    get() {
        File(cacheDir, "media").mkdirs()
        return File(cacheDir, "media")
    }

// private copies of images for ffmpeg to read, inside media/ so the cache cleanup covers leftovers
val Context.mediaInputDir: File
    get() {
        File(mediaCacheDir, "input").mkdirs()
        return File(mediaCacheDir, "input")
    }
