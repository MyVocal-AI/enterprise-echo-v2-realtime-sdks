package main

import (
	"bytes"
	"reflect"
	"testing"

	"github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go"
)

func TestCollectedTextAndSpeakers(t *testing.T) {
	for _, timestampFirst := range []bool{true, false} {
		var out bytes.Buffer
		c := newCollector(&out)
		final := `{"message_type":"committed_transcript","segment_index":0,"text":"Hello"}`
		timestamp := `{"message_type":"committed_transcript_with_timestamps","text":"Hello","words":[{"text":"Hello","start":0,"speaker_id":null}]}`
		speakers := `{"message_type":"diarized_transcript","segment_index":0,"words":[{"speaker_id":"a"},{"speaker_id":null}]}`
		for _, raw := range []string{
			`{"message_type":"speakers_merged","from_speaker_id":"a","to_speaker_id":"b"}`,
			final, final,
		} {
			testEvent(t, c, raw)
		}
		first, second := timestamp, speakers
		if !timestampFirst {
			first, second = speakers, timestamp
		}
		testEvent(t, c, first)
		if c.ready(1) {
			t.Fatal("coverage claimed before both results arrived")
		}
		testEvent(t, c, second)
		testEvent(t, c, `{"message_type":"speakers_merged","from_speaker_id":"b","to_speaker_id":"c"}`)
		if !c.ready(1) || c.ready(0) || !reflect.DeepEqual(c.lines, []string{"Hello"}) ||
			!reflect.DeepEqual(c.labels[0], []string{"c", ""}) {
			t.Fatalf("wrong coverage, text or speakers: %+v", c)
		}
		testEvent(t, c, `{"message_type":"committed_transcript","text":"Unindexed final"}`)
		if !reflect.DeepEqual(c.lines, []string{"Hello", "Unindexed final"}) || c.ready(2) {
			t.Fatal("unindexed final was dropped or assigned a fabricated speaker")
		}
	}
}

func testEvent(t *testing.T, c *collector, raw string) {
	t.Helper()
	event, err := asr.DecodeEvent([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	c.add(event)
}
