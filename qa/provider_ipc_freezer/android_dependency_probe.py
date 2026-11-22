#!/usr/bin/env python3
"""Reproduce stable versus unstable Android provider dependencies.

This builds and signs an isolated test-only APK; it never compiles or patches
WAEX/WhatsApp. It kills only the synthetic provider process after validating
the process name and PID. A stable-reference client death is a control; an
unstable-reference client remaining alive is the candidate. Neither outcome
alone proves that an uninstalled WAEX PR fixes WhatsApp's freezer failures.

Windows example:
  python device_provider_dependency_probe.py --out C:\\WAEX-Lab\\evidence\\waex-provider-dependency-20261113\\run1 --sdk C:\\WAEX-Lab\\android\\sdk --jdk C:\\WAEX-Lab\\tools\\jdk17 --serial emulator-5560 --user 0
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import zipfile


PACKAGE = "io.github.tkmbrain.waex.providerdependencyprobe"
AUTHORITY = PACKAGE + ".provider"

PROVIDER = r'''package io.github.tkmbrain.waex.providerdependencyprobe;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class ProbeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"ping".equals(method)) throw new IllegalArgumentException("Unexpected method");
        Bundle result = new Bundle();
        result.putInt("providerPid", android.os.Process.myPid());
        return result;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri u) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
'''

ACTIVITY = r'''package io.github.tkmbrain.waex.providerdependencyprobe;
import android.app.Activity;
import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class ProbeActivity extends Activity {
    private volatile boolean stopped;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Isolated Android provider dependency probe");
        setContentView(label);
        String token = getIntent().getStringExtra("token");
        String mode = getIntent().getStringExtra("mode");
        if (token == null || !token.matches("[a-f0-9]{24}")
                || !("stable".equals(mode) || "unstable".equals(mode))) {
            finish(); return;
        }
        new Thread(() -> execute(token, mode), "ProviderDependencyProbe").start();
    }
    @Override protected void onDestroy() { stopped = true; super.onDestroy(); }

    private void write(String token, String stage, String mode, int providerPid, String detail) {
        try {
            JSONObject result = new JSONObject()
                    .put("token", token).put("stage", stage).put("mode", mode)
                    .put("clientPid", android.os.Process.myPid())
                    .put("providerPid", providerPid)
                    .put("user", android.os.Process.myUid() / 100000)
                    .put("api", android.os.Build.VERSION.SDK_INT)
                    .put("detail", detail);
            File destination = new File(getFilesDir(), "probe-" + token + ".json");
            try (FileOutputStream out = new FileOutputStream(destination)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        } catch (Throwable error) {
            android.util.Log.e("WaexProviderProbe", "Cannot persist synthetic observation", error);
        }
    }
    private void execute(String token, String mode) {
        int providerPid = -1;
        ContentProviderClient provider = null;
        try {
            Uri uri = Uri.parse("content://io.github.tkmbrain.waex.providerdependencyprobe.provider");
            provider = "stable".equals(mode)
                    ? getContentResolver().acquireContentProviderClient(uri)
                    : getContentResolver().acquireUnstableContentProviderClient(uri);
            if (provider == null) throw new IllegalStateException("provider unavailable");
            Bundle ping = provider.call("ping", null, null);
            if (ping == null) throw new IllegalStateException("empty ping");
            providerPid = ping.getInt("providerPid", -1);
            if (providerPid <= 0 || providerPid == android.os.Process.myPid())
                throw new IllegalStateException("expected separate provider process");
            write(token, "READY", mode, providerPid, "provider lease intentionally held");
            // Keep the Activity's process alive while the external test kills only
            // its synthetic provider.  No real app or private provider is touched.
            SystemClock.sleep(5000);
            try {
                Bundle response = provider.call("ping", null, null);
                write(token, "SURVIVED", mode, providerPid,
                        response == null ? "provider returned null" : "provider call returned");
            } catch (Throwable binderFailure) {
                write(token, "SURVIVED", mode, providerPid,
                        binderFailure.getClass().getName());
            }
            // Leave enough time for ADB to observe the client process.
            SystemClock.sleep(3000);
        } catch (Throwable failure) {
            write(token, "FAIL", mode, providerPid,
                    failure.getClass().getName() + ": " + failure.getMessage());
        } finally {
            if (provider != null) provider.close();
        }
    }
}
'''


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as inp:
        for block in iter(lambda: inp.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def run(args: list[str | Path], *, log: Path | None = None,
        timeout: int = 90, check: bool = True, env: dict | None = None) -> subprocess.CompletedProcess:
    command = [str(item) for item in args]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=timeout, env=env)
    if log is not None:
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if check and result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} exit {result.returncode}: "
                           f"{(result.stderr or result.stdout)[:440]}")
    return result


def executable(path: Path) -> Path:
    if os.name == "nt":
        path = path.with_suffix(".exe")
    if not path.is_file():
        raise FileNotFoundError(path)
    return path


def build(out: Path, sdk: Path, jdk: Path) -> Path:
    bt = sdk / "build-tools" / "36.0.0"
    android = sdk / "platforms" / "android-36" / "android.jar"
    javac = executable(jdk / "bin" / "javac")
    java = executable(jdk / "bin" / "java")
    keytool = executable(jdk / "bin" / "keytool")
    aapt2 = executable(bt / "aapt2")
    aligner = executable(bt / "zipalign")
    for path in [android, bt / "lib" / "d8.jar", bt / "lib" / "apksigner.jar"]:
        if not path.is_file():
            raise FileNotFoundError(path)
    generated = out / "generated"; generated.mkdir()
    classes = out / "classes"; classes.mkdir()
    dex = out / "dex"; dex.mkdir()
    for name, body in [("ProbeActivity.java", ACTIVITY), ("ProbeProvider.java", PROVIDER)]:
        (generated / name).write_text(body, encoding="utf-8")
    manifest = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}" android:versionCode="1" android:versionName="lab-only">
<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36" />
<application android:debuggable="true" android:testOnly="true" android:allowBackup="false" android:label="WAEX provider death probe" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<provider android:name="{PACKAGE}.ProbeProvider" android:authorities="{AUTHORITY}" android:exported="false" android:process=":provider" />
<activity android:name="{PACKAGE}.ProbeActivity" android:exported="true" />
</application></manifest>'''
    (generated / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
    run([javac, "--release", "17", "-cp", android, "-d", classes,
         generated / "ProbeActivity.java", generated / "ProbeProvider.java"],
        log=out / "javac.log")
    jar = out / "classes.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob("*.class")):
            archive.write(file, file.relative_to(classes).as_posix())
    run([java, "-cp", bt / "lib" / "d8.jar", "com.android.tools.r8.D8",
         "--min-api", "24", "--lib", android, "--output", dex, jar],
        log=out / "d8.log")
    unsigned = out / "unsigned.apk"
    run([aapt2, "link", "-I", android, "--manifest", generated / "AndroidManifest.xml",
         "--min-sdk-version", "24", "--target-sdk-version", "36", "-o", unsigned],
        log=out / "aapt2.log")
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        for file in dex.glob("classes*.dex"):
            archive.write(file, file.name)
    aligned = out / "aligned.apk"
    run([aligner, "-f", "4", unsigned, aligned], log=out / "zipalign.log")
    key = out / "test-only-signing.p12"
    sign_env = dict(os.environ)
    sign_env["WAEX_FREEZER_PROBE_LOCAL_KEY"] = "android"  # Synthetic test key only.
    run([keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
         "-alias", "waex-probe", "-storepass:env", "WAEX_FREEZER_PROBE_LOCAL_KEY",
         "-keypass:env", "WAEX_FREEZER_PROBE_LOCAL_KEY", "-keyalg", "RSA", "-keysize",
         "2048", "-validity", "3650", "-dname", "CN=WAEX synthetic dependency probe"],
        log=out / "keytool.log", env=sign_env)
    signed = out / "provider-dependency-probe.apk"
    run([java, "-jar", bt / "lib" / "apksigner.jar", "sign", "--ks", key,
         "--ks-key-alias", "waex-probe", "--ks-pass", "env:WAEX_FREEZER_PROBE_LOCAL_KEY",
         "--key-pass", "env:WAEX_FREEZER_PROBE_LOCAL_KEY", "--out", signed, aligned],
        log=out / "sign.log", env=sign_env)
    run([java, "-jar", bt / "lib" / "apksigner.jar", "verify", "--verbose", signed],
        log=out / "verify-signature.log")
    return signed


def adb_cmd(adb: Path, serial: str, args: list[str], check: bool = True):
    return run([adb, "-s", serial, *args], timeout=45, check=check)


def read_state(adb: Path, serial: str, user: int, token: str) -> dict | None:
    result = adb_cmd(adb, serial, ["shell", "run-as", PACKAGE, "--user", str(user),
                                   "cat", "files/probe-" + token + ".json"], check=False)
    if result.returncode:
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def has_exit_reason(history: str, pid: int, process: str, code: int, name: str) -> bool:
    """Match two adjacent lines for one exact synthetic process exit record."""
    pattern = (r"(?m)^\s*timestamp=[^\n]*\bpid=" + str(pid)
               + r"\b[^\n]*\n\s*process=" + re.escape(process)
               + r"\s+reason=" + str(code) + r"\s+\(" + re.escape(name) + r"\)")
    return re.search(pattern, history) is not None


def observe(adb: Path, serial: str, user: int, mode: str, out: Path) -> dict:
    token = secrets.token_hex(12)
    adb_cmd(adb, serial, ["shell", "am", "force-stop", "--user", str(user), PACKAGE])
    launch = adb_cmd(adb, serial, ["shell", "am", "start", "--user", str(user),
                                    "-W", "-n", PACKAGE + "/.ProbeActivity",
                                    "--es", "token", token, "--es", "mode", mode])
    if "Error:" in launch.stdout:
        raise RuntimeError(launch.stdout)
    state = None
    for _ in range(30):
        state = read_state(adb, serial, user, token)
        if state and state.get("stage") in ("READY", "FAIL"):
            break
        time.sleep(0.2)
    if not state or state.get("stage") != "READY" or state.get("mode") != mode \
            or state.get("user") != user:
        raise RuntimeError(f"{mode}: invalid readiness: {state}")
    client_pid = state["clientPid"]
    provider_pid = state["providerPid"]
    if not isinstance(client_pid, int) or not isinstance(provider_pid, int) \
            or client_pid <= 0 or provider_pid <= 0 or client_pid == provider_pid:
        raise RuntimeError("Invalid distinct processes")
    # A root-assisted read-only check binds the PID to our synthetic process
    # before intentionally killing that exact synthetic provider.
    cmdline = adb_cmd(adb, serial, ["shell", "su", "-c",
                                    f"cat /proc/{provider_pid}/cmdline"])
    observed_process = cmdline.stdout.split("\x00", 1)[0].strip()
    if observed_process != PACKAGE + ":provider":
        raise RuntimeError(f"Refusing to kill unverified PID {provider_pid}")
    pid_status = adb_cmd(adb, serial, ["shell", "su", "-c",
                                       f"cat /proc/{provider_pid}/status"]).stdout
    uid_line = re.search(r"(?m)^Uid:\s+(\d+)", pid_status)
    if uid_line is None or int(uid_line.group(1)) // 100000 != user:
        raise RuntimeError(f"Refusing to kill PID {provider_pid} owned by another Android user")
    killed = adb_cmd(adb, serial, ["shell", "su", "-c", f"kill -9 {provider_pid}"])
    time.sleep(7.0)
    client_pids = adb_cmd(adb, serial, ["shell", "pidof", PACKAGE], check=False).stdout.strip().split()
    survived = str(client_pid) in client_pids
    after = read_state(adb, serial, user, token)
    exit_history = adb_cmd(adb, serial, ["shell", "dumpsys", "activity", "exit-info", PACKAGE]).stdout
    provider_sigkill = has_exit_reason(exit_history, provider_pid, PACKAGE + ":provider",
                                       2, "SIGNALED")
    dependent_client_died = has_exit_reason(exit_history, client_pid, PACKAGE,
                                            12, "DEPENDENCY DIED")
    record = {"mode": mode, "serial": serial, "user": user, "token": token,
              "api": state["api"], "client_pid": client_pid,
              "provider_pid": provider_pid, "provider_process_name": PACKAGE + ":provider",
              "pid_name_verified": True, "provider_uid": int(uid_line.group(1)),
              "kill_exit": killed.returncode,
              "client_pid_after": client_pids, "client_survived": survived,
              "provider_sigkill_confirmed": provider_sigkill,
              "client_dependency_death_confirmed": dependent_client_died,
              "before": state, "after": after}
    (out / f"observation-{mode}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--jdk", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", type=int, default=0)
    parser.add_argument("--existing-apk", type=Path,
                        help="Reuse a byte-identical previously signed synthetic APK")
    parser.add_argument("--reuse-installed", action="store_true",
                        help="Only reuse an already installed byte-identical synthetic APK")
    parser.add_argument("--install-existing", action="store_true",
                        help="Enable an already globally installed synthetic APK for the selected Android user")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_.:-]+", args.serial) or args.user < 0:
        parser.error("Exact device serial/user required")
    if args.reuse_installed and args.install_existing:
        parser.error("Choose either --reuse-installed or --install-existing")
    if (args.reuse_installed or args.install_existing) and not args.existing_apk:
        parser.error("Reusing an install requires --existing-apk")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {"status": "NOT_VERIFIED", "package": PACKAGE, "serial": args.serial,
              "user": args.user, "start": time.time(), "observations": [],
              "scope": "Synthetic Android provider and client in separate app processes, no WhatsApp/WAEX interaction"}
    try:
        sdk, jdk = args.sdk.resolve(), args.jdk.resolve()
        adb = executable(sdk / "platform-tools" / "adb")
        if adb_cmd(adb, args.serial, ["get-state"]).stdout.strip() != "device":
            raise RuntimeError("ADB device unavailable")
        users = adb_cmd(adb, args.serial, ["shell", "pm", "list", "users"]).stdout
        if not re.search(r"UserInfo\{" + str(args.user) + r":", users):
            raise RuntimeError("Android user not found")
        existing = adb_cmd(adb, args.serial, ["shell", "pm", "list", "packages", "--user",
                                              str(args.user), PACKAGE]).stdout
        already_installed = ("package:" + PACKAGE) in existing.splitlines()
        apk = args.existing_apk.resolve() if args.existing_apk else build(out, sdk, jdk)
        if not apk.is_file():
            raise FileNotFoundError(apk)
        # Check the supplied APK's package identity before touching Android.
        if args.existing_apk:
            aapt2 = executable(sdk / "build-tools" / "36.0.0" / "aapt2")
            badges = run([aapt2, "dump", "badging", apk]).stdout
            if not re.search(r"package: name='" + re.escape(PACKAGE) + r"'\s", badges):
                raise RuntimeError("Supplied APK has the wrong package identity")
        report["apk_sha256"] = sha(apk)
        if already_installed:
            if not args.reuse_installed:
                raise RuntimeError("Existing synthetic probe installed; refusing overwrite")
            report["installation"] = "reused exact installed APK without reinstall"
        elif args.install_existing:
            installed = adb_cmd(adb, args.serial, ["shell", "pm", "install-existing", "--user",
                                                    str(args.user), PACKAGE])
            if PACKAGE not in installed.stdout:
                raise RuntimeError("Android did not enable existing synthetic APK")
            report["installation"] = "enabled globally installed synthetic APK for selected user"
        else:
            installed = adb_cmd(adb, args.serial, ["install", "-t", "--user", str(args.user), apk])
            if "Success" not in installed.stdout:
                raise RuntimeError("Install not confirmed")
            report["installation"] = "installed isolated synthetic APK"
        found = adb_cmd(adb, args.serial, ["shell", "pm", "path", "--user", str(args.user), PACKAGE])
        paths = [line[8:].strip() for line in found.stdout.splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].endswith("/base.apk"):
            raise RuntimeError("Installed synthetic APK path is ambiguous")
        installed_sha = adb_cmd(adb, args.serial, ["shell", "sha256sum", paths[0]]).stdout.split()[0]
        if installed_sha != report["apk_sha256"]:
            raise RuntimeError("Installed synthetic APK bytes differ from local signed artifact")
        report["installed_apk_sha256"] = installed_sha
        for mode in ("stable", "unstable"):
            report["observations"].append(observe(adb, args.serial, args.user, mode, out))
        baseline, candidate = report["observations"]
        report["status"] = "PASS" if (not baseline["client_survived"]
                                     and baseline["provider_sigkill_confirmed"]
                                     and baseline["client_dependency_death_confirmed"]
                                     and candidate["client_survived"]
                                     and candidate["provider_sigkill_confirmed"]
                                     and not candidate["client_dependency_death_confirmed"]
                                     and candidate["after"]
                                     and candidate["after"].get("stage") == "SURVIVED") else "FAIL"
        report["interpretation"] = (
            "The synthetic stable client died and the unstable client survived provider SIGKILL"
            if report["status"] == "PASS" else
            "Control contrast not established; do not extrapolate to WAEX")
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        report["end"] = time.time()
        (out / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"status": report["status"], "artifact": str(out / "result.json"),
                      "error": report.get("error")}, ensure_ascii=False))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
