#!/usr/bin/env python3
"""Compile the real MainHook heartbeat method and test its scheduler contract.

This is a focused Java contract probe, not a full WAEX APK or WhatsApp test.
The method and executor declaration are extracted byte-for-byte from production.
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


MAIN_REL = Path("app/src/main/java/com/waenhancer/xposed/MainHook.java")
START = "private static void reportTargetRuntime("
EXECUTOR = "private static final ScheduledExecutorService HEARTBEAT_EXECUTOR"


def extract_braced(source: str, marker: str) -> str:
    start = source.find(marker)
    if start < 0:
        raise AssertionError(f"Missing production declaration: {marker}")
    opening = source.find("{", start)
    if opening < 0:
        raise AssertionError(f"Malformed production declaration: {marker}")
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start : index + 1]
    raise AssertionError(f"Unbalanced production declaration: {marker}")


def extract_production(source: str) -> tuple[str, str]:
    declaration = re.search(
        r"private static final ScheduledExecutorService HEARTBEAT_EXECUTOR\s*=\s*"
        r"Executors\.newSingleThreadScheduledExecutor\(task -> \{"
        r"[\s\S]*?\n\s*\}\);",
        source,
    )
    if declaration is None:
        raise AssertionError("Missing dedicated, single-threaded heartbeat executor")
    worker = declaration.group(0)
    method = extract_braced(source, START)
    if "scheduleWithFixedDelay(" not in method:
        raise AssertionError("Heartbeat must use completion-relative fixed delay")
    if "TimeUnit.MILLISECONDS" not in method:
        raise AssertionError("Heartbeat interval must retain millisecond units")
    if "Handler(Looper.getMainLooper())" in method:
        raise AssertionError("Heartbeat must not be scheduled on WhatsApp's UI Looper")
    if "thread.setDaemon(true)" not in worker:
        raise AssertionError("Heartbeat executor thread must be daemon")
    if "HEARTBEAT_STARTED.compareAndSet(false, true)" not in method:
        raise AssertionError("Missing at-most-once heartbeat scheduling guard")
    return worker, method


JAVA_TEMPLATE = r"""
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

class Context {
    Context getApplicationContext() { return this; }
}

class XposedBridge {
    static final List<String> errors = new CopyOnWriteArrayList<>();
    static void log(String message) { errors.add(message); }
}

class WhatsAppPackageDetector {
    static final List<Record> reports = new CopyOnWriteArrayList<>();
    static final AtomicBoolean failNext = new AtomicBoolean();
    static final AtomicInteger active = new AtomicInteger();
    static final AtomicInteger maxActive = new AtomicInteger();
    static volatile long slowMillis;

    static final class Record {
        final Thread thread;
        final String status;
        final String bridge;
        final long startedNanos;
        Record(String status, String bridge) {
            this.thread = Thread.currentThread();
            this.status = status;
            this.bridge = bridge;
            this.startedNanos = System.nanoTime();
        }
    }

    static void reportRuntimeHealth(Context ctx, String pkg, String status,
                                    String bridge, String error) {
        if (ctx == null || !"com.whatsapp".equals(pkg)) {
            throw new AssertionError("Unexpected context or target");
        }
        int concurrent = active.incrementAndGet();
        maxActive.accumulateAndGet(concurrent, Math::max);
        try {
            reports.add(new Record(status, bridge));
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("controlled heartbeat failure");
            }
            if (slowMillis > 0) {
                try { Thread.sleep(slowMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            active.decrementAndGet();
        }
    }
}

class MainHookHarness {
    private static final AtomicBoolean HEARTBEAT_STARTED = new AtomicBoolean(false);
    // Only the timer duration is scaled down in this isolated contract probe.
    private static final long HEARTBEAT_INTERVAL_MS = 40L;
    private static volatile String sRuntimeStatus = "INJECTED";
    private static volatile String sBridgeStatus = "PENDING";
    private static volatile String sRuntimeError;

    /* EXACT_EXECUTOR */

    /* EXACT_METHOD */

    static void testReport(Context context, String packageName, String status,
                           String bridge, String error) {
        reportTargetRuntime(context, packageName, status, bridge, error);
    }
    static boolean heartbeatStarted() { return HEARTBEAT_STARTED.get(); }
    static void stopHeartbeat() { HEARTBEAT_EXECUTOR.shutdownNow(); }
}

public class RuntimeHeartbeatChecks {
    private static int cases;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        cases++;
    }
    private static void awaitReports(int count, long maxMillis) throws Exception {
        long until = System.nanoTime() + maxMillis * 1_000_000L;
        while (WhatsAppPackageDetector.reports.size() < count &&
                System.nanoTime() < until) Thread.sleep(8);
        check(WhatsAppPackageDetector.reports.size() >= count,
                "Timed out waiting for report " + count);
    }
    public static void main(String[] args) throws Exception {
        Thread main = Thread.currentThread();
        try {
            MainHookHarness.testReport(new Context(), "com.whatsapp",
                    "READY", "PROVIDER", null);
            check(WhatsAppPackageDetector.reports.size() == 1,
                    "Initial report must be available synchronously");
            check(WhatsAppPackageDetector.reports.get(0).thread == main,
                    "Keep the original initial-report contract");
            Thread.sleep(12);
            check(WhatsAppPackageDetector.reports.size() == 1,
                    "First background report must retain its initial delay");

            awaitReports(4, 1100);
            Thread worker = WhatsAppPackageDetector.reports.get(1).thread;
            check(worker != main, "Recurring provider IPC cannot use the UI thread");
            check(worker.isDaemon(), "Heartbeat must not keep the process alive");
            check(worker.getName().equals("WAEX Runtime Health"),
                    "Scheduled thread must have a stable diagnostic name");
            for (int i = 1; i < WhatsAppPackageDetector.reports.size(); i++) {
                check(WhatsAppPackageDetector.reports.get(i).thread == worker,
                        "Must reuse only one worker thread");
            }

            int before = WhatsAppPackageDetector.reports.size();
            MainHookHarness.testReport(new Context(), "com.whatsapp",
                    "READY_WITH_ERRORS", "FAILED", null);
            check(WhatsAppPackageDetector.reports.size() == before + 1,
                    "A new status must retain immediate reporting");
            awaitReports(before + 3, 1100);
            check(WhatsAppPackageDetector.reports.get(
                    WhatsAppPackageDetector.reports.size() - 1).status.equals("READY_WITH_ERRORS"),
                    "Recurring report must observe the newest volatile runtime status");

            WhatsAppPackageDetector.failNext.set(true);
            int failedAt = WhatsAppPackageDetector.reports.size();
            awaitReports(failedAt + 3, 1100);
            check(!XposedBridge.errors.isEmpty(),
                    "Report failure must remain observable");
            check(XposedBridge.errors.get(0).contains("IllegalStateException"),
                    "Only bounded failure type is logged");
            check(WhatsAppPackageDetector.reports.size() >= failedAt + 3,
                    "A failed report must not cancel subsequent heartbeats");

            WhatsAppPackageDetector.slowMillis = 85L;
            int slowFrom = WhatsAppPackageDetector.reports.size();
            awaitReports(slowFrom + 3, 1700);
            check(WhatsAppPackageDetector.maxActive.get() == 1,
                    "Provider calls must not overlap");
            List<WhatsAppPackageDetector.Record> slow = new ArrayList<>(
                    WhatsAppPackageDetector.reports.subList(
                            slowFrom, WhatsAppPackageDetector.reports.size()));
            long minStartGapMs = Long.MAX_VALUE;
            for (int i = 1; i < slow.size(); i++) {
                long gapMs = (slow.get(i).startedNanos - slow.get(i - 1).startedNanos)
                        / 1_000_000L;
                minStartGapMs = Math.min(minStartGapMs, gapMs);
            }
            check(minStartGapMs >= 115L,
                    "Fixed-delay scheduling must wait after a slow provider call: "
                            + minStartGapMs);
            check(MainHookHarness.heartbeatStarted(),
                    "Scheduling guard must remain active");
            System.out.println("PASS " + cases + " checks, one daemon worker, " +
                    "failure recovery, status propagation, no overlapping IPC");
        } finally {
            MainHookHarness.stopHeartbeat();
        }
    }
}
"""


def compiler() -> list[str]:
    direct = shutil.which("javac")
    if direct:
        return [direct]
    java = shutil.which("java")
    if java:
        return [java, "com.sun.tools.javac.Main"]
    raise RuntimeError("JDK 17+ Java compiler is unavailable")


def run_once(source: str, directory: Path) -> dict:
    worker, method = extract_production(source)
    generated = JAVA_TEMPLATE.replace("/* EXACT_EXECUTOR */", worker).replace(
        "/* EXACT_METHOD */", method
    )
    directory.mkdir(parents=True, exist_ok=True)
    fixture = directory / "RuntimeHeartbeatChecks.java"
    fixture.write_text(generated, encoding="utf-8")
    prefix = compiler()
    cp = subprocess.run(
        prefix + ["--release", "17", "-d", str(directory), str(fixture)],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60,
    )
    mode = "--release 17"
    compile_log = cp.stdout
    # Some minimal JDK images expose javac but omit the ct.sym archive needed
    # for --release. Retain a JDK-17 bytecode smoke test; do not mislabel this
    # fallback as API-signature validation.
    if cp.returncode and "release version 17 not supported" in cp.stdout:
        cp = subprocess.run(
            prefix + ["-source", "17", "-target", "17",
                      "-d", str(directory), str(fixture)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=60,
        )
        mode = "-source 17 -target 17 (ct.sym unavailable)"
        compile_log += "\nFALLBACK:\n" + cp.stdout
    (directory / "compile.log").write_text(compile_log, encoding="utf-8")
    if cp.returncode:
        return {"status": "FAIL", "stage": "compile", "output": cp.stdout[-2000:]}
    proc = subprocess.run(
        ["java", "-cp", str(directory), "RuntimeHeartbeatChecks"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=12,
    )
    (directory / "runtime.log").write_text(proc.stdout, encoding="utf-8")
    return {
        "status": "PASS" if proc.returncode == 0 else "FAIL",
        "exit_code": proc.returncode,
        "compiler_mode": mode,
        "output": proc.stdout[-2000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path,
                        default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--baseline-root", type=Path)
    args = parser.parse_args()

    production = (args.source_root / MAIN_REL).read_text(encoding="utf-8")
    if args.out:
        out = args.out.resolve()
        if out.exists():
            raise SystemExit("Output directory must not already exist")
        out.mkdir(parents=True)
        context = None
    else:
        context = tempfile.TemporaryDirectory(prefix="waex-heartbeat-")
        out = Path(context.name)
    try:
        positive = run_once(production, out / "candidate")
        if positive["status"] != "PASS":
            print(json.dumps({"candidate": positive}, indent=2), file=sys.stderr)
            return 1
        negative = {}
        if args.baseline_root:
            baseline = (args.baseline_root / MAIN_REL).read_text(encoding="utf-8")
            try:
                run_once(baseline, out / "baseline")
                negative["baseline_main_looper"] = "FAIL: old implementation admitted"
            except AssertionError:
                negative["baseline_main_looper"] = "PASS: rejected"
        mutated = production.replace("thread.setDaemon(true)", "thread.setDaemon(false)", 1)
        try:
            run_once(mutated, out / "negative_not_daemon")
            negative["non_daemon_mutation"] = "FAIL: mutation admitted"
        except AssertionError:
            negative["non_daemon_mutation"] = "PASS: rejected"
        mutated = production.replace("scheduleWithFixedDelay(", "scheduleAtFixedRate(", 1)
        try:
            run_once(mutated, out / "negative_fixed_rate")
            negative["fixed_rate_mutation"] = "FAIL: mutation admitted"
        except AssertionError:
            negative["fixed_rate_mutation"] = "PASS: rejected"
        result = {
            "source_sha256": hashlib.sha256(production.encode()).hexdigest(),
            "fixture_sha256": hashlib.sha256(JAVA_TEMPLATE.encode()).hexdigest(),
            "candidate": positive,
            "negative_controls": negative,
            "evidence_scope": "Exact MainHook method/executor extracted; JVM-only, no APK",
        }
        (out / "result.json").write_text(json.dumps(result, indent=2) + "\n",
                                         encoding="utf-8")
        print(json.dumps(result, indent=2))
        return 0 if all(v.startswith("PASS") for v in negative.values()) else 1
    finally:
        if context:
            context.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
