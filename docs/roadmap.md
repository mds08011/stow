# Roadmap

What Stow is for, what is planned, and — most usefully — what has been deliberately turned down.

## Principles

These are the constraints the app is built around. A change that conflicts with one of them should be rejected in review even if it is individually appealing.

1. **Low friction above all.** The path from speech to clean text on the clipboard is the product. Every added tap needs to earn itself.
2. **Free tier only.** Groq's free tier is a hard constraint, not a starting point. No paid APIs, no subscriptions, no usage that assumes a credit card.
3. **Light-touch polish.** Polish removes fillers and fixes spelling, grammar and punctuation, with minimal rephrasing. It never expands, summarises, formalises, or invents. Presets that restructure (Task capture) still never add content that was not spoken.
4. **Field-first.** Gloves, sunlight, marginal LTE, a phone in a pocket mid-upload. Reliability under those conditions beats features.
5. **Simple enough to maintain.** One activity, plain `SharedPreferences`, hand-built JSON, no architecture frameworks. The app is maintained by a non-professional developer working with AI tools; the code has to stay legible to that workflow.

## Planned / open

Roughly in value order. See [assessment-2026-07.md](assessment-2026-07.md) for the reasoning behind each.

| Item | Notes |
|---|---|
| Screenshots in the README | Needs a physical device; scaffold is in `screenshots/` |
| Legacy launcher icon below API 26 | Either add PNG fallbacks or raise `minSdk` to 26 |
| Dated result-field background | `@android:drawable/edit_text` is a Holo 9-patch; renders poorly in dark mode |
| Background upload errors are silent | A failed upload while backgrounded shows nothing until you reopen |
| Encrypted API key storage | Judged not worth the dependency; README wording corrected instead |

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
