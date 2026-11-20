"""Run the installed wheel's examples and selected cases against Enterprise ASR.

Set MYVOCAL_API_KEY in this process. Use the shared, synthetic PCM samples.
Run with the candidate wheel and the public realtime-asr-sdk 0.1.1 installed.
"""
import argparse
import asyncio
import importlib.metadata
import json
import logging
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

from myvocal_echo_v2_realtime import AsrOptions, HandshakeError, RealtimeAsrClient, ServerError, parse_event


ROOT = Path(__file__).resolve().parents[1]
GATEWAY = "wss://openapi.myvocal.ai/asr/realtime"


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def example(script, sample, *args):
    result = subprocess.run(
        [sys.executable, "-I", "-X", "utf8", str(ROOT / "examples" / script), str(sample), *args],
        cwd=Path.cwd(),
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=80,
    )
    # Example stderr may contain sample transcript drafts. Save only the checked measurements below.
    if result.returncode:
        raise RuntimeError(f"{script} exited with {result.returncode}: {result.stderr[-500:]}")
    return result.stdout


def check_legacy(key, audio):
    from realtime_asr import AudioFormat, CommitStrategy, RealtimeASRClient, TranscriptionMessage

    logging.getLogger("realtime_asr.client").disabled = True
    logging.getLogger("websocket").disabled = True
    client = RealtimeASRClient(
        ws_url=GATEWAY,
        api_key=key,
        model_id="echo_v1_realtime",
        language="en",
        audio_format=AudioFormat.PCM_16000,
        commit_mode=CommitStrategy.MANUAL,
        word_timestamps=True,
    )
    finished = threading.Event()
    finals = []
    errors = []

    def final(msg):
        if msg.text:
            finals.append(msg.text)
            finished.set()

    def error(msg):
        errors.append(msg.message_type)
        finished.set()

    client.on_committed_transcript = final
    client.on_error = error
    try:
        client.connect()
        until = time.monotonic() + 10
        while client.session_id is None and time.monotonic() < until:
            time.sleep(0.02)
        check(client.session_id is not None, "legacy session did not start")
        for offset in range(0, len(audio), 6400):
            chunk = audio[offset:offset + 6400]
            commit = offset + len(chunk) == len(audio)
            client.send_audio(chunk, commit=commit)
            if not commit:
                time.sleep(len(chunk) / 32000)
        check(finished.wait(15) and finals and not errors, "legacy client did not receive a final transcript")
    finally:
        client.disconnect()

    data = json.loads((ROOT / "tests" / "fixtures" / "sdk-event.json").read_text(encoding="utf-8"))
    original = TranscriptionMessage.from_dict(data)
    candidate = parse_event(data)
    check(not any(word.word for word in original.words), "legacy replay changed")
    check([word.text for word in candidate.words] == [word["text"] for word in data["words"]], "word text differs")
    return {
        "distribution": importlib.metadata.version("realtime-asr-sdk"),
        "model": "echo_v1_realtime",
        "final": bool(finals),
        "replay_words": len(original.words),
        "legacy_nonempty_typed_words": sum(bool(word.word) for word in original.words),
        "candidate_nonempty_typed_words": sum(bool(word.text) for word in candidate.words),
    }


async def check_gateway(key, english):
    async with RealtimeAsrClient(key) as client:
        terms = ["w" * 50, *(f"term{i}" for i in range(9999))]
        boundary_runs = []
        for _ in range(2):
            result = await client.transcribe_segment(
                english, language="en", word_timestamps=True,
                keyterms=[f"hint{i}" for i in range(50)], hotwords_library=terms,
                previous_text="Customer order.",
            )
            check(result.text and result.words, "missing result in combined vocabulary session")
            boundary_runs.append(len(result.words))

        refused = {}
        for name, invalid_terms in (("empty_term", [""]), ("oversized_library", [*terms, "extra"])):
            try:
                async with client.open(AsrOptions(hotwords_library=invalid_terms)) as session:
                    await session.send_audio(english[:6400])
                    async with asyncio.timeout(15):
                        async for _ in session.events():
                            pass
            except ServerError as error:
                check(error.message_type == "input_error" and bool(error.error), f"unexpected {name} error")
                refused[name] = {"type": error.message_type, "close_code": error.close_code}
            else:
                raise AssertionError(f"{name} was accepted")

        async with client.open(AsrOptions(commit_mode="vad", language="en")) as session:
            async def feed():
                audio = english + bytes(96_000)
                for offset in range(0, len(audio), 6400):
                    frame = audio[offset:offset + 6400]
                    await session.send_audio(frame)
                    await asyncio.sleep(len(frame) / 32000)

            async def listen():
                async for item in session.events():
                    if item.message_type == "committed_transcript" and item.text:
                        return True
                return False

            async with asyncio.timeout(25):
                _, vad_final = await asyncio.gather(feed(), listen())
            check(vad_final, "VAD yielded no final transcript")

        opened = asyncio.Event()
        session = None

        async def midstream():
            nonlocal session
            async with client.open() as session:
                await session.send_audio(english[:6400])
                opened.set()
                async for _ in session.events():
                    pass

        task = asyncio.create_task(midstream())
        try:
            async with asyncio.timeout(15):
                await opened.wait()
        finally:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                cancelled = True
            else:
                cancelled = False
        check(cancelled and session.closed_by_client and session.close_code == 1000, "cancellation did not close")
        cancel_code = session.close_code

    async with RealtimeAsrClient("invalid-credential-for-myvolcal-sdk-acceptance") as client:
        try:
            async with client.open():
                raise AssertionError("Invalid key was accepted")
        except HandshakeError as error:
            check(error.status_code in (400, 401, 403), "missing HTTP rejection status")
            invalid_key = {"http_status": error.status_code}
        except ServerError as error:
            check(error.message_type == "auth_error", "missing auth_error")
            invalid_key = {"type": error.message_type, "close_code": error.close_code}

    return {
        "max_vocabulary_word_counts_on_two_sessions": boundary_runs,
        "rejected": refused,
        "vad_final": vad_final,
        "cancel_close_code": cancel_code,
        "invalid_key": invalid_key,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, default=ROOT.parent / "samples")
    cfg = parser.parse_args()
    key = os.environ["MYVOCAL_API_KEY"]
    english_path = cfg.samples / "english.pcm"
    bilingual_path = cfg.samples / "bilingual.pcm"
    english = english_path.read_bytes()

    # Run the legacy client first. Both SDKs are installed in this interpreter.
    legacy = check_legacy(key, english)
    timed = example("short_recording.py", english_path).splitlines()
    check(len(timed) > 1 and bool(timed[0]) and any("'order'\t" in item for item in timed[1:]),
          "single-segment CLI did not return the expected text and word timings")
    basic = example("short_recording.py", english_path, "--no-word-timestamps").splitlines()
    check(len(basic) == 1 and bool(basic[0]), "plain single-segment CLI did not return one final")
    call = json.loads(example("stream_call.py", bilingual_path))
    call_text = " ".join(item["text"] for item in call["committed"])
    speaker_ids = sorted({
        word["speaker_id"] for item in call["diarized"] for word in item["words"] if word["speaker_id"] is not None
    })
    indexes = [item["segment_index"] for item in call["committed"]]
    check(re.search("[a-zA-Z]", call_text) and re.search("[\u4e00-\u9fff]", call_text), "bilingual text missing")
    check(len(speaker_ids) >= 2 and 0 in indexes and not call["missing_diarization_indexes"], "speaker data missing")
    check(call["timestamps"] and not call["timestamp_event_count_shortfall"], "word timings missing")
    check(call["input_finished"] and call["completion"] == "unconfirmed", "unexpected automatic completion state")

    report = {
        "python": sys.version.split()[0],
        "candidate": importlib.metadata.version("myvocal-echo-v2-realtime"),
        "websockets": importlib.metadata.version("websockets"),
        "legacy_same_environment": legacy,
        "short_final_with_timestamps": timed[0],
        "short_timed_items": len(timed) - 1,
        "short_final_without_timestamps": basic[0],
        "call_final_text": call_text,
        "call_segment_indexes": indexes,
        "call_speaker_ids": speaker_ids,
        "call_timestamps_received": len(call["timestamps"]),
        "call_unassigned_timestamp_events": call["unassigned_timestamp_events"],
        "call_completion": call["completion"],
        "gateway": asyncio.run(check_gateway(key, english)),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
