package ai.myvocal.echo.realtime;

/** How the enterprise gateway decides that a segment is final. */
public enum CommitMode {
    MANUAL("manual"),
    VAD("vad"),
    TURN_PREDICTION("turn_prediction");

    final String wireValue;

    CommitMode(String wireValue) {
        this.wireValue = wireValue;
    }
}
