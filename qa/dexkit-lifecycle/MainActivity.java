package org.thekillermax.waexlab.dexkitlab;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodDataList;

/** Permission-free isolated dependency test. Reads this app's synthetic code only. */
public final class MainActivity extends Activity {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private TextView view;
    private FileOutputStream journal;
    private long started;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this);
        view.setText("WAEX dependency lifecycle laboratory\nNo permissions; synthetic data only.");
        setContentView(view);
        if (state != null || !getIntent().getBooleanExtra("run", false)) return;
        final String id = getIntent().getStringExtra("run_id");
        final int rounds = getIntent().getIntExtra("rounds", 256);
        final int workers = getIntent().getIntExtra("workers", 4);
        if (id == null || !id.matches("[a-f0-9]{12}") || rounds < 1 || rounds > 1024
                || workers < 1 || workers > 4 || Build.VERSION.SDK_INT != 34
                || !getPackageName().equals(BuildConfig.APPLICATION_ID)) {
            view.setText("BLOCKED: invalid experiment identity, parameters or Android API");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            view.setText("BLOCKED: a run already owns this process"); return;
        }
        new Thread(() -> execute(id, rounds, workers), "WAEX-DexKit-lifecycle").start();
    }

    private synchronized void record(String event, int round, String detail) throws Exception {
        JSONObject row = new JSONObject();
        row.put("event", event).put("round", round).put("detail", detail)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
        journal.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        journal.flush();
    }

    private void verifyQuery(DexKitBridge bridge, int c, int m, boolean absent) {
        String token = String.format(Locale.ROOT, "WAEX_DK_C%02d_M%02d", c, m);
        if (absent) token += "_ABSENT";
        MethodDataList found = bridge.findMethod(FindMethod.create()
                .searchPackages("org.thekillermax.waexlab.fixture")
                .matcher(MethodMatcher.create().usingEqStrings(token)));
        if (absent) {
            if (!found.isEmpty()) throw new AssertionError("Absent marker matched");
            return;
        }
        String expected = String.format(Locale.ROOT,
                "Lorg/thekillermax/waexlab/fixture/C%02d;->m%02d()Ljava/lang/String;", c, m);
        if (found.size() != 1 || !expected.equals(found.get(0).getDescriptor())) {
            throw new AssertionError("Synthetic method identity/count mismatch");
        }
    }

    private void execute(String id, int rounds, int workers) {
        started = SystemClock.elapsedRealtime();
        int completed = 0;
        String outcome = "FAIL";
        ExecutorService queries = Executors.newFixedThreadPool(2);
        try {
            File directory = getExternalFilesDir(null);
            if (directory == null) throw new IllegalStateException("Own evidence directory unavailable");
            File file = new File(directory, "run-" + id + ".jsonl");
            if (!file.createNewFile()) throw new IllegalStateException("Evidence already exists");
            journal = new FileOutputStream(file, false);
            record("START", 0, BuildConfig.DEXKIT_VERSION + ":" + BuildConfig.AAR_SHA256);
            System.loadLibrary("dexkit");
            String ownApk = getApplicationInfo().sourceDir;
            for (int round = 0; round < rounds; round++) {
                if (SystemClock.elapsedRealtime() - started > 30000) {
                    throw new java.util.concurrent.TimeoutException("Cooperative 30s experiment deadline");
                }
                record("BEFORE_CREATE", round, "own APK only");
                DexKitBridge bridge = DexKitBridge.create(ownApk);
                try {
                    if (!bridge.isValid()) throw new AssertionError("Bridge invalid after create");
                    bridge.setThreadNum(workers);
                    bridge.setMaxConcurrentQueries(2);
                    final int c = round % 16;
                    final int m = (round / 16) % 16;
                    record("BEFORE_QUERY_AND_CLOSE", round, "independent positive and absent markers");
                    if ((round & 1) == 0) {
                        verifyQuery(bridge, c, m, false);
                        verifyQuery(bridge, c, m, true);
                    } else {
                        Future<Void> positive = queries.submit((Callable<Void>) () -> {
                            verifyQuery(bridge, c, m, false); return null;
                        });
                        Future<Void> negative = queries.submit((Callable<Void>) () -> {
                            verifyQuery(bridge, c, m, true); return null;
                        });
                        positive.get(5, TimeUnit.SECONDS);
                        negative.get(5, TimeUnit.SECONDS);
                    }
                } finally {
                    bridge.close();
                }
                if (bridge.isValid()) throw new AssertionError("Bridge valid after close");
                completed++;
                record("AFTER_CLOSE", round, "closed");
            }
            outcome = "PASS";
        } catch (Throwable failure) {
            try { if (journal != null) record("FAIL", completed, failure.toString()); }
            catch (Exception ignored) { /* Host retains exit state if evidence I/O failed. */ }
        } finally {
            queries.shutdownNow();
            try {
                if (!queries.awaitTermination(2, TimeUnit.SECONDS)) outcome = "FAIL";
                if (journal != null) {
                    record("FINISHED", completed, outcome);
                    journal.getFD().sync();
                    journal.close();
                }
            } catch (Exception failure) { outcome = "FAIL"; }
            RUNNING.set(false);
            android.util.Log.i("WAEX_DK_LAB", "FINAL:" + id + ":" + outcome + ":" + completed);
            final String text = outcome + ": " + completed + "/" + rounds
                    + " lifecycle rounds; DexKit " + BuildConfig.DEXKIT_VERSION;
            runOnUiThread(() -> view.setText(text));
        }
    }
}
