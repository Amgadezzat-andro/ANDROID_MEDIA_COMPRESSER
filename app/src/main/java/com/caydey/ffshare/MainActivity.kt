package com.caydey.ffshare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.caydey.ffshare.databinding.ActivityMainBinding
import com.caydey.ffshare.databinding.ItemPresetCardBinding
import com.caydey.ffshare.utils.MediaAccess
import com.caydey.ffshare.utils.SaveFolder
import com.caydey.ffshare.utils.StorageScanner
import kotlin.concurrent.thread
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Settings.QualityPreset
import com.caydey.ffshare.utils.Utils
import com.google.android.material.dialog.MaterialAlertDialogBuilder


class MainActivity : AppCompatActivity() {

    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }
    private lateinit var binding: ActivityMainBinding

    private val presetAdapter = PresetPagerAdapter(onOpenSettings = { openSettings() })
    // only a swipe or a dot tap picks a preset, not the pager settling on the saved one
    private var userSwiping = false
    // true while the photo format buttons are set from code
    private var updatingPhotoFormat = false
    // the look this screen was built with, changing it in settings rebuilds the screen
    private var appliedTheme = AppTheme.NOTHING

    // the files app, no limit on how many (the photo picker stops at 100). the main button shows only
    // photos and videos, "browse files" adds audio
    private val selectedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        compress(it)
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        SaveFolder.set(this, uri)
        updateSaveFolder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = AppTheme.apply(this)
        super.onCreate(savedInstanceState)

        // the launcher can start a second copy on top of a restored task, keep only the first
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // the toolbar overlay picks its own title colour, which is wrong on themes like midnight
        binding.toolbar.setTitleTextColor(android.util.TypedValue().let { v ->
            theme.resolveAttribute(android.R.attr.textColor, v, true)
            if (v.resourceId != 0) getColor(v.resourceId) else v.data
        })

        binding.lblVersion.text = getString(R.string.version, App.versionName)

        // first launch: quality, folder and notifications in three quick steps.
        // a restored screen (rotation, app reopened after android closed it) already has it on top
        if (!settings.onboardingDone && savedInstanceState == null) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.btnSelectFile.setOnClickListener {
            selectedFileLauncher.launch(arrayOf("image/*", "video/*"))
        }
        binding.btnBrowseFiles.setOnClickListener {
            selectedFileLauncher.launch(utils.getAllowedMimes())
        }

        setUpPresetPager()

        binding.cardStorage.setOnClickListener { startActivity(Intent(this, StorageActivity::class.java)) }

        binding.togglePhotoFormat.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked || updatingPhotoFormat) return@addOnButtonCheckedListener
            settings.imageConversionOutput = if (buttonId == R.id.btnFormatWebp) Utils.MediaType.WEBP else Utils.MediaType.UNKNOWN
            updatePhotoFormat()
        }

        binding.btnChooseFolder.setOnClickListener { folderPickerLauncher.launch(SaveFolder.PICKER_START) }
        binding.btnClearFolder.setOnClickListener {
            SaveFolder.clear(this)
            updateSaveFolder()
        }
    }

    override fun onResume() {
        super.onResume()
        if (settings.appTheme != appliedTheme) {
            recreate()
            return
        }
        // settings screen may have switched the preset to custom, setup may have picked one
        if (!settings.isQualityPresetSet) {
            settings.applyQualityPreset(QualityPreset.BALANCED)
        }
        val preset = settings.qualityPreset
        presetAdapter.selected = preset
        userSwiping = false
        binding.pagerPreset.setCurrentItem(preset.ordinal, false)
        updateDots(preset.ordinal)
        updatePhotoFormat()
        updateOpenBatch()
        updateStorage()
        updateSaveFolder()
        updateStats()
    }

    // same as sharing to the app from the gallery
    private fun compress(uris: List<Uri>) {
        // left the picker without choosing anything
        if (uris.isEmpty()) {
            return
        }
        val intent = Intent(this, HandleMediaActivity::class.java)
        intent.action = Intent.ACTION_SEND_MULTIPLE
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Parcelable>(uris))
        startActivity(intent)
    }

    private fun setUpPresetPager() {
        val pager = binding.pagerPreset
        pager.adapter = presetAdapter
        pager.offscreenPageLimit = 1
        // neighbouring cards peek in at the edges so it's clear there is more to swipe to
        val sidePadding = resources.getDimensionPixelSize(R.dimen.preset_card_side_padding)
        (pager.getChildAt(0) as RecyclerView).apply {
            setPadding(sidePadding, 0, sidePadding, 0)
            clipToPadding = false
        }
        pager.setPageTransformer(MarginPageTransformer(resources.getDimensionPixelSize(R.dimen.preset_card_gap)))

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                // a drag that springs back to the same card never selects a page, so the flag has to end
                // here, or the next page change made by code would count as the user's choice
                when (state) {
                    ViewPager2.SCROLL_STATE_DRAGGING -> userSwiping = true
                    ViewPager2.SCROLL_STATE_IDLE -> userSwiping = false
                }
            }

            override fun onPageSelected(position: Int) {
                updateDots(position)
                if (userSwiping) {
                    userSwiping = false
                    selectPreset(PresetPagerAdapter.PRESETS[position])
                }
            }
        })

        val dotSize = resources.getDimensionPixelSize(R.dimen.preset_dot_touch_size)
        PresetPagerAdapter.PRESETS.forEachIndexed { index, preset ->
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
                setImageResource(R.drawable.preset_dot)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = getString(R.string.preset_dot_description, getString(PresetPagerAdapter.nameOf(preset)))
                setOnClickListener {
                    pager.setCurrentItem(index, true)
                    selectPreset(preset)
                }
            }
            binding.presetDots.addView(dot)
        }

        // cards differ in length and fonts can be large, so the pager takes the tallest card's height
        pager.post { fitPagerHeight() }
    }

    private fun selectPreset(preset: QualityPreset) {
        // called from the pager's scroll callback, the outline can only change once that pass is over
        binding.pagerPreset.post { presetAdapter.selected = preset }
        if (preset == settings.qualityPreset) return
        // custom keeps whatever is in the settings screen
        if (preset == QualityPreset.CUSTOM) settings.qualityPreset = preset else settings.applyQualityPreset(preset)
    }

    private fun updateDots(position: Int) {
        for (i in 0 until binding.presetDots.childCount) {
            binding.presetDots.getChildAt(i).alpha = if (i == position) 1f else 0.25f
        }
    }

    private fun fitPagerHeight() {
        val pager = binding.pagerPreset
        val recycler = pager.getChildAt(0) as RecyclerView
        val cardWidth = pager.width - recycler.paddingLeft - recycler.paddingRight
        if (cardWidth <= 0) return
        val tallest = PresetPagerAdapter.PRESETS.maxOf { preset ->
            val card = ItemPresetCardBinding.inflate(layoutInflater, recycler, false)
            presetAdapter.bind(card, preset)
            card.root.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            card.root.measuredHeight
        }
        if (pager.layoutParams.height != tallest) {
            pager.layoutParams = pager.layoutParams.apply { height = tallest }
        }
    }

    // a quick look at what photos, videos and audio take, the storage screen has the details
    private fun updateStorage() {
        if (!MediaAccess.has(this)) {
            binding.storageBar.visibility = View.GONE
            binding.lblStorageTotal.text = ""
            binding.lblStorageSummary.setText(R.string.storage_card_hint)
            return
        }
        val savePath = SaveFolder.displayName(this)
        thread(name = "storage-card") {
            val report = StorageScanner(applicationContext).scan(savePath)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val sizes = StorageScanner.Kind.values().map { report.totals.getValue(it).bytes }
                binding.lblStorageTotal.text = utils.bytesToHuman(report.mediaBytes)
                binding.lblStorageSummary.text = getString(R.string.storage_card_summary,
                    utils.bytesToHuman(sizes[0]), utils.bytesToHuman(sizes[1]), utils.bytesToHuman(sizes[2]))
                val colors = listOf(com.google.android.material.R.attr.colorPrimary,
                    com.google.android.material.R.attr.colorSecondary, android.R.attr.textColorSecondary).map { themeColor(it) }
                binding.storageBar.removeAllViews()
                binding.storageBar.clipToOutline = true
                binding.storageBar.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8 * resources.displayMetrics.density
                    setColor(themeColor(android.R.attr.textColorSecondary) and 0x22FFFFFF)
                }
                val total = sizes.sum().coerceAtLeast(1).toFloat()
                sizes.forEachIndexed { i, size ->
                    if (size > 0) binding.storageBar.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, size / total)
                        setBackgroundColor(colors[i])
                    })
                }
                binding.storageBar.visibility = View.VISIBLE
            }
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    // the compress screen is hidden from recents, without notifications this is the only way back to it
    private fun updateOpenBatch() {
        val state = HandleMediaActivity.openBatch
        binding.cardOpenBatch.visibility = if (state == HandleMediaActivity.Companion.BatchState.NONE) View.GONE else View.VISIBLE
        binding.lblOpenBatch.setText(
            if (state == HandleMediaActivity.Companion.BatchState.RUNNING) R.string.open_batch_running else R.string.open_batch_finished
        )
        binding.cardOpenBatch.setOnClickListener {
            // same as tapping the notification: brings the existing screen back, never starts a batch
            startActivity(Intent(this, HandleMediaActivity::class.java).putExtra(HandleMediaActivity.EXTRA_FROM_NOTIFICATION, true))
        }
    }

    // webp or the original's format; other formats can only be picked in the advanced settings
    private fun updatePhotoFormat() {
        val format = settings.imageConversionOutput
        updatingPhotoFormat = true
        when (format) {
            Utils.MediaType.WEBP -> binding.togglePhotoFormat.check(R.id.btnFormatWebp)
            Utils.MediaType.UNKNOWN -> binding.togglePhotoFormat.check(R.id.btnFormatOriginal)
            else -> binding.togglePhotoFormat.clearChecked()
        }
        updatingPhotoFormat = false
        binding.lblPhotoFormatHint.text = when (format) {
            Utils.MediaType.WEBP -> getString(R.string.photo_format_webp_hint)
            Utils.MediaType.UNKNOWN -> getString(R.string.photo_format_original_hint)
            else -> getString(R.string.photo_format_other_hint, format.name)
        }
    }

    private fun updateStats() {
        val inputBytes = settings.statsInputBytes
        val outputBytes = settings.statsOutputBytes
        if (settings.statsFiles == 0 || inputBytes == 0L) {
            // "0 B saved" reads like a failure before anything was compressed
            binding.lblStatsSaved.setText(R.string.stats_ready)
            binding.lblStatsDetail.setText(R.string.stats_empty)
        } else {
            binding.lblStatsSaved.text = getString(R.string.stats_saved, utils.bytesToHuman(maxOf(inputBytes - outputBytes, 0L)))
            binding.lblStatsDetail.text = getString(R.string.stats_detail, settings.statsFiles, (1 - outputBytes.toDouble() / inputBytes) * 100)
        }
    }

    // folder name big, its path and what happens to originals underneath
    private fun updateSaveFolder() {
        val folderPath = SaveFolder.displayName(this)
        val isSet = folderPath != null
        binding.imgSaveFolderDot.visibility = if (isSet) View.VISIBLE else View.GONE
        binding.lblSaveFolderState.setText(if (isSet) R.string.save_to_on else R.string.save_to_off)
        if (folderPath == null) {
            binding.lblSaveFolder.setText(R.string.save_to_none)
            binding.lblSaveFolderPath.setText(R.string.save_to_not_set)
            binding.btnChooseFolder.setText(R.string.save_to_choose)
        } else {
            binding.lblSaveFolder.text = folderPath.substringAfterLast('/')
            binding.lblSaveFolderPath.text = getString(R.string.save_to_path, folderPath)
            binding.btnChooseFolder.setText(R.string.save_to_change)
        }
        binding.btnClearFolder.visibility = if (isSet) View.VISIBLE else View.GONE
    }

    // the projects this app is built on, with their links
    private fun showAbout() {
        val credits = listOf(R.string.introduction_line_0, R.string.introduction_line_1, R.string.introduction_line_2)
            .map { getText(it) }
        val message = android.text.SpannableStringBuilder()
        credits.forEachIndexed { i, line -> if (i > 0) message.append("\n\n"); message.append(line) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.version, App.versionName))
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openSettings() {
        startActivity(Intent(applicationContext, PreferencesActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> openSettings()
            R.id.action_history -> startActivity(Intent(applicationContext, LogsActivity::class.java))
            R.id.action_about -> showAbout()
        }
        return super.onOptionsItemSelected(item)
    }

}
