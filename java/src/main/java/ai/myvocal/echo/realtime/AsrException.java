package ai.myvocal.echo.realtime;

import java.io.IOException;
import java.util.List;

/** A handshake, server, connection, listener or operation failure without an authenticated URL. */
public final class AsrException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Kind { HANDSHAKE, SERVER_ERROR, TRANSPORT, PROTOCOL, LISTENER, TIMEOUT, CLOSED, CANCELLED }

    private final Kind kind;
    private final Integer httpStatus;
    private final transient AsrEvent serverEvent;
    private final Integer closeCode;
    private final List<String> missingEvents;
    private final transient SegmentResult partialResult;

    AsrException(Kind kind, String message) {
        this(kind, message, null, null, null, List.of(), null);
    }

    AsrException(Kind kind, String message, Integer httpStatus, AsrEvent serverEvent, Integer closeCode,
                 List<String> missingEvents, SegmentResult partialResult) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.serverEvent = serverEvent;
        this.closeCode = closeCode;
        this.missingEvents = List.copyOf(missingEvents);
        this.partialResult = partialResult;
    }

    AsrException withProgress(List<String> missing, SegmentResult partial) {
        String detail = missing.isEmpty() ? getMessage() : getMessage() + ". Missing events: " + String.join(", ", missing);
        return new AsrException(kind, detail, httpStatus, serverEvent, closeCode, missing, partial);
    }

    AsrException withCloseCode(Integer code) {
        return new AsrException(kind, getMessage(), httpStatus, serverEvent, code, missingEvents, partialResult);
    }

    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public AsrEvent serverEvent() { return serverEvent; }
    public Integer closeCode() { return closeCode; }
    public List<String> missingEvents() { return missingEvents; }
    public SegmentResult partialResult() { return partialResult; }
}
