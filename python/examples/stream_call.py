"""Stream a bilingual call from raw 16 kHz mono PCM and save its received results."""
import argparse
import asyncio
import json
import os
import sys
from contextlib import aclosing
from dataclasses import asdict, replace
from pathlib import Path
from typing import AsyncIterable, AsyncIterator

from myvocal_echo_v2_realtime import AsrError, AsrEvent, AsrOptions, RealtimeAsrClient, RealtimeAsrSession


class CallTranscript:
    def __init__(self) -> None:
        self.committed: list[AsrEvent] = []
        self.committed_indexes: set[int] = set()
        self.diarized: dict[int, AsrEvent] = {}
        self.unassigned_diarized: list[AsrEvent] = []
        self.timestamps: list[AsrEvent] = []
        self.speaker_aliases: dict[str, str] = {}
        self.draft: AsrEvent | None = None

    def _label(self, event: AsrEvent) -> AsrEvent:
        return replace(event, words=tuple(
            replace(word, speaker_id=self.speaker_aliases.get(word.speaker_id, word.speaker_id))
            if word.speaker_id is not None else word
            for word in event.words
        ))

    def consume(self, event: AsrEvent) -> None:
        match event.message_type:
            case "partial_transcript":
                if self.draft is None or self.draft.text != event.text:
                    print(f"Draft  {event.text or ''}", file=sys.stderr)
                self.draft = self._label(event)
            case "committed_transcript":
                self.draft = None
                index = event.segment_index
                if index is None or index not in self.committed_indexes:
                    self.committed.append(event)
                    if index is not None:
                        self.committed_indexes.add(index)
                    print(f"Final  {event.text or ''}", file=sys.stderr)
            case "diarized_transcript":
                labeled = self._label(event)
                if event.segment_index is None:
                    self.unassigned_diarized.append(labeled)
                else:
                    self.diarized[event.segment_index] = labeled
                speakers = sorted({word.speaker_id for word in labeled.words if word.speaker_id is not None})
                print(f"Speakers  segment {event.segment_index}  {', '.join(speakers) or 'unresolved'}", file=sys.stderr)
            case "committed_transcript_with_timestamps":
                self.timestamps.append(event)
                print(f"Timings  segment {event.segment_index}  {len(event.words)} items", file=sys.stderr)
            case "speakers_merged":
                source, target = event.from_speaker_id, event.to_speaker_id
                if source is None or target is None:
                    return
                target = self.speaker_aliases.get(target, target)
                for alias, current in self.speaker_aliases.items():
                    if current == source:
                        self.speaker_aliases[alias] = target
                self.speaker_aliases[source] = target
                self.diarized = {index: self._label(stored) for index, stored in self.diarized.items()}
                self.unassigned_diarized = [self._label(stored) for stored in self.unassigned_diarized]
                if self.draft is not None:
                    self.draft = self._label(self.draft)
                print(f"Speaker merge  {source} → {target}", file=sys.stderr)

    def summary(self) -> dict:
        diarized = [*self.diarized.values(), *self.unassigned_diarized]
        timed_indexes = {event.segment_index for event in self.timestamps if event.segment_index is not None}
        return {
            "committed": [{"segment_index": item.segment_index, "text": item.text} for item in self.committed],
            "diarized": [
                {"segment_index": item.segment_index, "words": [asdict(word) for word in item.words]}
                for item in diarized
            ],
            "timestamps": [
                {"segment_index": item.segment_index, "words": [asdict(word) for word in item.words]}
                for item in self.timestamps
            ],
            "missing_diarization_indexes": sorted(self.committed_indexes - self.diarized.keys()),
            "committed_without_index": sum(item.segment_index is None for item in self.committed),
            "indexes_without_indexed_timestamps": sorted(self.committed_indexes - timed_indexes),
            "unassigned_timestamp_events": sum(item.segment_index is None for item in self.timestamps),
            "timestamp_event_count_shortfall": max(0, len(self.committed) - len(self.timestamps)),
            "last_draft": self.draft.text if self.draft is not None else None,
        }


async def pcm_file(path: Path) -> AsyncIterator[bytes]:
    bytes_per_second = 32_000
    with path.open("rb") as stream:
        while chunk := stream.read(6400):
            yield chunk
            await asyncio.sleep(len(chunk) / bytes_per_second)


async def stream_call(
    client: RealtimeAsrClient,
    audio: AsyncIterable[bytes],
    *,
    drain_timeout: float = 5,
) -> dict:
    """Feed an already paced PCM source, such as frames from an active call."""
    transcript = CallTranscript()
    options = AsrOptions(
        commit_mode="turn_prediction",
        multilingual_mode=True,
        speaker_diarization=True,
        word_timestamps=True,
        keyterms=["MyVocal"],
        hotwords_library=["Bishan Central"],
    )

    async def send(session: RealtimeAsrSession) -> None:
        async for chunk in audio:
            await session.send_audio(chunk)

    async def receive(session: RealtimeAsrSession) -> None:
        async for event in session.events():
            transcript.consume(event)

    async with client.open(options) as session:
        sender = asyncio.create_task(send(session))
        receiver = asyncio.create_task(receive(session))
        drain_ended_by = "peer_close"
        try:
            done, _ = await asyncio.wait((sender, receiver), return_when=asyncio.FIRST_COMPLETED)
            # Propagate error even when the two tasks finish in the same loop iteration.
            for task in done:
                task.result()
            input_finished = sender.done()
            if input_finished and not receiver.done():
                done, _ = await asyncio.wait((receiver,), timeout=drain_timeout)
                if not done:
                    drain_ended_by = "deadline"
                    await session.close()  # Keep the receiver running through the close.
                await receiver
        finally:
            for task in (sender, receiver):
                if not task.done():
                    task.cancel()
            await asyncio.gather(sender, receiver, return_exceptions=True)

    return {
        "completion": "unconfirmed",
        "input_finished": input_finished,
        "drain_ended_by": drain_ended_by,
        "close_code": session.close_code,
        **transcript.summary(),
    }


async def run(path: Path, drain_timeout: float) -> dict:
    async with RealtimeAsrClient(os.environ["MYVOCAL_API_KEY"]) as client:
        async with aclosing(pcm_file(path)) as audio:
            return await stream_call(client, audio, drain_timeout=drain_timeout)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pcm", type=Path)
    parser.add_argument("--drain-timeout", type=float, default=5)
    parser.add_argument("--output", type=Path, help="Save the received transcript and its completion status.")
    args = parser.parse_args()
    if not os.environ.get("MYVOCAL_API_KEY"):
        parser.error("Set MYVOCAL_API_KEY in the server process environment.")
    try:
        transcript = asyncio.run(run(args.pcm, args.drain_timeout))
        formatted = json.dumps(transcript, indent=2, ensure_ascii=False)
        if args.output is not None:
            args.output.write_text(formatted + "\n", encoding="utf-8")
        else:
            print(formatted)
    except (AsrError, OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
