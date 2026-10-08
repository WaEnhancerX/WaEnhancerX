package com.waenhancer.notices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NoticePayload(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("notices") val notices: List<NoticeItem> = emptyList()
)

@Serializable
data class NoticeItem(
    @SerialName("id") val id: String,
    @SerialName("revision") val revision: Int = 0,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("severity") val severity: String = "info",
    @SerialName("title") val title: String = "Notice",
    @SerialName("message") val message: String = "",
    @SerialName("format") val format: String = "plain",
    @SerialName("dismissible") val dismissible: Boolean = true,
    @SerialName("targets") val targets: NoticeTargets = NoticeTargets(),
    @SerialName("actions") val actions: List<NoticeAction> = emptyList()
) {
    val primaryAction: NoticeAction?
        get() = actions.firstOrNull { it.style.equals("primary", ignoreCase = true) }
            ?: actions.firstOrNull { !it.style.equals("dismiss", ignoreCase = true) }

    val secondaryAction: NoticeAction?
        get() {
            val primary = primaryAction
            return actions.firstOrNull { it.style.equals("secondary", ignoreCase = true) }
                ?: actions.firstOrNull { it != primary && !it.style.equals("dismiss", ignoreCase = true) }
        }

    val dismissAction: NoticeAction?
        get() = actions.firstOrNull { it.style.equals("dismiss", ignoreCase = true) }

    val severityRank: Int
        get() = when (severity.lowercase()) {
            "critical", "error" -> 3
            "warning" -> 2
            else -> 1
        }
}

@Serializable
data class NoticeTargets(
    @SerialName("channels") val channels: List<String> = emptyList(),
    @SerialName("versionMin") val versionMin: Int? = null,
    @SerialName("versionMax") val versionMax: Int? = null,
    @SerialName("versionCodes") val versionCodes: NoticeTargetVersionCodes? = null,
    @SerialName("commitIds") val commitIds: List<String> = emptyList()
) {
    val minVersion: Int? get() = versionMin ?: versionCodes?.min
    val maxVersion: Int? get() = versionMax ?: versionCodes?.max
}

@Serializable
data class NoticeTargetVersionCodes(
    @SerialName("min") val min: Int? = null,
    @SerialName("max") val max: Int? = null
)

@Serializable
data class NoticeAction(
    @SerialName("type") val type: String = "url",
    @SerialName("label") val label: String = "",
    @SerialName("url") val url: String? = null,
    @SerialName("style") val style: String = "primary"
)
