#!/usr/bin/env python3
"""Exercise the real ProfilePhotoDownloadHook file-resolution method with isolated host doubles.

This is a source-method JVM contract test, not an installed WhatsApp/LSPosed test.
Only the exact method body from the production Java source is compiled. The fake
Activity and JID resolver are explicit; no WhatsApp contacts or files are read.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOOK = Path("app/src/main/java/com/waenhancer/xposed/features/media/ProfilePhotoDownloadHook.java")
HEADER = "    private File resolveProfilePhotoFile(Activity activity) {"
LEGACY_FALLBACK = 'new File(activity.getFilesDir(), "me.jpg")'
CACHE_LAST = 'new File(cacheDir, "Profile Pictures/" + rawJid)'


def method_from_source(source: str) -> str:
    first = source.find(HEADER)
    if first < 0:
        raise AssertionError("Production file resolution method not found")
    brace = source.index("{", first)
    depth = 0
    for position in range(brace, len(source)):
        if source[position] == "{":
            depth += 1
        elif source[position] == "}":
            depth -= 1
            if depth == 0:
                return source[first:position + 1]
    raise AssertionError("Unterminated production file resolution method")


ACTIVITY = """package android.app;
import java.io.File;
public class Activity {
    public final String jid;
    private final File cache;
    private final File files;
    public Activity(String jid, File cache, File files) {
        this.jid = jid;
        this.cache = cache;
        this.files = files;
    }
    public File getCacheDir() { return cache; }
    public File getFilesDir() { return files; }
}
"""

RESOLVER = """package com.waenhancer.utils;
import android.app.Activity;
public final class ContactNameResolver {
    public static final ContactNameResolver INSTANCE = new ContactNameResolver();
    public String extractJidFromActivity(Activity activity) { return activity.jid; }
}
"""

PROBE_START = """package com.waenhancer.xposed.features.media;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import android.app.Activity;
import com.waenhancer.utils.ContactNameResolver;

final class ProfilePhotoSourceProbe {
    private Object extractContactObject(Activity activity) { return null; }
    private void logFailure(String operation, Throwable cause) {
        throw new AssertionError("Unexpected file resolution exception", cause);
    }
"""

PROBE_TESTS = """
    private static int checks = 0;
    private static void expect(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }
    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        File cache = scratch.resolve("cache").toFile();
        File files = scratch.resolve("files").toFile();
        if (!cache.mkdirs() || !files.mkdirs()) throw new AssertionError("Fixture dirs");
        File ownPhoto = new File(files, "me.jpg");
        Files.write(ownPhoto.toPath(), new byte[]{77, 69, 45, 80, 72, 79, 84, 79});
        String otherJid = "11111@s.whatsapp.net";
        Activity other = new Activity(otherJid, cache, files);
        ProfilePhotoSourceProbe probe = new ProfilePhotoSourceProbe();

        // The current viewer is a third party; a file called me.jpg proves nothing
        // about its identity and must not preempt the visible contact photo.
        expect(probe.resolveProfilePhotoFile(other) == null,
                "foreign JID without its cache does not select own me.jpg");

        File photoDir = new File(cache, "Profile Pictures");
        if (!photoDir.mkdirs()) throw new AssertionError("Photo cache fixture");
        File matchingJpg = new File(photoDir, otherJid + ".jpg");
        Files.write(matchingJpg.toPath(), new byte[]{84, 65, 82, 71, 69, 84});
        expect(matchingJpg.equals(probe.resolveProfilePhotoFile(other)),
                "exact foreign JID JPEG cache beats unrelated own photo");

        Files.delete(matchingJpg.toPath());
        File matchingJ = new File(photoDir, otherJid + ".j");
        Files.write(matchingJ.toPath(), new byte[]{74, 73, 68});
        expect(matchingJ.equals(probe.resolveProfilePhotoFile(other)),
                "exact foreign JID short-extension cache remains available");

        Files.delete(matchingJ.toPath());
        Files.write(matchingJpg.toPath(), new byte[0]);
        expect(probe.resolveProfilePhotoFile(other) == null,
                "empty JID cache cannot fall back to unrelated own photo");

        Activity unknown = new Activity(null, cache, files);
        expect(probe.resolveProfilePhotoFile(unknown) == null,
                "missing JID is unavailable without unrelated cache lookup");

        Activity selfUnverified = new Activity("22222@s.whatsapp.net", cache, files);
        expect(probe.resolveProfilePhotoFile(selfUnverified) == null,
                "unverified self label does not authorize own-photo fallback");

        System.out.println("RESULT checks=" + checks + " failed=0");
    }
}
"""


def compile_and_run(method: str, label: str, sandbox: Path) -> tuple[int, str]:
    root = sandbox / label
    (root / "android/app").mkdir(parents=True)
    (root / "com/waenhancer/utils").mkdir(parents=True)
    (root / "com/waenhancer/xposed/features/media").mkdir(parents=True)
    (root / "android/app/Activity.java").write_text(ACTIVITY, encoding="utf-8")
    (root / "com/waenhancer/utils/ContactNameResolver.java").write_text(RESOLVER, encoding="utf-8")
    (root / "com/waenhancer/xposed/features/media/ProfilePhotoSourceProbe.java").write_text(
        PROBE_START + method + PROBE_TESTS, encoding="utf-8")
    sources = [str(path) for path in root.rglob("*.java")]
    classes = root / "classes"
    compiled = subprocess.run(
        ["javac", "--release", "17", "-d", str(classes), *sources],
        capture_output=True, text=True, timeout=18, check=False)
    if compiled.returncode:
        raise AssertionError(label + " failed to compile:\n" + compiled.stdout + compiled.stderr)
    fixture = root / "fixture"
    fixture.mkdir()
    executed = subprocess.run(
        ["java", "-cp", str(classes),
         "com.waenhancer.xposed.features.media.ProfilePhotoSourceProbe", str(fixture)],
        capture_output=True, text=True, timeout=18, check=False)
    return executed.returncode, executed.stdout + executed.stderr


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--skip-mutant", action="store_true")
    args = parser.parse_args()
    source_path = args.source_root.resolve() / HOOK
    source = source_path.read_text(encoding="utf-8")
    method = method_from_source(source)
    print("SOURCE " + str(source_path))
    print("SOURCE_SHA256 " + hashlib.sha256(source.encode("utf-8")).hexdigest())
    print("METHOD_SHA256 " + hashlib.sha256(method.encode("utf-8")).hexdigest())
    with tempfile.TemporaryDirectory(prefix="waex-profile-source-") as directory:
        scratch = Path(directory)
        result, output = compile_and_run(method, "candidate", scratch)
        print("CANDIDATE_JVM_EXIT " + str(result))
        print(output, end="")
        if result != 0:
            raise SystemExit(result)
        if args.skip_mutant:
            return
        if LEGACY_FALLBACK in method:
            raise AssertionError("The unsafe me.jpg fallback remains in candidate")
        if method.count(CACHE_LAST) != 1:
            raise AssertionError("Unable to construct exact fallback mutant")
        mutant = method.replace(
            CACHE_LAST,
            CACHE_LAST + ',\n                            ' + LEGACY_FALLBACK, 1)
        mutant_exit, mutant_output = compile_and_run(mutant, "mutant", scratch)
        if mutant_exit == 0 or "foreign JID without its cache" not in mutant_output:
            raise AssertionError("Negative control failed to reject own-photo selection")
        print("EXPECTED_FAIL_MUTANT " + mutant_output.splitlines()[0])
        print("RESULT candidate PASS; reintroduced-me.jpg mutant REJECTED")


if __name__ == "__main__":
    main()
