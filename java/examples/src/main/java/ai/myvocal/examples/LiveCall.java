package ai.myvocal.examples;

import ai.myvocal.echo.realtime.AsrException;
import ai.myvocal.echo.realtime.AsrOptions;
import ai.myvocal.echo.realtime.AsrSession;
import ai.myvocal.echo.realtime.CloseInfo;
import ai.myvocal.echo.realtime.CommitMode;
import ai.myvocal.echo.realtime.RealtimeAsrClient;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public final class LiveCall {
    private static final int FRAME_BYTES = 3200;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: LiveCall <sample.pcm|--mic=seconds> <transcript.txt> [drain-seconds]");
        }
        var transcript = new CallTranscript();
        var out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        var options = AsrOptions.builder().commitMode(CommitMode.TURN_PREDICTION)
                .multilingualMode(true).speakerDiarization(true).wordTimestamps(true)
                .hotwordsLibrary(List.of("Bishan Central")).build();
        long drainSeconds = args.length == 3 ? Long.parseLong(args[2]) : 6;
        if (drainSeconds <= 0) {
            throw new IllegalArgumentException("Drain time must be positive");
        }

        String closeStatus = "Capture interrupted or failed";
        Path output = Path.of(args[1]);
        try (var client = new RealtimeAsrClient(System.getenv("MYVOCAL_API_KEY"));
             var session = client.open(options, event -> transcript.accept(event, out))) {
            try {
                if (args[0].startsWith("--mic=")) {
                    streamMicrophone(session, Long.parseLong(args[0].substring("--mic=".length())));
                } else {
                    try (InputStream pcm = Files.newInputStream(Path.of(args[0]))) {
                        streamFile(session, pcm);
                    }
                }
                CloseInfo close;
                try {
                    close = session.awaitClosed(Duration.ofSeconds(drainSeconds));
                    closeStatus = "Peer closed during drain (" + close.kind() + ", code " + close.code() + ")";
                } catch (AsrException deadline) {
                    if (deadline.kind() != AsrException.Kind.TIMEOUT) {
                        throw deadline;
                    }
                    session.close();
                    close = session.awaitClosed(Duration.ofSeconds(4));
                    closeStatus = "Drain deadline reached (" + close.kind() + ", code " + close.code() + ")";
                }
                if (close.error() != null) {
                    throw close.error();
                }
            } catch (AsrException failure) {
                closeStatus += " / " + failure.getMessage();
                session.close();
                try {
                    session.awaitClosed(Duration.ofSeconds(4));
                } catch (AsrException cleanupError) {
                    failure.addSuppressed(cleanupError);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                }
                throw failure;
            } finally {
                session.close();
                Files.write(output, transcript.report(closeStatus), StandardCharsets.UTF_8);
                out.println("Saved received results to " + output);
            }
        }
    }

    private static void streamFile(AsrSession session, InputStream pcm) throws Exception {
        long nextFrame = System.nanoTime();
        byte[] frame;
        while ((frame = pcm.readNBytes(FRAME_BYTES)).length > 0) {
            long delay = nextFrame - System.nanoTime();
            if (delay > 0) {
                NANOSECONDS.sleep(delay);
            }
            nextFrame = System.nanoTime() + Duration.ofMillis(100).toNanos();
            session.sendAudio(frame);
        }
    }

    private static void streamMicrophone(AsrSession session, long seconds) throws Exception {
        if (seconds <= 0) {
            throw new IllegalArgumentException("Microphone capture time must be positive");
        }
        var format = new AudioFormat(16000, 16, 1, true, false);
        try (TargetDataLine mic = AudioSystem.getTargetDataLine(format)) {
            mic.open(format);
            mic.start();
            byte[] buffer = new byte[FRAME_BYTES];
            long remaining = Math.multiplyExact(seconds, 32000L);
            while (remaining > 0) {
                int count = mic.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (count <= 0) {
                    break;
                }
                session.sendAudio(count == buffer.length ? buffer : Arrays.copyOf(buffer, count));
                remaining -= count;
            }
        }
    }
}
