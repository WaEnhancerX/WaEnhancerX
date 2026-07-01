package com.waenhancer.features

import com.waenhancer.api.contracts.WaexFeature
import com.waenhancer.api.contracts.WaexFeatureMetadata
import com.waenhancer.api.contracts.WaexPreferenceManager
import javax.inject.Inject

class HideSeenFeature @Inject constructor(
    private val preferenceManager: WaexPreferenceManager
) : WaexFeature {
    
    private val metadata = WaexFeatureMetadata(
        "privacy.hideSeen",
        "Hide Read Receipts",
        "Read messages privately without triggering blue ticks or play receipts.",
        "privacy",
        "ic_eye_off",
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
