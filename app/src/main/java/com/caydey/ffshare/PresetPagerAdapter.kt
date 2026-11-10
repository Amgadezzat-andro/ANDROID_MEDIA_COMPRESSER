package com.caydey.ffshare

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.caydey.ffshare.databinding.ItemPresetCardBinding
import com.caydey.ffshare.utils.Settings.QualityPreset

// the quality presets as swipeable cards on the main screen
class PresetPagerAdapter(private val onOpenSettings: () -> Unit) : RecyclerView.Adapter<PresetPagerAdapter.CardHolder>() {

    class CardHolder(val binding: ItemPresetCardBinding) : RecyclerView.ViewHolder(binding.root)

    private class PresetText(val name: Int, val tagline: Int, val spec: Int, val example: Int)

    // the card currently in use gets an outline and an "In use" badge
    var selected: QualityPreset = QualityPreset.BALANCED
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyItemChanged(old.ordinal, PAYLOAD_SELECTION)
            notifyItemChanged(value.ordinal, PAYLOAD_SELECTION)
        }

    override fun getItemCount() = PRESETS.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        CardHolder(ItemPresetCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        bind(holder.binding, PRESETS[position])
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int, payloads: MutableList<Any>) {
        // a selection change only restyles the card, rebinding all the text would flicker
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION }) {
            bindSelection(holder.binding, PRESETS[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    // also used to measure the tallest card, so the pager fits every card at any font size
    fun bind(binding: ItemPresetCardBinding, preset: QualityPreset) {
        val text = TEXTS.getValue(preset)
        binding.txtPresetName.setText(text.name)
        binding.txtPresetTagline.setText(text.tagline)
        binding.txtPresetSpec.setText(text.spec)
        binding.txtPresetExample.setText(text.example)
        binding.btnPresetSettings.visibility = if (preset == QualityPreset.CUSTOM) View.VISIBLE else View.GONE
        binding.btnPresetSettings.setOnClickListener { onOpenSettings() }
        bindSelection(binding, preset)
    }

    private fun bindSelection(binding: ItemPresetCardBinding, preset: QualityPreset) {
        val inUse = preset == selected
        val density = binding.root.resources.displayMetrics.density
        binding.card.strokeWidth = if (inUse) (2 * density).toInt() else 0
        binding.txtPresetBadge.visibility = when {
            inUse -> View.VISIBLE
            preset == QualityPreset.BALANCED -> View.VISIBLE
            else -> View.GONE
        }
        binding.txtPresetBadge.setText(if (inUse) R.string.preset_in_use else R.string.preset_recommended)
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        // card order, the same order as the enum so position == ordinal
        val PRESETS = QualityPreset.values().toList()

        private val TEXTS = mapOf(
            QualityPreset.ULTRA to PresetText(R.string.preset_ultra, R.string.preset_ultra_tagline,
                R.string.preset_ultra_spec, R.string.preset_ultra_example),
            QualityPreset.MAX_COMPRESSION to PresetText(R.string.preset_max_compression, R.string.preset_max_compression_tagline,
                R.string.preset_max_compression_spec, R.string.preset_max_compression_example),
            QualityPreset.BALANCED to PresetText(R.string.preset_balanced, R.string.preset_balanced_tagline,
                R.string.preset_balanced_spec, R.string.preset_balanced_example),
            QualityPreset.HIGH_QUALITY to PresetText(R.string.preset_high_quality, R.string.preset_high_quality_tagline,
                R.string.preset_high_quality_spec, R.string.preset_high_quality_example),
            QualityPreset.CUSTOM to PresetText(R.string.preset_custom, R.string.preset_custom_tagline,
                R.string.preset_custom_spec, R.string.preset_custom_example)
        )

        fun nameOf(preset: QualityPreset) = TEXTS.getValue(preset).name
    }
}
