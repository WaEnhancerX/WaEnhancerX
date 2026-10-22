package com.waenhancer.utils;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Rule and Android Base64 contracts, separate from the complete diagnostics flow. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE)
public class HmaContractTest {
    private static final String TARGET = "com.whatsapp", MODULE = "com.waenhancer";
    private static final String PATH = "/data/misc/hide_my_applist_qa/config.json";

    private static JSONArray array(String... values) { return new JSONArray(Arrays.asList(values)); }
    private static JSONObject scope(boolean whitelist) throws Exception {
        return new JSONObject().put("useWhitelist", whitelist).put("extraAppList", new JSONArray())
                .put("applyTemplates", new JSONArray()).put("applyPresets", new JSONArray());
    }
    private static JSONObject document(JSONObject scope) throws Exception {
        return new JSONObject().put("scope", new JSONObject().put(TARGET, scope)).put("templates", new JSONObject());
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static String envelope(byte[] bytes) throws Exception {
        return digest(bytes) + "  " + PATH + "\n" + Base64.encodeToString(bytes, Base64.DEFAULT);
    }

    @Test public void acceptsOnlyDocumentedServiceRootsAndManagerFiles() {
        String[] accepted = {
            "/data/misc/hide_my_applist/config.json",
            "/data/misc/hide_my_applist_a123/config.json",
            "/data/system/hide_my_applist_legacy/config.json",
            "/data/data/com.tsng.hidemyapplist/files/config.json",
            "/data/data/org.frknkrc44.hma_oss/files/config.json",
            "/data/data/com.google.android.hmal/files/config.json",
            "/data/user/0/com.tsng.hidemyapplist/files/config.json",
            "/data/user/0/org.frknkrc44.hma_oss/files/config.json",
            "/data/user/0/com.google.android.hmal/files/config.json",
            "/data/user/10/org.frknkrc44.hma_oss/files/config.json"
        };
        for (String value : accepted) assertTrue(value, HmaConfigFile.isConfigPath(value));
    }

    @Test public void rejectsForeignOwnersNestedServiceDirectoriesAndUndocumentedRoots() {
        String[] rejected = {
            "/data/data/example.foreign/files/hma/config.json",
            "/data/user/0/example.foreign/files/hma_oss/config.json",
            "/data/data/example.foreign/com.tsng.hidemyapplist/config.json",
            "/data/misc/example-service/hide_my_applist/config.json",
            "/data/system/example/hma/config.json",
            "/data/adb/hma/config.json",
            "/data/adb/hide_my_applist/config.json",
            "/data/user/010/org.frknkrc44.hma_oss/files/config.json",
            "/data/user/-1/org.frknkrc44.hma_oss/files/config.json",
            "/data/user/+10/org.frknkrc44.hma_oss/files/config.json",
            "/data/user/2147483648/org.frknkrc44.hma_oss/files/config.json",
            "/data/data/com.tsng.hidemyapplist/cache/config.json",
            "/data/data/com.tsng.hidemyapplist/files/nested/config.json",
            "/data/data/com.tsng.hidemyapplist.suffix/files/config.json",
            "/data/misc/hide_my_applist_qa/nested/config.json",
            "/data/misc/HIDE_MY_APPLIST/config.json",
            "/data/misc/hma_oss/config.json",
            "/data/misc/hide_my_applist_qa/config.json.bak"
        };
        for (String value : rejected) assertFalse(value, HmaConfigFile.isConfigPath(value));
    }

    @Test public void rejectsPathTraversalControlCharactersAndOversizedComponents() {
        String[] rejected = {null, "", "data/misc/hide_my_applist/config.json",
            "/data/misc/../hide_my_applist/config.json", "/data/misc/./hide_my_applist/config.json",
            "/data//misc/hide_my_applist/config.json", "/data/misc/hide_my_applist\n/config.json",
            "/data/misc/hide_my_applist\u0000/config.json",
            "/data/misc/hide_my_applist" + "x".repeat(256) + "/config.json"};
        for (String value : rejected) assertFalse(String.valueOf(value), HmaConfigFile.isConfigPath(value));
    }

    @Test public void exactManagerAllowlistRejectsSubstringAndCaseVariants() {
        for (String value : new String[]{"com.tsng.hidemyapplist", "org.frknkrc44.hma_oss", "com.google.android.hmal"})
            assertTrue(HmaConfigPolicy.isManagerPackage(value));
        for (String value : new String[]{null, "", "example.hma", "example.hidemyapp", "example.hmal",
                "com.tsng.hidemyapplist.other", "COM.TSNG.HIDEMYAPPLIST", " com.tsng.hidemyapplist"})
            assertFalse(String.valueOf(value), HmaConfigPolicy.isManagerPackage(value));
    }

    @Test public void snapshotPreservesCompleteUtf8PayloadAndDigest() throws Exception {
        String json = "{\"label\":\"Olá العربية 日本語 😀\",\"padding\":\"" + "x".repeat(12000) + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HmaConfigFile.Snapshot snapshot = HmaConfigFile.parseSnapshot(PATH, envelope(bytes));
        assertNotNull(snapshot);
        assertEquals(json, snapshot.json);
        assertEquals(digest(bytes), snapshot.digest);
        assertEquals(PATH, snapshot.path);
    }

    @Test public void snapshotRejectsTruncationWrongDigestAndInvalidHeader() throws Exception {
        String valid = envelope("{\"scope\":{}}".getBytes(StandardCharsets.UTF_8));
        assertNull(HmaConfigFile.parseSnapshot(PATH, valid.substring(0, valid.length() - 7)));
        assertNull(HmaConfigFile.parseSnapshot(PATH, "0".repeat(64) + valid.substring(64)));
        assertNull(HmaConfigFile.parseSnapshot(PATH, valid.substring(1)));
        assertNull(HmaConfigFile.parseSnapshot(PATH, valid.replaceFirst("\n", "")));
        assertNull(HmaConfigFile.parseSnapshot(PATH, "not-a-digest\nAAAA"));
        assertNull(HmaConfigFile.parseSnapshot(PATH, null));
    }

    @Test public void snapshotByteCapAcceptsBoundaryAndRejectsOneByteMore() throws Exception {
        byte[] accepted = new byte[HmaConfigFile.MAX_CONFIG_BYTES];
        Arrays.fill(accepted, (byte) 'a');
        assertNotNull(HmaConfigFile.parseSnapshot(PATH, envelope(accepted)));
        assertNull(HmaConfigFile.parseSnapshot(PATH, envelope(Arrays.copyOf(accepted, accepted.length + 1))));
    }

    @Test public void writeAndReadRejectUnsupportedPathOrOversizedReplacement() throws Exception {
        HmaConfigFile.Snapshot snapshot = HmaConfigFile.parseSnapshot(PATH, envelope("{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> HmaConfigFile.readCommand("/data/adb/hma/config.json"));
        assertThrows(IllegalArgumentException.class, () -> HmaConfigFile.writeCommand(null, "{}"));
        assertThrows(IllegalArgumentException.class, () -> HmaConfigFile.writeCommand(snapshot, null));
        assertThrows(IllegalArgumentException.class, () -> HmaConfigFile.writeCommand(snapshot, "x".repeat(65537)));
        assertThrows(IllegalArgumentException.class, () -> HmaConfigFile.writeCommand(snapshot, "é".repeat(32769)));
        assertTrue(HmaConfigFile.writeCommand(snapshot, "x".repeat(65536)).length() < 128 * 1024);
    }

    @Test public void repairLeavesOriginalObjectAndUnrelatedScopeUntouched() throws Exception {
        JSONObject original = document(scope(false).put("extraAppList", array(MODULE, "keep")));
        original.getJSONObject("scope").put("example.foreign", new JSONObject().put("arbitrary", new JSONArray().put(7).put(false)));
        original.put("unknownRootField", new JSONObject().put("preserve", "value"));
        String before = original.toString();
        JSONObject changed = HmaConfigPolicy.repair(original, TARGET, MODULE);
        assertNotNull(changed);
        assertEquals(before, original.toString());
        assertEquals(original.getJSONObject("scope").getJSONObject("example.foreign").toString(),
            changed.getJSONObject("scope").getJSONObject("example.foreign").toString());
        assertEquals(original.getJSONObject("unknownRootField").toString(), changed.getJSONObject("unknownRootField").toString());
        assertNull(HmaConfigPolicy.repair(changed, TARGET, MODULE));
    }

    @Test public void legacyTemplateCloneAvoidsCollisionAndPreservesEditorFields() throws Exception {
        JSONObject original = document(scope(false).put("applyTemplates", array("Shared")));
        JSONObject templates = original.getJSONObject("templates");
        JSONObject shared = new JSONObject().put("isWhitelist", true).put("appList", array(MODULE, "keep"))
            .put("unknown", new JSONObject().put("nested", 42));
        templates.put("Shared", shared);
        String stem = "Shared (WAEX " + TARGET + ")";
        templates.put(stem, new JSONObject().put("keepCollision", 1));
        templates.put(stem + " 1", new JSONObject().put("keepCollision", 2));
        JSONObject result = HmaConfigPolicy.repair(original, TARGET, MODULE);
        assertNotNull(result);
        JSONObject resultTemplates = result.getJSONObject("templates");
        assertEquals(shared.toString(), resultTemplates.getJSONObject("Shared").toString());
        assertEquals(1, resultTemplates.getJSONObject(stem).getInt("keepCollision"));
        assertEquals(2, resultTemplates.getJSONObject(stem + " 1").getInt("keepCollision"));
        assertEquals(stem + " 2", result.getJSONObject("scope").getJSONObject(TARGET).getJSONArray("applyTemplates").getString(0));
        JSONObject clone = resultTemplates.getJSONObject(stem + " 2");
        assertEquals(array("keep").toString(), clone.getJSONArray("appList").toString());
        assertTrue(clone.getBoolean("isWhitelist"));
        assertEquals(42, clone.getJSONObject("unknown").getInt("nested"));
    }

    @Test public void exhaustedCloneNamespaceFailsWithoutMutatingOriginal() throws Exception {
        JSONObject original = document(scope(false).put("applyTemplates", array("Shared")));
        JSONObject templates = original.getJSONObject("templates");
        templates.put("Shared", new JSONObject().put("appList", array(MODULE)));
        String stem = "Shared (WAEX " + TARGET + ")";
        templates.put(stem, new JSONObject());
        for (int i = 1; i <= 128; i++) templates.put(stem + " " + i, new JSONObject());
        String before = original.toString();
        assertThrows(JSONException.class, () -> HmaConfigPolicy.repair(original, TARGET, MODULE));
        assertEquals(before, original.toString());
    }

    @Test public void cloneDoesNotActivateMissingTemplateReferencedByAnotherScope() throws Exception {
        JSONObject original = document(scope(false).put("applyTemplates", array("Shared")));
        original.getJSONObject("templates").put("Shared", new JSONObject().put("appList", array(MODULE, "keep")));
        String stem = "Shared (WAEX " + TARGET + ")";
        original.getJSONObject("scope").put("example.foreign", scope(false).put("applyTemplates", array(stem)));
        // OSS ignores a missing template. Creating that global name must not make
        // its formerly inactive reference start hiding another package.
        assertNull(HmaConfigPolicy.blockingReason(original, "example.foreign", "keep"));
        JSONObject result = HmaConfigPolicy.repair(original, TARGET, MODULE);
        assertNotNull(result);
        assertFalse(result.getJSONObject("templates").has(stem));
        assertNull(HmaConfigPolicy.blockingReason(result, "example.foreign", "keep"));
        assertEquals(stem + " 1", result.getJSONObject("scope").getJSONObject(TARGET).getJSONArray("applyTemplates").getString(0));
    }

    @Test public void cloneDoesNotActivateMissingTemplateReferencedByDefaultConfig() throws Exception {
        JSONObject original = document(scope(false).put("applyTemplates", array("Shared")));
        original.getJSONObject("templates").put("Shared", new JSONObject().put("appList", array(MODULE, "keep")));
        String stem = "Shared (WAEX " + TARGET + ")";
        JSONObject defaults = scope(false).put("applyTemplates", array(stem));
        original.put("defaultConfig", defaults);
        JSONObject result = HmaConfigPolicy.repair(original, TARGET, MODULE);
        assertNotNull(result);
        assertFalse(result.getJSONObject("templates").has(stem));
        assertEquals(defaults.toString(), result.getJSONObject("defaultConfig").toString());
        assertEquals(stem + " 1", result.getJSONObject("scope").getJSONObject(TARGET).getJSONArray("applyTemplates").getString(0));
    }

    @Test public void onlyDeclaredExactTargetsAndNonemptyModuleAreRepairable() throws Exception {
        JSONObject original = document(scope(false).put("extraAppList", array(MODULE)));
        for (String target : new String[]{null, "", "COM.WHATSAPP", "com.whatsapp.other", "example.foreign"})
            assertNull(HmaConfigPolicy.repair(original, target, MODULE));
        assertNull(HmaConfigPolicy.repair(original, TARGET, null));
        assertNull(HmaConfigPolicy.repair(original, TARGET, ""));
        assertNull(HmaConfigPolicy.repair(new JSONObject(), TARGET, MODULE));
    }

    @Test public void ossPrecedenceRepairDoesNotWeakenGlobalPresetOrOtherScope() throws Exception {
        JSONObject selected = scope(false).put("extraAppList", array(MODULE, "keep"))
            .put("extraOppositeAppList", array(MODULE, "visible"))
            .put("applyPresets", array("xposed", "magisk"));
        JSONObject original = document(selected);
        original.getJSONObject("scope").put("com.whatsapp.w4b", new JSONObject(selected.toString()));
        original.put("ignoredPackagesForPresets", array("unrelated"));
        assertNotNull(HmaConfigPolicy.blockingReason(original, TARGET, MODULE));
        JSONObject result = HmaConfigPolicy.repair(original, TARGET, MODULE);
        JSONObject changed = result.getJSONObject("scope").getJSONObject(TARGET);
        assertEquals(array("keep").toString(), changed.getJSONArray("extraAppList").toString());
        assertEquals(array("visible", MODULE).toString(), changed.getJSONArray("extraOppositeAppList").toString());
        assertEquals(array("xposed", "magisk").toString(), changed.getJSONArray("applyPresets").toString());
        assertEquals(array("unrelated").toString(), result.getJSONArray("ignoredPackagesForPresets").toString());
        assertEquals(selected.toString(), result.getJSONObject("scope").getJSONObject("com.whatsapp.w4b").toString());
        assertNull(HmaConfigPolicy.blockingReason(result, TARGET, MODULE));
    }
}
