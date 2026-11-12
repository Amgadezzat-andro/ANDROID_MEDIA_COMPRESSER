package com.caydey.ffshare.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// read access to the whole media library, only the storage tracker needs it (compressing works without)
object MediaAccess {
    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun has(context: Context): Boolean = permissions.any { granted(context, it) } || isPartial(context)

    // android 14+ "allow limited access": only the photos and videos the user picked are counted
    fun isPartial(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
