package asr

import (
	"context"
	"errors"
	"time"
)

// Result contains the events collected from a single isolated manual commit.
// Words are the timestamp tokens. Speaker labels are available separately in
// Diarized.Words. The events retain their own segment indexes and raw payloads.
type Result struct {
	Text       string
	Words      []Word
	Committed  *Event
	Timestamps *Event
	Diarized   *Event
	CloseCode  int
}

// TranscribeSegment sends a short raw PCM recording in real time and commits
// its last chunk once. It forces Manual mode and returns after receiving the
// final text and all requested enhancements. If the session ends first it
// returns any collected results and an IncompleteError.
func (c *Client) TranscribeSegment(ctx context.Context, pcm []byte, opts Options) (Result, error) {
	if len(pcm) == 0 || len(pcm)%2 != 0 {
		return Result{}, errors.New("asr: recording must contain complete 16-bit PCM samples")
	}
	opts.CommitMode = Manual
	session, err := c.Open(ctx, opts)
	if err != nil {
		return Result{}, err
	}
	defer session.Close()

	sent := make(chan error, 1)
	go func() { sent <- sendRecording(session, pcm) }()

	var result Result
	events := session.Events()
	var sendErr error
	var sendDone bool
	for events != nil || !sendDone {
		select {
		case err := <-sent:
			sendErr, sendDone = err, true
			sent = nil
		case e, ok := <-events:
			if ok {
				result.add(e)
			} else {
				events = nil
			}
		}
		if sendDone && sendErr == nil && session.Err() == nil && len(result.missing(opts)) == 0 {
			_ = session.Close()
			return result.withCloseCode(session), session.Err()
		}
	}

	cause := session.Err()
	if cause == nil {
		cause = sendErr
	}
	if missing := result.missing(opts); len(missing) != 0 {
		if errors.Is(cause, ErrClosed) {
			cause = nil
		}
		cause = &IncompleteError{Missing: missing, Cause: cause}
	}
	return result.withCloseCode(session), cause
}

func (r *Result) add(event Event) {
	switch event.Type {
	case CommittedTranscript:
		if r.Committed == nil {
			r.Committed, r.Text = &event, event.Text
			if differentSegments(r.Timestamps, &event) {
				r.Timestamps, r.Words = nil, nil
			}
			if differentSegments(r.Diarized, &event) {
				r.Diarized = nil
			}
		}
	case CommittedTranscriptWithTimestamps:
		if r.Timestamps == nil && !differentSegments(r.Committed, &event) {
			r.Timestamps, r.Words = &event, event.Words
		}
	case DiarizedTranscript:
		if r.Diarized == nil && !differentSegments(r.Committed, &event) {
			r.Diarized = &event
		}
	}
}

func differentSegments(a, b *Event) bool {
	return a != nil && b != nil && a.SegmentIndex != nil && b.SegmentIndex != nil &&
		*a.SegmentIndex != *b.SegmentIndex
}

func (r Result) missing(opts Options) []MessageType {
	var missing []MessageType
	if r.Committed == nil {
		missing = append(missing, CommittedTranscript)
	}
	if (opts.WordTimestamps || opts.IncludeLanguageDetection) && r.Timestamps == nil {
		missing = append(missing, CommittedTranscriptWithTimestamps)
	}
	if opts.SpeakerDiarization && r.Diarized == nil {
		missing = append(missing, DiarizedTranscript)
	}
	return missing
}

func (r Result) withCloseCode(s *Session) Result {
	r.CloseCode = s.CloseCode()
	return r
}

func sendRecording(s *Session, pcm []byte) error {
	const frameSize = SampleRate * 2 / 10
	for len(pcm) > frameSize {
		if err := s.SendAudio(s.ctx, pcm[:frameSize]); err != nil {
			return err
		}
		pcm = pcm[frameSize:]
		timer := time.NewTimer(100 * time.Millisecond)
		select {
		case <-s.ctx.Done():
			timer.Stop()
			return s.sendError()
		case <-timer.C:
		}
	}
	return s.SendFinalAudio(s.ctx, pcm)
}
