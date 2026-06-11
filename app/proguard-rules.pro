# Waex ProGuard / R8 Rules

# 1. General Optimization & Obfuscation settings
-repackageclasses 'a'
-allowaccessmodification

# Keep source file names and line numbers for stack trace reporting
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 2. Kotlinx Serialization Rules
# Keep serializers and serializable class structures
-keep class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    *** Companion;
    *** $serializer;
}
-dontwarn kotlinx.serialization.compat.bridge.**

# 3. Retrofit & OkHttp Rules
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers class * {
    @retrofit2.http.** <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# 4. Room Database Rules
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase$Callback
-dontwarn androidx.room.paging.**

# 5. Hilt / Dagger DI Rules
-keep class dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.ContentProvider
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Activity
-keep class * extends androidx.fragment.app.Fragment
-keep class * extends androidx.viewmodel.ViewModel
-keep class * extends androidx.lifecycle.ViewModel

# 6. Keep domain models and network DTO classes to prevent serialization issues
-keep class com.waenhancer.domain.model.** { *; }
-keep class com.waenhancer.data.remote.dto.** { *; }
-keep class com.waenhancer.core.database.entity.** { *; }
