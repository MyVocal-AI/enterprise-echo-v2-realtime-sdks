package ai.myvocal.echo.realtime;

import java.util.List;

/** Independent results for a WebSocket with exactly one manual commit. */
public final class SegmentResult {
    private final AsrEvent committed;
    private final AsrEvent timestamps;
    private final AsrEvent diarized;

    SegmentResult(AsrEvent committed, AsrEvent timestamps, AsrEvent diarized) {
        this.committed = committed;
        this.timestamps = timestamps;
        this.diarized = diarized;
    }

    public String text() { return committed != null ? committed.text() : null; }
    public List<AsrWord> words() { return timestamps != null ? timestamps.words() : List.of(); }
    public AsrEvent committed() { return committed; }
    public AsrEvent timestamps() { return timestamps; }
    public AsrEvent diarized() { return diarized; }
}
