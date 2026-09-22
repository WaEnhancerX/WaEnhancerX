import java.util.Properties
import java.io.FileInputStream

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
        versionCode = project.findProperty("VERSION_CODE")?.toString()?.toInt() ?: 1
        versionName = project.findProperty("VERSION_NAME")?.toString() ?: "1.0.0"
    }

    signingConfigs {
        create("releaseConfig") {
            val keystorePropertiesFile = rootProject.file("local.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }
            val androidStoreFile = (project.findProperty("androidStoreFile") as? String)
                ?: keystoreProperties.getProperty("androidStoreFile") ?: "key.jks"
            val keyFile = rootProject.file(androidStoreFile)
            if (keyFile.exists()) {
                storeFile = keyFile
                storePassword = (project.findProperty("androidStorePassword") as? String)
                    ?: keystoreProperties.getProperty("androidStorePassword") ?: "123456"
                keyAlias = (project.findProperty("androidKeyAlias") as? String)
                    ?: keystoreProperties.getProperty("androidKeyAlias") ?: "my-alias"
                keyPassword = (project.findProperty("androidKeyPassword") as? String)
                    ?: keystoreProperties.getProperty("androidKeyPassword") ?: "123456"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            val config = signingConfigs.getByName("releaseConfig")
            if (config.storeFile != null && config.storeFile!!.exists()) {
                signingConfig = config
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val config = signingConfigs.getByName("releaseConfig")
            if (config.storeFile != null && config.storeFile!!.exists()) {
                signingConfig = config
            }
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
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.forEach {
            val output = it as? com.android.build.gradle.api.ApkVariantOutput
            if (output != null) {
                val suffix = if (variant.buildType.name == "debug") "_debug" else "_release"
                output.outputFileName = "WaEnhancerX-v${variant.versionName}${suffix}.apk"
            }
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
    implementation(libs.markwon.core)
    implementation(libs.markwon.html)
    implementation(libs.room.runtime)

    // Xposed Framework & DexKit Hooking Engine
    compileOnly(libs.libxposed.legacy)
    implementation(libs.dexkit)

    // Submodule Licensing Library (Private Submodule)
    implementation(project(":licensing"))


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

