package ai.myvocal.echo.realtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/** One connected enterprise ASR session. Closing releases it without committing audio. */
public final class AsrSession implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int NORMAL_CLOSE = 1000;
    private static final int ABNORMAL_CLOSE = 1006;

    private final AsrOptions options;
    private final AsrListener listener;
    private final UnaryOperator<String> redact;
    private final Consumer<AsrSession> onTerminated;
    private final Object state = new Object();
    private final ReentrantLock sendLock = new ReentrantLock();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<CloseInfo> closed = new CompletableFuture<>();
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "myvocal-asr-events");
        thread.setDaemon(true);
        return thread;
    });

    private CompletableFuture<WebSocket> opening;
    private WebSocket socket;
    private boolean firstFrame = true;
    private boolean localClosing;
    private boolean terminated;
    private boolean closeTimerStarted;
    private AsrException failure;
    private boolean errorQueued;

    AsrSession(AsrOptions options, AsrListener listener, UnaryOperator<String> redact, Consumer<AsrSession> onTerminated) {
        this.options = options;
        this.listener = listener;
        this.redact = redact;
        this.onTerminated = onTerminated;
    }

    void connect(HttpClient http, URI uri, Duration timeout) throws AsrException, InterruptedException {
        long deadline = System.nanoTime() + positiveNanos(timeout);
        synchronized (state) {
            if (!terminated) {
                opening = http.newWebSocketBuilder().connectTimeout(timeout).buildAsync(uri, new Receiver());
                opening.whenComplete((ws, error) -> {
                    if (error != null) {
                        failNow(connectionError(error));
                    }
                });
            }
        }
        try {
            ready.get(Math.max(0, deadline - System.nanoTime()), NANOSECONDS);
        } catch (TimeoutException expired) {
            var error = new AsrException(AsrException.Kind.TIMEOUT, "Timed out waiting for session_started");
            failNow(error);
            throw error;
        } catch (ExecutionException openingFailed) {
            throw (AsrException) openingFailed.getCause();
        } catch (InterruptedException cancelled) {
            close();
            throw cancelled;
        }
    }

    private AsrException connectionError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof WebSocketHandshakeException) {
            int status = ((WebSocketHandshakeException) error).getResponse().statusCode();
            return new AsrException(AsrException.Kind.HANDSHAKE, "WebSocket handshake returned HTTP " + status,
                    status, null, null, List.of(), null);
        }
        if (error instanceof java.net.http.HttpTimeoutException) {
            return new AsrException(AsrException.Kind.TIMEOUT, "WebSocket connection timed out");
        }
        return transportError("WebSocket connection failed", error);
    }

    public void sendAudio(byte[] pcm) throws AsrException, InterruptedException {
        sendAudio(pcm, SEND_TIMEOUT);
    }

    public void sendAudio(byte[] pcm, Duration timeout) throws AsrException, InterruptedException {
        send(pcm, false, timeout);
    }

    public void sendFinalAudio(byte[] pcm) throws AsrException, InterruptedException {
        sendFinalAudio(pcm, SEND_TIMEOUT);
    }

    /** Sends the final audio block and its manual commit in one WebSocket message. */
    public void sendFinalAudio(byte[] pcm, Duration timeout) throws AsrException, InterruptedException {
        send(pcm, true, timeout);
    }

    private void send(byte[] pcm, boolean commit, Duration timeout) throws AsrException, InterruptedException {
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length % 2 != 0) {
            throw new IllegalArgumentException("PCM must contain complete 16-bit samples");
        }
        long deadline = System.nanoTime() + positiveNanos(timeout);
        if (!sendLock.tryLock(timeout.toNanos(), NANOSECONDS)) {
            throw new AsrException(AsrException.Kind.TIMEOUT, "Timed out waiting to send audio");
        }
        try {
            WebSocket target;
            synchronized (state) {
                if (failure != null) {
                    throw failure;
                }
                if (localClosing || terminated) {
                    throw closingError();
                }
                target = socket;
            }
            String message = encodeAudio(pcm, commit);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AsrException(AsrException.Kind.TIMEOUT, "Timed out before sending audio");
            }
            try {
                target.sendText(message, true).get(remaining, NANOSECONDS);
                firstFrame = false;
            } catch (TimeoutException timeoutSending) {
                throw failOrReadExisting(new AsrException(AsrException.Kind.TIMEOUT, "Timed out sending audio"));
            } catch (ExecutionException | RuntimeException sendFailure) {
                throw failOrReadExisting(transportError("Audio send failed", sendFailure));
            } catch (InterruptedException cancelled) {
                close();
                throw cancelled;
            }
        } finally {
            sendLock.unlock();
        }
    }

    private String encodeAudio(byte[] pcm, boolean commit) {
        var frame = new JsonObject();
        frame.addProperty("message_type", "input_audio_chunk");
        frame.addProperty("audio_base_64", Base64.getEncoder().encodeToString(pcm));
        frame.addProperty("sample_rate", 16000);
        frame.addProperty("commit", commit);
        if (firstFrame) {
            if (options.previousText() != null) {
                frame.addProperty("previous_text", options.previousText());
            }
            if (!options.hotwordsLibrary().isEmpty()) {
                var words = new JsonArray(options.hotwordsLibrary().size());
                options.hotwordsLibrary().forEach(words::add);
                frame.add("hotwords_library", words);
            }
        }
        return JSON.toJson(frame);
    }

    /** Waits for the close callback to finish. A wait timeout leaves the session running. */
    public CloseInfo awaitClosed(Duration timeout) throws AsrException, InterruptedException {
        try {
            return closed.get(positiveNanos(timeout), NANOSECONDS);
        } catch (TimeoutException expired) {
            throw new AsrException(AsrException.Kind.TIMEOUT, "Timed out waiting for the WebSocket to close");
        } catch (ExecutionException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Override
    public void close() {
        synchronized (state) {
            if (localClosing || terminated) {
                return;
            }
            localClosing = true;
            if (socket == null) {
                finishAfterAbort();
                return;
            }
            startCloseTimer();
            socket.sendClose(NORMAL_CLOSE, "").whenComplete((ws, error) -> {
                if (error != null) {
                    finishAfterAbort();
                }
            });
        }
    }

    AsrException failure() {
        synchronized (state) {
            return failure;
        }
    }

    private void accept(WebSocket ws, String raw) {
        final AsrEvent event;
        try {
            event = AsrEvent.parse(redact.apply(raw));
        } catch (IllegalArgumentException malformed) {
            failNow(new AsrException(AsrException.Kind.PROTOCOL, "Invalid ASR event received from the gateway"));
            return;
        }
        synchronized (state) {
            if (terminated) {
                return;
            }
            callbacks.execute(() -> {
                try {
                    listener.onEvent(event);
                } catch (RuntimeException callbackError) {
                    failNow(new AsrException(AsrException.Kind.LISTENER, "ASR event listener failed"));
                } finally {
                    // Resume reads after the callback. A slow listener applies backpressure.
                    ws.request(1);
                }
            });
            if (event.isError() && failure == null) {
                failure = new AsrException(AsrException.Kind.SERVER_ERROR,
                        event.error() == null ? event.messageType() : event.messageType() + ": " + event.error(),
                        null, event, null, List.of(), null);
                notifyError();
                ready.completeExceptionally(failure);
                startCloseTimer();
            }
            if (AsrEvent.SESSION_STARTED.equals(event.messageType())) {
                ready.complete(null);
            }
        }
    }

    private AsrException failOrReadExisting(AsrException error) {
        synchronized (state) {
            if (failure != null) {
                return failure;
            }
            if (localClosing || terminated) {
                return closingError();
            }
            failNow(error);
            return error;
        }
    }

    private AsrException closingError() {
        return localClosing ? new AsrException(AsrException.Kind.CANCELLED, "ASR session was closed by the application")
                : new AsrException(AsrException.Kind.CLOSED, "ASR session is closed");
    }

    private void failNow(AsrException error) {
        synchronized (state) {
            if (terminated) {
                return;
            }
            if (!localClosing && failure == null) {
                failure = error;
            }
            finishAfterAbort();
        }
    }

    private void startCloseTimer() {
        if (!closeTimerStarted) {
            closeTimerStarted = true;
            CompletableFuture.delayedExecutor(2, SECONDS).execute(this::finishAfterAbort);
        }
    }

    private void finishAfterAbort() {
        synchronized (state) {
            if (terminated) {
                return;
            }
            finish(null, "");
            if (socket != null) {
                socket.abort();
            } else if (opening != null) {
                opening.cancel(true);
            }
        }
    }

    private void notifyError() {
        if (failure != null && !errorQueued) {
            errorQueued = true;
            AsrException error = failure;
            callbacks.execute(() -> {
                try {
                    listener.onError(error);
                } catch (RuntimeException ignored) {
                    // Always proceed to the close callback, even if the application throws here.
                }
            });
        }
    }

    private void finish(Integer code, String reason) {
        synchronized (state) {
            if (terminated) {
                return;
            }
            terminated = true;
            if (code != null && code != NORMAL_CLOSE && failure == null) {
                failure = new AsrException(AsrException.Kind.TRANSPORT, "WebSocket closed with code " + code);
            }
            if (failure != null && code != null) {
                failure = failure.withCloseCode(code);
            }
            CloseInfo.Kind kind;
            if (failure != null) {
                kind = failure.kind() == AsrException.Kind.SERVER_ERROR
                        ? CloseInfo.Kind.SERVER_ERROR : CloseInfo.Kind.TRANSPORT_ERROR;
            } else {
                kind = localClosing ? CloseInfo.Kind.LOCAL : CloseInfo.Kind.REMOTE;
            }
            var info = new CloseInfo(kind, code, reason, failure);
            ready.completeExceptionally(failure != null ? failure : closingError().withCloseCode(code));
            notifyError();
            callbacks.execute(() -> {
                try {
                    listener.onClosed(info);
                } catch (RuntimeException ignored) {
                    // The session has already terminated.
                } finally {
                    closed.complete(info);
                }
            });
            callbacks.shutdown();
            onTerminated.accept(this);
        }
    }

    static long positiveNanos(Duration timeout) {
        long nanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (nanos <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return nanos;
    }

    private static AsrException transportError(String operation, Throwable error) {
        return new AsrException(AsrException.Kind.TRANSPORT,
                operation + " (" + error.getClass().getSimpleName() + ")");
    }

    private final class Receiver implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            synchronized (state) {
                if (terminated) {
                    ws.abort();
                    return;
                }
                socket = ws;
                opening = null;
            }
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence fragment, boolean last) {
            message.append(fragment);
            if (last) {
                String raw = message.toString();
                message.setLength(0);
                accept(ws, raw);
            } else {
                ws.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            failNow(new AsrException(AsrException.Kind.PROTOCOL, "Expected a text WebSocket message"));
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (status == ABNORMAL_CLOSE) {
                failNow(new AsrException(AsrException.Kind.TRANSPORT, "WebSocket disconnected without a close frame"));
            } else {
                finish(status, redact.apply(reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            failNow(transportError("WebSocket transport failed", error));
        }
    }
}
