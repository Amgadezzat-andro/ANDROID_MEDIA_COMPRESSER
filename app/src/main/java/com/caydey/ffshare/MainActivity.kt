package com.caydey.ffshare

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.caydey.ffshare.databinding.ActivityMainBinding
import com.caydey.ffshare.utils.SaveFolder
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Settings.QualityPreset
import com.caydey.ffshare.utils.Utils


class MainActivity : AppCompatActivity() {

    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }
    private lateinit var binding: ActivityMainBinding

    // chip id -> preset, and the summary shown under the chips
    private val presetChips = mapOf(
        R.id.chipMaxCompression to QualityPreset.MAX_COMPRESSION,
        R.id.chipBalanced to QualityPreset.BALANCED,
        R.id.chipHighQuality to QualityPreset.HIGH_QUALITY,
        R.id.chipCustom to QualityPreset.CUSTOM
    )
    private val presetSummaries = mapOf(
        QualityPreset.MAX_COMPRESSION to R.string.preset_max_compression_summary,
        QualityPreset.BALANCED to R.string.preset_balanced_summary,
        QualityPreset.HIGH_QUALITY to R.string.preset_high_quality_summary,
        QualityPreset.CUSTOM to R.string.preset_custom_summary
    )
    // true while the chips are set from code, so the listener doesn't re-apply the preset
    private var updatingChips = false

    private val selectedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        // exited from file picker without selecting a file
        if (it.isEmpty()) {
            return@registerForActivityResult
        }

        val intent = Intent(this, HandleMediaActivity::class.java)
        val uris = ArrayList<Parcelable>(it) // convert List<> to ArrayList<> for intent

        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)

        // always send multiple since sending an array (uris)
        intent.action = Intent.ACTION_SEND_MULTIPLE

        // simulate clicking share button
        startActivity(intent)
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        SaveFolder.set(this, uri)
        updateSaveFolder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.lblVersion.text = getString(R.string.version, App.versionName)

        // first launch: quality, folder and notifications in three quick steps
        if (!settings.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // allow clicking on links
        binding.lblIntroductionLine0.movementMethod = LinkMovementMethod.getInstance()
        binding.lblIntroductionLine1.movementMethod = LinkMovementMethod.getInstance()
        binding.lblIntroductionLine2.movementMethod = LinkMovementMethod.getInstance()

        // Select File button listener
        binding.btnSelectFile.setOnClickListener {
            selectedFileLauncher.launch(utils.getAllowedMimes())
        }

        binding.chipGroupPreset.setOnCheckedStateChangeListener { _, checkedIds ->
            val preset = checkedIds.firstOrNull()?.let { presetChips[it] } ?: return@setOnCheckedStateChangeListener
            if (!updatingChips) {
                // custom keeps whatever is in the settings screen
                if (preset == QualityPreset.CUSTOM) settings.qualityPreset = preset else settings.applyQualityPreset(preset)
            }
            binding.lblPresetSummary.setText(presetSummaries.getValue(preset))
        }

        binding.btnChooseFolder.setOnClickListener { folderPickerLauncher.launch(SaveFolder.PICKER_START) }
        binding.btnClearFolder.setOnClickListener {
            SaveFolder.clear(this)
            updateSaveFolder()
        }
    }

    override fun onResume() {
        super.onResume()
        // settings screen may have switched the preset to custom, setup may have picked one
        if (!settings.isQualityPresetSet) {
            settings.applyQualityPreset(QualityPreset.BALANCED)
        }
        updatingChips = true
        binding.chipGroupPreset.check(presetChips.entries.first { it.value == settings.qualityPreset }.key)
        updatingChips = false
        updateSaveFolder()
        updateStats()
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

    private fun updateSaveFolder() {
        val folderName = SaveFolder.displayName(this)
        if (folderName == null) {
            binding.lblSaveFolder.setText(R.string.save_to_not_set)
            binding.btnClearFolder.visibility = View.GONE
        } else {
            binding.lblSaveFolder.text = folderName
            binding.btnClearFolder.visibility = View.VISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(applicationContext, PreferencesActivity::class.java))
            R.id.action_history -> startActivity(Intent(applicationContext, LogsActivity::class.java))
        }
        return super.onOptionsItemSelected(item)
    }
}
