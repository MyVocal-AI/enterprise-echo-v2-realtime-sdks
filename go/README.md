# MyVocal Enterprise Realtime ASR SDK for Go

Stream speech to `echo_v2_realtime` from a Go service using an Enterprise ASR key. The SDK connects to `wss://openapi.myvocal.ai/asr/realtime` and accepts raw 16 kHz, mono, signed 16-bit little-endian PCM. The module declares Go 1.21 or later.

## Install from GitHub

Run this from your Go module. Put your Enterprise ASR key in the server process environment variable `MYVOCAL_API_KEY`.

```sh
go get github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go@v0.1.0-rc.1
go run github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go/examples/segment -pcm english.pcm
```

Use your PCM file path or download the [shared synthetic audio](https://github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/tree/v0.1.0-rc.1/samples).

## Install from a locally built module proxy

Set `MYVOCAL_API_KEY` in the process environment using your secret manager. Keep the key on your server. The commands below use the candidate `v0.1.0-rc.1`. They work from a new Go module or an existing application outside the SDK checkout.

In PowerShell, set `$sdk` to the absolute path to the supplied `go` directory. If starting a new project, first run `go mod init example.com/voice-app`.

```powershell
$module = 'github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go'
$proxy = [Uri]::new((Resolve-Path (Join-Path $sdk 'dist/goproxy')).Path).AbsoluteUri
$env:GOPROXY = "$proxy,https://proxy.golang.org,direct"
$env:GONOSUMDB = $module
go get "$module@v0.1.0-rc.1"
```

On Linux or macOS, use an absolute `SDK_ROOT` pointing to that same directory.

```sh
MODULE=github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go
GOPROXY="file://$SDK_ROOT/dist/goproxy,https://proxy.golang.org,direct" \
  GONOSUMDB="$MODULE" go get "$MODULE@v0.1.0-rc.1"
```

Keep the same proxy settings available for subsequent commands until both the candidate and its dependency are cached. The module ZIP, `.mod`, and `.info` files ship together under `dist/goproxy`. To regenerate them from the supplied sources, run `python scripts/build_candidate.py` in the Go module. Git tags for a future release use the `go/vX.Y.Z` prefix because the module lives in the repository's `go/` subdirectory.

## Transcribe a short recording

With the module installed, run the segment example against the shared synthetic English recording.

```powershell
go run "$module/examples/segment" -pcm (Join-Path $sdk '../samples/english.pcm')
```

The command prints the final sentence and token timestamps in seconds. Example output includes `The order contains 42 boxes.` and an `"order"` token from 0.28 to 0.50 seconds. Timing and recognition can vary. Add `-timestamps=false` for text only. For your own recording, convert WAV to raw PCM with:

```sh
ffmpeg -i input.wav -f s16le -ar 16000 -ac 1 output.pcm
```

From your own Go code:

```go
import (
    "context"
    "os"
    "time"

    "github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go"
)

func transcribe(pcm []byte) (asr.Result, error) {
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()
    client := asr.NewClient(os.Getenv("MYVOCAL_API_KEY"))
    defer client.Close()
    return client.TranscribeSegment(ctx, pcm, asr.Options{WordTimestamps: true})
}
```

`TranscribeSegment` opens a separate `manual` session, sends approximately 100 ms of audio per frame at recording speed, and sets `commit=true` on the last frame. It waits for the ordinary committed transcript and any requested timestamp and speaker results. Read `Result.Text` for the final text, `Result.Words` for timestamp tokens, and `Result.Diarized.Words` for speaker IDs when present. A partial result can accompany an error. `IncompleteError.Missing` lists the events still needed.

## Stream a call

```powershell
go run "$module/examples/stream" -pcm (Join-Path $sdk '../samples/bilingual.pcm') -expected-segments 2 -drain 8s -transcript call.txt
```

The sample prints draft and committed transcripts, timestamp tokens, and speaker results. A typical run yields English and Chinese finals with `speaker_0` and `speaker_1`. `call.txt` contains one line per ordinary committed result. The expected count is specific to this demonstration, so a run that segments the sample differently may reach the drain deadline.

For live audio, use `-pcm -` and pipe PCM from your capture source. For example, on a Linux system with an ALSA microphone:

```sh
ffmpeg -loglevel error -f alsa -i default -f s16le -ar 16000 -ac 1 pipe:1 |
  go run "$MODULE/examples/stream" -pcm - -drain 8s -transcript call.txt
```

In an application, pass the call's cancellation context to `client.Open(ctx, opts)`. Start a goroutine that ranges over `session.Events()` while the capture loop calls `session.SendAudio(ctx, pcm)`. Set `CommitMode: asr.TurnPrediction` or `asr.VAD` for automatic commits. In `asr.Manual` mode, use `session.SendFinalAudio(ctx, lastPCM)` once at the end of each segment instead.

On EOF, keep reading events for as long as your application needs. The protocol has no whole-call completion event. The example waits for the requested result count when given one, or keeps reading up to `-drain`. A drain timeout reports that tail completeness is unconfirmed and saves the results already collected. Closing a session or canceling its context releases the connection without committing another segment. A failed write is never replayed automatically.

## Parameters and event fields

Before returning a session, `Open` waits for `session_started`. Each PCM chunk becomes an `input_audio_chunk` text message with `audio_base_64`, `sample_rate=16000`, and `commit`. The gateway receives the enterprise key as the URL-encoded `api-key` query parameter.

| Go option | Wire field and behavior |
| --- | --- |
| Fixed model and format | Query `model_id=echo_v2_realtime` and `audio_format=pcm_16000` |
| `CommitMode` | Query `commit_mode=manual` (default), `vad`, or `turn_prediction` |
| `Language` | Optional query `language`, for example `en` |
| `MultilingualMode` | Optional query `multilingual_mode=true` for transcription in the languages spoken |
| `SpeakerDiarization` | Optional query `speaker_diarization=true` |
| `WordTimestamps` | Optional query `word_timestamps=true` |
| `IncludeLanguageDetection` | Optional query `include_language_detection=true`. Enable word timestamps to consume the language field from timestamp events when supplied |
| `VADThreshold`, `VADSilenceThresholdSecs` | Optional VAD query values `vad_threshold`, `vad_silence_threshold_secs` |
| `MinSpeechDurationMS`, `MinSilenceDurationMS` | Optional VAD query values `min_speech_duration_ms`, `min_silence_duration_ms` |
| `Keyterms` | Up to 50 repeated `keyterms` query parameters |
| `HotwordsLibrary`, `PreviousText` | First audio message only, as `hotwords_library` (string array) and `previous_text`. Up to 10,000 library terms, each 1–50 characters. These can be used together and are sent again on the first frame of each new session |

| Event | Go fields and use |
| --- | --- |
| `session_started` | `SessionID` and `Config` when supplied |
| `partial_transcript` | `Text` is provisional. `TurnProbability`, `SpeakerID` and `Words` may be absent |
| `committed_transcript` | Append `Text` once. `SegmentIndex` may be absent. An index of 0 is valid |
| `diarized_transcript` | Match by `SegmentIndex` and read `Words[i].SpeakerID`. A nil label means unassigned |
| `committed_transcript_with_timestamps` | Read `Words[i].Text`, `Start`, and `End` in seconds. Other fields include `Type`, `LogProb`, `ChannelIndex` and `Characters` when supplied |
| `speakers_merged` | Use `FromSpeakerID` and `ToSpeakerID` to update speaker labels already stored |
| Error or unknown event | Read `Type`, `Raw` and, for errors, `Error` or `Message` |

Events are delivered in arrival order through one channel per session, with the original JSON in `Raw` apart from credential redaction in failure messages. Keep draining the channel so audio and results can flow concurrently. Optional numeric and speaker fields use pointers to distinguish nil from zero. The streaming example displays timestamp events on their own when `SegmentIndex` is absent and uses diarized events as the source of speaker labels. Delivering time and speaker events in either order preserves both outputs.

## Errors and shutdown

After the event channel closes, check `session.Err()` and `session.CloseCode()`. Use `errors.As` to inspect a `HandshakeError` for the HTTP status, a `ServerError` for the event type and detail, and a `CloseError` for unexpected WebSocket closure. `ServerError.CloseCode` is available when a close status was observed, including a server error followed by code 1000. `TransportError` describes another network or protocol failure. Check `context.Canceled` and `context.DeadlineExceeded` with `errors.Is`. Explicit `Close()` and a normal peer close have a nil terminal error when there was no preceding failure. That terminal state describes the connection. Check the events received to tell whether a transcript is complete.

## Keep an existing integration

The legacy enterprise Go SDK uses `github.com/myvocal-ai/realtime-asr-go/realtimeasr` for `echo_v1_realtime`. An application can import that path alongside this module and continue using the legacy client for existing calls. For timestamp replay on that SDK revision, read the legacy `OnMessage` raw JSON and decode its top-level `words` array using the token fields `text`, `start`, and `end`. The same saved message can be passed to `asr.DecodeEvent(raw)` in the new module. It returns those 27 token objects in `event.Words`, including spacing.
