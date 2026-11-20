import asyncio
import base64
import io
import json
import unittest
from contextlib import asynccontextmanager, redirect_stderr
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from websockets.asyncio.server import serve

from examples.stream_call import stream_call
from myvocal_echo_v2_realtime import (
    AsrOptions,
    ConnectionLost,
    HandshakeError,
    IncompleteResultError,
    RealtimeAsrClient,
    ServerError,
    TranscriptionTimeout,
    parse_event,
)


KEY = "key +/秘密?&"
PCM = bytes(6400)
FIXTURE = Path(__file__).parent / "fixtures" / "sdk-event.json"


async def event(socket, message_type, **fields):
    await socket.send(json.dumps({"message_type": message_type, **fields}))


@asynccontextmanager
async def gateway(handler, **kwargs):
    async with serve(handler, "127.0.0.1", 0, **kwargs) as server:
        port = server.sockets[0].getsockname()[1]
        async with RealtimeAsrClient(KEY, endpoint=f"ws://127.0.0.1:{port}/asr/realtime") as client:
            yield client


class ParsingTests(unittest.TestCase):
    def test_captured_27_items_preserve_text_spacing_and_seconds(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        parsed = parse_event(data)
        self.assertEqual(parsed.raw, data)
        self.assertIsNone(parsed.segment_index)
        self.assertEqual(len(parsed.words), 27)
        self.assertEqual([word.text for word in parsed.words], [word["text"] for word in data["words"]])
        order = parsed.words[2]
        self.assertEqual((order.text, order.start, order.end, order.speaker_id), ("order", 0.28, 0.50, None))

    def test_zero_null_unknown_and_merge(self):
        parsed = parse_event({"message_type": "partial_transcript", "text": "trial", "segment_index": 0,
                              "turn_probability": 0, "words": [{"text": "trial", "start": 0, "end": None}]})
        self.assertEqual((parsed.segment_index, parsed.turn_probability, parsed.words[0].start), (0, 0, 0))
        self.assertIsNone(parsed.words[0].end)
        merged = parse_event({"message_type": "speakers_merged", "from_speaker_id": "speaker_2",
                              "to_speaker_id": "speaker_1"})
        self.assertEqual((merged.from_speaker_id, merged.to_speaker_id), ("speaker_2", "speaker_1"))
        future = {"message_type": "future_event", "data": {"new_field": 0}}
        self.assertEqual(parse_event(future).raw, future)


class TransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_query_first_frame_and_receiving_during_send(self):
        received = []

        async def handler(ws):
            query = parse_qs(urlsplit(ws.request.path).query)
            await event(ws, "session_started", session_id="s")
            frames = [json.loads(await ws.recv())]
            await event(ws, "partial_transcript", text="hi", turn_probability=0)
            frames.append(json.loads(await ws.recv()))
            await event(ws, "committed_transcript", text="hi", segment_index=0)
            await event(ws, "future_event", data=[1, 2])
            received.append((query, frames))

        opts = AsrOptions(commit_mode="turn_prediction", multilingual_mode=True, word_timestamps=True,
                          speaker_diarization=True, keyterms=["a+b", "中文"], language="en",
                          hotwords_library=["Bishan Central"], previous_text="Customer order.")
        async with gateway(handler) as client:
            for _ in range(2):
                async with client.open(opts) as session:
                    iterator = session.events()
                    self.assertEqual((await anext(iterator)).session_id, "s")
                    await session.send_audio(PCM)
                    self.assertEqual((await anext(iterator)).turn_probability, 0)
                    await session.send_audio(PCM)
                    self.assertEqual((await anext(iterator)).segment_index, 0)
                    self.assertEqual((await anext(iterator)).raw["data"], [1, 2])
                    await iterator.aclose()
        self.assertEqual(len(received), 2)
        expected = dict(model_id=["echo_v2_realtime"], audio_format=["pcm_16000"],
                        commit_mode=["turn_prediction"], multilingual_mode=["true"],
                        speaker_diarization=["true"], word_timestamps=["true"], language=["en"],
                        keyterms=["a+b", "中文"], **{"api-key": [KEY]})
        for query, (first, second) in received:
            self.assertEqual(query, expected)
            self.assertEqual(first["hotwords_library"], ["Bishan Central"])
            self.assertEqual(first["previous_text"], "Customer order.")
            self.assertEqual(second, {"message_type": "input_audio_chunk", "sample_rate": 16000, "commit": False,
                                      "audio_base_64": base64.b64encode(PCM).decode()})

    async def test_segment_waits_for_both_enrichments_in_either_order(self):
        for order in (["timestamps", "diarized"], ["diarized", "timestamps"], []):
            with self.subTest(order=order):
                frames = []

                async def handler(ws):
                    await event(ws, "session_started")
                    async for message in ws:
                        frames.append(json.loads(message))
                        if frames[-1]["commit"]:
                            await event(ws, "committed_transcript", text="Hello",
                                        **({"segment_index": 0} if order else {}))
                            await asyncio.sleep(0.02)
                            for kind in order:
                                if kind == "timestamps":
                                    await event(ws, "committed_transcript_with_timestamps",
                                                words=[{"text": "Hello", "start": 0, "end": 0.2, "speaker_id": None}])
                                else:
                                    await event(ws, "diarized_transcript", segment_index=0,
                                                words=[{"text": "Hello", "speaker_id": "speaker_0"}])

                async with gateway(handler) as client:
                    result = await client.transcribe_segment(PCM * 2, word_timestamps=bool(order),
                                                             speaker_diarization=bool(order), timeout=2)
                self.assertEqual(result.text, "Hello")
                self.assertEqual([frame["commit"] for frame in frames], [False, True])
                self.assertEqual(b"".join(base64.b64decode(frame["audio_base_64"]) for frame in frames), PCM * 2)
                if order:
                    self.assertEqual(result.words[0].start, 0)
                    self.assertIsNone(result.timestamps.segment_index)
                    self.assertEqual(result.diarized.words[0].speaker_id, "speaker_0")

    async def test_incomplete_result_reports_exact_missing_type(self):
        for remote_close in (True, False):
            async def handler(ws):
                await event(ws, "session_started")
                await ws.recv()
                await event(ws, "committed_transcript", text="Hello")
                if remote_close:
                    await ws.close(1000)
                else:
                    await ws.wait_closed()

            async with gateway(handler) as client:
                cls = IncompleteResultError if remote_close else TranscriptionTimeout
                with self.assertRaises(cls) as error:
                    await client.transcribe_segment(PCM, word_timestamps=True, timeout=0.5)
                self.assertEqual(error.exception.missing, ("committed_transcript_with_timestamps",))
                self.assertEqual(error.exception.partial_result.text, "Hello")

    async def test_error_precedes_normal_peer_close_and_is_sanitized(self):
        from urllib.parse import quote_plus

        async def handler(ws):
            await event(ws, "session_started")
            await ws.recv()
            await event(ws, "input_error", error=f"Invalid term ({KEY}) at wss://host/asr?api-key={quote_plus(KEY)}")
            await ws.close(1000)

        async with gateway(handler) as client:
            seen = []
            with self.assertRaises(ServerError) as error:
                async with client.open() as session:
                    await session.send_audio(PCM)
                    async for item in session.events():
                        seen.append(item.message_type)
            self.assertEqual(error.exception.message_type, "input_error")
            self.assertEqual(error.exception.close_code, 1000)
            self.assertIn("Invalid term", error.exception.error)
            self.assertNotIn(KEY, str(error.exception))
            self.assertNotIn("wss://host", str(error.exception))
            self.assertEqual(seen, ["session_started", "input_error"])

    async def test_single_segment_does_not_accept_an_enhancement_for_a_different_index(self):
        async def handler(ws):
            await event(ws, "session_started")
            await ws.recv()
            await event(ws, "diarized_transcript", segment_index=1)
            await event(ws, "committed_transcript", text="Correct segment", segment_index=0)
            await event(ws, "diarized_transcript", segment_index=0, words=[{"text": "Correct"}])
            await ws.wait_closed()

        async with gateway(handler) as client:
            result = await client.transcribe_segment(PCM, speaker_diarization=True)
        self.assertEqual(result.diarized.segment_index, 0)

    async def test_start_error_is_preserved(self):
        async def handler(ws):
            await event(ws, "invalid_request", error="unsupported setting")
            await ws.close(1008)

        async with gateway(handler) as client:
            with self.assertRaises(ServerError) as error:
                async with client.open():
                    self.fail("Unexpected accepted session")
            self.assertEqual((error.exception.message_type, error.exception.close_code), ("invalid_request", 1008))

    async def test_handshake_status_and_no_url_in_exception(self):
        def reject(ws, request):
            return ws.respond(401, request.path)

        async with gateway(None, process_request=reject) as client:
            with self.assertRaises(HandshakeError) as error:
                async with client.open():
                    self.fail("Unexpected accepted session")
            self.assertEqual(error.exception.status_code, 401)
            self.assertNotIn("api-key", str(error.exception))
            self.assertIsNone(error.exception.__context__)

    async def test_close_and_task_cancellation_release_socket(self):
        async def handler(ws):
            await event(ws, "session_started")
            await ws.wait_closed()

        async with gateway(handler) as client:
            async with client.open() as session:
                async with asyncio.TaskGroup() as tasks:
                    async def receive():
                        return [e.message_type async for e in session.events()]

                    reading = tasks.create_task(receive())
                    await session.close()
                self.assertEqual(reading.result(), ["session_started"])
                self.assertTrue(session.closed_by_client)
                self.assertEqual(session.close_code, 1000)

            active = asyncio.Event()

            async def consumer():
                async with client.open() as cancelled:
                    async for _ in cancelled.events():
                        active.set()

            task = asyncio.create_task(consumer())
            await asyncio.wait_for(active.wait(), 2)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 2)

    async def test_abrupt_disconnect_is_not_success(self):
        async def handler(ws):
            await event(ws, "session_started")
            await ws.recv()
            ws.transport.abort()

        async with gateway(handler) as client:
            with self.assertRaises(ConnectionLost) as error:
                await client.transcribe_segment(PCM, timeout=2)
            self.assertEqual(error.exception.close_code, 1006)

    async def test_call_drains_late_events_keeps_unindexed_timings_and_updates_merge_chain(self):
        async def handler(ws):
            await event(ws, "session_started")
            frame = json.loads(await ws.recv())
            self.assertFalse(frame["commit"])
            await event(ws, "committed_transcript", text="First", segment_index=0)
            await event(ws, "committed_transcript", text="First", segment_index=0)
            await event(ws, "diarized_transcript", segment_index=0,
                        words=[{"text": "First", "speaker_id": "speaker_2"}, {"text": " "}])
            await event(ws, "committed_transcript", text="Second", segment_index=1)
            await event(ws, "committed_transcript", text="Same words without index")
            await event(ws, "partial_transcript", text="An unfinished third turn", turn_probability=0)
            await asyncio.sleep(0.02)
            await event(ws, "committed_transcript_with_timestamps", words=[{"text": "First", "start": 0}])
            await event(ws, "speakers_merged", from_speaker_id="speaker_2", to_speaker_id="speaker_1")
            await event(ws, "future_event", payload={"keep": True})
            await event(ws, "speakers_merged", from_speaker_id="speaker_1", to_speaker_id="speaker_0")
            await ws.wait_closed()

        async def audio():
            yield PCM

        async with gateway(handler) as client:
            with redirect_stderr(io.StringIO()):
                result = await stream_call(client, audio(), drain_timeout=0.2)
        self.assertEqual(result["completion"], "unconfirmed")
        self.assertEqual(result["drain_ended_by"], "deadline")
        self.assertTrue(result["input_finished"])
        self.assertEqual([item["segment_index"] for item in result["committed"]], [0, 1, None])
        self.assertEqual([item["speaker_id"] for item in result["diarized"][0]["words"]], ["speaker_0", None])
        self.assertIsNone(result["timestamps"][0]["segment_index"])
        self.assertEqual(result["timestamps"][0]["words"][0]["start"], 0)
        self.assertEqual(result["missing_diarization_indexes"], [1])
        self.assertEqual(result["unassigned_timestamp_events"], 1)
        self.assertEqual(result["last_draft"], "An unfinished third turn")


if __name__ == "__main__":
    unittest.main()
