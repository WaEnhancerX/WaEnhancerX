package com.waenhancer.config

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Key-only preference registry.
 *
 * Keep feature names, descriptions, and group labels in Android string resources:
 * `feat_<key>_title`, `feat_<key>_desc`, `sec_<groupId>`, and `catalog_tab_<tabId>`.
 * This makes the catalogue safe to use for navigation, settings rendering, and search
 * without copying translatable text into application code.
 */
@Serializable
data class PreferenceGroup(
    val id: String,
    val tabIndex: Int,
    val subTabId: String? = null,
    val tabId: String,
    val keys: List<String>,
    val proKeys: Set<String> = emptySet()
) {
    fun items(): List<FeatureItem> = keys.map { key ->
        FeatureItem(key, id, tabIndex, subTabId, tabId, key in proKeys)
    }
}

object FeatureCatalog {
    private const val ASSET_NAME = "preference_catalog.json"
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var groups: List<PreferenceGroup> = emptyList()
    @Volatile private var allFeatures: List<FeatureItem> = emptyList()
    @Volatile private var keyToItem: Map<String, FeatureItem> = emptyMap()
    @Volatile private var groupsByDestination: Map<Pair<Int, String?>, List<PreferenceGroup>> = emptyMap()

    val isInitialized: Boolean get() = groups.isNotEmpty()

    /** Load the authoritative, key-only preference catalog once for the process. */
    @JvmStatic
    fun initialize(context: Context) {
        if (groups.isNotEmpty()) return
        synchronized(this) {
            if (groups.isNotEmpty()) return
            val appContext = context.applicationContext
            val loadedGroups = appContext.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                json.decodeFromString<List<PreferenceGroup>>(reader.readText())
            }
            require(loadedGroups.flatMap { it.keys }.all { it.isNotBlank() }) {
                "Preference catalog contains a blank key"
            }
            val loadedFeatures = buildFeatures(loadedGroups)
            validateLocalizedResources(appContext, loadedGroups, loadedFeatures)
            groups = loadedGroups
            allFeatures = loadedFeatures
            keyToItem = loadedFeatures.associateBy { it.key }
            groupsByDestination = loadedGroups.groupBy { it.tabIndex to it.subTabId }
        }
    }

    val GROUPS: List<PreferenceGroup> get() = groups

    // A key may be rendered in more than one screen. Search uses this canonical route.
    private val canonicalGroupOverrides = mapOf(
        "download_view_once" to "media_utility_downloader",
        "send_audio_as_voice_status" to "media_quality_status"
    )

    val ALL_FEATURES: List<FeatureItem> get() = allFeatures

    private fun buildFeatures(source: List<PreferenceGroup>): List<FeatureItem> = source
        .flatMap { it.items() }
        .filter { item -> canonicalGroupOverrides[item.key]?.let { it == item.groupId } ?: true }
        .distinctBy { it.key }

    @JvmStatic
    fun findByKey(key: String?): FeatureItem? = key?.let {
        keyToItem[it] ?: keyToItem[PreferenceKeyAliases.canonical(it)]
    }

    /** Resolves legacy preference aliases without requiring catalog initialization. */
    @JvmStatic
    fun canonicalKey(key: String): String = PreferenceKeyAliases.canonical(key)

    /** Groups that should be rendered for one settings destination. */
    fun groupsFor(tabIndex: Int, subTabId: String?): List<PreferenceGroup> =
        groupsByDestination[tabIndex to subTabId].orEmpty()

    private fun validateLocalizedResources(
        context: Context,
        loadedGroups: List<PreferenceGroup>,
        loadedFeatures: List<FeatureItem>
    ) {
        fun hasString(name: String) = context.resources.getIdentifier(name, "string", context.packageName) != 0
        val missing = buildList {
            loadedFeatures.forEach { feature ->
                if (!hasString("feat_${feature.key}_title")) add("feat_${feature.key}_title")
                if (!hasString("feat_${feature.key}_desc")) add("feat_${feature.key}_desc")
            }
            loadedGroups.map { it.id }.distinct().forEach { groupId ->
                if (!hasString("sec_$groupId")) add("sec_$groupId")
            }
            loadedGroups.map { it.tabId }.distinct().forEach { tabId ->
                if (!hasString("catalog_tab_$tabId")) add("catalog_tab_$tabId")
            }
        }
        require(missing.isEmpty()) {
            "Preference catalog references missing localized strings: ${missing.joinToString()}"
        }
    }
}
