# MyVocal Enterprise Realtime ASR SDKs

Use these server-side SDKs to stream audio to the MyVocal Enterprise ASR model `echo_v2_realtime` and receive live drafts, finalized text, and optional word timings and speaker labels. The packages are specific to this realtime model. For batch transcription of prerecorded files, use the Batch API with `echo_v2`.

## Install by language

Use the README for your language for runtime requirements, package installation, and runnable examples.

- Java · [Java README](java/README.md)
- Python · [Python README](python/README.md)
- Go · [Go README](go/README.md)

## Quickstart

1. Install the SDK for your language. Supply your MyVocal Enterprise ASR key to your server process through `MYVOCAL_API_KEY`. Keep the key on your server. Keep it out of browser and mobile code, source control, and logs, including logs of the full WebSocket URL.
2. Run the language README's single-segment example with [`samples/english.pcm`](samples/english.pcm). Use manual commit mode and enable word timestamps. Expect one finalized English transcript and an event with word start and end times in seconds.
3. Run the streaming example with [`samples/bilingual.pcm`](samples/bilingual.pcm). Use `turn_prediction` and enable `multilingual_mode`, `speaker_diarization`, and `word_timestamps`. Expect finalized text in English and Chinese, speaker information by segment, and word timings. See the [sample guide](samples/README.md) for the input format and observed results.

The realtime WebSocket endpoint is `wss://openapi.myvocal.ai/asr/realtime`. The shared samples use raw 16 kHz, mono, signed 16-bit little-endian PCM. Set `audio_format=pcm_16000` for the connection and `sample_rate=16000` in each audio message. Start sending audio after `session_started`, pace chunks according to their audio duration, and keep reading events while sending.

## Choose how segments are finalized

- **Manual (`commit_mode=manual`).** Use this for an utterance whose boundary your application already knows. Submit the last audio chunk with `commit=true` once. Wait for the finalized text and any enabled results you need before closing.
- **Automatic (`commit_mode=turn_prediction`).** Use this for an ongoing conversation. Stream audio with `commit=false` and let the model decide when a turn is complete. Multiple segments can be finalized in one session. The first finalized segment marks the end of that segment. Continue reading after audio ends for any pending results. The protocol has no session-wide completion event, so treat any outstanding results at your application's wait deadline as unconfirmed.

Optional vocabulary hints use repeatable `keyterms` query values (up to 50). To send a larger vocabulary, include `hotwords_library` on the first audio message only (up to 10,000 nonempty entries, 50 characters per entry).

## Read transcript events

| Event | How to use it |
| --- | --- |
| `partial_transcript` | Replace the live draft with `text`. Read `turn_probability` when present. |
| `committed_transcript` | Append the segment's final `text` once. Preserve `segment_index` when present, including index `0`. |
| `diarized_transcript` | Match it to its committed segment using `segment_index` and read speaker labels from `words[].speaker_id`. |
| `committed_transcript_with_timestamps` | Read `words[].text`, `start`, and `end`. Times are in seconds and may be zero. Treat a timestamp event without `segment_index` as a separate result. |

Speaker and timestamp events can arrive in either order. Use them to enrich your output without appending the transcript again. Use the diarized event for speaker labels and retain those labels when timestamp words carry a null `speaker_id`. If the service sends an error such as `input_error`, read its `error` field and handle the failure even if the connection subsequently closes cleanly.

## Migrate from `echo_v1_realtime`

Existing `echo_v1_realtime` integrations can keep their current SDK and calls. For a new or migrating workload, add the separate SDK for your language, route that workload to the `echo_v2_realtime` client, and run both examples before switching traffic. If you previously parsed timestamp events yourself, map the word text from `words[].text` and use `start` and `end` for timings.
