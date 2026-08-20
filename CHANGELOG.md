# Changelog

All notable changes to Stow. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

Versions before 2.5 are reconstructed from git history and are less detailed.

## [2.8] — 2026-08-20

Groq retired the Llama chat models Stow used for polish. This release moves to a current model and makes sure the next retirement is a settings change rather than a release.

### Fixed
- **Polish works again.** Groq deprecated its Llama chat models on 2026-06-17 and stopped serving them in August 2026, so every polish request had started failing with `The model llama-3.1-8b-instant has been decommissioned`. The default polish model is now **`openai/gpt-oss-20b`** — Groq's own recommended replacement for the model Stow was using. Transcription was never affected: Whisper is not part of the chat deprecations and is unchanged.

### Added
- **The polish model is a setting.** **Settings → Polish model** is a plain text field prefilled with the default; empty restores it. When Groq next retires a model, paste a current id from `console.groq.com/docs/models` and carry on. Deliberately a free-form string and not a dropdown — a list of models is a list that goes stale exactly when you need it.

### Changed
- **A failed polish now explains itself and never degrades quietly.** Previously a decommissioned model produced a three-second toast containing a status code and a JSON blob. Now failures raise a dialog — *"Polish failed — showing the raw transcription"* — that names the model actually sent, quotes Groq's own message, and offers a **Settings…** button. Rejected keys, rate limits and server errors get the same treatment, matching what transcription errors have done since v2.7.
- **Truncated polish output is treated as a failure, not a result.** A generation that stops on the token cap comes back as ordinary-looking text that ends mid-sentence, and nothing downstream would have caught it. Stow now rejects it and keeps the raw transcript, along with an error envelope arriving on a `200`, or a missing message body.
- **The `max_tokens` floor rose from 256 to 1024.** `openai/gpt-oss-20b` is a reasoning model: it spends tokens thinking before writing, out of the same budget. On a short note the old floor could be spent entirely on reasoning, arriving as an empty or truncated polish. Requests to `openai/gpt-oss` models also now set `reasoning_effort: low` and `include_reasoning: false` — the reasoning is not wanted, and on a field connection not worth downloading. Both are omitted for any other model, since they would be rejected.
- Settings scrolls, so the buttons stay reachable on a short screen.

### Notes
- Both built-in polish prompts were re-read against the new model and **neither needed changing** — nothing in them was ever Llama-specific. See the review in [docs/polishing-prompt.md](docs/polishing-prompt.md). Because presets are stored per-install, your saved copies are untouched either way.
- **[Stow Web](https://github.com/mds08011/stow-web) has the same broken model and has not been updated**, so the cross-app parity check will fail until it is. See [docs/parity.md](docs/parity.md).

## [2.7] — 2026-07-30

Two behaviours ported from [Stow Web](https://github.com/mds08011/stow-web), which hit both problems first.

### Added
- **Rate-limit countdown.** A 429 now disables **Retry last upload** and counts down until a retry could actually succeed, rather than letting you burn another slot immediately. Groq reports the wait both as a `Retry-After` header and as prose in the error body (`try again in 2m59.56s`) and neither is reliable alone, so Stow takes whichever is larger.
- **Unfinished recordings are offered back.** The recording is checkpointed the moment it stops, *before* the first upload attempt. If Stow is killed mid-upload — the one window where audio existed on disk with nothing pointing at it — the next launch offers it with **Transcribe it** / **Discard**.

### Changed
- API errors now explain themselves (rejected key, file too large, rate limit, server error) instead of showing a status code and a JSON blob.

## [2.6] — 2026-07-30

Diagnostics for the July transcription-quality investigation. Installs straight over v2.5 — no uninstall, history preserved.

### Fixed
- **The microphone indicator no longer lies.** It previously enumerated *connected* input devices, so it displayed "Bluetooth Mic" whenever a headset was merely paired — while the phone's built-in microphone did the recording. It now reports the device the recorder is actually routed to, read back via `getRoutedDevice()` after start, and says "unknown" rather than guessing. This mislabel caused two days of unusable dictation before the audio path was suspected; see [docs/audio-investigation-2026-07.md](docs/audio-investigation-2026-07.md).

### Added
- Every note records which microphone and sample rate produced it, shown in history detail and included in exports — so a bad transcription can be attributed rather than guessed at.
- **Unreliable transcriptions are flagged.** Requests now use `verbose_json`, and the per-segment decoding statistics are checked against Whisper's own decode-failure thresholds. A note that looped, heard mostly silence, or decoded with low confidence is marked on the result screen and with ⚠ in history. Nothing is blocked or retried automatically — it just stops bad output from reading as fine.
- The raw API response for the last 20 transcriptions is kept under `Documents/responses/`, so a bad one can be inspected or compared against another model.
- **Recordings are kept instead of deleted on success** — the last 5, for a week. History detail gains **Share audio** and **Re-transcribe…**, the latter re-running the stored audio through `whisper-large-v3` (non-turbo) and showing it side by side before you decide whether to replace. A bad transcription is now reproducible rather than gone. Settings shows the space used and can clear it.

### Known limitation
- Stow records from the phone microphone only. A connected Bluetooth headset is **not** used for input; that work is tracked as A.4 in [docs/audio-implementation-prompts.md](docs/audio-implementation-prompts.md).

## [2.5] — 2026-07-30

The largest release so far: user-editable polish presets, a set of correctness fixes around losing data, and a working release pipeline.

> **Upgrading from 2.4 or earlier requires one uninstall.** Two things force it: releases before 2.5 were each signed with a throwaway key, and the application ID changed from the placeholder `com.example.stow` to `io.github.mds08011.stow`. Android treats the new ID as a different app, so 2.5 installs alongside the old one rather than over it.
>
> **Open History → Export all and save the export before upgrading**, then uninstall the old Stow, install 2.5, and re-enter your Groq API key and jargon terms. From 2.5 onward updates install normally and history persists.

### Added
- **Polish presets** — named, editable prompts replacing the single hardcoded one. Two ship built in: **Clean prose** (the previous light-cleanup behaviour, unchanged) and **Task capture** (splits a dictated capture into Markdown checkboxes grouped by job). Add, rename, edit and delete your own; built-ins can be reset but not deleted.
- **Show raw / Show polished toggle** on the result screen, keeping edits to each version separately.
- **Quick Settings tile** — start or stop a recording from the notification shade without opening the app.
- **Pause and resume**, from the notification or the main screen. Paused time is excluded from the note's duration and daily usage.
- **Retry last upload** — a failed upload keeps its audio and offers a one-tap retry. Network failures also retry once automatically.
- **Transcription ready notification** when a transcription finishes while the app is backgrounded.
- One-time **battery optimisation prompt** on first launch.
- Unit tests for history serialisation, legacy migration, search, and prompt assembly.
- A compile-only **Build check** workflow, separate from releases.
- `docs/ui-flow.md`, `docs/roadmap.md`, `docs/release-signing.md`, and this changelog.

### Changed
- **Application ID is now `io.github.mds08011.stow`**, replacing the `com.example.stow` placeholder that Android Studio generates. `com.example` is reserved for samples and is not a namespace anyone owns. Bundled into this release deliberately, since it forces the same uninstall the signing change already required.
- **Auto-polish no longer shows a confirmation dialog** — it goes straight to the editable result with the text copied. Manual **Polish** keeps the comparison dialog, now two buttons instead of three.
- **Release APKs are signed with a stable key**, so updates install in place. Previously every release used a freshly generated debug key.
- Audio is captured at **16 kHz mono, 32 kbps** — what Whisper uses anyway — cutting an hour of dictation to roughly 14 MB.
- Transcription requests send **only your jargon dictionary**. A hardcoded `CAD, HVAC, structural load, thermodynamic, schematic` seed was competing with your own terms for Whisper's limited prompt budget. Also sends `language=en` and `temperature=0`.
- The polish request sends the preset as the system message and the transcript, delimited, as the user message.
- **Clean prose** gained a rule against converting, rounding or reformatting numbers, units and identifiers, and its filler rule now distinguishes always-remove from remove-only-when-filler.
- Result label names the variant on screen: *Result — raw (editable)* / *Result — polished (editable)*.
- History reads come from an in-memory cache and writes happen off the UI thread; search is debounced.
- Status messages moved out of the transcript field into their own line.
- Dedicated mic icon for the notification and tile, replacing framework icons.

### Fixed
- **A transcription was lost entirely if the app was backgrounded during upload.** History is now written by the service the moment the transcription arrives.
- **The launcher icon's microphone was off-centre and undersized** — about 13 dp up and left of centre at roughly half the intended size, from a pivot that assumed the glyph's ink centre was (12, 12) when it is (12, 11.5). Also adds a monochrome layer for themed icons.
- Update checks compared versions as decimals, so 2.10 read as lower than 2.4 and three-part tags threw.
- Denying the microphone permission no longer wipes the result already on screen.
- The result screen survives rotation.
- A failed upload's audio is no longer overwritten by the next recording.
- Repo no longer tracks 581 generated build files.

## [2.4]
- Auto-copy the selected text and restore **Start** on the result screen.
- Remove Share and New from the result action row.

## [2.3]
- Editable result screen with an improved, searchable history browser.
- Structured local history storage (JSON) with search and export, migrating the legacy plain-text log.
- Broadcast recording duration so history can store it.

## [2.2]
- Optional post-transcription polish via Groq chat completions, with a conservative light-cleanup prompt.
- Auto-polish setting.
- `docs/polishing-prompt.md` established as the source of truth for polish behaviour.

## [2.1]
- Fix transcription field scroll-position retention.

## [2.0]
- In-app update checker using the GitHub releases API, with download and install.
- FileProvider configuration for installing downloaded APKs.

## [1.9]
- Scrollable transcription field.
- Adaptive launcher icon gravity fix (superseded in 2.5).
- Migrate the log file to `getExternalFilesDir` to fix a scoped-storage crash.

## [1.8 and earlier]
- Jargon Dictionary in its own dialog with a dedicated button.
- Transcript history viewer.
- Background recording via a foreground service, live chronometer, daily usage tracker, and Groq Whisper transcription.
