package com.waenhancer.features

import com.waenhancer.api.contracts.WaexFeature
import com.waenhancer.api.contracts.WaexFeatureMetadata
import com.waenhancer.api.contracts.WaexPreferenceManager
import javax.inject.Inject

class FreezeLastSeenFeature @Inject constructor(
    private val preferenceManager: WaexPreferenceManager
) : WaexFeature {
    
    private val metadata = WaexFeatureMetadata(
        "privacy.freezeLastSeen",
        "Freeze Last Seen",
        "Keep your last seen timestamp static to browse stealthily.",
        "privacy",
        "ic_clock",
        false,
        false,
        226220205,
        "VISIBLE"
    )

    override fun getMetadata(): WaexFeatureMetadata = metadata

    override fun isEnabled(): Boolean = preferenceManager.getBoolean(metadata.id, false)

    override fun setEnabled(enabled: Boolean) {
        preferenceManager.putBoolean(metadata.id, enabled)
    }
}
