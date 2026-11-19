package com.waenhancer.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.waenhancer.config.PreferenceStores

/**
 * Release channels, ordered from least to most stable. This file is the single source of truth for
 * channel parsing and for the Android version code formula:
 *
 *   versionCode = (X * 10000 + Y * 100 + Z) * 1000 + slot
 *   nightly: slot = N (1..499), beta: slot = 500 + N (N in 1..498), stable: slot = 999
 */
enum class UpdateChannel(val id: String, val stabilityRank: Int) {
    NIGHTLY("nightly", 0),
    BETA("beta", 1),
    STABLE("stable", 2);

    /** Channels whose releases may be offered as an update to a user who selected this channel. */
    fun eligibleChannels(): Set<UpdateChannel> = entries.filter { it.stabilityRank >= stabilityRank }.toSet()

    companion object {
        const val PREF_KEY = "update_channel"
        const val MAX_BASE_MAJOR = 209
        private const val STABLE_SLOT = 999L
        private const val BETA_SLOT_OFFSET = 500L
        private val versionShape = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-(.+))?$")
        private val nightlySuffix = Regex("^nightly\\.(\\d+)$", RegexOption.IGNORE_CASE)
        private val digits = Regex("\\d+")

        fun fromId(id: String?): UpdateChannel? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** `-nightly.` is NIGHTLY, no suffix is STABLE, every other suffix is BETA (legacy tags). */
        fun fromVersionName(name: String): UpdateChannel {
            val suffix = suffixOf(normalizeVersion(name)) ?: return STABLE
            return if (suffix.startsWith("nightly.", ignoreCase = true)) NIGHTLY else BETA
        }

        /** Android version code for [name], or null when the name or its build slot is invalid. */
        fun versionCodeOf(name: String): Long? {
            val normalized = normalizeVersion(name)
            val match = versionShape.matchEntire(normalized) ?: return null
            val major = match.groupValues[1].toLongOrNull() ?: return null
            val minor = match.groupValues[2].toLongOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toLongOrNull() ?: return null
            if (major > MAX_BASE_MAJOR || minor !in 0..99 || patch !in 0..99) return null
            val base = (major * 10_000L + minor * 100L + patch) * 1_000L
            val suffix = match.groups[4]?.value ?: return base + STABLE_SLOT
            val slot = if (fromVersionName(normalized) == NIGHTLY) {
                val n = nightlySuffix.find(suffix)?.groupValues?.get(1)?.toLongOrNull() ?: return null
                if (n !in 1..499) return null
                n
            } else {
                val n = digits.findAll(suffix).lastOrNull()?.value?.toLongOrNull() ?: return null
                if (n !in 1..498) return null
                BETA_SLOT_OFFSET + n
            }
            return base + slot
        }

        /** Stored selection, falling back to the installed build's channel. */
        fun resolveSelected(storedId: String?, installedVersionName: String): UpdateChannel =
            fromId(storedId) ?: fromVersionName(installedVersionName)

        fun selected(context: Context, installed: InstalledBuild = InstalledBuild.read(context)): UpdateChannel =
            resolveSelected(
                PreferenceStores.privateStore(context).getString(PREF_KEY, null),
                installed.versionName
            )

        /** Moving to a less stable channel needs an explicit confirmation. */
        fun requiresRiskConfirmation(from: UpdateChannel, to: UpdateChannel): Boolean =
            to.stabilityRank < from.stabilityRank

        private fun suffixOf(normalized: String): String? {
            val i = normalized.indexOf('-')
            return if (i > 0) normalized.substring(i + 1) else null
        }
    }
}

/** The running build. [versionCode] is on the channel formula's scale (see [read]). */
data class InstalledBuild(val versionName: String, val versionCode: Long) {
    val channel: UpdateChannel get() = UpdateChannel.fromVersionName(versionName)

    companion object {
        /**
         * Legacy installs carry old, unrelated codes (e.g. 20300 for 2.0.3). Their name still maps onto
         * the new scale, so the name-derived code wins and the package code is only a fallback.
         */
        fun of(versionName: String, packageVersionCode: Long) = InstalledBuild(
            versionName,
            UpdateChannel.versionCodeOf(versionName) ?: packageVersionCode
        )

        fun read(context: Context): InstalledBuild {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return of(info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info))
        }
    }
}
