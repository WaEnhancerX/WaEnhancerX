#!/usr/bin/env python3
"""Execute the real status-menu listener with a controlled asynchronous queue.

The fixture replaces only Android UI/host dependencies and scheduling. It checks
selection at tap time, not MediaStore permissions, file bytes, WhatsApp classes,
or Xposed injection. The negative control reintroduces worker-time selection.
"""

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / "app/src/main/java/com/waenhancer/xposed/features/media/StatusDownloadHook.java"
if not __debug__:
    raise SystemExit("BLOCKED: status selection assertions are disabled under Python -O")


def method(source: str, name: str) -> str:
    match = re.search(r"(?m)^    private (?:void|boolean) " + re.escape(name) + r"\(", source)
    if not match:
        raise AssertionError(f"Missing production method: {name}")
    opening = source.index("{", match.end())
    depth = 1
    cursor = opening + 1
    while depth and cursor < len(source):
        if source[cursor] == "{":
            depth += 1
        elif source[cursor] == "}":
            depth -= 1
        cursor += 1
    if depth:
        raise AssertionError(f"Unbalanced production method: {name}")
    return source[match.start():cursor]


JAVA = r'''
import java.io.File;
import java.util.ArrayList;
import java.util.List;

class Drawable {}
class MenuIconLoader { static Drawable load(Object context, String name) { return null; } }
class UiText { static String get(int id, String fallback) { return fallback; } }
class R { static class string {
    static final int waex_ui_copy_text=1, waex_ui_download=2,
        waex_ui_failed_to_download_status=3, waex_ui_please_wait_until_the_status_finishes=4;
} }
class Toast { static final int LENGTH_LONG=1; }
class UiThread {
    static final List<String> messages=new ArrayList<>();
    static void toast(Object context, String message) { messages.add(message); }
    static void toast(Object context, String message, int duration) {
        if(duration!=Toast.LENGTH_LONG)throw new AssertionError("Unexpected toast duration");
        messages.add("long:"+message);
    }
}
class XposedBridge {
    static final List<String> diagnostics=new ArrayList<>();
    static void log(String diagnostic) { diagnostics.add(diagnostic); }
}
class MenuItem {
    interface ClickListener { boolean onMenuItemClick(MenuItem item); }
    ClickListener clickListener;
    String title;
    void setTitle(String value) { title=value; }
    void setIcon(Drawable value) {}
    void setOnMenuItemClickListener(ClickListener listener) { clickListener=listener; }
    void tap() { if (clickListener==null || !clickListener.onMenuItemClick(this)) throw new AssertionError("Click not handled"); }
}
class CompletableFuture {
    // Cooperative scheduler: changing the host page before drain() is deterministic.
    static void runAsync(Runnable task) { StatusTapSnapshotContract.queue.add(task); }
}
public class StatusTapSnapshotContract {
    static final List<Runnable> queue=new ArrayList<>();
    static int checks,scenarios,failures;
    static class Item { final File media;final String text;
        Item(String path,String body) { media=path==null?null:new File(path);text=body; }
    }
    static class Host { Item current;Host(Item item) {current=item;} }
    static class Subject {
        static final String TAG="test";
        final Object context=new Object();
        final List<File> saved=new ArrayList<>();
        boolean ready=true, failSave;
        String copied;
        Object resolveCurrentStatusItem(Object source) { return ((Host)source).current; }
        File extractMediaFile(Object item) { return item==null?null:((Item)item).media; }
        String findVisibleTextStatus(Object source) {
            Item item=((Host)source).current;
            return item==null?null:item.text;
        }
        void copyToClipboard(Object context,String text) { copied=text; }
        boolean isMediaFileReady(File file) { return ready && file!=null; }
        void saveMediaFile(Object context,File file) {
            if(failSave)throw new IllegalStateException("synthetic-private-media-path");
            saved.add(file);
        }
        /* PRODUCTION_METHODS */
    }
    static void check(boolean value,String description) { checks++;if(!value)throw new AssertionError(description); }
    static void drain() { List<Runnable> tasks=new ArrayList<>(queue);queue.clear();for(Runnable task:tasks)task.run(); }
    interface Case { void run(); }
    static void test(String label,Case action) {
        scenarios++;queue.clear();UiThread.messages.clear();XposedBridge.diagnostics.clear();
        try { action.run(); System.out.println("PASS "+label); }
        catch(Throwable error) { failures++; System.out.println("FAIL "+label+": "+error.getMessage()); }
    }
    public static void main(String[] args) {
        final Item first=new Item("status-A.jpg",null);
        final Item second=new Item("status-B.png",null);
        test("same_page_before_worker",()->{
            Host host=new Host(first);Subject subject=new Subject();MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();check(queue.size()==1,"one download queued");
            drain();check(subject.saved.equals(List.of(first.media)),"selected file must be saved");
            check(UiThread.messages.isEmpty()&&XposedBridge.diagnostics.isEmpty(),"unexpected export error");
        });
        test("navigate_after_tap_before_worker",()->{
            Host host=new Host(first);Subject subject=new Subject();MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();check(queue.size()==1,"one download queued");
            host.current=second;drain();
            check(subject.saved.equals(List.of(first.media)),"wrong status exported after navigation: "+subject.saved);
            check(UiThread.messages.isEmpty()&&XposedBridge.diagnostics.isEmpty(),"unexpected export error");
        });
        test("navigate_before_tap",()->{
            Host host=new Host(first);Subject subject=new Subject();MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);host.current=second;item.tap();drain();
            check(subject.saved.equals(List.of(second.media)),"tap must use current page, not menu creation page");
            check(UiThread.messages.isEmpty()&&XposedBridge.diagnostics.isEmpty(),"unexpected export error");
        });
        test("two_taps_queued_before_workers",()->{
            Host host=new Host(first);Subject subject=new Subject();MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();host.current=second;item.tap();
            check(queue.size()==2,"each tap must enqueue its own selection");drain();
            check(subject.saved.equals(List.of(first.media,second.media)),"queued taps must not both export latest page: "+subject.saved);
            check(UiThread.messages.isEmpty()&&XposedBridge.diagnostics.isEmpty(),"unexpected export error");
        });
        test("text_status",()->{
            Host host=new Host(new Item(null,"text at tap"));Subject subject=new Subject();MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();drain();
            check(subject.saved.isEmpty() && queue.isEmpty(),"text must not save a media file");
            check("text at tap".equals(subject.copied),"text should be copied");
            check(UiThread.messages.isEmpty()&&XposedBridge.diagnostics.isEmpty(),"text unexpectedly reported an error");
        });
        test("media_not_ready",()->{
            Host host=new Host(first);Subject subject=new Subject();subject.ready=false;MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();drain();
            check(subject.saved.isEmpty(),"unready media must not be exported");
            check(UiThread.messages.equals(List.of("long:Please wait until the status finishes downloading, then try again")),
                    "missing pending-media notice: "+UiThread.messages);
            check(XposedBridge.diagnostics.isEmpty(),"pending media is not an exception");
            // Still loading: the status is known but its file is not on disk yet.
            UiThread.messages.clear();
            Host loading=new Host(new Item(null,null));Subject waiting=new Subject();MenuItem loadingItem=new MenuItem();
            waiting.configureStatusMenuItem(loadingItem,loading);loadingItem.tap();drain();
            check(waiting.saved.isEmpty()&&waiting.copied==null,"loading status must not export or copy");
            check(UiThread.messages.equals(List.of("long:Please wait until the status finishes downloading, then try again")),
                    "loading status must ask the user to wait: "+UiThread.messages);
        });
        test("save_throws",()->{
            Host host=new Host(first);Subject subject=new Subject();subject.failSave=true;MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();drain();
            check(subject.saved.isEmpty(),"failed save must not be reported as complete");
            check(UiThread.messages.equals(List.of("Failed to download status")),"failure toast missing: "+UiThread.messages);
            check(XposedBridge.diagnostics.size()==1 && XposedBridge.diagnostics.get(0).contains("IllegalStateException")
                    && !XposedBridge.diagnostics.get(0).contains("synthetic-private"),"diagnostic must redact error payload");
        });
        test("unresolved_status",()->{
            Host host=new Host(null);Subject subject=new Subject();MenuItem item=new MenuItem();
            subject.configureStatusMenuItem(item,host);item.tap();drain();
            check(subject.saved.isEmpty()&&subject.copied==null&&queue.isEmpty(),"no item must not export or copy");
            check(UiThread.messages.isEmpty()&&XposedBridge.diagnostics.isEmpty(),"unknown item must not fabricate error");
        });
        System.out.println("STATUS_TAP_CASES="+scenarios+";CHECKS="+checks+";FAILURES="+failures);
        if(failures!=0)System.exit(1);
    }
}
'''


def compile_and_run(java_source: str, temp: Path) -> tuple[int, str]:
    target = temp / "StatusTapSnapshotContract.java"
    target.write_text(java_source, encoding="utf-8")
    javac = shutil.which("javac")
    compile_command = ([javac] if javac else ["java", "com.sun.tools.javac.Main"])
    # The Linux runtime exposes jdk.compiler but omits ct.sym, so --release
    # is unavailable there; source/target still compile this isolated fixture.
    compatibility = ["--release", "17"] if javac else ["-source", "17", "-target", "17"]
    compile_command += [*compatibility, "-d", str(temp), str(target)]
    compiled = subprocess.run(compile_command, capture_output=True, text=True, timeout=40)
    if compiled.returncode:
        raise RuntimeError("Production fixture did not compile: " + compiled.stderr)
    command = ["java", "-cp", str(temp), "StatusTapSnapshotContract"]
    result = subprocess.run(command,
                            capture_output=True, text=True, timeout=20)
    return result.returncode, result.stdout + result.stderr


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=HOOK)
    parser.add_argument("--git-ref", help="Read the exact committed hook source from this Git revision")
    parser.add_argument("--expect-reference-failure", action="store_true")
    args = parser.parse_args()
    if args.git_ref:
        relative = HOOK.relative_to(ROOT).as_posix()
        source = subprocess.run(["git", "show", f"{args.git_ref}:{relative}"],
                                cwd=ROOT, capture_output=True, text=True, check=True).stdout
    else:
        source = args.source.read_text(encoding="utf-8")
    selected = "downloadStatusFile" if "    private void downloadStatusFile(" in source else "downloadCurrentStatus"
    blocks = "\n".join(method(source, name) for name in
                       ("configureStatusMenuItem", "isCurrentTextStatus", "isCurrentMediaStatus", selected))
    fixture = JAVA.replace("/* PRODUCTION_METHODS */", blocks)
    with tempfile.TemporaryDirectory(prefix="waex-status-tap-") as directory:
        temp = Path(directory)
        actual_exit, actual_log = compile_and_run(fixture, temp)
        print(actual_log, end="")
        if args.expect_reference_failure:
            assert actual_exit == 1 and "FAIL navigate_after_tap_before_worker:" in actual_log
            assert "FAIL two_taps_queued_before_workers:" in actual_log
            assert "PASS same_page_before_worker" in actual_log
            assert "PASS navigate_before_tap" in actual_log
            assert "PASS text_status" in actual_log
            assert "PASS media_not_ready" in actual_log
            assert "PASS save_throws" in actual_log
            assert "PASS unresolved_status" in actual_log
            assert "STATUS_TAP_CASES=8;" in actual_log and "FAILURES=2" in actual_log
            print("EXPECTED_BASELINE_FAILURE: deferred navigation exports a different status")
            return
        assert actual_exit == 0 and "STATUS_TAP_CASES=8;" in actual_log and "FAILURES=0" in actual_log, "Candidate failed"
        mutant = fixture.replace("downloadStatusFile(selectedMediaFile);",
                                 "downloadStatusFile(extractMediaFile(resolveCurrentStatusItem(sourceObject)));")
        assert mutant != fixture, "Negative control did not mutate late-bound source"
        mutant_dir = temp / "mutant"
        mutant_dir.mkdir()
        mutation_exit, mutation_log = compile_and_run(mutant, mutant_dir)
        assert mutation_exit == 1 and "FAIL navigate_after_tap_before_worker:" in mutation_log
        assert "FAIL two_taps_queued_before_workers:" in mutation_log
        assert "PASS same_page_before_worker" in mutation_log
        assert "PASS navigate_before_tap" in mutation_log
        assert "PASS text_status" in mutation_log
        assert "PASS media_not_ready" in mutation_log
        assert "PASS save_throws" in mutation_log
        assert "PASS unresolved_status" in mutation_log
        assert "STATUS_TAP_CASES=8;" in mutation_log and "FAILURES=2" in mutation_log
        print("PASS: production 8/8; late-binding mutant rejected (2 navigation failures, 6 controls pass)")


if __name__ == "__main__":
    main()
