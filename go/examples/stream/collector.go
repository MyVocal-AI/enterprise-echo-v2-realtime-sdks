package main

import (
	"fmt"
	"io"
	"sort"

	"github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go"
)

type collector struct {
	out        io.Writer
	lines      []string
	committed  map[int]bool
	labels     map[int][]string
	aliases    map[string]string
	timestamps int
}

func newCollector(out io.Writer) *collector {
	return &collector{
		out: out, committed: make(map[int]bool), labels: make(map[int][]string), aliases: make(map[string]string),
	}
}

func (c *collector) add(e asr.Event) {
	switch e.Type {
	case asr.PartialTranscript:
		if e.TurnProbability == nil {
			fmt.Fprintf(c.out, "partial %s\n", e.Text)
		} else {
			fmt.Fprintf(c.out, "partial (turn %.3f) %s\n", *e.TurnProbability, e.Text)
		}
	case asr.CommittedTranscript:
		if e.SegmentIndex != nil {
			if c.committed[*e.SegmentIndex] {
				return
			}
			c.committed[*e.SegmentIndex] = true
		}
		c.lines = append(c.lines, e.Text)
		fmt.Fprintf(c.out, "final %s %s\n", segmentLabel(e.SegmentIndex), e.Text)
	case asr.CommittedTranscriptWithTimestamps:
		c.timestamps++
		fmt.Fprintf(c.out, "timestamps %s", segmentLabel(e.SegmentIndex))
		for _, word := range e.Words {
			fmt.Fprintf(c.out, " %q[%s–%s]", word.Text, seconds(word.Start), seconds(word.End))
		}
		fmt.Fprintln(c.out)
	case asr.DiarizedTranscript:
		labels := make([]string, len(e.Words))
		for i, word := range e.Words {
			if word.SpeakerID != nil {
				labels[i] = c.resolve(*word.SpeakerID)
			}
		}
		if e.SegmentIndex != nil {
			c.labels[*e.SegmentIndex] = labels
		}
		fmt.Fprintf(c.out, "speakers %s %v\n", segmentLabel(e.SegmentIndex), displayLabels(labels))
	case asr.SpeakersMerged:
		if e.FromSpeakerID != nil && e.ToSpeakerID != nil {
			c.merge(*e.FromSpeakerID, *e.ToSpeakerID)
		}
	}
}

// Keep aliases flat so chains also correct labels stored before a merge.
func (c *collector) merge(from, to string) {
	from, to = c.resolve(from), c.resolve(to)
	if from == to || from == "" || to == "" {
		return
	}
	for id, label := range c.aliases {
		if label == from {
			c.aliases[id] = to
		}
	}
	c.aliases[from] = to
	for _, labels := range c.labels {
		for i, label := range labels {
			if label == from {
				labels[i] = to
			}
		}
	}
	fmt.Fprintf(c.out, "speaker merge %s → %s\n", from, to)
}

func (c *collector) resolve(id string) string {
	if label, ok := c.aliases[id]; ok {
		return label
	}
	return id
}

// A known test recording can supply an expected count. Timestamp events with
// no index contribute to that count without being assigned to a segment.
func (c *collector) ready(expected int) bool {
	if expected == 0 || len(c.lines) < expected || c.timestamps < len(c.lines) || len(c.committed) != len(c.lines) {
		return false
	}
	for index := range c.committed {
		if _, ok := c.labels[index]; !ok {
			return false
		}
	}
	return true
}

func (c *collector) printSummary() {
	fmt.Fprintf(c.out, "received %d final, %d timestamp, %d indexed speaker results\n",
		len(c.lines), c.timestamps, len(c.labels))
	indexes := make([]int, 0, len(c.labels))
	for index := range c.labels {
		indexes = append(indexes, index)
	}
	sort.Ints(indexes)
	for _, index := range indexes {
		fmt.Fprintf(c.out, "segment %d speaker labels %v\n", index, displayLabels(c.labels[index]))
	}
}

func segmentLabel(index *int) string {
	if index == nil {
		return "[no segment index]"
	}
	return fmt.Sprintf("[%d]", *index)
}

func seconds(value *float64) string {
	if value == nil {
		return "—"
	}
	return fmt.Sprintf("%.2fs", *value)
}

func displayLabels(labels []string) []string {
	names := append([]string(nil), labels...)
	for i, label := range names {
		if label == "" {
			names[i] = "?"
		}
	}
	return names
}
