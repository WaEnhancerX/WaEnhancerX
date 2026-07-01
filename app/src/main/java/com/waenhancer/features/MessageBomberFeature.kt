package com.waenhancer.features

import com.waenhancer.api.contracts.WaexFeature
import com.waenhancer.api.contracts.WaexFeatureMetadata
import com.waenhancer.api.contracts.WaexPreferenceManager
import javax.inject.Inject

class MessageBomberFeature @Inject constructor(
    private val preferenceManager: WaexPreferenceManager
) : WaexFeature {
    
    private val metadata = WaexFeatureMetadata(
        "automation.messageBomber",
        "Message Bomber",
        "Send repeated messages automatically for chat load testing.",
        "automation",
        "ic_bomb",
        true, // Pro Feature
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
