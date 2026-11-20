package ai.myvocal.echo.realtime;

/** How a WebSocket session ended. The close code is nullable if no close frame was received. */
public final class CloseInfo {
    public enum Kind { LOCAL, REMOTE, SERVER_ERROR, TRANSPORT_ERROR }

    private final Kind kind;
    private final Integer code;
    private final String reason;
    private final AsrException error;

    CloseInfo(Kind kind, Integer code, String reason, AsrException error) {
        this.kind = kind;
        this.code = code;
        this.reason = reason;
        this.error = error;
    }

    public Kind kind() { return kind; }
    public Integer code() { return code; }
    public String reason() { return reason; }
    public AsrException error() { return error; }
}
