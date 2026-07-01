package com.waenhancer.features

import com.waenhancer.api.contracts.WaexFeature
import com.waenhancer.api.contracts.WaexFeatureMetadata
import com.waenhancer.api.contracts.WaexPreferenceManager
import javax.inject.Inject

class FileSizeSpooferFeature @Inject constructor(
    private val preferenceManager: WaexPreferenceManager
) : WaexFeature {
    
    private val metadata = WaexFeatureMetadata(
        "media.fileSizeSpoofer",
        "File Size Spoofer",
        "Spoof file size metadata to bypass chat file upload limits.",
        "media",
        "ic_file",
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
