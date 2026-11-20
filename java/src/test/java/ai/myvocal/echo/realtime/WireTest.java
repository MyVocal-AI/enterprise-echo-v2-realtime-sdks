package ai.myvocal.echo.realtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    private static final Duration WAIT = Duration.ofSeconds(4);
    private static final String KEY = "test/+ &?钩";
    private static final String START = "{\"message_type\":\"session_started\",\"session_id\":\"fixture\"}";
    private static final String FINAL = "{\"message_type\":\"committed_transcript\",\"text\":\"A test\",\"segment_index\":0}";
    private static final String DIARIZED = "{\"message_type\":\"diarized_transcript\",\"segment_index\":0,"
            + "\"words\":[{\"text\":\"A\",\"speaker_id\":\"speaker_0\"},{\"text\":\" test\",\"speaker_id\":null}]}";
    private static final String TIMED = "{\"message_type\":\"committed_transcript_with_timestamps\","
            + "\"words\":[{\"text\":\"A\",\"start\":0,\"end\":0.5,\"speaker_id\":null},{\"text\":\" test\",\"start\":null}]}";

    @Test
    void decodesThePublishedFixtureViaThePublicEntryPoint() throws Exception {
        try (var stream = getClass().getResourceAsStream("/sdk-event.json")) {
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var expected = JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("words");
            AsrEvent event = AsrEvent.parse(raw);
            assertEquals(raw, event.rawJson());
            assertEquals(27, event.words().size());
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).getAsJsonObject().get("text").getAsString(), event.words().get(i).text());
            }
            assertEquals("order", event.words().get(2).text());
            assertEquals(0.28, event.words().get(2).start());
            assertEquals(0.50, event.words().get(2).end());
            assertNull(event.segmentIndex());
        }
    }

    @Test
    void manualSendsOneFinalFrameAndWaitsForEachRequestedEventInEitherOrder() throws Exception {
        for (boolean diarizedFirst : new boolean[]{false, true}) {
            try (var peer = new Peer(START, (socket, chunk) -> {
                if (chunk.get("commit").getAsBoolean()) {
                    socket.send(FINAL);
                    CompletableFuture.delayedExecutor(70, TimeUnit.MILLISECONDS)
                            .execute(() -> socket.send(diarizedFirst ? DIARIZED : TIMED));
                    CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS)
                            .execute(() -> socket.send(diarizedFirst ? TIMED : DIARIZED));
                }
            }); var client = peer.client()) {
                var options = AsrOptions.builder().commitMode(CommitMode.VAD)
                        .wordTimestamps(true).speakerDiarization(true).build();
                var result = client.transcribeSegment(new byte[7000], options, WAIT);
                assertEquals("A test", result.text());
                assertEquals(0, result.committed().segmentIndex());
                assertEquals(0.0, result.words().get(0).start());
                assertNull(result.words().get(1).start());
                assertNull(result.timestamps().segmentIndex());
                assertEquals("speaker_0", result.diarized().words().get(0).speakerId());
                assertNull(result.diarized().words().get(1).speakerId());
                assertEquals(3, peer.frames.size());
                var chunks = new ArrayList<>(peer.frames);
                for (int i = 0; i < chunks.size(); i++) {
                    var frame = chunks.get(i);
                    assertEquals(i == 2, frame.get("commit").getAsBoolean());
                    assertEquals(i == 2 ? 600 : 3200, Base64.getDecoder().decode(frame.get("audio_base_64").getAsString()).length);
                    assertEquals("input_audio_chunk", frame.get("message_type").getAsString());
                    assertEquals(16000, frame.get("sample_rate").getAsInt());
                }
                assertTrue(peer.paths.element().contains("commit_mode=manual"));
            }
        }
    }

    @Test
    void basicResultAndIncompleteEnhancedResultAreDistinct() throws Exception {
        try (var peer = new Peer(START, (socket, chunk) -> socket.send(FINAL)); var client = peer.client()) {
            var result = client.transcribeSegment(new byte[3200], AsrOptions.builder().build(), WAIT);
            assertEquals("A test", result.text());
            assertTrue(result.words().isEmpty());
            var ex = assertThrows(AsrException.class, () -> client.transcribeSegment(new byte[3200],
                    AsrOptions.builder().wordTimestamps(true).speakerDiarization(true).build(), Duration.ofMillis(700)));
            assertEquals(AsrException.Kind.TIMEOUT, ex.kind());
            assertEquals(List.of("committed_transcript_with_timestamps", "diarized_transcript"), ex.missingEvents());
            assertEquals("A test", ex.partialResult().text());
        }
    }

    @Test
    void firstFrameOptionsResetPerSessionAndQueryUsesRepeatedEncodedEntries() throws Exception {
        var keyterms = IntStream.range(0, 50).mapToObj(i -> "a b+主&" + i).collect(Collectors.toList());
        var library = IntStream.range(0, 10_000).mapToObj(i -> "𠀀".repeat(46) + String.format("%04d", i))
                .collect(Collectors.toList());
        try (var peer = new Peer(START, (s, f) -> {}); var client = peer.client()) {
            var options = AsrOptions.builder().commitMode(CommitMode.TURN_PREDICTION)
                    .language("en").multilingualMode(true).speakerDiarization(true).wordTimestamps(true)
                    .keyterms(keyterms).hotwordsLibrary(library).previousText("Earlier context").build();
            for (int i = 0; i < 2; i++) {
                try (var session = client.open(options, event -> {}, WAIT)) {
                    session.sendAudio(new byte[3200]);
                    session.sendAudio(new byte[3200]);
                    session.close();
                    assertEquals(CloseInfo.Kind.LOCAL, session.awaitClosed(WAIT).kind());
                }
            }
            assertEquals(4, peer.frames.size());
            for (String raw : peer.paths) {
                String query = URLDecoder.decode(raw, StandardCharsets.UTF_8);
                assertTrue(query.contains("api-key=" + KEY));
                assertTrue(query.contains("model_id=echo_v2_realtime&audio_format=pcm_16000"));
                assertTrue(query.contains("commit_mode=turn_prediction"));
                assertTrue(query.contains("language=en"));
                assertTrue(query.contains("multilingual_mode=true&speaker_diarization=true&word_timestamps=true"));
                assertEquals(50, raw.split("&keyterms=", -1).length - 1);
                assertTrue(query.contains("keyterms=a b+主&49"));
            }
            var frames = new ArrayList<>(peer.frames);
            for (int i : new int[]{0, 2}) {
                assertEquals(10_000, frames.get(i).getAsJsonArray("hotwords_library").size());
                assertEquals(library.get(9999), frames.get(i).getAsJsonArray("hotwords_library").get(9999).getAsString());
                assertEquals("Earlier context", frames.get(i).get("previous_text").getAsString());
                assertFalse(frames.get(i + 1).has("hotwords_library"));
                assertFalse(frames.get(i + 1).has("previous_text"));
            }
        }
    }

    @Test
    void preservesServerErrorEvenIfPeerClosesWith1000() throws Exception {
        try (var peer = new Peer(START, (socket, chunk) -> {
            socket.send("{\"message_type\":\"input_error\",\"error\":\"Invalid input test%2F%2B\"}");
            socket.close(1000);
        }); var client = peer.client()) {
            var listenerOrder = new ConcurrentLinkedQueue<String>();
            var listener = new AsrListener() {
                public void onEvent(AsrEvent event) {
                    listenerOrder.add(event.messageType());
                }
                public void onError(AsrException error) {
                    listenerOrder.add("error callback");
                }
                public void onClosed(CloseInfo close) {
                    listenerOrder.add("closed callback");
                }
            };
            try (var session = client.open(AsrOptions.builder().build(), listener)) {
                session.sendFinalAudio(new byte[3200]);
                var close = session.awaitClosed(WAIT);
                assertEquals(CloseInfo.Kind.SERVER_ERROR, close.kind());
                assertEquals(1000, close.code());
                assertEquals("input_error", close.error().serverEvent().messageType());
                assertTrue(close.error().serverEvent().error().startsWith("Invalid input"));
                assertEquals(List.of("session_started", "input_error", "error callback", "closed callback"),
                        new ArrayList<>(listenerOrder));
            }
            var ex = assertThrows(AsrException.class, () -> client.transcribeSegment(new byte[3200],
                    AsrOptions.builder().build(), WAIT));
            assertEquals(AsrException.Kind.SERVER_ERROR, ex.kind());
        }
    }

    @Test
    void cancellationAndUncleanPeerDisconnectAreDistinct() throws Exception {
        try (var peer = new Peer(START, (s, f) -> s.closeConnection(1006, "")); var client = peer.client()) {
            try (var session = client.open(AsrOptions.builder().build(), event -> {})) {
                session.close();
                assertEquals(CloseInfo.Kind.LOCAL, session.awaitClosed(WAIT).kind());
            }
            try (var session = client.open(AsrOptions.builder().build(), event -> {})) {
                session.sendAudio(new byte[2]);
                assertEquals(CloseInfo.Kind.TRANSPORT_ERROR, session.awaitClosed(WAIT).kind());
            }
        }
    }

    @Test
    void earlyConnectionCloseIsFailureAndRetainsMissingResults() throws Exception {
        try (var peer = new Peer(START, (s, f) -> {
            s.send(FINAL);
            s.close(1000);
        }); var client = peer.client()) {
            var error = assertThrows(AsrException.class, () -> client.transcribeSegment(new byte[2],
                    AsrOptions.builder().wordTimestamps(true).build(), WAIT));
            assertEquals(AsrException.Kind.CLOSED, error.kind());
            assertEquals(1000, error.closeCode());
            assertEquals(List.of("committed_transcript_with_timestamps"), error.missingEvents());
            assertEquals("A test", error.partialResult().text());
        }
    }

    @Test
    void waitsForSessionStartedAndRejectsLimitsWithoutOpeningAConnection() throws Exception {
        try (var peer = new Peer(null, (s, f) -> {}); var client = peer.client()) {
            assertThrows(IllegalArgumentException.class, () -> AsrOptions.builder().hotwordsLibrary(List.of(" ")).build());
            assertThrows(IllegalArgumentException.class, () -> AsrOptions.builder().hotwordsLibrary(List.of("𠀀".repeat(51))).build());
            assertThrows(IllegalArgumentException.class, () -> AsrOptions.builder().hotwordsLibrary(
                    java.util.Collections.nCopies(10_001, "term")).build());
            assertThrows(IllegalArgumentException.class, () -> AsrOptions.builder().keyterms(
                    java.util.Collections.nCopies(51, "term")).build());
            assertTrue(peer.paths.isEmpty());
            var error = assertThrows(AsrException.class, () -> client.open(AsrOptions.builder().build(), e -> {},
                    Duration.ofMillis(300)));
            assertEquals(AsrException.Kind.TIMEOUT, error.kind());
            assertTrue(peer.frames.isEmpty());
        }
    }

    @Test
    void rareAndUnknownEventsRetainOrderAndTypedFields() throws Exception {
        var seen = new ConcurrentLinkedQueue<AsrEvent>();
        try (var peer = new Peer(START, (socket, frame) -> {
            socket.send("{\"message_type\":\"partial_transcript\",\"turn_probability\":0,\"text\":\"draft\"}");
            socket.send("{\"message_type\":\"speakers_merged\",\"from_speaker_id\":\"speaker_0\",\"to_speaker_id\":\"speaker_1\"}");
            socket.send("{\"message_type\":\"speakers_merged\",\"from_speaker_id\":\"speaker_1\",\"to_speaker_id\":\"speaker_2\"}");
            socket.send("{\"message_type\":\"future_event\",\"new_value\":true}");
            socket.close(1000);
        }); var client = peer.client(); var session = client.open(AsrOptions.builder().build(), seen::add)) {
            session.sendAudio(new byte[2]);
            assertEquals(CloseInfo.Kind.REMOTE, session.awaitClosed(WAIT).kind());
            var events = new ArrayList<>(seen);
            assertEquals(List.of("session_started", "partial_transcript", "speakers_merged", "speakers_merged", "future_event"),
                    events.stream().map(AsrEvent::messageType).collect(Collectors.toList()));
            assertEquals(0.0, events.get(1).turnProbability());
            assertEquals("speaker_0", events.get(2).fromSpeakerId());
            assertEquals("speaker_2", events.get(3).toSpeakerId());
            assertTrue(events.get(4).rawJson().contains("\"new_value\":true"));
        }
    }

    @Test
    void preservesHandshakeStatusAndDoesNotExposeTheAuthenticatedUrl() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var response = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    while (!reader.readLine().isEmpty()) {
                        // Read the handshake headers before answering.
                    }
                    socket.getOutputStream().write(("HTTP/1.1 401 Unauthorized\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            var endpoint = URI.create("ws://localhost:" + server.getLocalPort() + "/asr/realtime");
            try (var client = new RealtimeAsrClient(KEY, endpoint)) {
                var error = assertThrows(AsrException.class, () -> client.open(AsrOptions.builder().build(), e -> {}, WAIT));
                assertEquals(AsrException.Kind.HANDSHAKE, error.kind());
                assertEquals(401, error.httpStatus());
                assertFalse(error.toString().contains("api-key"));
                assertFalse(error.toString().contains(KEY));
                assertNull(error.getCause());
            }
            response.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void redactsAnEchoedAuthenticationUrlWithoutDiscardingServerErrorDetails() throws Exception {
        try (var peer = new Peer(START, (socket, chunk) -> {
            String url = "wss://fixture.example/asr/realtime?api-key="
                    + java.net.URLEncoder.encode(KEY, StandardCharsets.UTF_8) + "&model_id=echo_v2_realtime";
            var error = new JsonObject();
            error.addProperty("message_type", "input_error");
            error.addProperty("error", "Invalid parameters for \"" + url + "\"");
            socket.send(error.toString());
            socket.close(1008);
        }); var client = peer.client();
             var session = client.open(AsrOptions.builder().build(), e -> {})) {
            session.sendFinalAudio(new byte[2]);
            var closed = session.awaitClosed(WAIT);
            assertEquals(CloseInfo.Kind.SERVER_ERROR, closed.kind());
            assertEquals(1008, closed.code());
            assertEquals("Invalid parameters for \"[redacted]\"", closed.error().serverEvent().error());
            assertFalse(closed.error().serverEvent().rawJson().contains("api-key"));
        }
    }
    private static final class Peer extends WebSocketServer implements AutoCloseable {
        final ConcurrentLinkedQueue<JsonObject> frames = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> paths = new ConcurrentLinkedQueue<>();
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final String openingEvent;
        private final BiConsumer<WebSocket, JsonObject> script;

        Peer(String openingEvent, BiConsumer<WebSocket, JsonObject> script) throws Exception {
            super(new InetSocketAddress("127.0.0.1", 0), 2);
            this.openingEvent = openingEvent;
            this.script = script;
            start();
            started.get(4, TimeUnit.SECONDS);
        }
        RealtimeAsrClient client() {
            return new RealtimeAsrClient(KEY, URI.create("ws://127.0.0.1:" + getPort() + "/asr/realtime"));
        }
        public void onOpen(WebSocket socket, ClientHandshake request) {
            paths.add(request.getResourceDescriptor());
            if (openingEvent != null) socket.send(openingEvent);
        }
        public void onMessage(WebSocket socket, String message) {
            var frame = JsonParser.parseString(message).getAsJsonObject();
            frames.add(frame);
            script.accept(socket, frame);
        }
        public void onClose(WebSocket socket, int code, String reason, boolean remote) {}
        public void onError(WebSocket socket, Exception ex) { started.completeExceptionally(ex); }
        public void onStart() { started.complete(null); }
        public void close() throws InterruptedException { stop(500); }
    }
}
