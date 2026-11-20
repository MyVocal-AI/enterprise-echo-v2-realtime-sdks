package ai.myvocal.examples;

import ai.myvocal.echo.realtime.AsrException;
import ai.myvocal.echo.realtime.AsrOptions;
import ai.myvocal.echo.realtime.RealtimeAsrClient;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class ShortRecording {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--no-timestamps"))) {
            throw new IllegalArgumentException("Usage: ShortRecording sample.pcm [--no-timestamps]");
        }
        byte[] pcm = Files.readAllBytes(Path.of(args[0]));
        var options = AsrOptions.builder().wordTimestamps(args.length == 1).build();
        var out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        try (var client = new RealtimeAsrClient(System.getenv("MYVOCAL_API_KEY"))) {
            var result = client.transcribeSegment(pcm, options, Duration.ofSeconds(30));
            out.println(result.text());
            for (var word : result.words()) {
                out.printf("%s  %s – %s s%n", word.text(), word.start(), word.end());
            }
        } catch (AsrException failure) {
            if (failure.partialResult() != null && failure.partialResult().text() != null) {
                out.println("Text received before failure: " + failure.partialResult().text());
            }
            throw failure;
        }
    }
}
