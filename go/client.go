package asr

import (
	"context"
	"errors"
	"net"
	"sync"

	"github.com/gorilla/websocket"
)

const endpoint = "wss://openapi.myvocal.ai/asr/realtime"

// Client creates independent ASR sessions. It is safe for concurrent use.
type Client struct {
	apiKey   string
	endpoint string

	mu       sync.Mutex
	closed   bool
	sessions map[*Session]struct{}
}

// NewClient creates an Enterprise ASR client. Obtain apiKey on your server,
// for example from the MYVOCAL_API_KEY environment variable.
func NewClient(apiKey string) *Client {
	return &Client{apiKey: apiKey, endpoint: endpoint, sessions: make(map[*Session]struct{})}
}

// Open connects and waits for session_started. The context controls the whole
// session lifetime, including the initial handshake. Read Events in parallel
// with audio sends. Open copies the first-frame library.
func (c *Client) Open(ctx context.Context, opts Options) (*Session, error) {
	if c.apiKey == "" {
		return nil, errors.New("asr: missing enterprise API key")
	}
	if opts.CommitMode == "" {
		opts.CommitMode = Manual
	}

	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, ErrClosed
	}
	sessionCtx, cancel := context.WithCancelCause(ctx)
	s := &Session{
		client: c, ctx: sessionCtx, cancel: cancel,
		events: make(chan Event, 64), ready: make(chan struct{}), done: make(chan struct{}),
		writer: make(chan struct{}, 1), mode: opts.CommitMode,
		library: append([]string(nil), opts.HotwordsLibrary...), previousText: opts.PreviousText,
	}
	c.sessions[s] = struct{}{}
	c.mu.Unlock()

	conn, err := c.dial(sessionCtx, opts)
	if err != nil {
		s.finish(err)
		return nil, err
	}
	s.conn = conn
	go s.read()

	select {
	case <-s.ready:
		if err := ctx.Err(); err != nil {
			_ = s.Close()
			return nil, err
		}
		return s, nil
	case <-s.done:
		select {
		case <-s.ready:
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			return s, nil
		default:
		}
		if err := s.Err(); err != nil {
			return nil, err
		}
		if errors.Is(context.Cause(sessionCtx), errLocalClose) {
			return nil, ErrClosed
		}
		return nil, errNotStarted
	}
}

func (c *Client) dial(ctx context.Context, opts Options) (*websocket.Conn, error) {
	dialer := *websocket.DefaultDialer
	network := &net.Dialer{}
	// The WebSocket dialer applies deadlines. Also interrupt the HTTP upgrade
	// itself when the parent context is canceled without a deadline.
	var stop func() bool
	dialer.NetDialContext = func(dialCtx context.Context, networkName, address string) (net.Conn, error) {
		conn, err := network.DialContext(dialCtx, networkName, address)
		if err == nil {
			stop = context.AfterFunc(ctx, func() { _ = conn.Close() })
		}
		return conn, err
	}
	conn, response, err := dialer.DialContext(ctx, c.endpoint+"?"+opts.query(c.apiKey).Encode(), nil)
	if stop != nil {
		stop()
	}
	if err == nil {
		return conn, nil
	}
	if response != nil {
		_ = response.Body.Close()
	}
	if cause := context.Cause(ctx); cause != nil {
		if errors.Is(cause, errLocalClose) {
			return nil, ErrClosed
		}
		return nil, cause
	}
	if response != nil {
		return nil, &HandshakeError{StatusCode: response.StatusCode}
	}
	return nil, &TransportError{Operation: "connect", Detail: redact(err.Error(), c.apiKey)}
}

// Close closes the client and releases all active and connecting sessions.
// It is safe to call more than once.
func (c *Client) Close() error {
	c.mu.Lock()
	c.closed = true
	sessions := make([]*Session, 0, len(c.sessions))
	for s := range c.sessions {
		sessions = append(sessions, s)
		s.cancel(errLocalClose)
	}
	c.mu.Unlock()

	for _, s := range sessions {
		<-s.done
	}
	return nil
}
