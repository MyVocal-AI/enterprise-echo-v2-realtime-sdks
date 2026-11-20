package asr

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strings"
)

var (
	// ErrClosed is returned by writes on a gracefully closed session and
	// by Open after closing the client.
	ErrClosed     = errors.New("asr: closed")
	errLocalClose = errors.New("asr: local close")
	errNotStarted = errors.New("asr: connection closed before session_started")
)

// HandshakeError is an HTTP rejection before the WebSocket session starts.
type HandshakeError struct {
	StatusCode int
}

func (e *HandshakeError) Error() string {
	return fmt.Sprintf("asr: WebSocket handshake returned HTTP %d", e.StatusCode)
}

// ServerError is an error event sent by the gateway. CloseCode is zero
// until a close status is available.
type ServerError struct {
	Type      MessageType
	Detail    string
	CloseCode int
}

func (e *ServerError) Error() string {
	if e.Detail == "" {
		return fmt.Sprintf("asr: %s", e.Type)
	}
	return fmt.Sprintf("asr: %s: %s", e.Type, e.Detail)
}

// CloseError represents a remote non-normal close or connection loss. Code
// 1006 is the WebSocket library's indication that no close frame was received.
type CloseError struct {
	Code   int
	Reason string
}

func (e *CloseError) Error() string {
	if e.Reason == "" {
		return fmt.Sprintf("asr: WebSocket closed (%d)", e.Code)
	}
	return fmt.Sprintf("asr: WebSocket closed (%d): %s", e.Code, e.Reason)
}

// TransportError describes an I/O or protocol failure without credentials.
type TransportError struct {
	Operation string
	Detail    string
}

func (e *TransportError) Error() string {
	return fmt.Sprintf("asr: %s: %s", e.Operation, e.Detail)
}

// IncompleteError accompanies a partially collected segment. A nil Cause
// means the peer closed normally before every required event arrived.
type IncompleteError struct {
	Missing []MessageType
	Cause   error
}

func (e *IncompleteError) Error() string {
	kinds := make([]string, len(e.Missing))
	for i, kind := range e.Missing {
		kinds[i] = string(kind)
	}
	msg := "asr: incomplete segment; missing " + strings.Join(kinds, ", ")
	if e.Cause != nil {
		msg += ": " + e.Cause.Error()
	}
	return msg
}

func (e *IncompleteError) Unwrap() error { return e.Cause }

var authURL = regexp.MustCompile(`(?i)(?:wss?|https?)://[^\s"'<>]*[?&]api-key=[^\s"'<>]*`)
var authQuery = regexp.MustCompile(`(?i)api-key=[^\s"'<>;&]*`)

func redact(text, key string) string {
	text = authURL.ReplaceAllString(text, "[redacted URL]")
	text = authQuery.ReplaceAllString(text, "[redacted credential]")
	if key != "" {
		jsonKey, _ := json.Marshal(key)
		keyForms := []string{key, url.QueryEscape(key), string(jsonKey[1 : len(jsonKey)-1])}
		for _, form := range keyForms {
			text = strings.ReplaceAll(text, form, "[redacted]")
		}
	}
	return text
}
