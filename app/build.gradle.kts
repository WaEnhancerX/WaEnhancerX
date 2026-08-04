plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.dagger.hilt.android)
}

android {
    namespace = "com.waenhancer.app"
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.waenhancer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Android/Compose standard dependencies
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // External dependencies needed on app classpath for Hilt annotation processing
    implementation(libs.retrofit.core)
    implementation(libs.okhttp.core)
    implementation(libs.room.runtime)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
    }
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Enforces package-level modular architecture rules for Wa Enhancer X"

    val srcDir = layout.projectDirectory.dir("src/main/java/com/waenhancer").asFile

    doLast {
        if (!srcDir.exists()) {
            println("Source directory com/waenhancer does not exist, skipping architecture check.")
            return@doLast
        }

        var violationCount = 0
        val violations = mutableListOf<String>()

        srcDir.walkTopDown().forEach { file ->
            if (file.isFile && (file.name.endsWith(".kt") || file.name.endsWith(".java"))) {
                val relativePath = file.relativeTo(srcDir).path
                
                // Determine which layer the file belongs to based on relative path
                val layer = when {
                    relativePath.startsWith("core/") -> "core"
                    relativePath.startsWith("hooks/") -> "hooks"
                    relativePath.startsWith("features/") -> "features"
                    relativePath.startsWith("api/") -> "api"
                    relativePath.startsWith("ui/") -> "ui"
                    relativePath.startsWith("plugins/") -> "plugins"
                    relativePath.startsWith("app/") -> "app"
                    relativePath.startsWith("compatibility/") -> "compatibility"
                    else -> "other"
                }

                if (layer == "other") return@forEach

                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        val importedPackage = trimmed
                            .substringAfter("import ")
                            .substringBefore(";")
                            .trim()

                        // Check import restrictions
                        val isViolating = when (layer) {
                            "core" -> {
                                importedPackage.startsWith("com.waenhancer.features") ||
                                        importedPackage.startsWith("com.waenhancer.ui") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.plugins")
                            }
                            "features" -> {
                                importedPackage.startsWith("com.waenhancer.core") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.ui") ||
                                        importedPackage.startsWith("com.waenhancer.plugins") ||
                                        importedPackage.startsWith("com.waenhancer.compatibility")
                            }
                            "ui" -> {
                                importedPackage.startsWith("com.waenhancer.core") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.plugins") ||
                                        importedPackage.startsWith("com.waenhancer.compatibility")
                            }
                            "hooks" -> {
                                importedPackage.startsWith("com.waenhancer.features") ||
                                        importedPackage.startsWith("com.waenhancer.ui") ||
                                        importedPackage.startsWith("com.waenhancer.plugins") ||
                                        importedPackage.startsWith("com.waenhancer.compatibility")
                            }
                            "api" -> {
                                importedPackage.startsWith("com.waenhancer.core") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.features") ||
                                        importedPackage.startsWith("com.waenhancer.ui") ||
                                        importedPackage.startsWith("com.waenhancer.plugins") ||
                                        importedPackage.startsWith("com.waenhancer.compatibility")
                            }
                            "plugins" -> {
                                importedPackage.startsWith("com.waenhancer.core") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.features") ||
                                        importedPackage.startsWith("com.waenhancer.ui") ||
                                        importedPackage.startsWith("com.waenhancer.compatibility")
                            }
                            "app" -> {
                                // App is the composition root: can use features, api, ui
                                // Must NOT directly access core internals, hooks, plugins, or compatibility
                                importedPackage.startsWith("com.waenhancer.core") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.plugins") ||
                                        importedPackage.startsWith("com.waenhancer.compatibility")
                            }
                            "compatibility" -> {
                                importedPackage.startsWith("com.waenhancer.core") ||
                                        importedPackage.startsWith("com.waenhancer.features") ||
                                        importedPackage.startsWith("com.waenhancer.hooks") ||
                                        importedPackage.startsWith("com.waenhancer.ui") ||
                                        importedPackage.startsWith("com.waenhancer.plugins") ||
                                        importedPackage.startsWith("com.waenhancer.app")
                            }
                            else -> false
                        }

                        if (isViolating) {
                            violationCount++
                            violations.add("Violation in file://${file.absolutePath} at line ${index + 1}: Forbidden import '$importedPackage' in '$layer' layer.")
                        }
                    }
                }
            }
        }

        if (violationCount > 0) {
            println("----------------------------------------------------------------------")
            println("🚨 ARCHITECTURE VERIFICATION FAILED: $violationCount violations found")
            println("----------------------------------------------------------------------")
            violations.forEach { println(it) }
            println("----------------------------------------------------------------------")
            throw GradleException("Architecture rules validation failed. Please fix the imports listed above.")
        } else {
            println("✅ Architecture verification successful: 0 violations found.")
        }
    }
}

tasks.named("check") {
    dependsOn("architectureCheck")
}


