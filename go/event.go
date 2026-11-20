// Package asr connects to the MyVocal Enterprise echo_v2_realtime service.
package asr

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
)

// MessageType is the gateway's message_type value.
type MessageType string

const (
	SessionStarted                    MessageType = "session_started"
	PartialTranscript                 MessageType = "partial_transcript"
	CommittedTranscript               MessageType = "committed_transcript"
	DiarizedTranscript                MessageType = "diarized_transcript"
	CommittedTranscriptWithTimestamps MessageType = "committed_transcript_with_timestamps"
	SpeakersMerged                    MessageType = "speakers_merged"
)

// Character holds an optional time interval in seconds.
type Character struct {
	Text  string   `json:"text"`
	Start *float64 `json:"start"`
	End   *float64 `json:"end"`
}

// Word is a word, a spacing token, or another token provided by the gateway.
// Nil fields indicate a missing or null value. A non-nil pointer can hold zero.
type Word struct {
	Text         string      `json:"text"`
	Start        *float64    `json:"start"`
	End          *float64    `json:"end"`
	SpeakerID    *string     `json:"speaker_id"`
	Type         string      `json:"type"`
	LogProb      *float64    `json:"logprob"`
	ChannelIndex *int        `json:"channel_index"`
	Characters   []Character `json:"characters"`
}

// Event contains the documented fields and an independent copy of the JSON
// payload. For unknown types use Type and Raw. Error messages received on a
// live session are redacted if they contain credentials or an authentication URL.
type Event struct {
	Type            MessageType     `json:"message_type"`
	SessionID       *string         `json:"session_id"`
	Config          json.RawMessage `json:"config"`
	Text            string          `json:"text"`
	Words           []Word          `json:"words"`
	SegmentIndex    *int            `json:"segment_index"`
	TurnProbability *float64        `json:"turn_probability"`
	SpeakerID       *string         `json:"speaker_id"`
	FromSpeakerID   *string         `json:"from_speaker_id"`
	ToSpeakerID     *string         `json:"to_speaker_id"`
	LanguageCode    *string         `json:"language_code"`
	Error           string          `json:"error"`
	Message         string          `json:"message"`
	Raw             json.RawMessage `json:"-"`
}

// DecodeEvent decodes a gateway message saved for replay or testing.
func DecodeEvent(raw []byte) (Event, error) {
	var event Event
	var envelope struct {
		Type MessageType `json:"message_type"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return event, err
	}
	if envelope.Type == "" {
		return event, errors.New("asr: missing message_type")
	}
	event.Type = envelope.Type
	switch event.Type {
	case SessionStarted, PartialTranscript, CommittedTranscript, DiarizedTranscript,
		CommittedTranscriptWithTimestamps, SpeakersMerged:
		if err := json.Unmarshal(raw, &event); err != nil {
			return Event{}, err
		}
	default:
		if event.isServerError() {
			var failure struct {
				Error   string `json:"error"`
				Message string `json:"message"`
			}
			if err := json.Unmarshal(raw, &failure); err != nil {
				return Event{}, err
			}
			event.Error, event.Message = failure.Error, failure.Message
		}
	}
	event.Raw = bytes.Clone(raw)
	return event, nil
}

func (event Event) isServerError() bool {
	switch event.Type {
	case "error", "invalid_request", "unaccepted_terms":
		return true
	default:
		return strings.HasSuffix(string(event.Type), "_error")
	}
}
