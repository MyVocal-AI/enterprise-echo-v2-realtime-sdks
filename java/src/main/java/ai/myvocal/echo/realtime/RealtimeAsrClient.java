package ai.myvocal.echo.realtime;

import com.google.gson.GsonBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/** Client for the MyVocal enterprise Echo v2 realtime ASR WebSocket. */
public final class RealtimeAsrClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("wss://openapi.myvocal.ai/asr/realtime");
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(15);
    private static final int FRAME_BYTES = 3200;
    private static final long FRAME_NANOS = Duration.ofMillis(100).toNanos();
    private static final Pattern AUTH_URL = Pattern.compile(
            "(?i)(?:wss?|https?)://[^\\s\\\\\"<>]*[?&](?:amp;)?api(?:-|%2d)key(?:=|%3d)[^\\s\\\\\"<>]*");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final List<String> keySpellings;
    private final String encodedKey;
    private final Set<AsrSession> sessions = new HashSet<>();
    private boolean closed;

    public RealtimeAsrClient(String apiKey) {
        this(apiKey, ENDPOINT);
    }

    // Package-private injection point for local protocol tests.
    RealtimeAsrClient(String apiKey, URI endpoint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("A MyVocal enterprise ASR API key is required");
        }
        this.endpoint = endpoint;
        String formEncoded = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        encodedKey = formEncoded.replace("+", "%20");
        String jsonKey = new GsonBuilder().disableHtmlEscaping().create().toJson(apiKey);
        String jsonEscaped = jsonKey.substring(1, jsonKey.length() - 1);
        keySpellings = List.of(encodedKey, formEncoded, jsonEscaped, apiKey);
    }

    public AsrSession open(AsrOptions options, AsrListener listener) throws AsrException, InterruptedException {
        return open(options, listener, OPEN_TIMEOUT);
    }

    /** Connects and waits for session_started within a single deadline. */
    public AsrSession open(AsrOptions options, AsrListener listener, Duration timeout)
            throws AsrException, InterruptedException {
        Objects.requireNonNull(options, "options");
        return open(options, listener, options.commitMode(), timeout);
    }

    private AsrSession open(AsrOptions options, AsrListener listener, CommitMode mode, Duration timeout)
            throws AsrException, InterruptedException {
        Objects.requireNonNull(listener, "listener");
        AsrSession.positiveNanos(timeout);
        var query = new StringBuilder("?api-key=").append(encodedKey)
                .append("&model_id=echo_v2_realtime&audio_format=pcm_16000");
        append(query, "commit_mode", mode.wireValue);
        if (options.language() != null) {
            append(query, "language", options.language());
        }
        append(query, "multilingual_mode", Boolean.toString(options.multilingualMode()));
        append(query, "speaker_diarization", Boolean.toString(options.speakerDiarization()));
        append(query, "word_timestamps", Boolean.toString(options.wordTimestamps()));
        for (String term : options.keyterms()) {
            append(query, "keyterms", term);
        }

        AsrSession session;
        synchronized (sessions) {
            if (closed) {
                throw new AsrException(AsrException.Kind.CANCELLED, "ASR client is closed");
            }
            session = new AsrSession(options, listener, this::redact, this::forget);
            sessions.add(session);
        }
        try {
            session.connect(http, URI.create(endpoint.toASCIIString() + query), timeout);
            return session;
        } catch (AsrException | InterruptedException failure) {
            session.close();
            throw failure;
        } catch (RuntimeException failure) {
            session.close();
            throw new AsrException(AsrException.Kind.TRANSPORT, "Could not start the enterprise WebSocket");
        }
    }

    private static void append(StringBuilder query, String field, String value) {
        query.append('&').append(field).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private String redact(String value) {
        if (value.toLowerCase(java.util.Locale.ROOT).contains("api")) {
            value = AUTH_URL.matcher(value).replaceAll("[redacted]");
        }
        for (String spelling : keySpellings) {
            value = value.replace(spelling, "[redacted]");
        }
        return value;
    }

    /**
     * Transcribes one short recording in manual mode. Timeout covers connection, pacing, sends and final events.
     * The last PCM block carries the only commit. Enhanced events stay separate even when no index is sent.
     */
    public SegmentResult transcribeSegment(byte[] pcm, AsrOptions options, Duration timeout)
            throws AsrException, InterruptedException {
        Objects.requireNonNull(pcm, "pcm");
        Objects.requireNonNull(options, "options");
        if (pcm.length == 0 || pcm.length % 2 != 0) {
            throw new IllegalArgumentException("Provide a nonempty recording made of complete 16-bit PCM samples");
        }
        long deadline = System.nanoTime() + AsrSession.positiveNanos(timeout);
        var results = new Results(options);
        try (AsrSession session = open(options, results, CommitMode.MANUAL, timeRemaining(deadline))) {
            long nextFrame = System.nanoTime();
            for (int start = 0; start < pcm.length; ) {
                long delay = nextFrame - System.nanoTime();
                if (delay > 0) {
                    NANOSECONDS.sleep(Math.min(delay, timeRemaining(deadline).toNanos()));
                }
                int end = start + Math.min(FRAME_BYTES, pcm.length - start);
                byte[] frame = Arrays.copyOfRange(pcm, start, end);
                nextFrame = System.nanoTime() + FRAME_NANOS;
                if (end == pcm.length) {
                    session.sendFinalAudio(frame, timeRemaining(deadline));
                } else {
                    session.sendAudio(frame, timeRemaining(deadline));
                }
                start = end;
            }
            return results.await(session, deadline);
        } catch (AsrException failure) {
            throw results.withProgress(failure);
        }
    }

    private static Duration timeRemaining(long deadline) throws AsrException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new AsrException(AsrException.Kind.TIMEOUT, "Timed out transcribing the segment");
        }
        return Duration.ofNanos(remaining);
    }

    private void forget(AsrSession session) {
        synchronized (sessions) {
            sessions.remove(session);
        }
    }

    @Override
    public void close() {
        List<AsrSession> active;
        synchronized (sessions) {
            closed = true;
            active = List.copyOf(sessions);
            sessions.clear();
        }
        active.forEach(AsrSession::close);
    }

    private static final class Results implements AsrListener {
        private final AsrOptions options;
        private AsrEvent committed;
        private AsrEvent timestamps;
        private AsrEvent diarized;
        private CloseInfo close;
        private AsrException error;

        private Results(AsrOptions options) {
            this.options = options;
        }

        @Override
        public synchronized void onEvent(AsrEvent event) {
            switch (event.messageType()) {
                case AsrEvent.COMMITTED:
                    committed = event;
                    break;
                case AsrEvent.TIMESTAMPS:
                    timestamps = event;
                    break;
                case AsrEvent.DIARIZED:
                    diarized = event;
                    break;
                default:
                    return;
            }
            notifyAll();
        }

        @Override
        public synchronized void onError(AsrException error) {
            this.error = error;
            notifyAll();
        }

        @Override
        public synchronized void onClosed(CloseInfo close) {
            this.close = close;
            notifyAll();
        }

        synchronized SegmentResult await(AsrSession session, long deadline) throws AsrException, InterruptedException {
            while (true) {
                AsrException failure = session.failure();
                if (failure != null || error != null) {
                    throw failure != null ? failure : error;
                }
                if (missing().isEmpty()) {
                    return snapshot();
                }
                if (close != null) {
                    boolean local = close.kind() == CloseInfo.Kind.LOCAL;
                    throw new AsrException(local ? AsrException.Kind.CANCELLED : AsrException.Kind.CLOSED,
                            "ASR session closed before all results arrived").withCloseCode(close.code());
                }
                NANOSECONDS.timedWait(this, timeRemaining(deadline).toNanos());
            }
        }

        synchronized AsrException withProgress(AsrException failure) {
            return failure.withProgress(missing(), snapshot());
        }

        private SegmentResult snapshot() {
            return new SegmentResult(committed, timestamps, diarized);
        }

        private List<String> missing() {
            var events = new ArrayList<String>(3);
            if (committed == null) {
                events.add(AsrEvent.COMMITTED);
            }
            if (options.wordTimestamps() && timestamps == null) {
                events.add(AsrEvent.TIMESTAMPS);
            }
            if (options.speakerDiarization() && diarized == null) {
                events.add(AsrEvent.DIARIZED);
            }
            return events;
        }
    }
}
