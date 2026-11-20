package ai.myvocal.examples;

import ai.myvocal.echo.realtime.AsrEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Keeps committed text, diarization and timing events independent. */
final class CallTranscript {
    private final List<AsrEvent> finals = new ArrayList<>();
    private final Set<Integer> indices = new HashSet<>();
    private final Map<Integer, AsrEvent> diarized = new HashMap<>();
    private final List<AsrEvent> unindexedDiarized = new ArrayList<>();
    private final List<AsrEvent> timing = new ArrayList<>();
    private final Map<String, String> speakerAliases = new HashMap<>();

    synchronized void accept(AsrEvent event, PrintWriter console) {
        switch (event.messageType()) {
            case AsrEvent.PARTIAL:
                console.printf("PARTIAL %s (turn probability %s)%n", event.text(), event.turnProbability());
                break;
            case AsrEvent.COMMITTED:
                if (event.segmentIndex() == null || indices.add(event.segmentIndex())) {
                    finals.add(event);
                    console.println("FINAL " + event.text());
                }
                break;
            case AsrEvent.DIARIZED:
                if (event.segmentIndex() == null) {
                    unindexedDiarized.add(event);
                } else {
                    diarized.put(event.segmentIndex(), event);
                }
                console.printf("SPEAKERS segment %s %s%n", event.segmentIndex(), speakers(event));
                break;
            case AsrEvent.TIMESTAMPS:
                timing.add(event);
                console.printf("TIMESTAMPS event %d, segment index %s, items %d%n",
                        timing.size(), event.segmentIndex(), event.words().size());
                break;
            case AsrEvent.SPEAKERS_MERGED:
                if (event.fromSpeakerId() != null && event.toSpeakerId() != null) {
                    String from = event.fromSpeakerId();
                    String to = speakerAliases.getOrDefault(event.toSpeakerId(), event.toSpeakerId());
                    speakerAliases.replaceAll((id, current) -> from.equals(current) ? to : current);
                    speakerAliases.put(from, to);
                    console.printf("SPEAKERS MERGED %s → %s%n", from, to);
                }
                break;
            default:
                break;
        }
    }

    private Set<String> speakers(AsrEvent event) {
        return event.words().stream().map(word -> word.speakerId())
                .filter(id -> id != null).map(id -> speakerAliases.getOrDefault(id, id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    synchronized List<String> report(String closeStatus) {
        var lines = new ArrayList<String>();
        lines.add("SESSION_END_UNCONFIRMED " + closeStatus);
        lines.add("Final segments received: " + finals.size());
        for (AsrEvent text : finals) {
            AsrEvent labels = diarized.get(text.segmentIndex());
            String speakers = labels == null ? "[speaker unavailable]" : speakers(labels).toString();
            lines.add(speakers + " " + text.text());
        }

        lines.add("");
        lines.add("Diarization missing for indexed final segments: "
                + finals.stream().map(AsrEvent::segmentIndex).filter(index -> index != null && !diarized.containsKey(index))
                .collect(Collectors.toList()));
        lines.add("Final segments without an index: " + finals.stream().filter(event -> event.segmentIndex() == null).count());
        lines.add("Unindexed diarization events: " + unindexedDiarized.size());
        lines.add("Independent word timing events: " + timing.size());
        for (int i = 0; i < timing.size(); i++) {
            AsrEvent event = timing.get(i);
            lines.add("Timing event " + (i + 1) + ", segment index " + event.segmentIndex());
            for (var word : event.words()) {
                lines.add(String.format("  %s  %s – %s s", word.text(), word.start(), word.end()));
            }
        }
        return lines;
    }
}
