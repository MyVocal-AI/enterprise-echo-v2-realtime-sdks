"""Protocol fields for the MyVocal Enterprise realtime ASR gateway."""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Literal, Mapping, Sequence


CommitMode = Literal["manual", "turn_prediction", "vad"]
SESSION_STARTED = "session_started"
COMMITTED = "committed_transcript"
TIMESTAMPS = "committed_transcript_with_timestamps"
DIARIZED = "diarized_transcript"
ERROR_TYPES = frozenset({
    "error", "auth_error", "input_error", "invalid_request", "quota_exceeded", "quota_exceeded_error",
    "transcriber_error", "commit_throttled", "unaccepted_terms", "rate_limited", "queue_overflow",
    "resource_exhausted", "session_time_limit_exceeded", "chunk_size_exceeded", "insufficient_audio_activity",
})


@dataclass(frozen=True, slots=True)
class AsrOptions:
    commit_mode: CommitMode = "manual"
    language: str | None = None
    multilingual_mode: bool = False
    speaker_diarization: bool = False
    word_timestamps: bool = False
    keyterms: Sequence[str] = ()
    hotwords_library: Sequence[str] | None = None
    previous_text: str | None = None

    def __post_init__(self) -> None:
        if self.commit_mode not in ("manual", "turn_prediction", "vad"):
            raise ValueError("commit_mode must be manual, turn_prediction, or vad")
        # Snapshot mutable inputs now. The gateway checks vocabulary limits and returns its own errors.
        for name in ("keyterms", "hotwords_library"):
            terms = getattr(self, name)
            if terms is not None:
                if isinstance(terms, (str, bytes)):
                    raise TypeError(f"{name} must be a sequence of strings")
                object.__setattr__(self, name, tuple(terms))


@dataclass(frozen=True, slots=True)
class Word:
    text: str
    start: float | None
    end: float | None
    speaker_id: str | None
    type: str | None


@dataclass(frozen=True, slots=True)
class AsrEvent:
    message_type: str
    raw: dict[str, Any]
    text: str | None = None
    turn_probability: float | None = None
    segment_index: int | None = None
    words: tuple[Word, ...] = ()
    from_speaker_id: str | None = None
    to_speaker_id: str | None = None
    session_id: str | None = None
    config: dict[str, Any] | None = None
    language_code: str | None = None
    error: str | None = None


class AsrError(Exception):
    """Base class for SDK and gateway failures."""


class ProtocolError(AsrError):
    """The gateway sent an invalid event envelope."""


def parse_event(message: str | bytes | Mapping[str, Any]) -> AsrEvent:
    """Decode a gateway event or a recorded payload, including unknown types."""
    if isinstance(message, Mapping):
        data = dict(message)
    else:
        try:
            data = json.loads(message)
        except (ValueError, RecursionError):
            raise ProtocolError("Invalid JSON from the ASR gateway") from None
    if not isinstance(data, dict) or not isinstance(data.get("message_type"), str) or not data["message_type"]:
        raise ProtocolError("ASR event is missing its message_type")

    items = data.get("words")
    # Preserve original entries in raw, including fields beyond the portable typed view.
    words = ()
    if isinstance(items, list):
        words = tuple(
            Word(word["text"], word.get("start"), word.get("end"), word.get("speaker_id"), word.get("type"))
            for word in items
            if isinstance(word, dict) and isinstance(word.get("text"), str)
        )
    error = data.get("error")
    if not isinstance(error, str) and data["message_type"] in ERROR_TYPES:
        error = data.get("message")
    return AsrEvent(
        message_type=data["message_type"],
        raw=data,
        text=data.get("text"),
        turn_probability=data.get("turn_probability"),
        segment_index=data.get("segment_index"),
        words=words,
        from_speaker_id=data.get("from_speaker_id"),
        to_speaker_id=data.get("to_speaker_id"),
        session_id=data.get("session_id"),
        config=data.get("config"),
        language_code=data.get("language_code"),
        error=error if isinstance(error, str) else None,
    )


@dataclass(frozen=True, slots=True)
class SegmentResult:
    committed: AsrEvent | None
    timestamps: AsrEvent | None = None
    diarized: AsrEvent | None = None

    @property
    def text(self) -> str | None:
        return self.committed.text if self.committed is not None else None

    @property
    def words(self) -> tuple[Word, ...]:
        return self.timestamps.words if self.timestamps is not None else ()
