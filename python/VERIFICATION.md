# Python SDK candidate verification

Verification date: 2026-11-20 (UTC+8). Candidate: `myvocal-echo-v2-realtime` 0.1.0 with the import `myvocal_echo_v2_realtime`.

## Build and clean installation

| Check | Result |
| --- | --- |
| Isolated `python -m build` | Rebuilt the wheel from the source distribution. Both hashes matched the files in `dist/`. |
| `python -m twine check --strict dist/*` | Both artifacts passed. |
| Clean Python 3.11.14 environment, `websockets` 17.4 | Installed the candidate wheel and the old 0.1.1 release attachment together with pip. `pip check` and 12 protocol tests passed. The tests and CLI examples used the installed packages. |
| Separate Python 3.13.14 environment, `websockets` 14.0 | Installed the candidate wheel and the lowest declared WebSocket dependency. All 12 protocol tests passed. The installed English CLI also returned 27 word/spacing items over the Enterprise gateway. |

| Artifact in `dist/` | SHA-256 |
| --- | --- |
| `myvocal_echo_v2_realtime-0.1.0-py3-none-any.whl` | `5209065bb07f9525d3d6c992a3165d9817e00047a61b07ea5dbbc206dec4d6a8` |
| `myvocal_echo_v2_realtime-0.1.0.tar.gz` | `478da6bbce5e68459f425dc308df171880dbf76df324e52801145ef3df10d66d` |

## Enterprise gateway observations

The tests sent the two shared synthetic PCM recordings to `wss://openapi.myvocal.ai/asr/realtime` with an authorized test key. The key was passed in process memory.

| Case | Observed result |
| --- | --- |
| Short recording with and without timestamps | Each run returned nonempty final English text. The timed run returned 27 items, including spaces, with `order` at 0.28–0.50 s. |
| Bilingual ongoing call | The installed example received English and Chinese text in final segments 0 and 1, with `speaker_0` and `speaker_1`. Two timing events arrived without a segment index. The example retained them separately and reported call completion as unconfirmed. A separate `--output` run on Python 3.13 verified the saved JSON contained two distinct segments and two timing events and reported close 1000. |
| First-frame vocabulary and context | Two separate sessions each accepted 50 query keyterms, 10,000 library terms including one 50-character term, and `previous_text`. Each returned final text and 27 word/spacing items. |
| VAD | The English recording followed by three seconds of PCM silence yielded final text using `vad` with no manual commit. |
| Rejections | The invalid key returned HTTP 401. An empty library term and 10,001 library terms each yielded `input_error` followed by close 1008. |
| Cancel while streaming | After sending the first PCM chunk, task cancellation propagated and the SDK closed with code 1000. |
| Released SDK coexistence | Installed the released `realtime-asr-sdk` 0.1.1 from the GitHub release tagged 1.1.0. The existing `realtime_asr` client used its documented callback API with the shared PCM instead of microphone capture and returned an `echo_v1_realtime` final. The new package then returned an `echo_v2_realtime` final in that same environment. Replaying the captured event through each public decoder yielded 27 items in both packages, with 0 nonempty typed word strings in the legacy package and 27 in the new package. |

## Protocol replay

The local WebSocket tests cover auth query encoding, repeated keyterms, first-frame-only context and vocabulary on two sessions, concurrent event consumption, one final commit frame, both enhancement orders, distinct indexes, unindexed timing events, zero and null fields, timeout and early close with missing results, an `input_error` followed by close 1000, and abrupt transport loss. The call example also passed fixtures for two successive speaker merges, a null speaker label, a future event, and a pending draft at the drain deadline.

Gateway forwarding of `speakers_merged` remains unverified. The ongoing call test used paced synthetic input. Microphone hardware and live telephony routing were outside this verification run.

## Repeat the checks

From this Python directory after creating and activating a clean environment:

```sh
python -m pip install dist/myvocal_echo_v2_realtime-0.1.0-py3-none-any.whl
python -m unittest discover -s tests -v
```

For the Enterprise regression, install the [published legacy source attachment](https://github.com/MyVocal-AI/realtime-asr-sdk/releases/tag/1.1.0) in the same environment. Supply the authorized synthetic test key through the process environment and run:

```sh
python -I -X utf8 tests/live_acceptance.py
```
