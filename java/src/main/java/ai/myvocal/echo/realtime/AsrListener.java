package ai.myvocal.echo.realtime;

/** Callbacks run in receive order, serially on this session's callback thread. */
@FunctionalInterface
public interface AsrListener {
    void onEvent(AsrEvent event);

    default void onError(AsrException error) {}

    default void onClosed(CloseInfo close) {}
}
