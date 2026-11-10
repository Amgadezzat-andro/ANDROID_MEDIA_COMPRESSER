package com.caydey.ffshare

import android.os.Bundle
import androidx.preference.*
import com.caydey.ffshare.utils.Settings

class PreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        dynamicallyShowCustomName()
        dynamicallyAddCustomParamTooltips()
        linkQualityPreset()
        toggleAdvancedSettings()
    }
    private fun toggleAdvancedSettings() {
        val advancedCategories = arrayOf("prefs_photos", "prefs_videos", "prefs_files", "prefs_custom_params")
        val showAdvanced = findPreference<SwitchPreference>(Settings.SHOW_ADVANCED_SETTINGS) ?: return
        fun apply(show: Boolean) {
            for (key in advancedCategories) {
                findPreference<PreferenceCategory>(key)?.isVisible = show
            }
        }
        showAdvanced.setOnPreferenceChangeListener { _, value ->
            apply(value as Boolean)
            true
        }
        apply(showAdvanced.isChecked)
    }
    private fun linkQualityPreset() {
        val presetPreference = findPreference<ListPreference>(Settings.QUALITY_PRESET) ?: return
        // every setting any preset touches
        val presetKeys = Settings.QualityPreset.values().flatMap { it.values.keys }.toSet()

        // picking a preset fills in its settings, setting .value does not fire their listeners
        presetPreference.setOnPreferenceChangeListener { _, value ->
            val preset = Settings.QualityPreset.valueOf(value as String)
            preset.values.forEach { (key, presetValue) ->
                findPreference<ListPreference>(key)?.value = presetValue
            }
            true
        }

        // changing one of those settings by hand means the preset no longer applies
        for (key in presetKeys) {
            findPreference<ListPreference>(key)?.setOnPreferenceChangeListener { _, _ ->
                presetPreference.value = Settings.QualityPreset.CUSTOM.name
                true
            }
        }
    }
    private fun dynamicallyAddCustomParamTooltips() {
        val customParamKeys = arrayOf("pref_custom_video_params", "pref_custom_audio_params", "pref_custom_image_params")
        for (customParamKey in customParamKeys) {
            val element = findPreference<EditTextPreference>(customParamKey)
            element?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
    }
    private fun dynamicallyShowCustomName() {
        // only show pref_compressed_media_custom_name if pref_compressed_media_name is "Custom"
        val customMediaNamePreference = findPreference<EditTextPreference>("pref_compressed_media_custom_name")
        val compressedMediaNamePreference = findPreference<ListPreference>("pref_compressed_media_name")
        compressedMediaNamePreference?.setOnPreferenceChangeListener { _, value ->
            customMediaNamePreference?.isVisible = (value == "CUSTOM")
            true
        }
        // trigger update for initial load
        compressedMediaNamePreference?.callChangeListener(compressedMediaNamePreference.value)
    }
}