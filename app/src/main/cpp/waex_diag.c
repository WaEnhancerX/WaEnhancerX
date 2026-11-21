// JNI bridge for com.waenhancer.utils.NativeDiagPaths.
//
// Builds the root-hiding-tool (Magisk DenyList / Hide My Applist) file paths, package
// names, and shell command fragments that HmaDiagnostics and MagiskDenylistDiagnostics use
// to detect whether something is stopping WAEX's hooks from loading in WhatsApp (and to fix
// it). Only arm64-v8a is built (see app/build.gradle.kts); callers must check
// NativeDiagPaths.available first and degrade gracefully when this library is absent.
#include <jni.h>
#include <string.h>

static jstring newUtf(JNIEnv *env, const char *s) {
    return (*env)->NewStringUTF(env, s);
}

static jobjectArray newStringArray(JNIEnv *env, const char *const *items, int count) {
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray array = (*env)->NewObjectArray(env, count, stringClass, NULL);
    for (int i = 0; i < count; i++) {
        jstring item = newUtf(env, items[i]);
        (*env)->SetObjectArrayElement(env, array, i, item);
        (*env)->DeleteLocalRef(env, item);
    }
    return array;
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_lspConfigDb(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "/data/adb/lspd/config/modules_config.db");
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_hmaZygiskPath(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "/data/adb/modules/hma_oss_zygisk");
}

JNIEXPORT jobjectArray JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_knownHmaPackages(JNIEnv *env, jclass cls) {
    (void) cls;
    static const char *const packages[] = {
        "com.tsng.hidemyapplist",
        "org.frknkrc44.hma_oss",
        "com.google.android.hmal",
    };
    return newStringArray(env, packages, 3);
}

JNIEXPORT jobjectArray JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_knownHmaConfigPaths(JNIEnv *env, jclass cls) {
    (void) cls;
    static const char *const paths[] = {
        "/data/adb/hma/config.json",
        "/data/adb/hma_oss/config.json",
        "/data/adb/modules/hma_oss_zygisk/config.json",
        "/data/adb/modules/hma_oss/config.json",
        "/data/adb/modules/hide_my_applist/config.json",
        "/data/misc/hide_my_applist/config.json",
        "/data/misc/hide_my_applist_oss/config.json",
        "/data/system/hide_my_applist/config.json",
        "/data/system/users/0/hide_my_applist/config.json",
        "/data/data/org.frknkrc44.hma_oss/files/config.json",
        "/data/data/org.frknkrc44.hma_oss/files/temp_config.json",
        "/data/data/com.tsng.hidemyapplist/files/config.json",
        "/data/data/com.tsng.hidemyapplist/files/temp_config.json",
        "/data/data/com.google.android.hmal/files/config.json",
        "/data/user/0/org.frknkrc44.hma_oss/files/config.json",
        "/data/user/0/org.frknkrc44.hma_oss/files/temp_config.json",
        "/data/user/0/com.tsng.hidemyapplist/files/config.json",
        "/data/user/0/com.tsng.hidemyapplist/files/temp_config.json",
        "/data/user/0/com.google.android.hmal/files/config.json",
        "/data/user_de/0/org.frknkrc44.hma_oss/files/config.json",
        "/data/user_de/0/org.frknkrc44.hma_oss/files/temp_config.json",
        "/data/user_de/0/com.tsng.hidemyapplist/files/config.json",
        "/data/user_de/0/com.google.android.hmal/files/config.json",
    };
    return newStringArray(env, paths, 23);
}

// Combined root probe: is the HMA zygisk module present and enabled, is any HMA/HMAL app
// data directory present, or does any known HMA config glob resolve.
JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_hmaActiveCheckCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env,
        "[ -d /data/adb/modules/hma_oss_zygisk ] && [ ! -f /data/adb/modules/hma_oss_zygisk/disable ] && echo active; "
        "ls -d /data/adb/modules/*hma* /data/adb/modules/*hide_my_applist* 2>/dev/null; "
        "ls -d /data/data/org.frknkrc44.hma_oss /data/data/com.tsng.hidemyapplist /data/data/com.google.android.hmal 2>/dev/null; "
        "ls /data/adb/hma*/config.json /data/misc/hide_my_applist*/config.json /data/system/*hide_my_applist*/config.json 2>/dev/null; true");
}

// Checks the LSPosed module DB for an HMA/HMAL entry, via grep first and sqlite3 as fallback.
JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_lspHmaCheckCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env,
        "[ -f /data/adb/lspd/config/modules_config.db ] && "
        "(grep -E 'hidemyapp|hma' /data/adb/lspd/config/modules_config.db 2>/dev/null || "
        "sqlite3 /data/adb/lspd/config/modules_config.db "
        "\"SELECT 1 FROM modules WHERE (module_pkg_name LIKE '%hidemyapp%' OR module_pkg_name LIKE '%hma%') "
        "AND enabled = 1 LIMIT 1\" 2>/dev/null); true");
}

// Shallow filesystem fallback for discovering HMA/HMAL config files when the known paths
// above all miss (e.g. a non-stock install location).
JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_hmaShallowSearchCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "find /data/misc /data/adb /data/system -maxdepth 3 -name '*config.json' 2>/dev/null; true");
}

// Static glob fallback appended after the dynamically-discovered config path list in
// HmaDiagnostics.findAllHmaConfigPaths.
JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_hmaConfigGlobCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env,
        "ls /data/misc/hide_my_applist*/config.json /data/system/users/*/hide_my_applist*/config.json "
        "/data/adb/modules/*hma*/config.json /data/adb/modules/*hide_my_applist*/config.json 2>/dev/null; true");
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_lspLogGlob(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "/data/adb/lspd/log/*.log");
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_magiskVersionCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "magisk -v 2>/dev/null");
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_magiskDenylistStatusCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "magisk --denylist status 2>/dev/null; echo exit:$?");
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_magiskDenylistListCommand(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "magisk --denylist ls 2>/dev/null");
}

// packageName is inserted by the caller between these two fragments.
JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_magiskDenylistRemovePrefix(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, "magisk --denylist rm ");
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_utils_NativeDiagPaths_magiskDenylistRemoveSuffix(JNIEnv *env, jclass cls) {
    (void) cls;
    return newUtf(env, " 2>/dev/null; echo exit:$?");
}
