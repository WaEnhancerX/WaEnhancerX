package com.waenhancer.xposed.features.media;

import de.robv.android.xposed.XposedBridge;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ParallelDownloadingInputStream:
 * Accelerates large WhatsApp media downloads by splitting them into 2MB chunks
 * and prefetching upcoming chunks in parallel using concurrent HTTP Range requests.
 *
 * <p>Chunk 0 is streamed directly from WhatsApp's original connection, while chunks
 * 1..N are fetched concurrently across multiple daemon worker threads.</p>
 */
public final class ParallelDownloadingInputStream extends InputStream {

    private static final String TAG = "[WAEX][ParallelDownload]";
    public static final int CHUNK_SIZE = 2 * 1024 * 1024; // 2 MB

    private final InputStream originalStream;
    private final long totalLength;
    private final int chunkSize;
    private final URL downloadUrl;
    private final long startOffset;
    private final int totalChunks;
    private final int maxAheadQueue;
    private final ThreadPoolExecutor threadPool;
    private final Map<Integer, Future<byte[]>> chunkFutures = new HashMap<>();

    private int nextChunkToSubmit = 1;
    private int currentChunkIndex = 0;
    private byte[] currentChunkBuffer = null;
    private int currentChunkOffset = 0;
    private long originalBytesConsumed = 0L;

    private boolean fallbackToSequential = false;
    private boolean isClosed = false;

    public ParallelDownloadingInputStream(
            InputStream originalStream,
            long totalLength,
            int connectionCount,
            URL downloadUrl,
            long startOffset
    ) {
        if (originalStream == null) throw new IllegalArgumentException("originalStream cannot be null");
        if (downloadUrl == null) throw new IllegalArgumentException("downloadUrl cannot be null");
        if (totalLength <= 0 || connectionCount <= 0) {
            throw new IllegalArgumentException("Invalid length " + totalLength + " or connections " + connectionCount);
        }

        this.originalStream = originalStream;
        this.totalLength = totalLength;
        this.chunkSize = CHUNK_SIZE;
        this.downloadUrl = downloadUrl;
        this.startOffset = Math.max(0L, startOffset);
        this.totalChunks = (int) ((totalLength + chunkSize - 1) / chunkSize);
        this.maxAheadQueue = Math.max(2, connectionCount + 2);

        final AtomicInteger threadId = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, "WaexParallelDl-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        this.threadPool = new ThreadPoolExecutor(
                connectionCount,
                connectionCount,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                factory
        );
        this.threadPool.allowCoreThreadTimeOut(true);

        // Prefetch initial wave of chunks ahead
        enqueueUpcomingChunks();
    }

    private int getChunkLength(int chunkIndex) {
        return (int) Math.min((long) chunkSize, totalLength - ((long) chunkIndex * chunkSize));
    }

    private synchronized void enqueueUpcomingChunks() {
        while (nextChunkToSubmit < totalChunks && (nextChunkToSubmit - currentChunkIndex) <= maxAheadQueue) {
            final int chunkToFetch = nextChunkToSubmit++;
            chunkFutures.put(chunkToFetch, threadPool.submit(new Callable<byte[]>() {
                @Override
                public byte[] call() throws Exception {
                    long offsetInFile = (long) chunkToFetch * chunkSize;
                    int expectedLength = getChunkLength(chunkToFetch);
                    return fetchRange(offsetInFile, expectedLength);
                }
            }));
        }
    }

    private byte[] fetchRange(long offsetInFile, int length) throws IOException {
        long rangeStart = startOffset + offsetInFile;
        long rangeEnd = rangeStart + length - 1;

        URLConnection rawConn = downloadUrl.openConnection();
        if (!(rawConn instanceof HttpURLConnection)) {
            throw new IOException("URL is not HTTP/HTTPS: " + downloadUrl);
        }
        HttpURLConnection conn = (HttpURLConnection) rawConn;
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd);
            conn.setRequestProperty("Accept-Encoding", "identity");

            int code = conn.getResponseCode();
            String contentRange = conn.getHeaderField("Content-Range");
            if (contentRange == null) contentRange = "";

            if (code == 206) {
                // A matching *start* is insufficient: a server may return an overlapping,
                // short or differently bounded range while still delivering enough bytes.
                // In that case the caller must fall back to the original sequential stream.
                String expectedPrefix = "bytes " + rangeStart + "-" + rangeEnd + "/";
                if (!contentRange.startsWith(expectedPrefix)) {
                    throw new IOException("Unexpected Content-Range: " + contentRange + ", expected: " + expectedPrefix);
                }
                String completeLength = contentRange.substring(expectedPrefix.length());
                if (!"*".equals(completeLength)) {
                    try {
                        long reportedTotal = Long.parseLong(completeLength);
                        if (reportedTotal <= rangeEnd) {
                            throw new NumberFormatException("total does not include the returned range");
                        }
                    } catch (NumberFormatException invalid) {
                        throw new IOException("Invalid Content-Range total: " + contentRange, invalid);
                    }
                }

                byte[] data = new byte[length];
                try (InputStream is = conn.getInputStream()) {
                    int bytesRead = 0;
                    while (bytesRead < length) {
                        int r = is.read(data, bytesRead, length - bytesRead);
                        if (r < 0) {
                            throw new IOException("Range " + rangeStart + " truncated at " + bytesRead + " of " + length);
                        }
                        bytesRead += r;
                    }
                }
                return data;
            } else {
                throw new IOException("Range request failed: HTTP " + code + " range=" + rangeStart + "-" + rangeEnd);
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) throw new NullPointerException("buffer is null");
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException("Invalid buffer offset=" + offset + " length=" + length);
        }
        if (isClosed) throw new IOException("Stream closed");
        if (length == 0) return 0;

        if (fallbackToSequential) {
            return readSequentialOrThrow(buffer, offset, length);
        }

        // Chunk 0 is read directly from the initial WhatsApp stream
        if (currentChunkIndex == 0) {
            int chunk0Remaining = getChunkLength(0) - currentChunkOffset;
            if (chunk0Remaining > 0) {
                int bytesRead = originalStream.read(buffer, offset, Math.min(length, chunk0Remaining));
                if (bytesRead > 0) {
                    currentChunkOffset += bytesRead;
                    originalBytesConsumed += bytesRead;
                } else if (bytesRead < 0) {
                    // Returning EOF here reports success to callers even though the known
                    // response length has not been consumed (including a single-chunk file).
                    throw new IOException("Original download truncated at " + currentChunkOffset
                            + " of " + getChunkLength(0) + " first-chunk bytes");
                }
                return bytesRead;
            }

            if (totalChunks == 1) {
                return -1; // Single-chunk file finished
            }

            // Chunk 0 complete: transition to prefetched parallel chunks
            byte[] nextBytes;
            try {
                Future<byte[]> firstPrefetch = chunkFutures.remove(1);
                nextBytes = (firstPrefetch != null) ? firstPrefetch.get() : null;
            } catch (Throwable error) {
                XposedBridge.log(TAG + " Prefetched chunk 1 failed, falling back: " + error.getMessage());
                nextBytes = null;
            }

            if (nextBytes == null) {
                // Graceful fallback to sequential if range request failed
                fallbackToSequential = true;
                cancelPendingTasks();
                return readSequentialOrThrow(buffer, offset, length);
            }

            // Close the initial connection safely; subsequent chunks come from our range workers
            try {
                originalStream.close();
            } catch (Throwable ignored) {}

            currentChunkIndex = 1;
            currentChunkBuffer = nextBytes;
            currentChunkOffset = 0;
            enqueueUpcomingChunks();
        }

        // Reading from parallel buffer
        byte[] activeBuffer = currentChunkBuffer;
        if (activeBuffer != null && currentChunkOffset == activeBuffer.length) {
            int nextIndex = currentChunkIndex + 1;
            if (nextIndex >= totalChunks) {
                return -1; // Download fully complete
            }

            currentChunkIndex = nextIndex;
            byte[] nextBytes;
            try {
                Future<byte[]> future = chunkFutures.remove(nextIndex);
                if (future != null) {
                    nextBytes = future.get();
                } else {
                    long offsetInFile = (long) nextIndex * chunkSize;
                    nextBytes = fetchRange(offsetInFile, getChunkLength(nextIndex));
                }
            } catch (ExecutionException e) {
                long offsetInFile = (long) nextIndex * chunkSize;
                nextBytes = fetchRange(offsetInFile, getChunkLength(nextIndex));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }

            currentChunkBuffer = nextBytes;
            currentChunkOffset = 0;
            activeBuffer = nextBytes;
            enqueueUpcomingChunks();
        }

        if (activeBuffer == null) {
            return -1;
        }

        int available = activeBuffer.length - currentChunkOffset;
        int bytesToCopy = Math.min(length, available);
        System.arraycopy(activeBuffer, currentChunkOffset, buffer, offset, bytesToCopy);
        currentChunkOffset += bytesToCopy;
        return bytesToCopy;
    }

    private int readSequentialOrThrow(byte[] buffer, int offset, int length) throws IOException {
        long remaining = totalLength - originalBytesConsumed;
        if (remaining == 0) return -1;

        int bytesRead = originalStream.read(buffer, offset, (int) Math.min(remaining, (long) length));
        if (bytesRead < 0) {
            throw new IOException("Original sequential download truncated at " + originalBytesConsumed
                    + " of " + totalLength + " bytes");
        }
        if (bytesRead > 0) originalBytesConsumed += bytesRead;
        return bytesRead;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int r = read(single, 0, 1);
        if (r < 0) return -1;
        return single[0] & 0xFF;
    }

    private synchronized void cancelPendingTasks() {
        for (Future<byte[]> f : chunkFutures.values()) {
            f.cancel(true);
        }
        chunkFutures.clear();
        threadPool.shutdownNow();
    }

    @Override
    public void close() throws IOException {
        if (isClosed) return;
        isClosed = true;
        cancelPendingTasks();
        try {
            originalStream.close();
        } catch (Throwable ignored) {}
    }
}

