"""Transcribe one short headerless 16 kHz mono PCM recording."""
import argparse
import asyncio
import os
from pathlib import Path

from myvocal_echo_v2_realtime import AsrError, RealtimeAsrClient


async def transcribe(path: Path, word_timestamps: bool, timeout: float) -> None:
    async with RealtimeAsrClient(os.environ["MYVOCAL_API_KEY"]) as client:
        result = await client.transcribe_segment(
            path.read_bytes(), language="en", word_timestamps=word_timestamps, timeout=timeout
        )
    print(result.text)
    for word in result.words:
        print(f"{word.text!r}\t{word.start}\t{word.end}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pcm", type=Path)
    parser.add_argument("--no-word-timestamps", action="store_true")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()
    if not os.environ.get("MYVOCAL_API_KEY"):
        parser.error("Set MYVOCAL_API_KEY in the server process environment.")
    try:
        asyncio.run(transcribe(args.pcm, not args.no_word_timestamps, args.timeout))
    except (AsrError, OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
