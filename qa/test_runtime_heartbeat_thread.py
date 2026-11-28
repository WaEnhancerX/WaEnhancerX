#!/usr/bin/env python3
"""Check the current off-main runtime reporting contract, not the retired heartbeat.

Exact production declarations/methods are extracted from MainHook. Android
lifecycle/provider endpoints are strict doubles; this is not APK/device acceptance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

MAIN_REL = Path("app/src/main/java/com/waenhancer/xposed/MainHook.java")


def extract_braced(source, marker):
    if source.count(marker) != 1:
        raise AssertionError(f"Expected one production declaration: {marker}")
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"Unbalanced production declaration: {marker}")


def extract_production(source):
    # Current nightly removed the clock. Reintroducing it is not reconciliation.
    if re.search(r"HEARTBEAT_|scheduleWithFixedDelay|scheduleAtFixedRate", source):
        raise AssertionError("Retired periodic runtime clock was reintroduced")
    begin = source.index("private static final ExecutorService REPORT_EXECUTOR")
    end = source.index("/** Runs", begin)
    declarations = source[begin:end]
    fields = []
    for name in ("sRuntimeStatus", "sBridgeStatus", "sRuntimeError"):
        match = re.search(r"private static volatile String " + name + r"[^;]*;", source)
        if not match:
            raise AssertionError(f"Missing runtime state: {name}")
        fields.append(match.group(0))
    methods = [extract_braced(source, "private static void " + name + "(")
               for name in ("reportTargetRuntime", "queueDeferred", "drainDeferred",
                            "releaseDeferredReporting", "installDeferredReporting")]
    return "\n".join([*fields, declarations, *methods])


JAVA = r'''
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
@interface NonNull {}
class Context {
    Context app = this;
    Context getApplicationContext() { return app; }
}
class Activity {}
class Looper {
    static final Looper MAIN = new Looper();
    static Looper getMainLooper() { return MAIN; }
}
class Handler {
    static final List<Runnable> callbacks = new ArrayList<>();
    static final List<Long> delays = new ArrayList<>();
    Handler(Looper looper) { if (looper != Looper.MAIN) throw new AssertionError("unexpected Looper"); }
    boolean postDelayed(Runnable task, long delay) {
        if (task == null || delay != 5000L) throw new AssertionError("unexpected fallback contract");
        callbacks.add(task); delays.add(delay); return true;
    }
}
class ActivityTracker {
    interface ActivityListener {
        void onActivityCreated(Activity activity);
        void onActivityResumed(Activity activity);
        void onActivityStopped(Activity activity);
        void onActivityDestroyed(Activity activity);
    }
    static final List<ActivityListener> listeners = new ArrayList<>();
    static Activity current;
    static boolean fail;
    static void addListener(ActivityListener listener) {
        if (fail) throw new IllegalStateException("synthetic listener failure");
        listeners.add(listener);
    }
    static Activity getCurrentActivity() { return current; }
}
class HookErrors {
    static int calls;
    static void swallowed(String site, Throwable failure) {
        if (!"MainHook".equals(site) || !(failure instanceof IllegalStateException))
            throw new AssertionError("unexpected diagnostic");
        calls++;
    }
}
class WhatsAppPackageDetector {
    record Report(Context context, String pkg, String status, String bridge, String error, Thread thread) {}
    static final List<Report> reports = new CopyOnWriteArrayList<>();
    static final AtomicInteger active = new AtomicInteger();
    static final AtomicInteger maxActive = new AtomicInteger();
    static volatile CountDownLatch hold;
    static volatile CountDownLatch entered;
    static volatile boolean failNext;
    static void reportRuntimeHealth(Context context, String pkg, String status, String bridge, String error) {
        if (context == null || !("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg)))
            throw new AssertionError("unexpected context/package");
        int count = active.incrementAndGet();
        maxActive.accumulateAndGet(count, Math::max);
        try {
            reports.add(new Report(context, pkg, status, bridge, error, Thread.currentThread()));
            CountDownLatch wait = hold;
            if (wait != null) {
                entered.countDown();
                if (!wait.await(500, TimeUnit.MILLISECONDS)) throw new AssertionError("provider wait timed out");
            }
            if (failNext) { failNext = false; throw new IllegalStateException("synthetic provider failure"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        } finally { active.decrementAndGet(); }
    }
}
class MainHook {
    /* PRODUCTION */
    static void report(Context c, String pkg, String state, String bridge, String error) {
        reportTargetRuntime(c, pkg, state, bridge, error);
    }
    static void release() { releaseDeferredReporting(); }
    static void install() { installDeferredReporting(new Context()); }
    static String state() { return sRuntimeStatus + "/" + sBridgeStatus + "/" + sRuntimeError; }
    static void flush() throws Exception { REPORT_EXECUTOR.submit(() -> {}).get(3, TimeUnit.SECONDS); }
    static void reset() throws Exception {
        if (WhatsAppPackageDetector.hold != null) WhatsAppPackageDetector.hold.countDown();
        flush();
        DEFERRED_REPORTS.clear(); REPORTING_RELEASED.set(false); REPORTING_INSTALLED.set(false);
        sRuntimeStatus="INJECTED"; sBridgeStatus="PENDING"; sRuntimeError=null;
        WhatsAppPackageDetector.reports.clear(); WhatsAppPackageDetector.maxActive.set(0);
        WhatsAppPackageDetector.hold=null; WhatsAppPackageDetector.entered=null; WhatsAppPackageDetector.failNext=false;
        ActivityTracker.listeners.clear(); ActivityTracker.current=null; ActivityTracker.fail=false;
        Handler.callbacks.clear(); Handler.delays.clear(); HookErrors.calls=0;
    }
    static void stop() { REPORT_EXECUTOR.shutdownNow(); }
}
public class RuntimeHeartbeatChecks {
    interface Check { void run() throws Exception; }
    static int cases, failed;
    static final Thread caller = Thread.currentThread();
    static final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void report(String state) { MainHook.report(new Context(), "com.whatsapp", state, "PROVIDER", null); }
    static List<WhatsAppPackageDetector.Report> records() { return WhatsAppPackageDetector.reports; }
    static void test(String name, Check body) throws Exception {
        MainHook.reset(); cases++;
        try { body.run(); System.out.println("PASS|" + name); }
        catch (Throwable error) { failed++; System.out.println("FAIL|" + name + "|" + error); }
        finally { if (WhatsAppPackageDetector.hold != null) WhatsAppPackageDetector.hold.countDown(); MainHook.flush(); }
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> uncaught.add(error));
        try {
            test("state changes immediately but IPC waits for release", () -> {
                MainHook.report(new Context(),"com.whatsapp","READY","FAILED","error-type");
                check(MainHook.state().equals("READY/FAILED/error-type"),"local status not updated");
                MainHook.flush(); check(records().isEmpty(),"provider ran before release");
            });
            test("queued reports preserve order and captured status", () -> {
                report("FIRST"); report("SECOND"); MainHook.release(); MainHook.flush();
                check(records().size()==2,"queued reports lost/duplicated");
                check(records().get(0).status().equals("FIRST")&&records().get(1).status().equals("SECOND"),"captured status overwritten");
            });
            test("application context and business package are preserved", () -> {
                Context owner=new Context(), app=new Context(); owner.app=app;
                MainHook.report(owner,"com.whatsapp.w4b","READY","PROVIDER","diagnostic");
                MainHook.release();MainHook.flush(); var r=records().get(0);
                check(r.context()==app&&r.pkg().equals("com.whatsapp.w4b")&&r.error().equals("diagnostic"),"IPC inputs changed");
            });
            test("missing application context falls back to caller context", () -> {
                Context owner=new Context();owner.app=null; MainHook.report(owner,"com.whatsapp","READY","PROVIDER",null);
                MainHook.release();MainHook.flush();check(records().get(0).context()==owner,"context fallback lost");
            });
            test("one low priority daemon worker handles provider IPC", () -> {
                MainHook.release();report("FIRST");report("SECOND");MainHook.flush();
                Thread worker=records().get(0).thread();
                check(worker!=caller&&worker.isDaemon(),"IPC worker is caller or non-daemon");
                check(worker.getName().equals("WAEX-Report")&&worker.getPriority()==Thread.MIN_PRIORITY,"worker identity/priority changed");
                check(records().get(1).thread()==worker,"worker not reused");
            });
            test("repeated release never duplicates a report", () -> {
                report("ONCE");for(int i=0;i<4;i++)MainHook.release();MainHook.flush();check(records().size()==1,"duplicate drain");
            });
            test("later reports execute after release", () -> {
                MainHook.release();report("LATER");MainHook.flush();check(records().size()==1,"post-release report missing");
                check(records().get(0).thread()!=caller,"post-release caller IPC");
            });
            test("reporting installation is idempotent", () -> {
                MainHook.install();MainHook.install();check(ActivityTracker.listeners.size()==1&&Handler.callbacks.size()==1,"duplicate lifecycle registration");
                check(Handler.delays.get(0)==5000L,"fallback delay changed");
            });
            test("resume releases queued reporting", () -> {
                report("RESUME");MainHook.install(); var listener=ActivityTracker.listeners.get(0);
                listener.onActivityCreated(new Activity());listener.onActivityStopped(new Activity());listener.onActivityDestroyed(new Activity());
                MainHook.flush();check(records().isEmpty(),"non-resume lifecycle released reports");
                listener.onActivityResumed(new Activity());MainHook.flush();check(records().size()==1,"resume did not drain");
            });
            test("late injection releases if activity already exists", () -> {
                report("LATE");ActivityTracker.current=new Activity();MainHook.install();MainHook.flush();check(records().size()==1,"late injection stuck");
            });
            test("fallback releases but does not perform IPC on UI callback", () -> {
                report("FALLBACK");MainHook.install();Handler.callbacks.get(0).run();MainHook.flush();
                check(records().size()==1&&records().get(0).thread()!=caller,"fallback IPC ran inline");
            });
            test("listener failure remains diagnosed and fallback works", () -> {
                report("FAILURE");ActivityTracker.fail=true;MainHook.install();check(HookErrors.calls==1,"registration failure not diagnosed");
                Handler.callbacks.get(0).run();MainHook.flush();check(records().size()==1,"fallback missing after listener failure");
            });
            test("slow provider calls do not overlap or block report submission", () -> {
                MainHook.release();WhatsAppPackageDetector.hold=new CountDownLatch(1);WhatsAppPackageDetector.entered=new CountDownLatch(1);
                report("SLOW");check(WhatsAppPackageDetector.entered.await(2,TimeUnit.SECONDS),"provider never entered");
                report("NEXT");check(records().size()==1,"second call overlapped first");
                WhatsAppPackageDetector.hold.countDown();MainHook.flush();
                check(records().size()==2&&WhatsAppPackageDetector.maxActive.get()==1,"serial provider contract broken");
            });
            test("provider exception does not discard later reports", () -> {
                MainHook.release();WhatsAppPackageDetector.failNext=true;report("FAIL");report("RECOVER");MainHook.flush();
                check(records().size()==2&&records().get(1).status().equals("RECOVER"),"worker failure lost later report");
                check(!uncaught.isEmpty()&&uncaught.get(uncaught.size()-1) instanceof IllegalStateException,"failure not visible");
            });
            test("concurrent release and enqueue preserve every report", () -> {
                int threads=6, each=40; ExecutorService senders=Executors.newFixedThreadPool(threads);
                CountDownLatch start=new CountDownLatch(1); List<Future<?>> futures=new ArrayList<>();
                try {
                    for(int i=0;i<threads;i++){final int id=i;futures.add(senders.submit(() -> {try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}for(int j=0;j<each;j++)report(id+":"+j);}));}
                    start.countDown();MainHook.release();for(Future<?> f:futures)f.get(3,TimeUnit.SECONDS);MainHook.flush();
                    Set<String> unique=new HashSet<>();for(var r:records())unique.add(r.status());
                    check(records().size()==threads*each&&unique.size()==threads*each,"race lost or duplicated a report");
                    check(WhatsAppPackageDetector.maxActive.get()==1,"concurrent provider IPC");
                } finally { senders.shutdownNow(); }
            });
            System.out.println("RESULT|"+cases+"|"+failed);
            check(cases==15&&failed==0,"runtime reporting regressions");
        } finally { MainHook.stop(); }
    }
}
'''


def run_once(source, directory):
    production = extract_production(source)
    generated = JAVA.replace("/* PRODUCTION */", production)
    directory.mkdir(parents=True)
    fixture = directory / "RuntimeHeartbeatChecks.java"
    fixture.write_text(generated, encoding="utf-8")
    steps = []
    for command in (["javac", "--release", "17", "-d", str(directory), str(fixture)],
                    ["java", "-cp", str(directory), "RuntimeHeartbeatChecks"]):
        process = subprocess.run(command, text=True, capture_output=True, timeout=20)
        log = process.stdout + process.stderr
        stage = "compile" if command[0] == "javac" else "runtime"
        (directory / f"{stage}.log").write_text(log, encoding="utf-8")
        steps.append({"command": command, "exit": process.returncode, "output": log})
        if stage == "compile" and process.returncode:
            break
    summary = re.search(r"RESULT\|(\d+)\|(\d+)", steps[-1]["output"])
    return {"compiled": len(steps) == 2, "passed": len(steps) == 2 and steps[-1]["exit"] == 0,
            "cases": int(summary[1]) if summary else 0, "failures": int(summary[2]) if summary else None,
            "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
            "extracted_sha256": hashlib.sha256(production.encode()).hexdigest(),
            "generated_sha256": hashlib.sha256(generated.encode()).hexdigest(), "steps": steps}


def execute(root, out):
    source = (root / MAIN_REL).read_text(encoding="utf-8")
    result = {"candidate": run_once(source, out / "candidate"), "mutations": {},
              "scope": "Extracted production methods/declarations with strict JVM lifecycle/provider doubles; no APK/device."}
    for name, before, after in (
        ("non-daemon", "t.setDaemon(true);", "t.setDaemon(false);"),
        ("inline-provider", "REPORT_EXECUTOR.execute(task);", "task.run();"),
        ("discard-before-release", "DEFERRED_REPORTS.add(task);", "/* mutation: discarded */"),
        ("duplicate-installation", "if (!REPORTING_INSTALLED.compareAndSet(false, true)) return;", "REPORTING_INSTALLED.set(true);"),
    ):
        count = source.count(before)
        assert count == (2 if name == "inline-provider" else 1), f"mutation marker changed: {name}"
        result["mutations"][name] = run_once(source.replace(before, after), out / name)
    result["status"] = "PASS" if result["candidate"]["passed"] and all(
        entry["compiled"] and entry["cases"] == 15 and not entry["passed"] and entry["failures"] > 0
        for entry in result["mutations"].values()) else "FAIL"
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: {name: value for name, value in v.items() if name != "steps"}
                      for k, v in [("candidate", result["candidate"]), *result["mutations"].items()]}, indent=2))
    print(result["status"])
    return result["status"] == "PASS"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    if args.out:
        args.out.mkdir(parents=True, exist_ok=False)
        return 0 if execute(args.source_root, args.out) else 1
    with tempfile.TemporaryDirectory(prefix="waex-runtime-reporting-") as folder:
        return 0 if execute(args.source_root, Path(folder)) else 1


if __name__ == "__main__":
    raise SystemExit(main())
