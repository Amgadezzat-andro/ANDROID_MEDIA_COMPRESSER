package com.caydey.ffshare

import android.app.Activity
import androidx.annotation.StyleRes
import com.caydey.ffshare.utils.Settings

// the look picked in settings. NOTHING is the base theme itself, the others are overlays on top of it
enum class AppTheme(@StyleRes val overlay: Int?) {
    NOTHING(null),
    ONE_UI(R.style.ThemeOverlay_NMC_OneUi),
    MATERIAL_YOU(R.style.ThemeOverlay_NMC_Android),
    MIDNIGHT(R.style.ThemeOverlay_NMC_Midnight),
    CLASSIC(R.style.ThemeOverlay_NMC_Classic);

    companion object {
        // call before super.onCreate, so every view is inflated with the picked look
        fun apply(activity: Activity): AppTheme {
            val theme = Settings(activity.applicationContext).appTheme
            theme.overlay?.let { activity.theme.applyStyle(it, true) }
            return theme
        }
    }
}
