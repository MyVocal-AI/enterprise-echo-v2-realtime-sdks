package asr

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const fixtureKey = "fixture-Key +/&=中文"

func mockClient(t *testing.T, handler func(*websocket.Conn, url.Values)) *Client {
	t.Helper()
	var handlers sync.WaitGroup
	t.Cleanup(handlers.Wait)
	peer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlers.Add(1)
		defer handlers.Done()
		upgrader := websocket.Upgrader{}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		defer conn.Close()
		_ = conn.SetReadDeadline(time.Now().Add(4 * time.Second))
		handler(conn, r.URL.Query())
	}))
	t.Cleanup(peer.Close)
	c := NewClient(fixtureKey)
	c.endpoint = strings.Replace(peer.URL, "http://", "ws://", 1) + "/asr/realtime"
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func deadline(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func reply(t *testing.T, c *websocket.Conn, data string) {
	t.Helper()
	if err := c.WriteMessage(websocket.TextMessage, []byte(data)); err != nil {
		t.Error(err)
	}
}

func finish(t *testing.T, c *websocket.Conn, code int) {
	t.Helper()
	err := c.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, ""), time.Now().Add(time.Second))
	if err != nil {
		t.Error(err)
	}
	_, _, _ = c.ReadMessage()
}

func receiveFrame(t *testing.T, conn *websocket.Conn) map[string]json.RawMessage {
	t.Helper()
	var frame map[string]json.RawMessage
	if err := conn.ReadJSON(&frame); err != nil {
		t.Error(err)
	}
	return frame
}

func TestPublishedFixture(t *testing.T) {
	raw, err := os.ReadFile("testdata/sdk-event.json")
	if err != nil {
		t.Fatal(err)
	}
	event, err := DecodeEvent(raw)
	if err != nil {
		t.Fatal(err)
	}
	var want struct {
		Words []Word `json:"words"`
	}
	if err := json.Unmarshal(raw, &want); err != nil {
		t.Fatal(err)
	}
	if event.Type != CommittedTranscriptWithTimestamps || event.SegmentIndex != nil || !bytes.Equal(event.Raw, raw) ||
		len(event.Words) != 27 || !reflect.DeepEqual(event.Words, want.Words) || event.Words[1].Text != " " {
		t.Fatalf("fixture mismatch: type=%q words=%d", event.Type, len(event.Words))
	}
	word := event.Words[2]
	if word.Text != "order" || *word.Start != 0.28 || *word.End != 0.50 || word.SpeakerID != nil {
		t.Fatalf("wrong order token: %+v", word)
	}
}

func TestFirstFramePerSessionAndRoundTripOptions(t *testing.T) {
	keyterms := make([]string, 50)
	hotwords := make([]string, 10000)
	for i := range keyterms {
		keyterms[i] = fmt.Sprintf("term /+& #%d", i)
	}
	for i := range hotwords {
		hotwords[i] = strings.Repeat("🚉", 48) + string(rune(0x1F400+i/100)) + string(rune(0x1F400+i%100))
	}
	client := mockClient(t, func(ws *websocket.Conn, q url.Values) {
		want := map[string]string{"api-key": fixtureKey, "model_id": "echo_v2_realtime", "audio_format": "pcm_16000", "commit_mode": "turn_prediction", "language": "en", "multilingual_mode": "true", "speaker_diarization": "true", "word_timestamps": "true"}
		for k, v := range want {
			if q.Get(k) != v {
				t.Errorf("incorrect query parameter %q", k)
			}
		}
		if !reflect.DeepEqual(q["keyterms"], keyterms) || q.Has("previous_text") || q.Has("hotwords_library") {
			t.Error("wrong Query placement")
		}
		reply(t, ws, `{"message_type":"session_started","session_id":"a","config":{"model_id":"echo_v2_realtime"}}`)
		first := receiveFrame(t, ws)
		var previous string
		_ = json.Unmarshal(first["previous_text"], &previous)
		if string(first["message_type"]) != `"input_audio_chunk"` || string(first["sample_rate"]) != "16000" ||
			string(first["commit"]) != "false" || string(first["audio_base_64"]) != `"AAE="` ||
			previous != "Order & delivery" {
			t.Error("unexpected first-frame envelope")
		}
		var library []string
		if err := json.Unmarshal(first["hotwords_library"], &library); err != nil || !reflect.DeepEqual(library, hotwords) {
			t.Error("maximum-library data did not round trip")
		}
		second := receiveFrame(t, ws)
		if second["hotwords_library"] != nil || second["previous_text"] != nil || string(second["commit"]) != "false" {
			t.Error("unexpected second-frame fields")
		}
		finish(t, ws, 1000)
	})
	ctx := deadline(t)
	for i := 0; i < 2; i++ {
		session, err := client.Open(ctx, Options{
			CommitMode: TurnPrediction, Language: "en", MultilingualMode: true,
			SpeakerDiarization: true, WordTimestamps: true, Keyterms: keyterms,
			HotwordsLibrary: hotwords, PreviousText: "Order & delivery",
		})
		if err != nil {
			t.Fatal(err)
		}
		if event := <-session.Events(); event.Type != SessionStarted || event.SessionID == nil || *event.SessionID != "a" {
			t.Fatalf("expected session_started, got %q", event.Type)
		}
		for j := 0; j < 2; j++ {
			if err := session.SendAudio(ctx, []byte{0, 1}); err != nil {
				t.Fatal(err)
			}
		}
		for range session.Events() {
		}
		if session.Err() != nil || session.CloseCode() != 1000 {
			t.Fatalf("clean close: %v code=%d", session.Err(), session.CloseCode())
		}
	}
}

const final = `{"message_type":"committed_transcript","segment_index":0,"text":"Hello"}`
const timed = `{"message_type":"committed_transcript_with_timestamps","text":"Hello","words":[{"text":"Hello","start":0,"end":0.4,"speaker_id":null}]}`
const diarized = `{"message_type":"diarized_transcript","segment_index":0,"words":[{"text":"Hello","speaker_id":"speaker_0"}]}`

func TestTranscribeSegmentWaitsForEnhancements(t *testing.T) {
	for _, order := range [][2]string{{timed, diarized}, {diarized, timed}} {
		c := mockClient(t, func(ws *websocket.Conn, q url.Values) {
			if q.Get("commit_mode") != "manual" {
				t.Error("segment helper failed to select manual")
			}
			reply(t, ws, `{"message_type":"session_started"}`)
			frameStart := time.Now()
			if f := receiveFrame(t, ws); string(f["commit"]) != "false" {
				t.Error("premature commit")
			}
			if f := receiveFrame(t, ws); string(f["commit"]) != "true" || string(f["audio_base_64"]) != `"AAE="` {
				t.Error("last PCM chunk was not committed")
			}
			if time.Since(frameStart) < 80*time.Millisecond {
				t.Error("PCM frames were not paced")
			}
			reply(t, ws, final)
			time.Sleep(25 * time.Millisecond)
			reply(t, ws, order[0])
			time.Sleep(25 * time.Millisecond)
			reply(t, ws, order[1])
			if _, _, err := ws.ReadMessage(); !websocket.IsCloseError(err, 1000) {
				t.Errorf("expected client close after one commit, got %v", err)
			}
		})
		pcm := append(make([]byte, 3200), 0, 1)
		got, err := c.TranscribeSegment(deadline(t), pcm, Options{
			CommitMode: VAD, WordTimestamps: true, SpeakerDiarization: true,
		})
		if err != nil {
			t.Fatal(err)
		}
		if got.Text != "Hello" || len(got.Words) != 1 || got.Words[0].Start == nil || *got.Words[0].Start != 0 ||
			got.Words[0].SpeakerID != nil || got.Diarized == nil || len(got.Diarized.Words) != 1 ||
			*got.Diarized.Words[0].SpeakerID != "speaker_0" || *got.Diarized.SegmentIndex != 0 ||
			got.Timestamps.SegmentIndex != nil {
			t.Fatalf("unexpected segment: %+v", got)
		}
	}
}

func TestMissingEnhancementsAndServerFailureAreNotSuccess(t *testing.T) {
	for _, scenario := range []string{"normal-close", "server-error", "deadline", "abrupt"} {
		t.Run(scenario, func(t *testing.T) {
			c := mockClient(t, func(ws *websocket.Conn, _ url.Values) {
				reply(t, ws, `{"message_type":"session_started"}`)
				receiveFrame(t, ws)
				reply(t, ws, final)
				switch scenario {
				case "normal-close":
					finish(t, ws, 1000)
				case "server-error":
					reply(t, ws, `{"message_type":"input_error","error":"bad hotword"}`)
					finish(t, ws, 1000)
				case "deadline":
					_, _, _ = ws.ReadMessage()
				}
			})
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			result, err := c.TranscribeSegment(ctx, []byte{0, 1}, Options{WordTimestamps: true})
			var incomplete *IncompleteError
			if !errors.As(err, &incomplete) || result.Text != "Hello" ||
				!reflect.DeepEqual(incomplete.Missing, []MessageType{CommittedTranscriptWithTimestamps}) {
				t.Fatalf("expected text and missing timestamps, got %+v / %v", result, err)
			}
			var serverErr *ServerError
			var closeErr *CloseError
			switch scenario {
			case "server-error":
				if !errors.As(err, &serverErr) || serverErr.Type != "input_error" ||
					serverErr.Detail != "bad hotword" || result.CloseCode != 1000 {
					t.Fatalf("lost server error or close code: %v / %d", err, result.CloseCode)
				}
			case "deadline":
				if !errors.Is(err, context.DeadlineExceeded) {
					t.Fatalf("lost deadline: %v", err)
				}
			case "abrupt":
				if !errors.As(err, &closeErr) || closeErr.Code != 1006 {
					t.Fatalf("lost disconnect: %v", err)
				}
			}
		})
	}
}

func TestRawAndRareEventsRemainOrdered(t *testing.T) {
	const partial = `{"message_type":"partial_transcript","text":"H","turn_probability":0,"speaker_id":null,"words":[{"text":"H","start":null,"end":0}]}`
	const merge1 = `{"message_type":"speakers_merged","from_speaker_id":"a","to_speaker_id":"b"}`
	const merge2 = `{"message_type":"speakers_merged","from_speaker_id":"b","to_speaker_id":"c"}`
	const unknown = `{"message_type":"future_event","meta":{"nested":true},"words":{"future":"shape"}}`
	const unindexedFinal = `{"message_type":"committed_transcript","text":"No index"}`
	messages := []string{`{"message_type":"session_started"}`, partial, final, timed, diarized, merge1, merge2, unknown, unindexedFinal}
	c := mockClient(t, func(ws *websocket.Conn, _ url.Values) {
		for _, msg := range messages {
			reply(t, ws, msg)
		}
		finish(t, ws, 1000)
	})
	s, err := c.Open(deadline(t), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var events []Event
	for e := range s.Events() {
		events = append(events, e)
	}
	if len(events) != len(messages) {
		t.Fatalf("got %d events: %v", len(events), s.Err())
	}
	for i := range events {
		if string(events[i].Raw) != messages[i] {
			t.Fatalf("raw/order changed at %d", i)
		}
	}
	p := events[1]
	if p.TurnProbability == nil || *p.TurnProbability != 0 || p.SpeakerID != nil || p.Words[0].Start != nil ||
		p.Words[0].End == nil || *p.Words[0].End != 0 || events[8].SegmentIndex != nil {
		t.Fatalf("lost zero or null: %+v", p)
	}
	if *events[5].FromSpeakerID != "a" || *events[5].ToSpeakerID != "b" || *events[6].ToSpeakerID != "c" ||
		events[7].Type != "future_event" {
		t.Fatal("wrong merge or unknown fields")
	}
}

func TestEarlyHTTPFailureAndCredentialRedaction(t *testing.T) {
	peer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, r.URL.String(), http.StatusUnauthorized)
	}))
	defer peer.Close()
	c := NewClient(fixtureKey)
	c.endpoint = strings.Replace(peer.URL, "http://", "ws://", 1)
	defer c.Close()
	_, err := c.Open(deadline(t), Options{})
	var httpErr *HandshakeError
	if !errors.As(err, &httpErr) || httpErr.StatusCode != 401 || strings.Contains(err.Error(), fixtureKey) ||
		strings.Contains(err.Error(), url.QueryEscape(fixtureKey)) || strings.Contains(err.Error(), "api-key=") {
		t.Fatalf("wrong handshake error: %v", err)
	}
}

func TestActiveCancelAndCloseUnblockReader(t *testing.T) {
	for _, activeCancel := range []bool{true, false} {
		activeCancel := activeCancel
		c := mockClient(t, func(ws *websocket.Conn, _ url.Values) {
			reply(t, ws, `{"message_type":"session_started"}`)
			_, _, err := ws.ReadMessage()
			if !activeCancel && !websocket.IsCloseError(err, 1000) {
				t.Errorf("close frame missing: %v", err)
			}
		})
		ctx, cancel := context.WithCancel(deadline(t))
		s, err := c.Open(ctx, Options{})
		if err != nil {
			t.Fatal(err)
		}
		if activeCancel {
			cancel()
		} else {
			_ = s.Close()
		}
		select {
		case <-s.Done():
		case <-time.After(time.Second):
			t.Fatal("session did not release reader")
		}
		if activeCancel && !errors.Is(s.Err(), context.Canceled) || !activeCancel && s.Err() != nil {
			t.Fatalf("wrong terminal state: %v", s.Err())
		}
		cancel()
	}
}

func TestErrorBeforeSessionStartedKeepsCloseCode(t *testing.T) {
	c := mockClient(t, func(ws *websocket.Conn, _ url.Values) {
		reply(t, ws, `{"message_type":"invalid_request","error":"too many keyterms"}`)
		finish(t, ws, 1008)
	})
	_, err := c.Open(deadline(t), Options{})
	var service *ServerError
	if !errors.As(err, &service) || service.Type != "invalid_request" ||
		service.Detail != "too many keyterms" || service.CloseCode != 1008 {
		t.Fatalf("lost startup error: %+v", err)
	}
}

func TestCanceledHTTPUpgrade(t *testing.T) {
	reached := make(chan struct{})
	peer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(reached)
		<-r.Context().Done()
	}))
	defer peer.Close()
	c := NewClient(fixtureKey)
	c.endpoint = strings.Replace(peer.URL, "http://", "ws://", 1)
	defer c.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := c.Open(ctx, Options{}); done <- err }()
	select {
	case <-reached:
	case <-time.After(time.Second):
		t.Fatal("mock upgrade did not start")
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("lost context cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("upgrade remained blocked after cancel")
	}
}

func TestServerErrorRedactsRawAndRetainsDetail(t *testing.T) {
	c := mockClient(t, func(ws *websocket.Conn, _ url.Values) {
		reply(t, ws, `{"message_type":"session_started"}`)
		err := ws.WriteJSON(map[string]string{
			"message_type": "input_error",
			"error": "bad option at wss://gateway/asr/realtime?api-key=" +
				url.QueryEscape(fixtureKey) + "&model_id=internal",
		})
		if err != nil {
			t.Error(err)
		}
		finish(t, ws, 1000)
	})
	s, err := c.Open(deadline(t), Options{})
	if err != nil {
		t.Fatal(err)
	}
	for event := range s.Events() {
		if event.isServerError() && (!json.Valid(event.Raw) ||
			strings.Contains(string(event.Raw), "gateway/asr") ||
			!strings.HasPrefix(event.Error, "bad option")) {
			t.Error("event payload was not correctly redacted")
		}
	}
	var server *ServerError
	if !errors.As(s.Err(), &server) || server.CloseCode != 1000 ||
		!strings.HasPrefix(server.Detail, "bad option") || strings.Contains(server.Detail, "gateway/asr") {
		t.Fatalf("bad server error: %v", s.Err())
	}
}
