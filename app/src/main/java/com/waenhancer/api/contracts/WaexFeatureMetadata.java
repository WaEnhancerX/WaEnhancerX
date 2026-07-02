package com.waenhancer.api.contracts;

import java.util.List;

public final class WaexFeatureMetadata {
    private final String id;
    private final String title;
    private final String description;
    private final String category;
    private final String icon;
    private final boolean isPro;
    private final boolean isExperimental;
    private final long minimumVersion;
    private final String visibility;
    private final List<WaexCapability> requiredCapabilities;

    public WaexFeatureMetadata(
            String id,
            String title,
            String description,
            String category,
            String icon,
            boolean isPro,
            boolean isExperimental,
            long minimumVersion,
            String visibility,
            List<WaexCapability> requiredCapabilities) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.icon = icon;
        this.isPro = isPro;
        this.isExperimental = isExperimental;
        this.minimumVersion = minimumVersion;
        this.visibility = visibility;
        this.requiredCapabilities = requiredCapabilities;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getIcon() {
        return icon;
    }

    public boolean isPro() {
        return isPro;
    }

    public boolean isExperimental() {
        return isExperimental;
    }

    public long getMinimumVersion() {
        return minimumVersion;
    }

    public String getVisibility() {
        return visibility;
    }

    public List<WaexCapability> getRequiredCapabilities() {
        return requiredCapabilities;
    }
}
