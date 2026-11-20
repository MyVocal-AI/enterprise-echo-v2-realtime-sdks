package ai.myvocal.examples;

import ai.myvocal.echo.realtime.AsrEvent;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.*;

class CallTranscriptTest {
    @Test
    void savesEachCommitOnceAndUpdatesLabelsWithoutGuessingTimingIndexes() {
        for (boolean labelsFirst : new boolean[]{false, true}) {
            var report = new CallTranscript();
            var console = new PrintWriter(Writer.nullWriter());
            var committed = AsrEvent.parse("{\"message_type\":\"committed_transcript\",\"segment_index\":0,\"text\":\"Indexed\"}");
            var labels = AsrEvent.parse("{\"message_type\":\"diarized_transcript\",\"segment_index\":0,"
                    + "\"words\":[{\"text\":\"Indexed\",\"speaker_id\":\"speaker_0\"}]}");
            var timing = AsrEvent.parse("{\"message_type\":\"committed_transcript_with_timestamps\","
                    + "\"words\":[{\"text\":\"Indexed\",\"start\":0,\"end\":0.5,\"speaker_id\":null}]}");
            var unindexed = AsrEvent.parse("{\"message_type\":\"committed_transcript\",\"text\":\"Repeat\"}");
            report.accept(committed, console);
            report.accept(labelsFirst ? labels : timing, console);
            report.accept(labelsFirst ? timing : labels, console);
            report.accept(committed, console);
            report.accept(unindexed, console);
            report.accept(unindexed, console);
            report.accept(merge("speaker_0", "speaker_1"), console);
            report.accept(merge("speaker_1", "speaker_2"), console);
            var lines = report.report("fixture");
            assertEquals("SESSION_END_UNCONFIRMED fixture", lines.get(0));
            assertEquals(1, lines.stream().filter("[speaker_2] Indexed"::equals).count());
            assertEquals(2, lines.stream().filter("[speaker unavailable] Repeat"::equals).count());
            assertTrue(lines.contains("Diarization missing for indexed final segments: []"));
            assertTrue(lines.contains("Timing event 1, segment index null"));
            assertTrue(lines.contains("  Indexed  0.0 – 0.5 s"));
        }
    }

    private static AsrEvent merge(String from, String to) {
        return AsrEvent.parse(String.format(
                "{\"message_type\":\"speakers_merged\",\"from_speaker_id\":\"%s\",\"to_speaker_id\":\"%s\"}",
                from, to));
    }
}
