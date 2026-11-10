package com.caydey.ffshare

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.caydey.ffshare.databinding.ActivityOnboardingBinding
import com.caydey.ffshare.utils.SaveFolder
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Settings.QualityPreset

// first launch: pick a quality, a save folder and allow notifications, then the app just works
class OnboardingActivity : AppCompatActivity() {
    private val settings: Settings by lazy { Settings(applicationContext) }
    private lateinit var binding: ActivityOnboardingBinding

    private val presetRadios = mapOf(
        R.id.radioMaxCompression to QualityPreset.MAX_COMPRESSION,
        R.id.radioBalanced to QualityPreset.BALANCED,
        R.id.radioHighQuality to QualityPreset.HIGH_QUALITY
    )

    // the notification step is skipped on android 12 and older (no permission needed) or if already allowed
    private val needsNotificationStep: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    private val stepCount: Int
        get() = if (needsNotificationStep) 3 else 2

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SaveFolder.set(this, uri)
        }
        updateStep()
    }

    // either answer finishes setup, compressing works without notifications too
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        finishOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOnboardingFolder.setOnClickListener { folderPickerLauncher.launch(SaveFolder.PICKER_START) }
        binding.btnOnboardingSkip.setOnClickListener { next() }
        binding.btnOnboardingNotNow.setOnClickListener { finishOnboarding() }
        binding.btnOnboardingNotifications.setOnClickListener {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.btnOnboardingNext.setOnClickListener { next() }
        binding.btnOnboardingBack.setOnClickListener { back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        updateStep()
    }

    private fun next() {
        when (binding.flipper.displayedChild) {
            0 -> {
                settings.applyQualityPreset(presetRadios[binding.radioPreset.checkedRadioButtonId] ?: QualityPreset.BALANCED)
                binding.flipper.showNext()
            }
            1 -> if (needsNotificationStep) binding.flipper.showNext() else finishOnboarding()
            else -> finishOnboarding()
        }
        updateStep()
    }

    private fun back() {
        if (binding.flipper.displayedChild == 0) {
            // leaving on the first step means "just use the defaults"
            finishOnboarding()
        } else {
            binding.flipper.showPrevious()
            updateStep()
        }
    }

    private fun updateStep() {
        val step = binding.flipper.displayedChild
        binding.txtStep.text = getString(R.string.onboarding_step, step + 1, stepCount)
        binding.btnOnboardingBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE

        val folderName = SaveFolder.displayName(this)
        binding.txtOnboardingFolder.text = folderName?.let { getString(R.string.onboarding_folder_chosen, it) } ?: ""
        // when a step's own button is the way forward (pick a folder, allow notifications), the big
        // Next button hides and skipping stays a small text button under it
        val waitingForFolder = step == 1 && folderName == null
        val askingNotifications = step == 2
        binding.btnOnboardingNext.visibility = if (waitingForFolder || askingNotifications) View.INVISIBLE else View.VISIBLE
        binding.btnOnboardingSkip.visibility = if (waitingForFolder) View.VISIBLE else View.GONE
        binding.btnOnboardingNext.setText(if (step == 1 && !needsNotificationStep) R.string.onboarding_done else R.string.onboarding_next)
    }

    private fun finishOnboarding() {
        if (!settings.isQualityPresetSet) {
            settings.applyQualityPreset(QualityPreset.BALANCED)
        }
        settings.onboardingDone = true
        finish()
    }
}
