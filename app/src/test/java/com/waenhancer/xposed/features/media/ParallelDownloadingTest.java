package com.waenhancer.xposed.features.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;

public class ParallelDownloadingTest {

    @Test
    public void singleChunkStreamReadsCompletelyFromOriginalStream() throws Exception {
        byte[] expected = "Small media payload under 2MB".getBytes();
        ByteArrayInputStream original = new ByteArrayInputStream(expected);
        URL testUrl = new URL("https://example.com/media/test.mp4");

        ParallelDownloadingInputStream stream = new ParallelDownloadingInputStream(
                original,
                expected.length,
                4,
                testUrl,
                0L
        );

        byte[] output = new byte[expected.length];
        int bytesRead = 0;
        while (bytesRead < expected.length) {
            int r = stream.read(output, bytesRead, expected.length - bytesRead);
            if (r < 0) break;
            bytesRead += r;
        }

        assertEquals(expected.length, bytesRead);
        assertArrayEquals(expected, output);
        assertEquals(-1, stream.read());
        stream.close();
    }

    @Test
    public void closedStreamThrowsIOException() throws Exception {
        byte[] data = new byte[100];
        ByteArrayInputStream original = new ByteArrayInputStream(data);
        URL testUrl = new URL("https://example.com/media/test.mp4");

        ParallelDownloadingInputStream stream = new ParallelDownloadingInputStream(
                original,
                data.length,
                4,
                testUrl,
                0L
        );
        stream.close();

        try {
            stream.read();
            fail("Expected IOException after close");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }
}

