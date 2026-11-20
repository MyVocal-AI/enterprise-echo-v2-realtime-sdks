package ai.myvocal.echo.realtime;

import java.util.List;
import java.util.Objects;

/** Immutable options for one WebSocket session. */
public final class AsrOptions {
    private final CommitMode commitMode;
    private final String language;
    private final boolean multilingualMode;
    private final boolean speakerDiarization;
    private final boolean wordTimestamps;
    private final List<String> keyterms;
    private final List<String> hotwordsLibrary;
    private final String previousText;

    private AsrOptions(Builder builder) {
        commitMode = builder.commitMode;
        language = builder.language;
        multilingualMode = builder.multilingualMode;
        speakerDiarization = builder.speakerDiarization;
        wordTimestamps = builder.wordTimestamps;
        keyterms = copyTerms(builder.keyterms, "keyterms", 50);
        hotwordsLibrary = copyTerms(builder.hotwordsLibrary, "hotwords_library", 10_000);
        for (String word : hotwordsLibrary) {
            if (word.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                    || word.codePointCount(0, word.length()) > 50) {
                throw new IllegalArgumentException("hotwords_library entries must contain 1 to 50 characters and cannot be blank");
            }
        }
        previousText = builder.previousText;
    }

    private static List<String> copyTerms(List<String> terms, String name, int limit) {
        if (terms.size() > limit) {
            throw new IllegalArgumentException(name + " allows at most " + limit + " entries");
        }
        if (terms.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " cannot contain null entries");
        }
        return List.copyOf(terms);
    }

    public static Builder builder() { return new Builder(); }
    public CommitMode commitMode() { return commitMode; }
    public String language() { return language; }
    public boolean multilingualMode() { return multilingualMode; }
    public boolean speakerDiarization() { return speakerDiarization; }
    public boolean wordTimestamps() { return wordTimestamps; }
    public List<String> keyterms() { return keyterms; }
    public List<String> hotwordsLibrary() { return hotwordsLibrary; }
    public String previousText() { return previousText; }

    public static final class Builder {
        private CommitMode commitMode = CommitMode.MANUAL;
        private String language;
        private boolean multilingualMode;
        private boolean speakerDiarization;
        private boolean wordTimestamps;
        private List<String> keyterms = List.of();
        private List<String> hotwordsLibrary = List.of();
        private String previousText;

        private Builder() {}

        public Builder commitMode(CommitMode value) { commitMode = Objects.requireNonNull(value); return this; }
        public Builder language(String value) { language = value; return this; }
        public Builder multilingualMode(boolean value) { multilingualMode = value; return this; }
        public Builder speakerDiarization(boolean value) { speakerDiarization = value; return this; }
        public Builder wordTimestamps(boolean value) { wordTimestamps = value; return this; }
        public Builder keyterms(List<String> value) { keyterms = Objects.requireNonNull(value); return this; }
        public Builder hotwordsLibrary(List<String> value) { hotwordsLibrary = Objects.requireNonNull(value); return this; }
        public Builder previousText(String value) { previousText = value; return this; }

        public AsrOptions build() { return new AsrOptions(this); }
    }
}
