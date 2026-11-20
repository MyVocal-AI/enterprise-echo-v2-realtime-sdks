// Stream a PCM file at capture speed or read live PCM from standard input.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go"
)

func main() {
	path := flag.String("pcm", "../samples/bilingual.pcm", "raw PCM S16LE, 16 kHz, mono; use - for stdin")
	drain := flag.Duration("drain", 4*time.Second, "maximum time to receive pending events after EOF")
	expected := flag.Int("expected-segments", 0, "result count to wait for with a known input")
	transcript := flag.String("transcript", "", "optional path for the committed transcript")
	flag.Parse()
	log.SetFlags(0)
	if *expected < 0 || *drain < 0 {
		log.Fatal("expected-segments and drain must be nonnegative")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	if err := run(ctx, *path, *drain, *expected, *transcript); err != nil {
		log.Fatal(err)
	}
}

func run(ctx context.Context, path string, drain time.Duration, expected int, transcript string) error {
	input := os.Stdin
	if path != "-" {
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		input = file
		defer input.Close()
	}
	client := asr.NewClient(os.Getenv("MYVOCAL_API_KEY"))
	defer client.Close()
	session, err := client.Open(ctx, asr.Options{
		CommitMode: asr.TurnPrediction, MultilingualMode: true, SpeakerDiarization: true,
		WordTimestamps: true, HotwordsLibrary: []string{"Bishan Central"},
	})
	if err != nil {
		return err
	}
	defer session.Close()

	audioCtx, cancelAudio := context.WithCancel(ctx)
	defer cancelAudio()
	sent := make(chan error, 1)
	go func() { sent <- sendPCM(audioCtx, session, input, path != "-") }()

	results := newCollector(os.Stdout)
	err = receiveUntilDone(session, sent, results, drain, expected)
	results.printSummary()
	if transcript != "" {
		text := strings.Join(results.lines, "\n")
		if len(results.lines) != 0 {
			text += "\n"
		}
		if writeErr := os.WriteFile(transcript, []byte(text), 0600); writeErr != nil {
			return errors.Join(err, writeErr)
		}
	}
	return err
}

func receiveUntilDone(s *asr.Session, sent <-chan error, c *collector, drain time.Duration, expected int) error {
	events := s.Events()
	var drainDeadline <-chan time.Time
	var audioErr error
	var inputComplete, expired bool
receive:
	for {
		select {
		case err := <-sent:
			sent = nil
			audioErr = err
			inputComplete = err == nil
			timer := time.NewTimer(drain)
			defer timer.Stop()
			drainDeadline = timer.C
		case event, ok := <-events:
			if !ok {
				break receive
			}
			c.add(event)
		case <-drainDeadline:
			expired = true
			break receive
		}
		if inputComplete && c.ready(expected) {
			break
		}
	}
	if sent != nil {
		select {
		case audioErr = <-sent:
			inputComplete = audioErr == nil
		default:
		}
	}
	_ = s.Close()
	for event := range events {
		c.add(event)
	}
	if err := s.Err(); err != nil {
		return err
	}
	if audioErr != nil {
		return audioErr
	}
	if expired || !inputComplete || !c.ready(expected) {
		return errors.New("tail completeness unconfirmed; keeping received results")
	}
	fmt.Fprintln(c.out, "received the requested segment results")
	return nil
}

func sendPCM(ctx context.Context, s *asr.Session, source io.Reader, pace bool) error {
	const frameSize = asr.SampleRate * 2 / 10
	buf := make([]byte, frameSize)
	for {
		n, err := io.ReadFull(source, buf)
		if n > 0 {
			if sendErr := s.SendAudio(ctx, buf[:n]); sendErr != nil {
				return sendErr
			}
		}
		if err == io.EOF || err == io.ErrUnexpectedEOF {
			return nil
		}
		if err != nil {
			return err
		}
		if pace {
			if err := waitFrame(ctx); err != nil {
				return err
			}
		}
	}
}

func waitFrame(ctx context.Context) error {
	timer := time.NewTimer(100 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
