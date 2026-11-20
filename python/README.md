# MyVocal Enterprise Realtime ASR for Python

Use this asyncio SDK to stream audio from your server to MyVocal Enterprise ASR with `echo_v2_realtime`. Read live drafts and finalized text during a call, with optional word timings and speaker labels. For long prerecorded files, use the Enterprise Batch API with `echo_v2`.

## Install the candidate

Python 3.11 or later is required. From this Python package directory, install the local wheel:

```sh
python -m venv .venv
# Windows PowerShell
.venv\Scripts\python.exe -m pip install dist/myvocal_echo_v2_realtime-0.1.0-py3-none-any.whl
# macOS or Linux
.venv/bin/python -m pip install dist/myvocal_echo_v2_realtime-0.1.0-py3-none-any.whl
```

With the virtual environment active, use the commands below. Set `MYVOCAL_API_KEY` in the server process environment using your secret manager. The package is `myvocal-echo-v2-realtime`, imported as `myvocal_echo_v2_realtime`. The examples use the two [shared synthetic samples](../samples/README.md). Audio must be raw 16 kHz, mono, signed 16-bit little-endian PCM without a file header.

```sh
ffmpeg -i input.wav -f s16le -ar 16000 -ac 1 output.pcm
```

## One short recording

```sh
python examples/short_recording.py ../samples/english.pcm
```

Expect one final English transcript and a row for each word or spacing item with start and end times in seconds. For example, the sample has returned:

```text
The order contains 42 boxes. The total price is $125. Please deliver them tomorrow.
'order'  0.28  0.5
```

```python
import asyncio
import os
from pathlib import Path

from myvocal_echo_v2_realtime import RealtimeAsrClient


async def main():
    async with RealtimeAsrClient(os.environ["MYVOCAL_API_KEY"]) as client:
        result = await client.transcribe_segment(
            Path("../samples/english.pcm").read_bytes(), language="en", word_timestamps=True, timeout=30
        )
        print(result.text)
        for word in result.words:
            print(word.text, word.start, word.end)


asyncio.run(main())
```

`transcribe_segment` opens a dedicated manual session. It sends 200 ms chunks paced by audio duration, with `commit=true` on the last chunk exactly once. It waits for the committed transcript and each enabled enhancement before returning. `result.committed`, `result.timestamps`, and `result.diarized` expose the separate events. `result.words` is the timestamp event's word list. This one-commit session can receive an unindexed timestamp event for the request. The event keeps its original absent index.

## Stream a call

```sh
python examples/stream_call.py ../samples/bilingual.pcm --drain-timeout 5 --output call.json
```

The file example plays English followed by Chinese at audio speed. Expect live drafts, committed text such as “The order contains 42 boxes.” and “明天上午送到办公室。”, speaker labels such as `speaker_0` and `speaker_1`, and separate word timings. Wording, event counts, timestamps, labels, and segmentation can vary. Draft, final, and enhancement notifications print to stderr. `call.json` saves each committed segment once, with separate diarized and timing records.

For a live call, pass your application’s already paced `AsyncIterable[bytes]` to the example's `stream_call(client, audio)` or adapt its send loop. Run the sender and event consumer concurrently. The example uses `turn_prediction`, multilingual output, speaker diarization, and word timestamps. `send_audio` sends `commit=false` every time. When your audio source ends, keep reading events up to the application's drain deadline. The gateway has no call-wide completion marker. The example returns the results received with `completion: "unconfirmed"`, any outstanding indexed enrichments, and the last uncommitted draft. The counts for unindexed timing events show what arrived without asserting a per-segment match. A timeout ends the drain even when all previously seen segments appear to have enhancements.

## Options and wire format

`client.open(AsrOptions(...))` yields a session after the gateway sends `session_started`. The SDK uses `wss://openapi.myvocal.ai/asr/realtime` and URL-encodes the `api-key` query value. Pass only trusted endpoints to the optional `endpoint` override. Client settings `open_timeout`, `start_timeout`, and `close_timeout` default to 10, 10, and 3 seconds. The single-segment `timeout` covers waiting for the connection, sending, and receiving results. Socket cleanup can take additional time.

| SDK option or field | Wire behavior |
| --- | --- |
| `api_key` | Required client argument. Encoded Query `api-key`. |
| Model and audio format | Query `model_id=echo_v2_realtime`, `audio_format=pcm_16000`. Every audio frame carries `sample_rate=16000`. |
| `commit_mode` | Query `manual` (default), `turn_prediction`, or `vad`. `send_final_audio(last_pcm)` submits a manual segment. |
| `language` | Optional Query language hint such as `en`. |
| `multilingual_mode` | Query `multilingual_mode=true` when enabled. Keeps recognized languages in their respective scripts. |
| `speaker_diarization` | Query `speaker_diarization=true` when enabled. |
| `word_timestamps` | Query `word_timestamps=true` when enabled. |
| `keyterms` | One Query `keyterms` per value, up to 50. |
| `hotwords_library` | Optional first audio frame JSON string array. Up to 10,000 nonempty terms, at most 50 characters each. |
| `previous_text` | Optional first audio frame JSON string. May be used with the library and ordinary keyterms. |
| Audio and commit | Each JSON frame has `message_type="input_audio_chunk"`, Base64 `audio_base_64`, integer `sample_rate`, and boolean `commit`. |

The gateway validates vocabulary limits and returns its error details. Both first-frame fields reset on a new session. Keep one event consumer per session and consume promptly as audio is sent. Use separate sessions for separate calls.

## Read events in order

`async for event in session.events()` yields `AsrEvent` objects in receive order. Every event has `message_type` and a `raw` gateway payload with any echoed credential redacted. `parse_event` also accepts recorded JSON or a mapping for replay.

| `message_type` | Fields and handling |
| --- | --- |
| `session_started` | `session_id`, `config`. The SDK also delivers this event to the iterator. |
| `partial_transcript` | `text`, optional `turn_probability`, `words` and `language_code` when present. Use as the latest draft. |
| `committed_transcript` | Save `text` once. `segment_index` may be `None`. Index `0` is valid. |
| `diarized_transcript` | Join to the committed text by `segment_index`. Read `words[].speaker_id`; unresolved labels are `None`. |
| `committed_transcript_with_timestamps` | Read `words[].text`, `start`, `end`, and optional `type` and `speaker_id`. Preserve spacing, time `0`, and missing times separately. Keep events with no index separate in continuous sessions. Use diarized words as the source of speaker labels. |
| `speakers_merged` | Update stored labels in event order using `from_speaker_id` and `to_speaker_id`. The example handles chained merges. |
| Error and future events | Error events carry `error` and `message_type`. Unknown event types still yield the raw payload. |

## Errors, stopping, and cancellation

- `HandshakeError.status_code` carries the HTTP status if the upgrade is rejected. Network, TLS, and session startup failures before the service is ready may have no HTTP status.
- `ServerError` exposes `message_type`, `error`, and `close_code` separately. A running event consumer receives the error event before the exception. An `input_error` stays a failure even if followed by close code `1000`. If the service sends an error and no close, the SDK stops waiting for a peer close after the close timeout.
- `ConnectionLost` indicates abnormal closure or sending after the connection ended. A clean close ends streaming iteration. Read `session.close_code` and `session.closed_by_client` to tell the close origin. For a single segment, a clean close with missing results raises `IncompleteResultError`.
- `TranscriptionTimeout` and `IncompleteResultError` both expose `missing` event types and `partial_result`.
- Call `await session.close()` or exit the session context after collecting your results. Cancelling the task that owns the context propagates `asyncio.CancelledError` after cleanup. Closing and cancelling neither commit new audio nor reconnect or resend earlier audio.

## Keep the existing SDK during migration

Install this wheel alongside the [existing Enterprise realtime Python SDK](https://github.com/MyVocal-AI/realtime-asr-sdk/releases/tag/1.1.0). Existing `echo_v1_realtime` callers keep `from realtime_asr import RealtimeASRClient` and their existing callbacks. Route new `echo_v2_realtime` sessions to `from myvocal_echo_v2_realtime import RealtimeAsrClient`.

For the same timestamp event containing `{"text": "order", "start": 0.28, "end": 0.50}` in `words`, use `msg.raw_data["words"][i]["text"]` with the old 0.1.1 package. Its typed `msg.words[i].word` is empty for that payload. With this SDK use `event.words[i].text` and `event.words[i].start` / `event.words[i].end`.

## Build and run the protocol tests

```sh
python -m pip install build
python -m build
python -m pip install dist/myvocal_echo_v2_realtime-0.1.0-py3-none-any.whl
python -m unittest discover -s tests -v
```

The tests use a local WebSocket server and a captured synthetic timestamp event. They do not require a MyVocal key.
