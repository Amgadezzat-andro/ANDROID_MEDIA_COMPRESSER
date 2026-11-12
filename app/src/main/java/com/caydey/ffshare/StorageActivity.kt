package com.caydey.ffshare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.caydey.ffshare.utils.MediaAccess
import com.caydey.ffshare.utils.SaveFolder
import com.caydey.ffshare.utils.StorageScanner
import com.caydey.ffshare.utils.StorageScanner.Kind
import com.caydey.ffshare.utils.Utils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.concurrent.thread

// what photos, videos and audio take on the phone: totals, biggest folders, biggest files to compress
class StorageActivity : AppCompatActivity() {
    private val utils: Utils by lazy { Utils(applicationContext) }
    private lateinit var content: LinearLayout

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.navigationIcon?.setTint(color(android.R.attr.textColor))
        toolbar.setTitleTextColor(color(android.R.attr.textColor))
        toolbar.setNavigationOnClickListener { finish() }
        content = findViewById(R.id.storageContent)
        findViewById<View>(R.id.btnAllow).setOnClickListener { permissionLauncher.launch(MediaAccess.permissions) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val allowed = MediaAccess.has(this)
        findViewById<View>(R.id.cardPermission).visibility = if (allowed) View.GONE else View.VISIBLE
        if (!allowed) {
            content.removeAllViews()
            return
        }
        val scanning = findViewById<View>(R.id.lblScanning)
        if (content.childCount == 0) scanning.visibility = View.VISIBLE
        val savePath = SaveFolder.displayName(this)
        thread(name = "storage-scan") {
            val report = StorageScanner(applicationContext).scan(savePath)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning.visibility = View.GONE
                show(report, savePath)
            }
        }
    }

    private fun show(report: StorageScanner.Report, savePath: String?) {
        content.removeAllViews()
        val kindColors = mapOf(
            Kind.PHOTO to color(com.google.android.material.R.attr.colorPrimary),
            Kind.VIDEO to color(com.google.android.material.R.attr.colorSecondary),
            Kind.AUDIO to color(android.R.attr.textColorSecondary)
        )

        // totals
        val totalCard = card()
        totalCard.addView(label(getString(R.string.storage_media_title)))
        totalCard.addView(display(utils.bytesToHuman(report.mediaBytes)))
        totalCard.addView(text(getString(R.string.storage_device_line,
            utils.bytesToHuman(report.deviceTotalBytes), utils.bytesToHuman(report.deviceFreeBytes)), secondary = true))
        totalCard.addView(bar(Kind.values().map { report.totals.getValue(it).bytes.toFloat() to kindColors.getValue(it) }))
        for (kind in Kind.values()) {
            val total = report.totals.getValue(kind)
            totalCard.addView(row(getString(kindName(kind)),
                getString(R.string.storage_count_size, total.count, utils.bytesToHuman(total.bytes)), dot = kindColors.getValue(kind)))
        }
        if (MediaAccess.isPartial(this)) {
            totalCard.addView(text(getString(R.string.storage_partial), secondary = true, top = 12))
        }
        content.addView(totalCard.parent as View)

        // the app's own copies
        if (savePath != null && report.compressedFolderBytes >= 0) {
            val savedCard = card()
            savedCard.addView(label(getString(R.string.storage_compressed_title)))
            savedCard.addView(row(savePath, getString(R.string.storage_count_size,
                report.compressedFolderCount, utils.bytesToHuman(report.compressedFolderBytes))))
            content.addView(savedCard.parent as View)
        }

        // biggest folders, with a bar against the biggest one
        if (report.folders.isNotEmpty()) {
            val folderCard = card()
            folderCard.addView(label(getString(R.string.storage_folders_title)))
            val biggest = report.folders.first().bytes.coerceAtLeast(1)
            for (folder in report.folders) {
                folderCard.addView(row(folder.path.ifEmpty { "/" },
                    getString(R.string.storage_count_size, folder.count, utils.bytesToHuman(folder.bytes))))
                folderCard.addView(bar(listOf(folder.bytes.toFloat() to kindColors.getValue(Kind.PHOTO),
                    (biggest - folder.bytes).toFloat() to 0), height = 4))
            }
            content.addView(folderCard.parent as View)
        }

        // biggest files, tap one to compress it
        if (report.biggest.isNotEmpty()) {
            val fileCard = card()
            fileCard.addView(label(getString(R.string.storage_biggest_title)))
            val videos = report.biggest.filter { it.kind == Kind.VIDEO }
            if (videos.size > 1) {
                fileCard.addView(MaterialButton(this).apply {
                    text = getString(R.string.storage_compress_videos, videos.size)
                    setOnClickListener { compress(videos.map { it.uri }) }
                })
            }
            fileCard.addView(text(getString(R.string.storage_tap_hint), secondary = true))
            for (item in report.biggest) {
                fileCard.addView(row(item.name, utils.bytesToHuman(item.bytes), sub = item.folder,
                    dot = kindColors.getValue(item.kind)) { compress(listOf(item.uri)) })
            }
            content.addView(fileCard.parent as View)
        }
    }

    // same as sharing these files to the app
    private fun compress(uris: List<Uri>) {
        val intent = Intent(this, HandleMediaActivity::class.java)
        intent.action = Intent.ACTION_SEND_MULTIPLE
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Parcelable>(uris))
        startActivity(intent)
    }

    private fun kindName(kind: Kind) = when (kind) {
        Kind.PHOTO -> R.string.storage_photos
        Kind.VIDEO -> R.string.storage_videos
        Kind.AUDIO -> R.string.storage_audio
    }

    // ---- small view builders, the rows follow the app theme through text appearances and theme colours

    private fun card(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
            addView(inner)
        }
        return inner
    }

    private fun label(value: String) = TextView(this).apply {
        setTextAppearance(R.style.TextAppearance_Nothing_Label)
        setTextColor(color(android.R.attr.textColorSecondary))
        text = value
    }

    private fun display(value: String) = TextView(this).apply {
        setTextAppearance(R.style.TextAppearance_FFShare_BigNumber)
        setTextColor(color(com.google.android.material.R.attr.colorSecondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
        text = value
        setPadding(0, dp(6), 0, 0)
    }

    private fun text(value: String, secondary: Boolean = false, top: Int = 2) = TextView(this).apply {
        text = value
        textSize = 14f
        if (secondary) alpha = 0.7f
        setPadding(0, dp(top), 0, 0)
    }

    private fun row(title: String, value: String, sub: String? = null, dot: Int? = null, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
            if (onClick != null) {
                setBackgroundResource(TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
                setOnClickListener { onClick() }
            }
        }
        if (dot != null) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
                background = ContextCompat.getDrawable(this@StorageActivity, R.drawable.preset_dot)?.mutate()?.apply { setTint(dot) }
            })
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (sub != null) texts.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            alpha = 0.6f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        row.addView(texts)
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextAppearance(R.style.TextAppearance_Nothing_Label)
            setTextColor(color(android.R.attr.textColor))
            setPadding(dp(8), 0, 0, 0)
        })
        return row
    }

    // a split bar, parts are (size, colour); colour 0 is the empty track
    private fun bar(parts: List<Pair<Float, Int>>, height: Int = 10): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(8) }
            clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(height).toFloat()
                setColor(color(android.R.attr.textColorSecondary) and 0x22FFFFFF)
            }
        }
        val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
        for ((size, partColor) in parts) {
            if (size <= 0f) continue
            bar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, size / total)
                if (partColor != 0) setBackgroundColor(partColor)
            })
        }
        return bar
    }

    private fun color(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
