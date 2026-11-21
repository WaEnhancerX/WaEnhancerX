package com.waenhancer.xposed.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Local sh processes and explicit stream failures; no su invocation or privilege elevation. */
public final class RootCommandBoundedChecks {
    private static int checks;
    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    private static RootCommandRunner.Starter shell() {
        return command -> new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
    }
    private static String repeat(int count) { return "head -c " + count + " /dev/zero | tr '\\000' x"; }
    private static final class StreamProcess extends Process {
        private final InputStream stream;
        StreamProcess(InputStream stream) { this.stream = stream; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stream; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { try { stream.close(); } catch (IOException ignored) {} }
    }
    public static void main(String[] args) {
        check("ok".equals(RootCommandRunner.runBounded("printf ' ok '", 2000, 16, shell())), "complete output retains legacy trimming");
        check("".equals(RootCommandRunner.runBounded("true", 2000, 16, shell())), "empty successful output");
        check(RootCommandRunner.runBounded(repeat(12000), 2000, 16000, shell()).length() == 12000, "capture greater than 8192 remains complete");
        check(RootCommandRunner.runBounded(repeat(98304), 2000, 98304, shell()).length() == 98304, "exact 96 KiB cap");
        check(RootCommandRunner.runBounded(repeat(98305), 2000, 98304, shell()) == null, "cap plus one fails closed");
        check(RootCommandRunner.runBounded(repeat(250000), 2000, 8192, shell()) == null, "excess output drains without returning prefix");
        check(RootCommandRunner.run(repeat(250000), 2000, shell()).length() == 8192, "existing unbounded-contract API still truncates to 8192");
        check(RootCommandRunner.runBounded("printf x; exit 7", 2000, 16, shell()) == null, "nonzero exit rejects partial output");
        check(RootCommandRunner.runBounded("sleep 1", 40, 16, shell()) == null, "timeout fails closed");
        check(RootCommandRunner.runBounded("ok", 2000, 16, value -> { throw new IOException("test"); }) == null, "startup IO failure");
        check(RootCommandRunner.runBounded("ok", 2000, 16, value -> { throw new SecurityException("test"); }) == null, "startup security failure");
        check(RootCommandRunner.runBounded("ok", 2000, 0, shell()) == null, "zero limit rejected");
        check(RootCommandRunner.runBounded("ok", 2000, 1048577, shell()) == null, "oversized limit rejected");
        check(RootCommandRunner.runBounded(null, 2000, 16, shell()) == null, "null command rejected");
        check(RootCommandRunner.runBounded(" ", 2000, 16, shell()) == null, "blank command rejected");
        check(RootCommandRunner.runBounded("ok", 0, 16, shell()) == null, "nonpositive timeout rejected");
        check(RootCommandRunner.runBounded("ok", 2000, 16, null) == null, "missing starter rejected");
        check(RootCommandRunner.runBounded("ok", 2000, 16, value -> new StreamProcess(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("test stream failure"); }
        })) == null, "stream IO failure never masquerades as complete output");
        check("é".equals(RootCommandRunner.runBounded("ok", 2000, 1, value -> new StreamProcess(
            new ByteArrayInputStream("é".getBytes(StandardCharsets.UTF_8))))), "limit is documented captured characters");
        Thread.currentThread().interrupt();
        check(RootCommandRunner.runBounded("sleep 1", 2000, 16, shell()) == null && Thread.currentThread().isInterrupted(), "interruption preserved");
        Thread.interrupted();
        System.out.println("ROOT_BOUNDED_CHECKS=" + checks);
    }
}
