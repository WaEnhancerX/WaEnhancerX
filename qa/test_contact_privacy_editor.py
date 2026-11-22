#!/usr/bin/env python3
"""Execute both exact manager save callbacks and its complete contact-list loader.

Compiles the production ContactPrivacy model, loader, save helper (when present)
and both callback bodies. JSON uses the existing hash-pinned AOSP Android 14
source bootstrap, with its bounded downloads and offline cache; an explicit
--android-jar may reuse a local framework JAR. Context, contact-name lookup and
preference storage are in-memory adapters. This does not render Compose or
execute WhatsApp call hooks. --out retains the result and generated harness;
the default output directory is temporary.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from test_settings_backup_json import SHA256, android_doubles, json_sources

ROOT = Path(__file__).resolve().parents[1]


def block(source, start):
    opening = source.index('{', start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--android-jar', type=Path)
    parser.add_argument('--json-source-dir', type=Path,
                        default=os.environ.get('WAEX_AOSP_JSON_SOURCE_DIR'))
    parser.add_argument('--out', type=Path)
    parser.add_argument('--expect-failures', type=int, default=0)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='waex-contact-privacy-') as directory:
        if args.out is None:
            args.out = Path(directory)
        run_checks(args)


def run_checks(args):
    source_path = args.source_root / 'app/src/main/java/com/waenhancer/ui/navigation/Navigation.kt'
    source = source_path.read_text()
    model_start = source.index('data class ContactPrivacy(')
    model_end = source.index('\n)', model_start) + 2
    loader = block(source, source.index('fun loadContactPrivacyList('))
    helper_marker = 'private fun saveContactPrivacy('
    helper = block(source, source.index(helper_marker)) if helper_marker in source else ''
    callbacks = []
    for index, match in enumerate(re.finditer(r'onUpdateContact = \{ updated ->', source)):
        body = block(source, match.start()).split('updated ->', 1)[1]
        callbacks.append('fun save' + str(index) + '''(preferenceManager: WaexPreferenceManager,
    contactPrivacyList: MutableList<ContactPrivacy>, updated: ContactPrivacy) {''' + body)
    assert len(callbacks) == 2, 'Both manager navigation callbacks must be exercised'
    harness = r'''
import org.json.JSONObject
class Context
class WaexPreferenceManager(val data: MutableMap<String, Any> = linkedMapOf()) {
    var writes = 0
    val all: Map<String, Any>? get() = data
    fun getString(key: String, default: String?): String? = data[key] as? String ?: default
    fun putString(key: String, value: String) { writes++; data[key] = value }
}
object ContactNameResolver {
    fun resolveName(context: Context, jid: String, stored: String, phone: String) = stored
}
__PRODUCTION__
fun main() {
    var checks = 0
    var failures = 0
    fun verify(ok: Boolean, label: String) {
        checks++
        if (!ok) { failures++; println("ASSERTION_FAILURE: $label") }
    }
    val id = "15551234567"
    val jid = "$id@s.whatsapp.net"
    val keys = listOf("${id}_privacy", "${jid}_privacy", "per_contact_rules_$jid")
    val original = """{"name":"Original name","phone":"+15551234567","HideSeen":false,"HideViewStatus":true,"HideReceipt":true,"vendor":{"tag":"keep","values":[1,true,null]},"scope":"always"}"""
    for (block in listOf(false, true)) for (freeze in listOf(false, true)) {
        val prefs = WaexPreferenceManager()
        prefs.data[keys[0]] = JSONObject(original).put("BlockCall", block).put("FreezeLastSeen", freeze).toString()
        val loaded = loadContactPrivacyList(Context(), prefs)
        verify(loaded.size == 1, "load produces one contact")
        verify(loaded.single().freezeLastSeen == freeze, "BlockCall=$block does not set freeze=$freeze")
    }
    val saves = listOf(::save0, ::save1)
    for ((route, save) in saves.withIndex()) {
        for (block in listOf<Boolean?>(null, false, true)) for (freeze in listOf(false, true)) {
            val document = JSONObject(original).apply { if (block != null) put("BlockCall", block) }
            val prefs = WaexPreferenceManager()
            prefs.data[keys[0]] = document.toString()
            val contact = ContactPrivacy(id, "Resolved display name", jid)
            val contacts = mutableListOf(contact)
            val updated = contact.copy(hideSeen = true, hideTyping = true, hideRecording = true,
                antiRevoke = true, freezeLastSeen = freeze)
            save(prefs, contacts, updated)
            val result = JSONObject(prefs.data[keys[0]] as String)
            val label = "route=$route block=$block freeze=$freeze"
            verify(result.has("BlockCall") == (block != null), "$label retains BlockCall presence")
            if (block != null) verify(result.optBoolean("BlockCall") == block, "$label retains BlockCall value")
            verify(result.optBoolean("FreezeLastSeen") == freeze, "$label keeps freeze independent")
            verify(result.optBoolean("HideViewStatus") && result.optBoolean("HideReceipt"), "$label retains status/delivery rules")
            verify(result.optString("phone") == "+15551234567", "$label retains phone metadata")
            verify(result.optString("name") == "Original name", "$label preserves unedited name")
            verify(result.optJSONObject("vendor")?.toString() == document.getJSONObject("vendor").toString(), "$label retains nested unknown data")
            verify(result.optBoolean("HideSeen") && result.optBoolean("HideTyping") &&
                result.optBoolean("HideRecording") && result.optBoolean("AntiRevoke"), "$label saves edited controls")
            verify(keys.all { prefs.data[it] == result.toString() }, "$label writes all existing aliases consistently")
            verify(contacts.single() == updated, "$label updates displayed contact")
        }
        // Only the explicit WhatsApp call-privacy dialog can confirm legacy call rules.
        // Editing manager presence controls preserves a marker, and never invents one.
        for (confirmed in listOf<Boolean?>(null, false, true)) {
            val document = JSONObject(original).put("BlockCall", true).apply {
                if (confirmed != null) put("BlockCallConfirmed", confirmed)
            }
            val markerPrefs = WaexPreferenceManager()
            markerPrefs.data[keys[0]] = document.toString()
            val markerContact = ContactPrivacy(id, "Display", jid)
            save(markerPrefs, mutableListOf(markerContact), markerContact.copy(freezeLastSeen = true))
            val markerResult = JSONObject(markerPrefs.data[keys[0]] as String)
            verify(markerResult.has("BlockCallConfirmed") == (confirmed != null),
                "route=$route preserves confirmation presence=$confirmed without inferring it")
            if (confirmed != null) verify(markerResult.optBoolean("BlockCallConfirmed") == confirmed,
                "route=$route preserves explicit confirmation value=$confirmed")
        }
        // Use the live authoritative document, including changes made while the modal was open.
        val contact = ContactPrivacy(id, "Display", jid, freezeLastSeen = true)
        val prefs = WaexPreferenceManager()
        prefs.data[keys[0]] = JSONObject(original).put("BlockCall", false).toString()
        val contacts = mutableListOf(contact)
        prefs.data[keys[0]] = JSONObject(original).put("BlockCall", true).put("newField", "later").toString()
        prefs.data[keys[1]] = JSONObject(original).put("BlockCall", false).toString()
        save(prefs, contacts, contact.copy(freezeLastSeen = false))
        val liveResult = JSONObject(prefs.data[keys[0]] as String)
        verify(liveResult.optBoolean("BlockCall"), "route=$route preserves live authoritative BlockCall")
        verify(liveResult.optString("newField") == "later", "route=$route preserves live additional field")
        for (fallback in listOf(1, 2)) {
            val fallbackPrefs = WaexPreferenceManager()
            fallbackPrefs.data[keys[fallback]] = JSONObject(original).put("BlockCall", true).toString()
            save(fallbackPrefs, mutableListOf(contact), contact.copy(freezeLastSeen = false))
            verify(JSONObject(fallbackPrefs.data[keys[0]] as String).optBoolean("BlockCall"),
                "route=$route preserves fallback document=$fallback")
        }
        for (invalid in listOf("", "{not-json")) {
            val malformed = WaexPreferenceManager()
            malformed.data[keys[0]] = invalid
            malformed.data[keys[1]] = original
            val before = malformed.data.toMap()
            val displayed = mutableListOf(contact)
            save(malformed, displayed, contact.copy(hideSeen = true))
            verify(malformed.data == before && malformed.writes == 0,
                "route=$route never overwrites malformed authoritative rules")
            verify(displayed.single() == contact, "route=$route does not show failed serialization as saved")
        }
    }
    println("EDITOR_CHECKS=$checks")
    println("EDITOR_FAILURES=$failures")
}
'''
    harness = harness.replace('__PRODUCTION__', '\n'.join([source[model_start:model_end], helper, loader, *callbacks]))
    args.out.mkdir(parents=True, exist_ok=True)
    kotlin = args.out / 'ContactPrivacyEditorChecks.kt'
    kotlin.write_text(harness)
    jar = args.out / 'checks.jar'
    if args.android_jar is not None:
        json_classpath = args.android_jar.resolve()
        json_identity = {'android_jar_sha256': hashlib.sha256(json_classpath.read_bytes()).hexdigest()}
    else:
        cache = (args.json_source_dir or Path.home() / '.cache/waex-qa/android-json-14').resolve()
        aosp = json_sources(cache, allow_download=args.json_source_dir is None)
        java_sources = []
        for name, content in android_doubles(ROOT).items():
            if name.startswith('android/content/'):
                continue
            annotation = args.out / 'src' / name
            annotation.parent.mkdir(parents=True, exist_ok=True)
            annotation.write_text(content)
            java_sources.append(annotation)
        json_classpath = args.out / 'json_classes'
        subprocess.run(['javac', '--release', '17', '-d', str(json_classpath),
                        *map(str, [*java_sources, *aosp])], check=True, timeout=90)
        json_identity = {'aosp_json_android_14_source_sha256': SHA256}
        print('AOSP JSON: Android 14 android-14.0.0_r1; 6/6 source digests verified', flush=True)
    subprocess.run([shutil.which('kotlinc') or 'kotlinc', '-jvm-target', '17', '-classpath', str(json_classpath),
                    str(kotlin), '-include-runtime', '-d', str(jar)], check=True, timeout=90)
    execution = subprocess.run(['java', '-cp', os.pathsep.join(map(str, (jar, json_classpath))), 'ContactPrivacyEditorChecksKt'],
                               text=True, capture_output=True, timeout=30)
    (args.out / 'execution.log').write_text(execution.stdout + execution.stderr)
    print(execution.stdout, end='')
    print(execution.stderr, end='')
    execution.check_returncode()
    failures = int(re.search(r'EDITOR_FAILURES=(\d+)', execution.stdout).group(1))
    result = {
        'checks': int(re.search(r'EDITOR_CHECKS=(\d+)', execution.stdout).group(1)),
        'failures': failures,
        'expected_failures': args.expect_failures,
        'accepted': failures == args.expect_failures,
        'source_sha256': hashlib.sha256(source_path.read_bytes()).hexdigest(),
        'json_implementation': json_identity,
        'limitations': 'Extracted real callbacks/model/loader/helper with in-memory storage and name lookup; no Compose rendering or WhatsApp calls.',
    }
    (args.out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    if not result['accepted']:
        raise SystemExit('Unexpected editor assertion-failure count')


if __name__ == '__main__':
    main()
