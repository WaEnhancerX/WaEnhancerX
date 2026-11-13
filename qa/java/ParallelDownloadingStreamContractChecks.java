package com.waenhancer.xposed.features.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone JVM regression tests for production stream integrity and fallback behavior. */
public final class ParallelDownloadingStreamContractChecks {
    private static int passed;

    public static void main(String[] args) throws Exception {
        // Selective execution permits independent negative controls against the
        // unmodified upstream class, without altering any production source.
        if (args.length == 1) {
            switch (args[0]) {
                case "truncated-single": singleChunkTruncatedMustFail(); break;
                case "range-end": incorrectRangeEndMustFallBackToSequential(); break;
                case "invalid-slice": boundsMustRejectInvalidSlices(); break;
                default: throw new IllegalArgumentException("Unknown case: " + args[0]);
            }
            System.out.println("PASS " + args[0]);
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Expected zero or one case name");
        singleChunkComplete();
        singleChunkTruncatedMustFail();
        firstChunkTruncatedMustFail();
        exactRangeMustProduceExpectedBytes();
        offsetRangeMustProduceExpectedBytes();
        incorrectRangeEndMustFallBackToSequential();
        incorrectRangeTotalMustFallBackToSequential();
        malformedRangeTotalMustFallBackToSequential();
        http200MustFallBackToSequential();
        boundsMustRejectInvalidSlices();
        System.out.println("PASS " + passed + "/10 parallel stream integrity cases");
    }

    private static void singleChunkComplete() throws Exception {
        byte[] data = "small intact media".getBytes(StandardCharsets.UTF_8);
        try (InputStream stream = stream(data, data.length, null, 0)) {
            require(Arrays.equals(data, readFully(stream)), "single chunk bytes");
        }
        passed++;
    }

    private static void singleChunkTruncatedMustFail() throws Exception {
        byte[] data = "short".getBytes(StandardCharsets.UTF_8);
        try (InputStream stream = stream(data, data.length + 5, null, 0)) {
            expectTruncation(stream, "single chunk truncated");
        }
        passed++;
    }

    private static void firstChunkTruncatedMustFail() throws Exception {
        byte[] data = new byte[ParallelDownloadingInputStream.CHUNK_SIZE - 1];
        Arrays.fill(data, (byte) 'a');
        FakeRangeHandler handler = new FakeRangeHandler(
                "bytes 2097152-2097152/2097153", new byte[]{'b'}, 206);
        try (InputStream stream = stream(data, ParallelDownloadingInputStream.CHUNK_SIZE + 1L,
                handler, 0)) {
            expectTruncation(stream, "first chunk truncated");
        }
        passed++;
    }

    private static void exactRangeMustProduceExpectedBytes() throws Exception {
        byte[] data = largePayload();
        FakeRangeHandler handler = new FakeRangeHandler(
                "bytes 2097152-2097174/2097175", Arrays.copyOfRange(data,
                ParallelDownloadingInputStream.CHUNK_SIZE, data.length), 206);
        // Intentionally expose only chunk 0 from the original stream: an accidental
        // sequential fallback cannot falsely pass the valid-range acceptance case.
        try (InputStream stream = stream(Arrays.copyOf(data,
                ParallelDownloadingInputStream.CHUNK_SIZE), data.length, handler, 0)) {
            require(Arrays.equals(data, readFully(stream)), "valid range bytes");
            require(handler.requests == 1, "valid range request count");
            require("bytes=2097152-2097174".equals(handler.requestedRange), "correct requested range");
            require(handler.bodyOpened == 1, "valid 206 response body must be used");
        }
        passed++;
    }

    private static void offsetRangeMustProduceExpectedBytes() throws Exception {
        byte[] data = largePayload();
        FakeRangeHandler handler = new FakeRangeHandler(
                "bytes 2097402-2097424/2097425", Arrays.copyOfRange(data,
                ParallelDownloadingInputStream.CHUNK_SIZE, data.length), 206);
        try (InputStream stream = stream(Arrays.copyOf(data,
                ParallelDownloadingInputStream.CHUNK_SIZE), data.length, handler, 250)) {
            require(Arrays.equals(data, readFully(stream)), "nonzero start offset bytes");
            require("bytes=2097402-2097424".equals(handler.requestedRange), "offset range request");
            require(handler.bodyOpened == 1, "valid offset 206 response body must be used");
        }
        passed++;
    }

    private static void incorrectRangeEndMustFallBackToSequential() throws Exception {
        byte[] data = largePayload();
        byte[] maliciousChunk = new byte[data.length - ParallelDownloadingInputStream.CHUNK_SIZE];
        Arrays.fill(maliciousChunk, (byte) 'X');
        FakeRangeHandler handler = new FakeRangeHandler(
                "bytes 2097152-2097175/2097176", maliciousChunk, 206);
        try (InputStream stream = stream(data, data.length, handler, 0)) {
            require(Arrays.equals(data, readFully(stream)),
                    "mismatched Content-Range end must preserve original sequential bytes");
            require(handler.bodyOpened == 0, "invalid range body must not be consumed");
        }
        passed++;
    }

    private static void incorrectRangeTotalMustFallBackToSequential() throws Exception {
        byte[] data = largePayload();
        byte[] wrongChunk = new byte[data.length - ParallelDownloadingInputStream.CHUNK_SIZE];
        Arrays.fill(wrongChunk, (byte) 'X');
        FakeRangeHandler handler = new FakeRangeHandler(
                "bytes 2097152-2097174/2097174", wrongChunk, 206);
        try (InputStream stream = stream(data, data.length, handler, 0)) {
            require(Arrays.equals(data, readFully(stream)), "impossible range total must fall back");
            require(handler.bodyOpened == 0, "impossible total body must not be consumed");
        }
        passed++;
    }

    private static void malformedRangeTotalMustFallBackToSequential() throws Exception {
        byte[] data = largePayload();
        byte[] wrongChunk = new byte[data.length - ParallelDownloadingInputStream.CHUNK_SIZE];
        Arrays.fill(wrongChunk, (byte) 'X');
        FakeRangeHandler handler = new FakeRangeHandler(
                "bytes 2097152-2097174/malformed", wrongChunk, 206);
        try (InputStream stream = stream(data, data.length, handler, 0)) {
            require(Arrays.equals(data, readFully(stream)), "nonnumeric range total must fall back");
            require(handler.bodyOpened == 0, "malformed total body must not be consumed");
        }
        passed++;
    }

    private static void http200MustFallBackToSequential() throws Exception {
        byte[] data = largePayload();
        FakeRangeHandler handler = new FakeRangeHandler(null,
                Arrays.copyOfRange(data, ParallelDownloadingInputStream.CHUNK_SIZE, data.length), 200);
        try (InputStream stream = stream(data, data.length, handler, 0)) {
            require(Arrays.equals(data, readFully(stream)), "non-partial response fallback bytes");
            require(handler.bodyOpened == 0, "HTTP 200 body must not be consumed as a range");
        }
        passed++;
    }

    private static void boundsMustRejectInvalidSlices() throws Exception {
        byte[] data = new byte[]{1, 2};
        try (InputStream stream = stream(data, data.length, null, 0)) {
            try {
                stream.read(new byte[3], -1, 0);
                throw new AssertionError("invalid offset accepted when length=0");
            } catch (IndexOutOfBoundsException expected) {
                // Required by InputStream contract, including empty requests.
            }
            try {
                stream.read(new byte[3], 2, 2);
                throw new AssertionError("invalid length accepted");
            } catch (IndexOutOfBoundsException expected) {
                // Required by InputStream contract.
            }
        }
        passed++;
    }

    private static byte[] largePayload() {
        byte[] data = new byte[ParallelDownloadingInputStream.CHUNK_SIZE + 23];
        for (int index = 0; index < data.length; index++) data[index] = (byte) (index * 7 + 3);
        return data;
    }

    private static ParallelDownloadingInputStream stream(byte[] originalBytes, long expectedLength,
            FakeRangeHandler handler, long startOffset) throws Exception {
        URL url = new URL(null, "https://qa.invalid/media", handler != null ? handler : new FakeRangeHandler(null, null, 200));
        return new ParallelDownloadingInputStream(new ByteArrayInputStream(originalBytes),
                expectedLength, 2, url, startOffset);
    }

    private static void expectTruncation(InputStream stream, String label) throws Exception {
        try {
            readFully(stream);
            throw new AssertionError(label + ": accepted early EOF");
        } catch (IOException expected) {
            require(expected.getMessage().contains("truncated"), label + ": diagnostic");
        }
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int count;
        while ((count = stream.read(buffer, 0, buffer.length)) != -1) {
            if (count == 0) throw new AssertionError("positive-length read returned zero");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class FakeRangeHandler extends URLStreamHandler {
        private final String responseRange;
        private final byte[] responseBody;
        private final int responseCode;
        volatile int requests;
        volatile int bodyOpened;
        volatile String requestedRange;

        FakeRangeHandler(String responseRange, byte[] responseBody, int responseCode) {
            this.responseRange = responseRange;
            this.responseBody = responseBody;
            this.responseCode = responseCode;
        }

        @Override protected URLConnection openConnection(URL url) {
            requests++;
            return new HttpURLConnection(url) {
                @Override public void setRequestProperty(String name, String value) {
                    if ("Range".equals(name)) requestedRange = value;
                }
                @Override public int getResponseCode() { return FakeRangeHandler.this.responseCode; }
                @Override public String getHeaderField(String name) {
                    return "Content-Range".equals(name) ? responseRange : null;
                }
                @Override public InputStream getInputStream() {
                    bodyOpened++;
                    return new ByteArrayInputStream(responseBody == null ? new byte[0] : responseBody);
                }
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
            };
        }
    }
}
