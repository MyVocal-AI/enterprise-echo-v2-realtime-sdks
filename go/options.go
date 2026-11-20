package asr

import (
	"net/url"
	"strconv"
)

const (
	// Model and AudioFormat are the model and PCM format used on every connection.
	Model       = "echo_v2_realtime"
	AudioFormat = "pcm_16000"
	SampleRate  = 16000
)

// CommitMode selects manual or automatic segmentation.
type CommitMode string

const (
	Manual         CommitMode = "manual"
	VAD            CommitMode = "vad"
	TurnPrediction CommitMode = "turn_prediction"
)

// Options configures one connection. Its zero value uses Manual and lets the
// service choose the language. Optional numeric settings are omitted when nil.
type Options struct {
	CommitMode               CommitMode
	Language                 string
	MultilingualMode         bool
	SpeakerDiarization       bool
	WordTimestamps           bool
	IncludeLanguageDetection bool
	VADThreshold             *float64
	VADSilenceThresholdSecs  *float64
	MinSpeechDurationMS      *int
	MinSilenceDurationMS     *int

	// Keyterms supplies up to 50 ordinary terms as repeated query parameters.
	Keyterms []string
	// HotwordsLibrary supplies up to 10,000 terms of 1–50 characters each.
	// HotwordsLibrary and PreviousText are sent with the first audio frame.
	HotwordsLibrary []string
	PreviousText    string
}

func (o Options) query(key string) url.Values {
	q := url.Values{
		"api-key":      {key},
		"model_id":     {Model},
		"audio_format": {AudioFormat},
		"commit_mode":  {string(o.CommitMode)},
	}
	if o.Language != "" {
		q.Set("language", o.Language)
	}
	for k, enabled := range map[string]bool{
		"multilingual_mode":          o.MultilingualMode,
		"speaker_diarization":        o.SpeakerDiarization,
		"word_timestamps":            o.WordTimestamps,
		"include_language_detection": o.IncludeLanguageDetection,
	} {
		if enabled {
			q.Set(k, "true")
		}
	}
	if o.VADThreshold != nil {
		q.Set("vad_threshold", strconv.FormatFloat(*o.VADThreshold, 'f', -1, 64))
	}
	if o.VADSilenceThresholdSecs != nil {
		q.Set("vad_silence_threshold_secs", strconv.FormatFloat(*o.VADSilenceThresholdSecs, 'f', -1, 64))
	}
	if o.MinSpeechDurationMS != nil {
		q.Set("min_speech_duration_ms", strconv.Itoa(*o.MinSpeechDurationMS))
	}
	if o.MinSilenceDurationMS != nil {
		q.Set("min_silence_duration_ms", strconv.Itoa(*o.MinSilenceDurationMS))
	}
	for _, term := range o.Keyterms {
		q.Add("keyterms", term)
	}
	return q
}
