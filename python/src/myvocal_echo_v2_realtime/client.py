"""Async transport for the MyVocal Enterprise realtime ASR gateway."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Sequence
from urllib.parse import quote, quote_plus, urlencode

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import ConnectionClosed, ConnectionClosedError, InvalidStatus, WebSocketException
from websockets.protocol import State

from .errors import ConnectionLost, HandshakeError, IncompleteResultError, ServerError, TranscriptionTimeout
from .protocol import (
    COMMITTED,
    DIARIZED,
    ERROR_TYPES,
    SESSION_STARTED,
    TIMESTAMPS,
    AsrError,
    AsrEvent,
    AsrOptions,
    ProtocolError,
    SegmentResult,
    parse_event,
)


ENDPOINT = "wss://openapi.myvocal.ai/asr/realtime"
SAMPLE_RATE = 16_000
BYTES_PER_SECOND = SAMPLE_RATE * 2
FRAME_BYTES = 6_400  # 200 ms of 16-bit mono PCM
# WebSocket debug logs otherwise include the full request target and incoming frames.
WIRE_LOGGER = logging.Logger("myvocal_echo_v2_realtime.wire", level=logging.CRITICAL + 1)
AUTH_URL = re.compile(r"""(?:wss?://|https?://|/)[^\s"'<>]*[?&]api-key=[^\s"'<>]*""", re.IGNORECASE)


def _pcm_frame(pcm: bytes) -> None:
    if not isinstance(pcm, bytes):
        raise TypeError("Audio must be bytes containing raw PCM")
    if not pcm or len(pcm) % 2:
        raise ValueError("Audio must contain a whole, nonempty number of 16-bit PCM samples")


class RealtimeAsrSession:
    """One started session. Send and consume from separate asyncio tasks."""

    def __init__(self, socket: ClientConnection, options: AsrOptions, api_key: str, close_timeout: float) -> None:
        self._socket = socket
        self._options = options
        self._secrets = tuple(dict.fromkeys((api_key, quote(api_key, safe=""), quote_plus(api_key))))
        self._close_timeout = close_timeout
        self._ready = asyncio.Event()
        self._started = False
        self._events: asyncio.Queue[AsrEvent | None] = asyncio.Queue()
        self._consuming = False
        self._first_frame = True
        self._send_lock = asyncio.Lock()
        self._failure: AsrError | None = None
        self._error_event: AsrEvent | None = None
        self._closed_by_client = False
        self._closing: asyncio.Task[None] | None = None
        self._reader = asyncio.create_task(self._receive(), name="myvocal-asr-receive")

    @property
    def close_code(self) -> int | None:
        """WebSocket close code when available, including synthetic 1006 on a lost transport."""
        # websockets 14.0 exposes the code on the protocol used by the asyncio connection.
        return self._socket.protocol.close_code

    @property
    def closed_by_client(self) -> bool:
        """Whether the SDK initiated the WebSocket close."""
        return self._closed_by_client

    async def _wait_started(self, timeout: float) -> None:
        try:
            async with asyncio.timeout(timeout):
                await self._ready.wait()
        except TimeoutError:
            await self.close()
            self._raise_failure()
            raise HandshakeError("Timed out waiting for ASR session_started") from None
        self._raise_failure()
        if not self._started:
            raise ConnectionLost(self.close_code)

    def _redact(self, value: Any) -> Any:
        if isinstance(value, str):
            value = AUTH_URL.sub("[redacted URL]", value)
            for secret in self._secrets:
                value = value.replace(secret, "[redacted]")
            return value
        if isinstance(value, dict):
            return {key: self._redact(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self._redact(item) for item in value]
        return value

    async def _receive(self) -> None:
        try:
            async with asyncio.timeout(None) as deadline:
                async for message in self._socket:
                    event = self._decode(message)
                    if self._error_event is None and (
                        event.message_type in ERROR_TYPES or event.error is not None
                    ):
                        self._error_event = event
                        deadline.reschedule(asyncio.get_running_loop().time() + self._close_timeout)
                    if event.message_type == SESSION_STARTED and self._error_event is None:
                        self._started = True
                        self._ready.set()
                    self._events.put_nowait(event)
        except ConnectionClosedError:
            self._failure = ConnectionLost(self.close_code or 1006)
        except ProtocolError as error:
            self._failure = error
        except TimeoutError:
            # The gateway sent an error frame but didn't follow it with a close in time.
            pass
        finally:
            if self._error_event is not None:
                self._failure = ServerError(
                    self._error_event.message_type,
                    self._error_event.error if self._error_event.error is not None else "ASR gateway reported an error",
                    self.close_code,
                )
            self._events.put_nowait(None)
            self._ready.set()

    def _decode(self, message: str | bytes) -> AsrEvent:
        try:
            data = self._redact(json.loads(message))
        except (ValueError, RecursionError):
            pass
        else:
            if isinstance(data, dict):
                return parse_event(data)
        raise ProtocolError("ASR gateway event must be a JSON object")

    def _raise_failure(self) -> None:
        if self._failure is not None:
            raise self._failure

    async def events(self) -> AsyncIterator[AsrEvent]:
        """Yield events in wire order. Use one consumer per session.

        The iterator yields a server's error event before raising ServerError.
        Clean closes end iteration. Inspect close_code and closed_by_client.
        """
        if self._consuming:
            raise RuntimeError("A session supports one events() consumer at a time")
        self._consuming = True
        try:
            while (event := await self._events.get()) is not None:
                yield event
            self._events.put_nowait(None)
            self._raise_failure()
        finally:
            self._consuming = False

    async def send_audio(self, pcm: bytes) -> None:
        """Send one PCM chunk without committing."""
        await self._send(pcm, commit=False)

    async def send_final_audio(self, pcm: bytes) -> None:
        """Send the last PCM chunk of one manual segment with commit=true."""
        if self._options.commit_mode != "manual":
            raise ValueError("send_final_audio requires commit_mode='manual'")
        await self._send(pcm, commit=True)

    async def _send(self, pcm: bytes, *, commit: bool) -> None:
        _pcm_frame(pcm)
        async with self._send_lock:
            if self._reader.done() or self._error_event is not None:
                await asyncio.shield(self._reader)
                self._raise_failure()
                raise ConnectionLost(self.close_code)
            frame = {
                "message_type": "input_audio_chunk",
                "audio_base_64": base64.b64encode(pcm).decode("ascii"),
                "sample_rate": SAMPLE_RATE,
                "commit": commit,
            }
            if self._first_frame:
                if self._options.hotwords_library is not None:
                    frame["hotwords_library"] = list(self._options.hotwords_library)
                if self._options.previous_text is not None:
                    frame["previous_text"] = self._options.previous_text
                self._first_frame = False
            try:
                await self._socket.send(json.dumps(frame, ensure_ascii=False, separators=(",", ":")))
                return
            except ConnectionClosed:
                pass
            except asyncio.CancelledError:
                await self.close()
                raise
            # The reader may still be processing the error frame that preceded this close.
            await asyncio.shield(self._reader)
            self._raise_failure()
            raise ConnectionLost(self.close_code)

    async def _close(self) -> None:
        if self._error_event is not None:
            await self._reader
        if self._socket.state is State.OPEN:
            self._closed_by_client = True
        await self._socket.close()
        await self._reader

    async def close(self) -> None:
        """Release the connection. Calling close does not commit or resend audio."""
        if self._closing is None:
            self._closing = asyncio.create_task(self._close(), name="myvocal-asr-close")
        await asyncio.shield(self._closing)


class RealtimeAsrClient:
    """Connect to the enterprise gateway using an enterprise ASR key."""

    def __init__(
        self,
        api_key: str,
        *,
        endpoint: str = ENDPOINT,
        open_timeout: float = 10,
        start_timeout: float = 10,
        close_timeout: float = 3,
    ) -> None:
        if not api_key:
            raise ValueError("A MyVocal Enterprise ASR key is required")
        self._api_key = api_key
        self._endpoint = endpoint
        self._open_timeout = open_timeout
        self._start_timeout = start_timeout
        self._close_timeout = close_timeout
        self._sessions: set[RealtimeAsrSession] = set()

    async def __aenter__(self) -> RealtimeAsrClient:
        return self

    async def __aexit__(self, *exc) -> None:
        await self.close()

    async def close(self) -> None:
        await asyncio.gather(*(session.close() for session in self._sessions))

    def _url(self, options: AsrOptions) -> str:
        query = [
            ("api-key", self._api_key),
            ("model_id", "echo_v2_realtime"),
            ("audio_format", "pcm_16000"),
            ("commit_mode", options.commit_mode),
        ]
        if options.language is not None:
            query.append(("language", options.language))
        for name in ("multilingual_mode", "speaker_diarization", "word_timestamps"):
            if getattr(options, name):
                query.append((name, "true"))
        query.extend(("keyterms", term) for term in options.keyterms)
        return f"{self._endpoint}?{urlencode(query)}"

    async def _connect(self, options: AsrOptions) -> ClientConnection:
        try:
            return await connect(
                self._url(options),
                logger=WIRE_LOGGER,
                open_timeout=self._open_timeout,
                close_timeout=self._close_timeout,
            )
        except InvalidStatus as error:
            status = error.response.status_code
            failure = HandshakeError(f"ASR WebSocket handshake rejected (HTTP {status})", status)
        except (OSError, ValueError, WebSocketException) as error:
            failure = HandshakeError(f"ASR WebSocket handshake failed ({type(error).__name__})")
        # The underlying exception can contain a URL or response with the key.
        raise failure

    @asynccontextmanager
    async def open(self, options: AsrOptions | None = None) -> AsyncIterator[RealtimeAsrSession]:
        """Open one session and wait for session_started before yielding it."""
        options = options if options is not None else AsrOptions()
        socket = await self._connect(options)
        session = RealtimeAsrSession(socket, options, self._api_key, self._close_timeout)
        self._sessions.add(session)
        try:
            await session._wait_started(self._start_timeout)
            yield session
        finally:
            try:
                await session.close()
            finally:
                self._sessions.discard(session)
        session._raise_failure()

    async def transcribe_segment(
        self,
        pcm: bytes,
        *,
        word_timestamps: bool = False,
        speaker_diarization: bool = False,
        multilingual_mode: bool = False,
        language: str | None = None,
        keyterms: Sequence[str] = (),
        hotwords_library: Sequence[str] | None = None,
        previous_text: str | None = None,
        timeout: float = 30,
    ) -> SegmentResult:
        """Stream one short segment, commit once, and await all requested result events."""
        _pcm_frame(pcm)
        options = AsrOptions(
            word_timestamps=word_timestamps, speaker_diarization=speaker_diarization,
            multilingual_mode=multilingual_mode, language=language,
            keyterms=keyterms, hotwords_library=hotwords_library, previous_text=previous_text,
        )
        commit_started = asyncio.Event()
        committed: AsrEvent | None = None
        enhancements: dict[str, dict[int | None, AsrEvent]] = {TIMESTAMPS: {}, DIARIZED: {}}
        expected = [COMMITTED]
        if word_timestamps:
            expected.append(TIMESTAMPS)
        if speaker_diarization:
            expected.append(DIARIZED)

        def enhancement(kind: str) -> AsrEvent | None:
            variants = enhancements[kind]
            if committed is None or committed.segment_index is None:
                return next(iter(variants.values()), None)
            return variants.get(committed.segment_index, variants.get(None))

        def result() -> SegmentResult:
            return SegmentResult(committed, enhancement(TIMESTAMPS), enhancement(DIARIZED))

        def missing() -> tuple[str, ...]:
            available = {COMMITTED: committed, TIMESTAMPS: enhancement(TIMESTAMPS), DIARIZED: enhancement(DIARIZED)}
            return tuple(kind for kind in expected if available[kind] is None)

        async def receive(session: RealtimeAsrSession) -> None:
            nonlocal committed
            async for event in session.events():
                if not commit_started.is_set():
                    continue
                if event.message_type == COMMITTED and committed is None:
                    committed = event
                elif event.message_type in enhancements and event.message_type in expected:
                    enhancements[event.message_type].setdefault(event.segment_index, event)
                if not missing():
                    return
            raise IncompleteResultError(missing(), result(), session.close_code)

        deadline = asyncio.timeout(timeout)
        session = None
        try:
            async with deadline:
                async with self.open(options) as session:
                    send = asyncio.create_task(_send_segment(session, pcm, commit_started))
                    read = asyncio.create_task(receive(session))
                    try:
                        await asyncio.gather(send, read)
                        deadline.reschedule(None)
                    finally:
                        for task in (send, read):
                            if not task.done():
                                task.cancel()
                        await asyncio.gather(send, read, return_exceptions=True)
        except TimeoutError:
            if not deadline.expired():
                raise
            # If an error event had already arrived, it remains the cause of failure.
            if session is not None:
                session._raise_failure()
            raise TranscriptionTimeout(missing(), result()) from None
        return result()


async def _send_segment(session: RealtimeAsrSession, pcm: bytes, commit_started: asyncio.Event) -> None:
    for offset in range(0, len(pcm), FRAME_BYTES):
        frame = pcm[offset : offset + FRAME_BYTES]
        if offset + len(frame) == len(pcm):
            commit_started.set()
            await session.send_final_audio(frame)
        else:
            await session.send_audio(frame)
            await asyncio.sleep(len(frame) / BYTES_PER_SECOND)
