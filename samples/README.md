# Shared realtime audio samples

These samples reuse the local Windows SpeechSynthesizer audio from the MyVocal Enterprise ASR gateway acceptance run on 2026-11-20. The source files, `english.wav` and `chinese.wav`, were synthesized from fictional order and delivery test phrases on 2026-11-17. They contain synthetic speech and silence only. No customer recordings were used.

Both files are headerless PCM S16LE (signed 16-bit little-endian), 16,000 Hz, mono, at 32,000 bytes per second.

| Sample | Duration | Input and suggested settings |
| --- | ---: | --- |
| [`english.pcm`](english.pcm) | 8.958 s | English order and delivery request. Use `commit_mode=manual` and `word_timestamps=true`. |
| [`bilingual.pcm`](bilingual.pcm) | 19.765 s | English voice, 0.5 s of silence, Chinese voice, and 2.0 s of trailing silence, matching the gateway acceptance input. Use `commit_mode=turn_prediction`, `multilingual_mode=true`, `speaker_diarization=true`, and `word_timestamps=true`. Stream with `commit=false`. |

The language-specific examples take raw PCM. To prepare your own input from a WAV file, run:

```sh
ffmpeg -i input.wav -f s16le -ar 16000 -ac 1 output.pcm
```

## What to look for

- **English.** The gateway returned one finalized transcript and one timestamp event. The text included “The order contains 42 boxes.” In that timestamp response, `order` started at 0.28 s and ended at 0.50 s.
- **Bilingual.** The gateway returned English and Chinese text, including “The order contains 42 boxes.” and “明天上午送到办公室。” Partial events carried `turn_probability`. The run produced two committed segments and a diarized and timestamp result for each. Segment indexes `0` and `1` mapped to `speaker_0` and `speaker_1` in the diarized events. One timestamp event arrived before its corresponding diarized event, and the other arrived after. The timestamp events had no usable segment index and carried null speaker labels in their word entries.

These are observations from one gateway acceptance run. Text, punctuation, event counts, segment boundaries, timings, and speaker IDs can vary.
