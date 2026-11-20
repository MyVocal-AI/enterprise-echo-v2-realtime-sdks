package ai.myvocal.echo.realtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** One inbound gateway message, including events introduced after this SDK was built. */
public final class AsrEvent {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public static final String SESSION_STARTED = "session_started";
    public static final String PARTIAL = "partial_transcript";
    public static final String COMMITTED = "committed_transcript";
    public static final String TIMESTAMPS = "committed_transcript_with_timestamps";
    public static final String DIARIZED = "diarized_transcript";
    public static final String SPEAKERS_MERGED = "speakers_merged";

    private static final Set<String> ERROR_TYPES = Set.of(
            "error", "auth_error", "quota_exceeded", "transcriber_error", "input_error",
            "invalid_request", "commit_throttled", "unaccepted_terms", "rate_limited", "queue_overflow",
            "resource_exhausted", "session_time_limit_exceeded", "chunk_size_exceeded", "insufficient_audio_activity");

    private final String rawJson;
    private final JsonObject payload;
    private final String messageType;
    private final List<AsrWord> words;

    private AsrEvent(String rawJson, JsonObject payload) {
        this.rawJson = rawJson;
        this.payload = payload;
        messageType = string(payload, "message_type");
        if (messageType == null || messageType.isEmpty()) {
            throw new IllegalArgumentException("ASR event is missing message_type");
        }
        var items = new ArrayList<AsrWord>();
        JsonElement wordList = payload.get("words");
        if (wordList != null && wordList.isJsonArray()) {
            for (JsonElement word : wordList.getAsJsonArray()) {
                if (word.isJsonObject()) {
                    items.add(new AsrWord(word.getAsJsonObject()));
                }
            }
        }
        words = List.copyOf(items);
    }

    /** Parses a recorded gateway message using the same decoder as the WebSocket. */
    public static AsrEvent parse(String rawJson) {
        JsonObject payload;
        try {
            payload = JSON.fromJson(rawJson, JsonObject.class);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid ASR event JSON");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Expected an ASR event object");
        }
        return new AsrEvent(rawJson, payload);
    }

    public String rawJson() { return rawJson; }
    public String messageType() { return messageType; }
    public String text() { return string(payload, "text"); }
    public String sessionId() { return string(payload, "session_id"); }
    public String languageCode() { return string(payload, "language_code"); }
    public Integer segmentIndex() { return integer(payload, "segment_index"); }
    public Double turnProbability() { return decimal(payload, "turn_probability"); }
    public List<AsrWord> words() { return words; }
    public String fromSpeakerId() { return string(payload, "from_speaker_id"); }
    public String toSpeakerId() { return string(payload, "to_speaker_id"); }
    public String error() { return string(payload, "error"); }

    public String configJson() {
        JsonElement config = payload.get("config");
        return config != null && config.isJsonObject() ? config.toString() : null;
    }

    public boolean isError() {
        return ERROR_TYPES.contains(messageType) || messageType.endsWith("_error") || error() != null;
    }

    static String string(JsonObject source, String name) {
        JsonElement value = source.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    static Integer integer(JsonObject source, String name) {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException outOfRange) {
            return null;
        }
    }

    static Double decimal(JsonObject source, String name) {
        JsonElement value = source.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : null;
    }
}
