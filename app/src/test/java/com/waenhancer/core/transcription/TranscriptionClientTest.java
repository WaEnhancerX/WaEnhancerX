package com.waenhancer.core.transcription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TranscriptionClientTest {

    private static final byte[] AUDIO = "OggS-fake-opus".getBytes(StandardCharsets.UTF_8);

    /** Records requests and replays canned responses in order. */
    private static final class FakeTransport implements TranscriptionClient.Transport {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        private final List<TranscriptionClient.Response> responses = new ArrayList<>();

        FakeTransport reply(int code, String body) {
            responses.add(new TranscriptionClient.Response(code, body));
            return this;
        }

        @Override
        public TranscriptionClient.Response send(String method, String url,
                                                 Map<String, String> requestHeaders, byte[] body) {
            urls.add(method + " " + url);
            headers.add(new LinkedHashMap<>(requestHeaders));
            bodies.add(body);
            return responses.remove(0);
        }
    }

    @Test
    public void customBaseUrlGetsTranscriptionPathAppended() throws Exception {
        assertEquals("https://host/stt/v1/audio/transcriptions",
                TranscriptionClient.resolveOpenAiEndpoint("https://host/stt/v1/"));
        assertEquals("https://host/stt/v1/audio/transcriptions",
                TranscriptionClient.resolveOpenAiEndpoint(" https://host/stt/v1/audio/transcriptions "));
    }

    @Test
    public void customUrlWithoutSchemeIsRejected() {
        try {
            TranscriptionClient.resolveOpenAiEndpoint("host/v1");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertTrue(expected.getMessage().contains("https://"));
        }
    }

    @Test
    public void customProviderSendsOpenAiMultipartWithBearerKey() throws Exception {
        FakeTransport transport = new FakeTransport().reply(200, "{\"text\":\" Ahoj, ako sa máš?\\n\"}");
        String text = new TranscriptionClient(transport).transcribe(
                new TranscriptionClient.Config("Custom", "https://host/stt/v1", "sk-test", ""),
                AUDIO, "PTT-20261019-WA0001.opus");

        assertEquals("Ahoj, ako sa máš?", text);
        assertEquals("POST https://host/stt/v1/audio/transcriptions", transport.urls.get(0));
        assertEquals("Bearer sk-test", transport.headers.get(0).get("Authorization"));
        String body = new String(transport.bodies.get(0), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"));
        assertTrue(body.contains("filename=\"PTT-20261019-WA0001.ogg\""));
        assertTrue(body.contains("Content-Type: audio/ogg\r\n\r\nOggS-fake-opus\r\n"));
        String contentType = transport.headers.get(0).get("Content-Type");
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        assertTrue(body.endsWith("--" + boundary + "--\r\n"));
    }

    @Test
    public void languageIsSentOnlyWhenChosenAndValid() throws Exception {
        FakeTransport transport = new FakeTransport().reply(200, "{\"text\":\"a\"}").reply(200, "{\"text\":\"b\"}");
        TranscriptionClient client = new TranscriptionClient(transport);
        client.transcribe(new TranscriptionClient.Config("Custom", "https://h/v1", "k", "", "SK"), AUDIO, "a.opus");
        client.transcribe(new TranscriptionClient.Config("Custom", "https://h/v1", "k", "", "sk\"; x"), AUDIO, "a.opus");
        assertTrue(new String(transport.bodies.get(0), StandardCharsets.UTF_8)
                .contains("name=\"language\"\r\n\r\nsk\r\n"));
        assertFalse(new String(transport.bodies.get(1), StandardCharsets.UTF_8).contains("name=\"language\""));
    }

    @Test
    public void assemblyAiUsesLanguageCodeInsteadOfDetection() throws Exception {
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"upload_url\":\"u\"}")
                .reply(200, "{\"id\":\"t\"}")
                .reply(200, "{\"status\":\"completed\",\"text\":\"x\"}");
        new TranscriptionClient(transport).transcribe(
                new TranscriptionClient.Config("AssemblyAI", "", "aai", "", "sk"), AUDIO, "a.opus");
        String body = new String(transport.bodies.get(1), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"language_code\":\"sk\""));
        assertFalse(body.contains("language_detection"));
    }

    @Test
    public void silentWavIsAValidOneSecondPcmFile() {
        byte[] wav = TranscriptionClient.silentWav();
        assertEquals(44 + 32_000, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals("waex-test.wav", TranscriptionClient.uploadName("waex-test.wav"));
        assertEquals("audio/wav", TranscriptionClient.contentType("waex-test.wav"));
    }

    @Test
    public void modelDiscoveryReadsOpenAiListWithSpeechModelsFirst() throws Exception {
        FakeTransport transport = new FakeTransport().reply(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"llama3\"},{\"id\":\"whisper-1\"},{\"id\":\"gpt-4o-transcribe\"}]}");
        java.util.List<String> models = new TranscriptionClient(transport)
                .listModels("https://host/stt/v1/audio/transcriptions", "k");
        assertEquals("GET https://host/stt/v1/models", transport.urls.get(0));
        assertEquals("Bearer k", transport.headers.get(0).get("Authorization"));
        assertEquals(java.util.Arrays.asList("gpt-4o-transcribe", "whisper-1", "llama3"), models);
    }

    @Test
    public void modelDiscoveryAcceptsOtherShapesAndExplainsMissingList() throws Exception {
        java.util.List<String> models = new TranscriptionClient(new FakeTransport()
                .reply(200, "{\"models\":[{\"name\":\"base\"},{\"name\":\"small\"}]}"))
                .listModels("https://h/v1", "");
        assertEquals(java.util.Arrays.asList("base", "small"), models);
        try {
            new TranscriptionClient(new FakeTransport().reply(404, "not found")).listModels("https://h/v1", "k");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertTrue(expected.getMessage().contains("does not list"));
        }
    }

    @Test
    public void groqUsesFixedEndpointAndModel() throws Exception {
        FakeTransport transport = new FakeTransport().reply(200, "{\"text\":\"hello\",\"x_groq\":{\"id\":\"1\"}}");
        String text = new TranscriptionClient(transport).transcribe(
                new TranscriptionClient.Config("Groq", "ignored", "gsk", "ignored"), AUDIO, "a.opus");
        assertEquals("hello", text);
        assertEquals("POST " + TranscriptionClient.GROQ_ENDPOINT, transport.urls.get(0));
        assertTrue(new String(transport.bodies.get(0), StandardCharsets.UTF_8)
                .contains(TranscriptionClient.GROQ_DEFAULT_MODEL));
    }

    @Test
    public void errorsAreReadableAndNeverContainTheKey() throws Exception {
        FakeTransport transport = new FakeTransport()
                .reply(401, "{\"error\":{\"message\":\"Invalid API Key\"}}");
        try {
            new TranscriptionClient(transport).transcribe(
                    new TranscriptionClient.Config("Groq", "", "gsk-secret", ""), AUDIO, "a.opus");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertEquals("API key rejected: Invalid API Key", expected.getMessage());
            assertFalse(expected.getMessage().contains("gsk-secret"));
        }
    }

    @Test
    public void nginxHtmlErrorPageStillGivesStatusMessage() throws Exception {
        FakeTransport transport = new FakeTransport().reply(404, "<html><body>404</body></html>");
        try {
            new TranscriptionClient(transport).transcribe(
                    new TranscriptionClient.Config("Custom", "https://h/v1", "k", ""), AUDIO, "a.opus");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertEquals("Endpoint not found (check the URL)", expected.getMessage());
        }
    }

    @Test
    public void missingKeyAndOversizedAudioFailBeforeAnyRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        TranscriptionClient client = new TranscriptionClient(transport);
        try {
            client.transcribe(new TranscriptionClient.Config("Groq", "", " ", ""), AUDIO, "a.opus");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertTrue(expected.getMessage().startsWith("No API key"));
        }
        try {
            client.transcribe(new TranscriptionClient.Config("Groq", "", "k", ""),
                    new byte[TranscriptionClient.MAX_AUDIO_BYTES + 1], "a.opus");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertEquals("Voice note is too long", expected.getMessage());
        }
        assertTrue(transport.urls.isEmpty());
    }

    @Test
    public void assemblyAiUploadsCreatesAndPolls() throws Exception {
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"upload_url\":\"https://cdn.assemblyai.com/upload/abc\"}")
                .reply(200, "{\"id\":\"tr_1\",\"status\":\"queued\"}")
                .reply(200, "{\"id\":\"tr_1\",\"status\":\"completed\",\"words\":[{\"text\":\"x\"}],"
                        + "\"text\":\"Dobrý deň\"}");
        String text = new TranscriptionClient(transport).transcribe(
                new TranscriptionClient.Config("AssemblyAI", "", "aai", ""), AUDIO, "a.opus");

        assertEquals("Dobrý deň", text);
        assertEquals("POST " + TranscriptionClient.ASSEMBLY_BASE + "/upload", transport.urls.get(0));
        assertEquals("aai", transport.headers.get(0).get("Authorization"));
        assertEquals("GET " + TranscriptionClient.ASSEMBLY_BASE + "/transcript/tr_1", transport.urls.get(2));
        assertTrue(new String(transport.bodies.get(1), StandardCharsets.UTF_8)
                .contains("\"audio_url\":\"https://cdn.assemblyai.com/upload/abc\""));
    }

    @Test
    public void assemblyAiErrorStatusIsReported() throws Exception {
        FakeTransport transport = new FakeTransport()
                .reply(200, "{\"upload_url\":\"u\"}")
                .reply(200, "{\"id\":\"t\"}")
                .reply(200, "{\"status\":\"error\",\"error\":\"Audio duration is too short\"}");
        try {
            new TranscriptionClient(transport).transcribe(
                    new TranscriptionClient.Config("AssemblyAI", "", "aai", ""), AUDIO, "a.opus");
            fail();
        } catch (TranscriptionClient.TranscriptionException expected) {
            assertEquals("AssemblyAI: Audio duration is too short", expected.getMessage());
        }
    }

    @Test
    public void miniJsonHandlesEscapesNestingAndRejectsGarbage() {
        Object parsed = TranscriptionClient.parseJsonOrNull(
                "{\"a\":[1,2.5,{\"b\":null}],\"t\":\"\\u010d\\\"x\\\"\",\"ok\":true}");
        assertTrue(parsed instanceof Map);
        assertEquals("č\"x\"", ((Map<?, ?>) parsed).get("t"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) parsed).get("ok"));
        assertEquals(null, TranscriptionClient.parseJsonOrNull("{\"a\":"));
        assertEquals(null, TranscriptionClient.parseJsonOrNull("{} trailing"));
    }

    @Test
    public void deeplyNestedJsonIsRejectedInsteadOfOverflowingTheStack() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100_000; i++) deep.append('[');
        assertEquals(null, TranscriptionClient.parseJsonOrNull(deep.toString()));
        StringBuilder ok = new StringBuilder("{\"text\":\"x\",\"a\":");
        for (int i = 0; i < 60; i++) ok.append('[');
        for (int i = 0; i < 60; i++) ok.append(']');
        assertTrue(TranscriptionClient.parseJsonOrNull(ok.append('}').toString()) instanceof Map);
    }

    @Test
    public void uploadNameIsSanitizedAndUsesOgg() {
        assertEquals("PTT-1.ogg", TranscriptionClient.uploadName("PTT-1.opus"));
        assertEquals("a_b_.ogg", TranscriptionClient.uploadName("a\"b\r.opus"));
        assertEquals("voice-note.ogg", TranscriptionClient.uploadName(null));
        assertEquals("waex_dictation.m4a", TranscriptionClient.uploadName("waex_dictation.m4a"));
        assertEquals("audio/mp4", TranscriptionClient.contentType("waex_dictation.m4a"));
        assertEquals("audio/ogg", TranscriptionClient.contentType("PTT-1.ogg"));
    }

    @Test
    public void jsonQuoteEscapesControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", TranscriptionClient.jsonQuote("a\"b\\c\n\u0001"));
    }
}
