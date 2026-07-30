# Audio fix — implementation prompts

Companion to [audio-investigation-2026-07.md](audio-investigation-2026-07.md). Each prompt is self-contained and can be pasted into a fresh session against a clean `main`.

**Run them in this order.** Diagnostics come first deliberately: F2 changes which microphone is used, and without D1 there is no way to tell whether it worked. Shipping the fix before the measurement is how this bug survived two days in the first place.

| Order | Prompt | Covers | Effort |
|---|---|---|---|
| 1 | A.1 | F1 + D1 — truthful indicator, record the real route | small |
| 2 | A.2 | F3 + D3 — verbose_json, confidence signals, stored responses | medium |
| 3 | A.3 | D2 + D4 — keep raw audio, re-transcribe with a chosen model | medium |
| 4 | A.4 | F2 + F4 — real Bluetooth routing with a user setting | larger |

Conventions for every prompt:
- Stow is a single-activity Kotlin app: plain `SharedPreferences`, hand-built JSON, no architecture frameworks. Match that style; add no new dependencies unless the prompt says so.
- Package is `io.github.mds08011.stow`.
- **Never bulk-edit sources with PowerShell** — `Get-Content`/`Set-Content` corrupts UTF-8 (this repo has been bitten). Use per-file edits.
- Verify by pushing and reading the "Build check" run; there is no Android SDK on the local machine.
- Update `README.md`, `docs/ui-flow.md` and `CHANGELOG.md` in the same commit as the behaviour they describe.

---

## A.1 — Truthful mic indicator + record the actual route (F1 + D1)

```
You are working in the Stow repo (Android dictation app, Kotlin, package
io.github.mds08011.stow). Fix a UI claim that actively misleads, and start recording
which microphone was really used.

THE BUG: MainActivity decides the mic indicator like this:

    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    isBluetooth = devices.any { it.type == TYPE_BLUETOOTH_SCO || it.type == TYPE_BLUETOOTH_A2DP }
    tvMicIndicator.text = if (isBluetooth) "Bluetooth Mic" else "Internal Mic"

getDevices(GET_DEVICES_INPUTS) enumerates CONNECTED devices, not the ROUTED one. Any
paired headset makes this say "Bluetooth Mic" while the phone's internal mic actually
records — the app performs no audio routing at all (no startBluetoothSco, no
setCommunicationDevice anywhere in its history). A user lost two days of dictation to
this. The preceding activeRecordingConfigurations branch also races the state
broadcast and usually falls through.

Do NOT attempt to fix routing here — that is a later change. This change makes the app
tell the truth about what it is doing today.

FILES: RecordingService.kt, MainActivity.kt, TranscriptionHistory.kt,
activity_main.xml, docs/ui-flow.md, README.md.

IMPLEMENT:

1. RecordingService: immediately after mediaRecorder.start() succeeds, capture the real
   route. MediaRecorder.getRoutedDevice() is available from API 24 (= minSdk) and
   returns AudioDeviceInfo? — it can be null briefly, so retry once after ~150 ms on the
   service's handler before giving up.
   Derive:
     - routeType: a readable label from AudioDeviceInfo.getType() — at minimum
       BUILTIN_MIC -> "Phone mic", BLUETOOTH_SCO -> "Bluetooth (SCO)",
       WIRED_HEADSET -> "Wired headset", USB_DEVICE/USB_HEADSET -> "USB",
       else -> "Other (type N)"
     - routeName: productName?.toString()
     - sampleRate: the rate actually in use. Prefer the recorder's configured rate;
       if AudioDeviceInfo.getSampleRates() is non-empty include it too.
   Delete audio_record.m4a from nothing here; this change adds no file handling.

2. Broadcast the route with STATE_RECORDING as new extras (EXTRA_ROUTE_LABEL,
   EXTRA_ROUTE_SAMPLE_RATE) and remember it on the service for step 4.

3. MainActivity: DELETE both existing isBluetooth branches entirely. Set
   tvMicIndicator from the broadcast extras — e.g. "Phone mic - 16 kHz" or
   "Bluetooth (SCO) - 8 kHz". Before the first broadcast arrives show "Mic: checking…",
   and if the route could not be determined show "Mic: unknown" — never guess. Import
   cleanup: android.media.AudioManager / AudioDeviceInfo may become unused in
   MainActivity.

4. TranscriptionHistory.Entry: add two nullable fields, routeLabel: String? and
   routeSampleRate: Int?. Persist them in entryToJson/parseJson, tolerating their
   absence in existing files (older entries simply have null — do NOT bump any format
   version or migrate). RecordingService passes them when it creates the entry.

5. History detail dialog: when routeLabel is non-null, show a small grey line under the
   timestamp, e.g. "Phone mic - 16 kHz". Include it in toExportBlock() so exported
   notes carry it too.

6. Extend the existing unit tests in TranscriptionHistoryTest: JSON round-trip
   preserves both new fields; parsing an entry JSON that lacks them yields nulls rather
   than throwing.

7. Docs: update the ui-flow.md ownership table and README to state plainly that Stow
   records from the phone microphone and does not currently use a Bluetooth headset
   mic, and that the indicator reports the route actually in use.

ACCEPTANCE: the indicator can no longer say "Bluetooth" unless getRoutedDevice()
actually reported a Bluetooth device; every new history entry carries the route and
sample rate; old entries still load; testDebugUnitTest and both assemble tasks pass.
```

---

## A.2 — Confidence signals from the API (F3 + D3)

```
You are working in the Stow repo (Android dictation app, Kotlin, package
io.github.mds08011.stow). Make degraded transcriptions detectable instead of silent.

BACKGROUND: Whisper hallucinates fluently on low-SNR audio — repetition loops, plausible
wrong words, phantom phrases. The app currently requests the default response format and
so gets back only a "text" field, with no way to tell a clean transcription from a
fabricated one. Groq's endpoint supports verbose_json, which returns per-segment
avg_logprob, no_speech_prob and compression_ratio.

FILES: RecordingService.kt, TranscriptionHistory.kt, MainActivity.kt, README.md,
docs/ui-flow.md.

IMPLEMENT:

1. Add .addFormDataPart("response_format", "verbose_json") to the transcription request.
   Keep language=en and temperature=0 as they are.

2. Parse the richer response. "text" stays the transcript. Additionally compute across
   segments:
     - avgLogprob: mean of segment avg_logprob
     - maxNoSpeechProb: max of segment no_speech_prob
     - maxCompressionRatio: max of segment compression_ratio
   Be defensive: if "segments" is missing or empty, fall back to reading "text" alone and
   leave the metrics null. A parsing failure must never lose the transcription.

3. Store the raw response body verbatim alongside the note so a bad recording can be
   analysed later. Write it to
   getExternalFilesDir(DIRECTORY_DOCUMENTS)/responses/<entryId>.json rather than into
   the history JSON, which is read on every history operation and must stay small. Cap
   the directory at the 20 most recent files, deleting oldest first.

4. TranscriptionHistory.Entry: add nullable avgLogprob: Double?, maxNoSpeechProb:
   Double?, maxCompressionRatio: Double?. Persist and parse them, tolerating absence in
   existing files.

5. Flag likely-bad transcriptions. Add Entry.qualityWarning(): String? returning a short
   reason when any of these hold (nulls mean no warning):
     - avgLogprob < -1.0            -> "low confidence"
     - maxNoSpeechProb > 0.6        -> "mostly silence or noise"
     - maxCompressionRatio > 2.4    -> "repetitive output"
   These are the conventional Whisper thresholds; put them in named constants with a
   comment saying so, since they will need tuning against real recordings.

6. Surface it without nagging: when qualityWarning() is non-null, show a single-line
   caution on the result screen ("Transcription may be unreliable - low confidence")
   and a marker in the history list preview. Do NOT block, re-record or auto-retry.

7. Unit tests: parsing a verbose_json fixture yields the right aggregates; a fixture with
   no segments still yields the transcript with null metrics; qualityWarning() fires on
   each threshold and stays null when all are healthy or absent.

8. Docs: document verbose_json, the stored responses directory and its cap, and the
   thresholds, in ui-flow.md and README troubleshooting.

ACCEPTANCE: a normal recording transcribes exactly as before and stores a response file;
metrics appear on new history entries; a malformed or minimal response still produces a
transcription; tests and both assemble tasks pass.
```

---

## A.3 — Keep raw audio and allow re-transcription (D2 + D4)

```
You are working in the Stow repo (Android dictation app, Kotlin, package
io.github.mds08011.stow). Make a bad transcription reproducible instead of unrecoverable.

BACKGROUND: audio is deleted as soon as transcription succeeds, so when output is garbled
there is nothing left to analyse or re-run. The user needs to A/B a bad recording against
whisper-large-v3 (non-turbo).

FILES: RecordingService.kt, TranscriptionHistory.kt, MainActivity.kt, README.md.

IMPLEMENT:

1. Stop deleting audio on success. Keep the most recent 5 audio_*.m4a files in
   externalCacheDir, deleting oldest beyond that, and additionally drop any file older
   than 7 days. Keep the existing behaviour that a failed upload's audio is retained for
   Retry regardless of that cap.
   Note externalCacheDir can be reclaimed by the OS under storage pressure — treat a
   missing file as normal, never as an error.

2. Record which file belongs to which note: add audioPath: String? to
   TranscriptionHistory.Entry, persisted and parsed, tolerating absence.

3. History detail: when audioPath is non-null AND the file still exists, add two entries
   to the existing "More…" actions menu:
     - "Share audio" - share the .m4a via the existing shareText-style ACTION_SEND path
       (use FileProvider, which is already configured; set type audio/mp4)
     - "Re-transcribe…" - see step 4
   Hide both when the file is gone. Do not add buttons to the main screen.

4. Re-transcribe flow: a small dialog listing the model to use —
   "whisper-large-v3-turbo (current)" and "whisper-large-v3 (slower, more accurate)".
   On choice, re-upload the stored audio with that model and the current jargon, then
   show the new text in a comparison dialog against the existing transcript with
   "Replace" and "Keep existing" buttons. Replacing updates rawText on the entry and
   leaves polishedText untouched.
   Extract the model into a parameter rather than the hardcoded string; add a
   MODEL_TURBO / MODEL_LARGE constant pair.

5. This is an on-demand, foreground-initiated upload: reuse the existing
   ACTION_RETRY_UPLOAD plumbing where it fits, or add ACTION_RETRANSCRIBE taking the
   file path, model and entry id. Use the dataSync foreground service type, not
   microphone — nothing is being recorded.

6. Storage honesty: show the total size of retained audio in the Settings dialog with a
   "Clear saved audio" button.

7. Docs: README section explaining that the last few recordings are kept on device for
   troubleshooting, where they live, and how to clear them.

ACCEPTANCE: after a successful transcription the audio still exists and is linked to the
note; Share audio produces a playable file; re-transcribing with whisper-large-v3 returns
text and offers replacement; the cache never exceeds 5 files; a deleted or evicted file
degrades gracefully; tests and both assemble tasks pass.
```

---

## A.4 — Real Bluetooth microphone routing (F2 + F4)

```
You are working in the Stow repo (Android dictation app, Kotlin, package
io.github.mds08011.stow). Give the app actual control over which microphone it records
from. Do A.1 first — without the truthful indicator and stored route there is no way to
verify this works.

BACKGROUND: the app has never performed audio routing. setAudioSource(MIC) with no SCO
connection records from the built-in mic, so a connected Bluetooth headset is never used
for input (A2DP is output-only and has no mic path at all).

IMPORTANT - the goal is CONTROL, not "always Bluetooth". SCO is narrowband 8-16 kHz and a
phone mic held reasonably close often gives better Whisper output than a headset SCO mic.
This must ship as a user setting, defaulting to the phone mic, so behaviour never changes
silently under anyone.

FILES: RecordingService.kt, MainActivity.kt, AndroidManifest.xml, README.md,
docs/ui-flow.md, docs/audio-investigation-2026-07.md.

IMPLEMENT:

1. Setting: add "Microphone" to the Settings dialog with two options, stored in
   StowPrefs under mic_preference:
     - "Phone microphone (recommended)"  -> value "phone"  [DEFAULT]
     - "Bluetooth headset when available" -> value "bluetooth"
   Include a one-line hint that Bluetooth headset mics are narrowband and often
   transcribe worse than the phone mic.

2. Acquire SCO when the preference is "bluetooth" AND a TYPE_BLUETOOTH_SCO input device
   exists:
     - API 31+: audioManager.setCommunicationDevice(device) for that AudioDeviceInfo.
     - Below 31: audioManager.mode = MODE_IN_COMMUNICATION, then startBluetoothSco(),
       then WAIT for ACTION_SCO_AUDIO_STATE_UPDATED with SCO_AUDIO_STATE_CONNECTED
       before calling MediaRecorder.start(). Starting the recorder early is the classic
       cause of silent fallback to the internal mic.
     - Time out after 3 seconds. On timeout, fall back to the phone mic, proceed with the
       recording, and report the fallback (see step 4) — never fail the recording.
     - Always release on stop and on every error path: clearCommunicationDevice() /
       stopBluetoothSco(), and restore audioManager.mode to MODE_NORMAL.

3. Sample rate: MediaRecorder currently requests 16000 Hz unconditionally. SCO may
   deliver 8 kHz. Query the routed device's getSampleRates() and, when a Bluetooth route
   is in use and 16000 is not offered, request the highest rate it does offer instead of
   forcing 16000. Whisper resamples server-side, so matching the route is better than
   fighting it. Log the requested and actual rate.
   Add BLUETOOTH_CONNECT to the manifest and request it at runtime on API 31+ — it is
   required to enumerate and select Bluetooth devices. Denial must degrade to the phone
   mic, not to a crash.

4. Report what actually happened. Extend the A.1 route reporting so the result screen and
   the history entry record the effective route, and show a toast when the requested
   route was not obtained: "Bluetooth mic unavailable - used phone mic". Silence here is
   exactly the failure mode that caused the original bug.

5. F4 - route changes mid-recording: register an AudioManager.AudioDeviceCallback while
   recording. If the routed input device changes (e.g. GPS audio toggling routes), record
   the fact on the entry and surface a caution on the result screen. Do not attempt to
   restart the recording.

6. Also evaluate and leave a comment (do not change behaviour blindly):
   AudioSource.VOICE_RECOGNITION applies less aggressive AGC and noise suppression than
   MIC and is often better for dictation. If switching, make it a constant with a comment
   explaining the tradeoff.

7. Docs: update the investigation document's "What v2.5 already changed" table to mark
   Bluetooth routing as addressed, and document the new setting in the README.

ACCEPTANCE: with the setting on "phone", behaviour is identical to today. With it on
"bluetooth" and a headset connected, the route reported by getRoutedDevice() is
BLUETOOTH_SCO and the headset mic is audibly the source. With "bluetooth" set and no
headset, recording proceeds on the phone mic with a toast. Denying BLUETOOTH_CONNECT
degrades to the phone mic. SCO is always released — verify no lingering
MODE_IN_COMMUNICATION after stopping. Tests and both assemble tasks pass.

DEVICE TESTING REQUIRED - none of this is verifiable in CI:
  - Record with buds in, phone in pocket, audio playing from the phone speaker. On
    "bluetooth" the speaker audio should be faint and the voice clear; on "phone" the
    reverse. That contrast is the whole fix.
  - Confirm the reported route matches reality in both settings.
  - Take a call or trigger GPS audio mid-recording and confirm the route-change caution.
  - Confirm audio still records after toggling the setting without restarting the app.
```

---

## Release gate for this work

Do not cut a release containing A.4 without device-testing it. A silent fallback to the wrong microphone is the exact failure this whole investigation was about, and CI cannot detect it.

After A.1 ships, the confirming test from the investigation becomes decisive: the indicator will state which microphone was actually used, so a garbled transcription can immediately be attributed or exonerated.
