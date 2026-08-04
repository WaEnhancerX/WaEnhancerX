package com.waenhancer.api.contracts

data class WaexPreferenceGroup(
    val name: String,
    val category: String,
    val keys: List<WaexPreferenceKey<*>>
) {
    init {
        val validCategories = setOf(
            "privacy", "media", "status", "chat",
            "interface", "automation", "security", "pro"
        )
        require(category in validCategories) { "Invalid category namespace: $category" }
        
        for (key in keys) {
            val keyCategory = key.key.split(".")[0]
            require(keyCategory == category) {
                "Key '${key.key}' does not belong to category '$category'"
            }
        }
    }
}
