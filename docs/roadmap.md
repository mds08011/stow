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

### 1. Decide whether A.4 is needed (blocked on one test, not on code)

The July 2026 investigation found the app has **never** used a Bluetooth headset microphone — `AudioSource.MIC` with no SCO routing always records from the phone. v2.6 made that visible but did not change it. A.4 would add real routing control.

**Do not build A.4 until this test has been run**, because it may not be worth building:

> Record a normal note with the phone at arm's reach, not in a pocket. Check the mic line on the main screen and in history detail.
>
> - If transcription quality is now fine, the July failure was **microphone distance**, not routing. A.4 becomes a convenience rather than a fix, and may be worth skipping entirely — Bluetooth SCO is narrowband 8–16 kHz and frequently transcribes *worse* than a phone mic held properly.
> - If quality is still poor with the phone close, then the audio path itself is suspect and A.4 moves back up.

If it does go ahead: [audio-implementation-prompts.md](audio-implementation-prompts.md) § A.4 is written and ready. It defaults to the phone mic, adds an explicit *Prefer Bluetooth mic / Always use phone mic* setting, and requires device testing that CI cannot do.

### 2. Device testing owed from v2.5 and v2.6

None of this is verifiable in CI, and none of it has been confirmed on hardware:

| What | Why it matters |
|---|---|
| Background the app during upload → notification → reopen | The A-1 data-loss fix; the highest-consequence unverified change |
| Exactly one history entry per recording | A duplicate would be quiet and easy to miss |
| Pause 30 s mid-recording → stored duration excludes it | Feeds the daily free-tier counter |
| Airplane-mode → **Retry last upload** | Recovers a note instead of losing it |
| Quick Settings tile start/stop | Never exercised on a device |
| Launcher icon against other home-screen icons | Geometry is correct; visual weight is a judgement call |

### 3. Tune the quality thresholds against real recordings

`LOGPROB_LIMIT`, `NO_SPEECH_LIMIT` and `COMPRESSION_RATIO_LIMIT` in `TranscriptionHistory` are Whisper's own defaults, **not** validated against this user's audio. Once there are a few flagged and unflagged notes, check for false positives and negatives and adjust. They are named constants for exactly this reason.

## Planned / open

Roughly in value order. See [assessment-2026-07.md](assessment-2026-07.md) for the reasoning behind each.

| Item | Notes |
|---|---|
| **A.4 — Bluetooth mic routing** | Gated on the test above. Prompt is written and ready |
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

## Release gates

Before cutting a release:

1. Build check green (`assembleDebug`, `assembleRelease`, unit tests).
2. Signing secrets configured — see [release-signing.md](release-signing.md).
3. Version bumped in `app/build.gradle.kts` and the README.
4. `CHANGELOG.md` updated.
5. Device-tested: background during upload, pause/resume duration, upload retry.
