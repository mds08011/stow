# Roadmap

What Stow is for, what is planned, and — most usefully — what has been deliberately turned down.

## Principles

These are the constraints the app is built around. A change that conflicts with one of them should be rejected in review even if it is individually appealing.

1. **Low friction above all.** The path from speech to clean text on the clipboard is the product. Every added tap needs to earn itself.
2. **Free tier only.** Groq's free tier is a hard constraint, not a starting point. No paid APIs, no subscriptions, no usage that assumes a credit card.
3. **Light-touch polish.** Polish removes fillers and fixes spelling, grammar and punctuation, with minimal rephrasing. It never expands, summarises, formalises, or invents. Presets that restructure (Task capture) still never add content that was not spoken.
4. **Field-first.** Gloves, sunlight, marginal LTE, a phone in a pocket mid-upload. Reliability under those conditions beats features.
5. **Simple enough to maintain.** One activity, plain `SharedPreferences`, hand-built JSON, no architecture frameworks. The app is maintained by a non-professional developer working with AI tools; the code has to stay legible to that workflow.

## Next steps — read this first if picking the project back up

### 1. A.4 (Bluetooth routing) — resolved, not building it

**Settled on 2026-07-30.** The gating test was run on v2.7 and came back clean:

> Recorded a normal note with the phone at arm's reach. The indicator read **`Mic: Phone mic · 16 kHz`** and the transcription was good.

That confirms the investigation's central finding on hardware — the app was recording from the phone the whole time while the old indicator claimed Bluetooth — and it resolves the cause. **The July failure was microphone distance, not routing.** Nothing about the audio path needs changing.

A.4 is therefore **declined unless a concrete hands-free need appears**, e.g. "I want to dictate while driving without touching the phone." Reasons, in order of weight:

1. **SCO audio is likely worse, not better.** A headset mic runs over HFP — a telephony path, 8 kHz narrowband or 16 kHz wideband depending on what the phone and buds negotiate, and heavily processed with noise suppression and AGC tuned for call intelligibility rather than transcription fidelity. The phone mic already measures 16 kHz and transcribes well. This is a plausible downgrade.
2. **It reintroduces the exact bug just escaped.** A.4's most likely failure is SCO not connecting and the recorder silently falling back to the internal mic — July's bug, with more moving parts.
3. **It changes the capture path.** Everything since v2.5 has been additive or diagnostic. This is the one part of the app whose failure cannot be seen without listening.
4. **`MODE_IN_COMMUNICATION` has device-wide side effects** — ducking other audio, altering volume behaviour, and leaving the phone in call-audio mode if any code path misses the release.
5. **Another runtime permission** (`BLUETOOTH_CONNECT` on Android 12+) and its denial path.

**If it is ever revisited:** [audio-implementation-prompts.md](audio-implementation-prompts.md) § A.4 is written and ready. It defaults to the phone mic behind an explicit setting, so it is opt-in and reversible. One useful property — the experiment is self-measuring: A.1 already reports route and sample rate per note, and A.3 can re-transcribe the same audio, so the same content can be A/B'd both ways and judged on evidence.

### 2. Device testing owed from v2.5, v2.6 and v2.7

None of this is verifiable in CI. A general "record, transcribe, read the result" pass was done on v2.7 on 2026-07-30 and looked good, which covers the happy path; the items below need specific circumstances that ordinary use will not produce.

| What | Why it matters | State |
|---|---|---|
| Record → transcribe → result, phone at arm's reach | The happy path, and the mic indicator | ✅ 2026-07-30, v2.7 |
| Background the app during upload → notification → reopen | The A-1 data-loss fix; the highest-consequence unverified change | ⬜ |
| Exactly one history entry per recording | A duplicate would be quiet and easy to miss | ⬜ |
| Pause 30 s mid-recording → stored duration excludes it | Feeds the daily free-tier counter | ⬜ |
| Airplane-mode → **Retry last upload** | Recovers a note instead of losing it | ⬜ |
| Kill Stow mid-upload → relaunch offers the recording back | v2.7 crash recovery | ⬜ |
| A real 429 → Retry counts down and stays disabled | v2.7 rate-limit handling | ⬜ |
| Quick Settings tile start/stop | Never exercised on a device | ⬜ |
| Launcher icon against other home-screen icons | Geometry is correct; visual weight is a judgement call | ⬜ |

### 3. Tune the quality thresholds against real recordings

`LOGPROB_LIMIT`, `NO_SPEECH_LIMIT` and `COMPRESSION_RATIO_LIMIT` in `TranscriptionHistory` are Whisper's own defaults, **not** validated against this user's audio. Once there are a few flagged and unflagged notes, check for false positives and negatives and adjust. They are named constants for exactly this reason.

## Planned / open

Roughly in value order. See [assessment-2026-07.md](assessment-2026-07.md) for the reasoning behind each.

| Item | Notes |
|---|---|
| Screenshots in the README | Needs a physical device; scaffold is in `screenshots/` |
| Legacy launcher icon below API 26 | Either add PNG fallbacks or raise `minSdk` to 26 |
| Dated result-field background | `@android:drawable/edit_text` is a Holo 9-patch; renders poorly in dark mode |
| Background upload errors are silent | A failed upload while backgrounded shows nothing until you reopen — the sibling of the A-1 fix |
| Encrypted API key storage | Judged not worth the dependency; README wording corrected instead |

### 4. Changing anything shared with Stow Web

[Stow Web](https://github.com/mds08011/stow-web) duplicates the built-in polish prompts, the preset ids and names, the jargon placeholder, and both model ids. Change any of those here and the web app silently stops matching — same preset name, different output.

CI catches it from both sides now, but the cheap move is to change both repos in the same sitting. See [parity.md](parity.md) for the full contract and for the July 2026 incident that prompted it.

## Deliberately declined

Each of these has been considered and turned down. Reopening one means arguing against a principle above, not just proposing a feature.

| Not doing | Why |
|---|---|
| **On-device transcription** (Whisper tiny, Vosk) | Contradicts the whole premise — the app exists so the phone does not run a model. Adds 40–100 MB to the APK and drains battery. |
| **Summarisation, expansion, action-item extraction beyond Task capture** | Violates light-touch polish. A "summarise" button would be the first feature to break principle 3. |
| **Speaker diarisation** | Needs a larger model and a paid tier; solves a problem single-speaker field notes do not have. |
| **Cloud sync / accounts / multi-device history** | Requires a backend and an account system. **Export all → share sheet** already covers cross-device access for free. |
| **Paid Groq tier or alternate paid providers** | Breaks principle 2 outright. |
| **Play Store distribution** | Sideloading from GitHub Releases is the intended path. Play policy would force changes to the battery-optimisation prompt and the in-app updater. |
| **Bluetooth headset mic routing (A.4)** | Declined 2026-07-30 after the phone mic tested well at 16 kHz. HFP audio is telephony-processed and likely transcribes worse, and the most probable bug is a silent fallback to the internal mic — July's failure again. Reopen only if hands-free capture becomes a real need; see §1 above. |

## Release gates

Before cutting a release:

1. Build check green (`assembleDebug`, `assembleRelease`, unit tests).
2. Signing secrets configured — see [release-signing.md](release-signing.md).
3. Version bumped in `app/build.gradle.kts` and the README.
4. `CHANGELOG.md` updated.
5. Device-tested: background during upload, pause/resume duration, upload retry.
