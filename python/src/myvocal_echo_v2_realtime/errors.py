"""Failures surfaced to asyncio applications."""
from .protocol import AsrError, SegmentResult


class HandshakeError(AsrError):
    def __init__(self, message: str, status_code: int | None = None) -> None:
        self.status_code = status_code
        super().__init__(message)


class ServerError(AsrError):
    def __init__(self, message_type: str, error: str, close_code: int | None) -> None:
        self.message_type = message_type
        self.error = error
        self.close_code = close_code
        super().__init__(f"{message_type}: {error}")


class ConnectionLost(AsrError):
    def __init__(self, close_code: int | None) -> None:
        self.close_code = close_code
        super().__init__(f"ASR WebSocket disconnected (close code {close_code})")


class IncompleteResultError(AsrError):
    def __init__(self, missing: tuple[str, ...], partial_result: SegmentResult, close_code: int | None) -> None:
        self.missing = missing
        self.partial_result = partial_result
        self.close_code = close_code
        super().__init__(f"ASR ended before receiving: {', '.join(missing)} (close code {close_code})")


class TranscriptionTimeout(TimeoutError, AsrError):
    def __init__(self, missing: tuple[str, ...], partial_result: SegmentResult) -> None:
        self.missing = missing
        self.partial_result = partial_result
        super().__init__(f"ASR segment deadline expired; still waiting for: {', '.join(missing)}")
