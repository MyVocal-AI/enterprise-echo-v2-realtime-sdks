package ai.myvocal.echo.realtime;

import com.google.gson.JsonObject;

/** A word or spacing item. Times are nullable seconds. */
public final class AsrWord {
    private final String text;
    private final Double start;
    private final Double end;
    private final String speakerId;
    private final String type;
    private final Integer channelIndex;
    private final Double logprob;

    AsrWord(JsonObject word) {
        text = AsrEvent.string(word, "text");
        start = AsrEvent.decimal(word, "start");
        end = AsrEvent.decimal(word, "end");
        speakerId = AsrEvent.string(word, "speaker_id");
        type = AsrEvent.string(word, "type");
        channelIndex = AsrEvent.integer(word, "channel_index");
        logprob = AsrEvent.decimal(word, "logprob");
    }

    public String text() { return text; }
    public Double start() { return start; }
    public Double end() { return end; }
    public String speakerId() { return speakerId; }
    public String type() { return type; }
    public Integer channelIndex() { return channelIndex; }
    public Double logprob() { return logprob; }
}
