# MyVocal Enterprise Realtime ASR SDK for Java

Transcribe a short recording or an ongoing audio stream with the MyVocal enterprise `echo_v2_realtime` model. The SDK connects to `wss://openapi.myvocal.ai/asr/realtime`. Use your MyVocal enterprise ASR API key from a server-side environment variable named `MYVOCAL_API_KEY`.

Requires Java **11 or later**. The candidate was built and run on Eclipse Temurin 11.0.29 with Maven 3.9.12. Use **16 kHz, mono, signed 16-bit little-endian PCM** with no file header.

## Install the candidate

Run the commands from the `java` directory. The candidate JAR and its POM are distributed together. Maven or Gradle resolves Gson from Maven Central using the supplied POM.

```sh
mvn org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file "-Dfile=artifacts/echo-v2-realtime-asr-0.1.0-rc.1.jar" "-DpomFile=artifacts/echo-v2-realtime-asr-0.1.0-rc.1.pom"
```

Maven dependency

```xml
<dependency>
    <groupId>ai.myvocal</groupId>
    <artifactId>echo-v2-realtime-asr</artifactId>
    <version>0.1.0-rc.1</version>
</dependency>
```

Gradle Kotlin DSL after the same local install

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("ai.myvocal:echo-v2-realtime-asr:0.1.0-rc.1")
}
```

Use the `ai.myvocal.echo.realtime` Java package. Each existing integration with `echo_v1_realtime` can continue using its current client and model. Teams using raw WebSockets can use the current enterprise endpoint for either model.

## Transcribe a short recording

With `MYVOCAL_API_KEY` available in the process environment, run the sample from this directory.

```sh
mvn -q -f examples/pom.xml compile exec:java "-Dexec.mainClass=ai.myvocal.examples.ShortRecording" "-Dexec.args=../samples/english.pcm"
```

Expected excerpt from the synthetic English recording. Transcription and timing can vary.

```text
The order contains 42 boxes. The total price is $125. Please deliver them tomorrow.
order  0.28 – 0.5 s
```

Append `--no-timestamps` inside `-Dexec.args` for text only. Use FFmpeg to convert a WAV file.

```sh
ffmpeg -i input.wav -f s16le -ar 16000 -ac 1 output.pcm
```

The sample calls this blocking API. The method uses a new manual session, paces 100 ms chunks, sends `commit=true` on the last chunk once, and waits for the ordinary final plus each requested enhancement. The timeout covers opening, sending and receiving. Use `SegmentResult.diarized()` when you request speaker diarization.

```java
import ai.myvocal.echo.realtime.AsrOptions;
import ai.myvocal.echo.realtime.RealtimeAsrClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

var options = AsrOptions.builder().wordTimestamps(true).build();
try (var client = new RealtimeAsrClient(System.getenv("MYVOCAL_API_KEY"))) {
    var result = client.transcribeSegment(
            Files.readAllBytes(Path.of("sample.pcm")), options, Duration.ofSeconds(30));
    System.out.println(result.text());
    for (var word : result.words()) {
        System.out.printf("%s %s %s%n", word.text(), word.start(), word.end());
    }
}
```

## Stream a two-person call

Replay the synthetic bilingual sample at audio speed with automatic turn detection, text in both languages, speaker labels, word timings and a first-frame hotword.

```sh
mvn -q -f examples/pom.xml compile exec:java "-Dexec.mainClass=ai.myvocal.examples.LiveCall" "-Dexec.args=../samples/bilingual.pcm call.txt"
```

Use a Java Sound microphone that supports the required format to stream up to 20 seconds. The microphone device must be available to the JVM.

```sh
mvn -q -f examples/pom.xml compile exec:java "-Dexec.mainClass=ai.myvocal.examples.LiveCall" "-Dexec.args=--mic=20 call.txt"
```

Watch `PARTIAL` and `FINAL` events arrive during capture. The UTF-8 `call.txt` report contains each final segment once, the matching speaker labels when available, and an independent section for word timings. Typical output from the bilingual sample follows.

```text
SESSION_END_UNCONFIRMED Drain deadline reached (LOCAL, code 1000)
Final segments received: 2
[speaker_0] The order contains 42 boxes. The total price is $125. Please deliver them tomorrow.
[speaker_1] 好的，明天上午送到办公室。订单数量是42箱，请确认地址。
```

The third argument sets the drain limit in seconds, with a default of 6. After audio stops, the example keeps reading through the drain limit or peer closure and saves the results it received. The protocol provides no whole-call completion event. `SESSION_END_UNCONFIRMED` applies even when the counts look complete. The report lists indexed final segments still missing speaker labels and timing events with their own optional index. Add ordinary acoustic silence after speech when replaying a file for automatic turn detection.

For a call server, open with `client.open(options, listener)`. Send audio in capture order with `session.sendAudio(pcm)` as each block becomes available. To implement manual segmentation, call `session.sendFinalAudio(lastPcm)` for the last block of each segment and keep reading the corresponding results before closing. Callbacks run in order on a separate per-session thread. Keep them short and move slow work to your own executor. Await closure from your application thread. `close()` requests resource cleanup without a commit. `client.close()` closes the client's active sessions. Transcription and sends are never automatically retried or replayed.

## Options and wire format

| Java option | Enterprise field | Behavior |
| --- | --- | --- |
| `RealtimeAsrClient(apiKey)` | Query `api-key` | SDK URL-encodes the key. |
| Fixed model and audio | Query `model_id=echo_v2_realtime`, `audio_format=pcm_16000` | Each frame sets `sample_rate=16000`. |
| `commitMode(CommitMode)` | Query `commit_mode` | `MANUAL` (`manual`, default), `VAD` (`vad`), or `TURN_PREDICTION` (`turn_prediction`). `transcribeSegment` always uses manual. |
| `language(String)` | Query `language` | Optional language hint such as `en`. |
| `multilingualMode(boolean)` | Query `multilingual_mode` | Keep different spoken languages in their original form. Default false. |
| `speakerDiarization(boolean)` | Query `speaker_diarization` | Request per-word speaker labels in diarization events. Default false. |
| `wordTimestamps(boolean)` | Query `word_timestamps` | Request separate word timing events. Default false. |
| `keyterms(List<String>)` | Repeated Query `keyterms` | Up to 50 ordinary hotwords. |
| `hotwordsLibrary(List<String>)` | First audio frame `hotwords_library` | Up to 10,000 nonblank entries, up to 50 Unicode characters each. |
| `previousText(String)` | First audio frame `previous_text` | Optional prior text. Can accompany the library. |

The server first sends `session_started`. The SDK then allows `input_audio_chunk` JSON messages containing `audio_base_64`, `sample_rate`, and `commit`. First-frame options are sent once per connection, including when you open a second session with the same options. The receiver stays active while audio is sent.

## Events and results

Every received message is delivered through `AsrListener.onEvent(AsrEvent)`. Use `messageType()` and `rawJson()` for additional fields and future event types. `AsrEvent.parse(rawJson)` is the same decoder used for WebSocket messages. The SDK redacts credentials if a gateway message echoes them.

| Gateway event | Java fields |
| --- | --- |
| `session_started` | `sessionId()`, `configJson()` |
| `partial_transcript` | `text()`, nullable `turnProbability()`, `languageCode()`, `words()` when present |
| `committed_transcript` | `text()` and nullable `segmentIndex()`, including zero |
| `diarized_transcript` | `text()`, `segmentIndex()`, and `words().get(i).speakerId()` when known |
| `committed_transcript_with_timestamps` | `text()`, optional `languageCode()` and `segmentIndex()`, word `text()`, `start()`, and `end()` in seconds |
| `speakers_merged` | `fromSpeakerId()`, `toSpeakerId()`. The call example updates previously received labels in event order. |
| `input_error` and other gateway errors | `messageType()`, `error()`, `isError()`, plus `onError` and `onClosed`. |
| Other events | `messageType()`, `rawJson()` |

Word and spacing items stay in wire order. Word objects also expose `type()`, `channelIndex()`, and `logprob()` when supplied. Further metadata such as characters is in `rawJson()`. Nullable numeric fields distinguish `null` from `0`. Match diarization only by supplied segment indexes. Keep timing events without an index separate. A null timestamp speaker label leaves diarization labels intact. Speaker merges were tested with replayed events.

## Errors, timeouts and close handling

- `AsrException.kind()` distinguishes HTTP handshake failures, gateway errors, transport/protocol problems, listener failure, timeout, peer closure and cancellation. A failed HTTP upgrade exposes `httpStatus()`. A gateway error exposes its type and detail in `serverEvent()`.
- `onClosed(CloseInfo)` and `session.awaitClosed(timeout)` distinguish `LOCAL`, `REMOTE`, `SERVER_ERROR`, and `TRANSPORT_ERROR`. `code()` and `reason()` expose received close-frame details. A prior server error still determines the status when the peer sends code 1000. A timed-out `awaitClosed` leaves the session running.
- `transcribeSegment` failures expose `missingEvents()` and `partialResult()` with results received so far. A peer close before all requested results arrive is a failure, even if it sent code 1000. `Thread.interrupt()` stops blocking operations with `InterruptedException`.
- Opening waits up to 15 seconds by default, including `session_started`. Each send has a 10-second default timeout. Both APIs accept a `Duration` override. Keep JDK HTTP wire logging disabled when using an enterprise key.

## Build from source

Run `mvn clean package` from the Java source directory to run the SDK tests and build the binary and sources JARs under `target`. The SDK JAR is a library. The standalone `examples/pom.xml` consumes it through Maven coordinates. After local installation, run `mvn -f examples/pom.xml test` to test the example's transcript handling.

For the wire contract see the [MyVocal enterprise realtime ASR reference](https://api-docs.myvocal.ai/3714899w0).
