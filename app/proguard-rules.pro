# Waex ProGuard / R8 Rules

# 1. General Optimization & Obfuscation settings
-repackageclasses 'a'
-allowaccessmodification
-overloadaggressively

# Keep source file names and line numbers for stack trace reporting
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 2. Coroutines Shrinking & Optimization
# Strip coroutine assertions and debug trace recovery in production builds
-assumevalues class kotlinx.coroutines.internal.DiagnosticsKt {
    boolean getASSERTIONS_ENABLED() return false;
}
-dontwarn kotlinx.coroutines.**

# 3. Kotlinx Serialization
# Keep Companion and serializers. We allow obfuscation of the companion and serialization helper classes
-keepclassmembers class * {
    *** Companion;
    *** $serializer;
}
# Keep the fields of classes marked with @Serializable to allow JSON mapping/reflection
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn kotlinx.serialization.compat.bridge.**

# 4. Room Database Optimization
# Room database implementations are loaded by reflection. We keep the database class constructors.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-dontwarn androidx.room.paging.**

# 5. Retrofit & OkHttp (R8 strips unused library code, keeping only reflections)
# Retrofit AAR bundles its own consumer rules. We only need to keep HTTP method annotations on interfaces.
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.** <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# 6. Hilt & Jetpack Compose
# ViewModels are instantiated via reflection. Keep only their constructors.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
# Hilt, Activity, Fragment, Service, and BroadcastReceiver classes are automatically kept 
# by Android Gradle Plugin's default proguard-android-optimize.txt via AndroidManifest analysis.

# 7. Plugin Class Loading System (CRITICAL)
# API contract interfaces used by dynamically loaded plugins MUST not be obfuscated or stripped.
-keep interface com.waenhancer.api.contracts.** { *; }

# 8. Keep Data Transfer Objects (DTOs) & Models (prevent serialization / DB schema mismatch)
# These rules target the new package-level modular structure.
-keep class com.waenhancer.api.contracts.** { *; }
-keep class com.waenhancer.features.**.model.** { *; }
-keep class com.waenhancer.core.**.entity.** { *; }

# 9. Markwon & Commonmark optional extension dependencies
-dontwarn org.commonmark.**
-dontwarn io.noties.markwon.**

# 10. Xposed Framework Entrypoints & Module Status Sentinel (CRITICAL)
-keep public class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep public class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep public class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

-keep class com.waenhancer.xposed.MainHook { *; }
-keep class com.waenhancer.xposed.utils.ModuleStatus {
    public static boolean isModuleActive();
}


