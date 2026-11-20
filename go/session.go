package asr

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Session is a single full-duplex Enterprise ASR connection.
type Session struct {
	client *Client
	ctx    context.Context
	cancel context.CancelCauseFunc
	conn   *websocket.Conn

	ready  chan struct{}
	events chan Event
	done   chan struct{}

	// The writer token protects frame order and first-frame state.
	writer       chan struct{}
	mode         CommitMode
	sentFirst    bool
	library      []string
	previousText string

	mu        sync.Mutex
	serverErr *ServerError
	err       error
	closeCode int
}

// Events delivers messages in arrival order, including session_started and
// server error events. Consume promptly to keep receiving from the gateway.
// The channel closes when the session ends.
func (s *Session) Events() <-chan Event { return s.events }

// Done closes after the session has stopped and closed the events channel.
func (s *Session) Done() <-chan struct{} { return s.done }

// Err reports the terminal error after Events closes or Done is signaled.
// A gateway error takes precedence over a later normal close.
func (s *Session) Err() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.serverErr != nil {
		failure := *s.serverErr
		failure.CloseCode = s.closeCode
		return &failure
	}
	return s.err
}

// CloseCode reports the WebSocket close code after the session ends, or zero
// if no status is available. Code 1006 indicates an abnormal loss of transport.
func (s *Session) CloseCode() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closeCode
}

// Close initiates a normal local close and releases the connection. It does not
// commit audio or wait for additional transcription events. Read Err separately.
func (s *Session) Close() error {
	s.cancel(errLocalClose)
	<-s.done
	return nil
}

// SendAudio sends an already acquired PCM chunk without committing it. Streamed
// audio should be paced by your capture source. Calls are serialized.
// If ctx cancels during a write, the session closes without replaying the frame.
func (s *Session) SendAudio(ctx context.Context, pcm []byte) error {
	return s.send(ctx, pcm, false)
}

// SendFinalAudio sends the last PCM chunk of a segment with commit=true.
// It is used with Manual mode. Wait on Events for the committed results.
func (s *Session) SendFinalAudio(ctx context.Context, pcm []byte) error {
	return s.send(ctx, pcm, true)
}

func (s *Session) send(ctx context.Context, pcm []byte, commit bool) error {
	if commit && s.mode != Manual {
		return errors.New("asr: SendFinalAudio requires manual commit mode")
	}
	if len(pcm)%2 != 0 {
		return errors.New("asr: PCM chunk must contain whole 16-bit samples")
	}
	select {
	case s.writer <- struct{}{}:
		defer func() { <-s.writer }()
	case <-s.ctx.Done():
		return s.sendError()
	case <-ctx.Done():
		return ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if s.ctx.Err() != nil || s.Err() != nil {
		return s.sendError()
	}

	msg := struct {
		Type         string   `json:"message_type"`
		Audio        string   `json:"audio_base_64"`
		SampleRate   int      `json:"sample_rate"`
		Commit       bool     `json:"commit"`
		Library      []string `json:"hotwords_library,omitempty"`
		PreviousText string   `json:"previous_text,omitempty"`
	}{
		Type: "input_audio_chunk", Audio: base64.StdEncoding.EncodeToString(pcm),
		SampleRate: SampleRate, Commit: commit,
	}
	if !s.sentFirst {
		msg.Library = s.library
		msg.PreviousText = s.previousText
	}
	stop := context.AfterFunc(ctx, func() { s.cancel(ctx.Err()) })
	err := s.conn.WriteJSON(msg)
	if !stop() {
		s.cancel(ctx.Err())
	}
	if err != nil {
		s.cancel(&TransportError{Operation: "send audio", Detail: redact(err.Error(), s.client.apiKey)})
		return s.sendError()
	}
	s.sentFirst = true
	s.library = nil
	s.previousText = ""
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return nil
}

func (s *Session) sendError() error {
	if err := s.Err(); err != nil {
		return err
	}
	if cause := context.Cause(s.ctx); cause != nil && !errors.Is(cause, errLocalClose) {
		return cause
	}
	return ErrClosed
}

func (s *Session) read() {
	transportDone := make(chan struct{})
	closeTransport := func() {
		if errors.Is(context.Cause(s.ctx), errLocalClose) {
			_ = s.conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""), time.Now().Add(time.Second))
		}
		_ = s.conn.Close()
		close(transportDone)
	}
	stop := context.AfterFunc(s.ctx, closeTransport)

	err := s.readEvents()
	if stop() {
		closeTransport()
	} else {
		<-transportDone
	}
	s.finish(err)
}

func (s *Session) readEvents() error {
	started := false
	for {
		messageKind, raw, err := s.conn.ReadMessage()
		if err != nil {
			return s.readError(err)
		}
		if messageKind != websocket.TextMessage {
			return &TransportError{Operation: "receive event", Detail: "expected a JSON text message"}
		}
		event, err := DecodeEvent(raw)
		if err != nil {
			return &TransportError{Operation: "decode event", Detail: redact(err.Error(), s.client.apiKey)}
		}
		if event.isServerError() {
			event.Error = redact(event.Error, s.client.apiKey)
			event.Message = redact(event.Message, s.client.apiKey)
			event.Raw = json.RawMessage(redact(string(event.Raw), s.client.apiKey))
			detail := event.Error
			if detail == "" {
				detail = event.Message
			}
			s.mu.Lock()
			if s.serverErr == nil {
				s.serverErr = &ServerError{Type: event.Type, Detail: detail}
			}
			s.mu.Unlock()
		}
		select {
		case s.events <- event:
		case <-s.ctx.Done():
			return s.contextError()
		}
		if event.Type == SessionStarted && !started {
			started = true
			close(s.ready)
		}
	}
}

func (s *Session) contextError() error {
	cause := context.Cause(s.ctx)
	if errors.Is(cause, errLocalClose) {
		return nil
	}
	return cause
}

func (s *Session) readError(err error) error {
	var peerClose *websocket.CloseError
	if errors.As(err, &peerClose) && (peerClose.Code != websocket.CloseAbnormalClosure || s.ctx.Err() == nil) {
		s.mu.Lock()
		s.closeCode = peerClose.Code
		s.mu.Unlock()
		if peerClose.Code == websocket.CloseNormalClosure {
			return s.contextError()
		}
		return &CloseError{Code: peerClose.Code, Reason: redact(peerClose.Text, s.client.apiKey)}
	}
	if s.ctx.Err() != nil {
		return s.contextError()
	}
	return &TransportError{Operation: "read", Detail: redact(err.Error(), s.client.apiKey)}
}

func (s *Session) finish(err error) {
	s.mu.Lock()
	s.err = err
	s.mu.Unlock()
	s.cancel(ErrClosed)
	close(s.events)
	s.client.mu.Lock()
	close(s.done)
	delete(s.client.sessions, s)
	s.client.mu.Unlock()
}
