# Audio quality investigation — July 2026

**Reported:** dictations on 29–30 Jul 2026 came out severely garbled. Raw and polished output equally bad, so the problem is upstream of the polish step.
**Build in use:** v2.4 (`13916d3`). v2.5 did not publish until 30 Jul 15:28 UTC, so none of that week's changes were in play.
**Investigated at:** `b605f7b`.

---

## Summary

Stow has never used the Bluetooth microphone. The app calls `setAudioSource(MediaRecorder.AudioSource.MIC)` and performs no routing control of any kind, so recordings come from the phone's built-in microphone whether or not a headset is connected. The "Bluetooth Mic" indicator reports device *availability*, not the active route, so it displayed Bluetooth while the internal mic recorded.

Dictating in a moving car with the phone pocketed or mounted, believing a headset mic was live, produces exactly the low-SNR input under which Whisper hallucinates fluently. Every reported symptom follows from that, amplified by v2.4 sending no `temperature` and a wrong-domain `prompt` on every request.

---

## Evidence

### No audio routing control has ever existed

Searched across every commit in the repository:

```
git log --all -S "startBluetoothSco"     → no results
git log --all -S "setPreferredDevice"    → no results
git log --all -S "MODE_IN_COMMUNICATION" → no results
git log --all -S "getRoutedDevice"       → no results
```

`AudioSource.MIC` without an active SCO connection routes to the built-in microphone. A2DP is an output-only profile and has no microphone path at all, so a device connected purely as A2DP can never supply input.

Corroborating observation from the report: the transcript picked up GPS directions from the **phone speaker** "far too clearly for a headset mic" — the signature of a microphone on the same device as the speaker.

### The indicator reports availability, not routing

`MainActivity`, unchanged from v2.4 through v2.5:

```kotlin
val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
isBluetooth = devices.any { it.type == TYPE_BLUETOOTH_SCO || it.type == TYPE_BLUETOOTH_A2DP }
tvMicIndicator.text = if (isBluetooth) "Bluetooth Mic" else "Internal Mic"
```

`getDevices(GET_DEVICES_INPUTS)` enumerates **connected** devices, not the **routed** one. Any paired headset makes this read "Bluetooth Mic" regardless of what is capturing.

The preceding branch (`activeRecordingConfigurations`) is closer to legitimate but races the state broadcast — it frequently runs before the recording configuration registers, then falls through to the enumeration check.

### The new-pairing theory does not hold

The reporter suspected the new Pixel Buds pair broke routing. Routing was never present to break; the app behaved identically with the previous pair. The variable that changed is behavioural — believing the headset mic was live leads to speaking at conversational volume with the phone pocketed, in a car.

### No code changed before the incident

v2.4's last code commit was **2026-07-16**, thirteen days before the bad dictations. There is no regression. This also rules out a changed model string, prompt, or retry behaviour.

### Chunking, stitching and retry are not involved

v2.4 uploads one file in one multipart request. No chunking has ever existed in any commit. No retry existed in v2.4; the retry added in v2.5 re-sends the whole file after an `IOException` where no response was received, so it cannot duplicate or drop a segment. There is no VAD or silence trimming anywhere in the codebase.

Phrase repetition and mid-word truncation are therefore **Whisper-side artifacts of degraded audio**, not stitching defects.

### The v2.4 request amplified hallucination

```kotlin
.addFormDataPart("model", "whisper-large-v3-turbo")
.addFormDataPart("prompt", prompt)    // no language, no temperature
```

**No `temperature`.** OpenAI-compatible Whisper defaults to 0 *with automatic fallback* — on failing its compression-ratio and log-probability thresholds it retries at 0.2, 0.4, 0.6 and upward. Degraded audio triggers that ladder, and high-temperature Whisper decoding is precisely what produces repetition loops and fluent substitutions.

**A wrong-domain prompt on every request.** `"CAD, HVAC, structural load, thermodynamic, schematic"` plus the jargon dictionary biased decoding on every recording. It does not contain the reported phantom phrase, so it is unlikely to be the direct source, but the jargon dictionary was appended to the same steering string identically across recordings and is worth checking for road or traffic terms.

The repeated phantom phrase is otherwise well explained: Whisper emits high-prior phrases from its training distribution on low-signal audio, and two recordings in the same vehicle share acoustic conditions.

---

## Symptom mapping

| Reported symptom | Explanation |
|---|---|
| Phrase repetition | Whisper repetition loop under temperature fallback on low-SNR audio |
| Same phantom phrase in two recordings | High-prior hallucination in matched acoustic conditions; possibly steered by the shared `prompt` |
| Fluent-but-wrong substitutions | Classic Whisper behaviour on degraded input |
| Hard truncation mid-word | Likely a route change mid-recording corrupting the file tail, or Whisper dropping a final low-confidence segment |
| Long recordings worst | Cumulative — more low-SNR audio, more opportunity for the temperature ladder to engage |

---

## Ranked causes

| # | Cause | Confidence | Explains |
|---|---|---|---|
| 1 | Recording from the internal mic while believing it was the headset | Very high | GPS pickup, low SNR, and therefore symptoms 1, 3, 5 |
| 2 | Whisper temperature fallback on degraded audio | High | Repetition loops, substitutions, phantom phrases |
| 3 | Hardcoded wrong-domain prompt steering decoding | Medium | Substitutions, possibly the phantom phrase |
| 4 | Route change mid-recording corrupting the file tail | Medium-low | Mid-word truncation |
| 5 | Jargon dictionary contents steering output | Low (cheap to check) | Phantom phrase, if it contains road terms |

**Ruled out:** chunk stitching, retry duplication, model change, any code regression before 29 Jul.

---

## Status of this investigation

| Fix | State |
|---|---|
| F1 / D1 — truthful indicator, route recorded per note | Shipped, v2.6 |
| F3 / D3 — `verbose_json`, quality warnings, stored responses | Shipped, v2.6 |
| D2 / D4 — retained audio, share, re-transcribe with model choice | Shipped, v2.6 |
| F2 / F4 — actual Bluetooth routing, route-change detection | **Declined** — see below |

Causes 2 and 3 are addressed by the explicit `temperature`, `language` and prompt changes in v2.5 plus the warnings in v2.6.

**Cause 1 is resolved without changing the audio path.** The confirming test was run on v2.7 on 2026-07-30: a normal note with the phone at arm's reach reported `Mic: Phone mic · 16 kHz` and transcribed well. That confirms the finding on hardware — the app was recording from the phone throughout while the old indicator claimed Bluetooth — and identifies the real cause as **microphone distance**, not routing.

Bluetooth routing (A.4) is therefore declined. HFP audio is telephony-processed and would plausibly transcribe worse than the phone mic already does, and its most likely bug is a silent fallback to the internal mic — this same failure, with more moving parts. Full reasoning in [roadmap.md § 1](roadmap.md).

## What v2.5 already changed

| Item | Status |
|---|---|
| `temperature=0` sent explicitly | Fixed |
| `language=en` sent | Fixed |
| Hardcoded prompt seed removed | Fixed |
| 16 kHz mono capture matching Whisper's own rate | Fixed |
| **Bluetooth microphone actually used** | **Not addressed** |
| **Misleading mic indicator** | **Not addressed** |

v2.5 should measurably reduce causes 2 and 3. It does nothing for cause 1 — wearing a headset and expecting it to be used will produce the same bad audio.

**Untested caveat:** `setAudioSamplingRate(16000)` has not been exercised on a Bluetooth route. It is correct for the internal mic. If SCO is enabled and delivers 8 kHz the framework upsamples (harmless but pointless), and an encoder that rejects the combination would surface as "Failed to start recording". Validate when F2 lands.

---

## Fix plan

Ordered. **Land the diagnostics before F2** so the fix can be measured rather than assumed.

### F1 — Make the mic indicator honest
Replace device enumeration with `MediaRecorder.getRoutedDevice()` called *after* `start()`, reported through the state broadcast. Until routing is genuinely controlled, showing "Internal Mic" truthfully is far better than showing "Bluetooth Mic" falsely — the false label is what made this take two days to notice.

### F2 — Actually acquire the Bluetooth microphone
API 31+: `AudioManager.setCommunicationDevice()` with the `TYPE_BLUETOOTH_SCO` device. Below 31: `startBluetoothSco()` and **wait for `SCO_AUDIO_STATE_CONNECTED`** before `MediaRecorder.start()` — starting early is the classic cause of silent fallback to the internal mic. Release with `clearCommunicationDevice()` / `stopBluetoothSco()` on stop.

> The goal is **control, not "always Bluetooth"**. SCO is narrowband 8–16 kHz; a phone microphone held reasonably close often produces better Whisper output than a headset SCO mic. Ship this as an explicit setting — *Prefer Bluetooth mic* / *Always use phone mic* — with F1's truthful indicator showing what was actually obtained. Also worth evaluating `AudioSource.VOICE_RECOGNITION`, which applies less aggressive AGC and noise processing than `MIC`.

### F3 — Harden against hallucination
Keep `temperature=0` and `language=en`. Move to `response_format=verbose_json` for per-segment `avg_logprob`, `no_speech_prob` and `compression_ratio` — an objective degradation signal that also enables warning at capture time that a recording is likely bad.

### F4 — Detect mid-recording route changes
Register an `AudioDeviceCallback` while recording; log route changes and surface them on the note.

---

## Diagnostics (land regardless)

### D1 — Record the actual route
`getRoutedDevice()` after start: device type, product name, and the sample rate in use. Store on the history entry and show in note detail. This alone would have caught the problem on day one.

### D2 — Retain recent raw audio
Keep the last ~5 `.m4a` files instead of deleting on success (size-capped, with cleanup), plus a **Share audio** action in history detail so a bad recording can be pulled off the device.

### D3 — Persist the raw API response
With `verbose_json`, store the JSON beside the entry. Combined with D2 this allows re-running identical audio against `whisper-large-v3` (non-turbo) for comparison.

### D4 — Re-transcribe action
Re-submit a stored recording with a selectable model, so an A/B is one tap rather than a manual `curl`.

---

## Confirming test (no code required)

On v2.5: wear the buds, play audio from the phone speaker, put the phone in a pocket, and speak normally for thirty seconds. If the transcript captures the speaker audio clearly and the voice poorly, cause 1 is confirmed outright.
