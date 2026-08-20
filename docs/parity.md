# Stow ↔ Stow Web parity

Two apps, one idea: [Stow](https://github.com/mds08011/stow) on Android, [Stow Web](https://github.com/mds08011/stow-web) as a static page for iPad and iPhone. This is the single source of truth for what they share, where they deliberately differ, and how divergence is prevented.

**Lockstep is not the goal.** Background recording is impossible in a web page; a Quick Settings tile is meaningless there. Meanwhile Stow Web has crash recovery and a rate-limit countdown Android lacks. Forcing feature parity would mean either building things that cannot exist or suppressing good platform work.

The actual goal is narrower: **things that are supposed to be identical are provably identical, and everything else differs on purpose rather than by accident.**

## The shared contract

These must match byte-for-byte across both repos. They are checked mechanically — see below.

| Item | Android | Web |
|---|---|---|
| Clean prose prompt | `PolishPresets.DEFAULT_CLEAN_PROSE_PROMPT` | `DEFAULT_CLEAN_PROSE_PROMPT` |
| Task capture prompt | `PolishPresets.DEFAULT_TASK_CAPTURE_PROMPT` | `DEFAULT_TASK_CAPTURE_PROMPT` |
| Preset ids | `ID_CLEAN_PROSE`, `ID_TASK_CAPTURE` | same names |
| Preset display names | `DEFAULT_*_NAME` | same names |
| Jargon placeholder | `JARGON_PLACEHOLDER` = `{{JARGON_LIST}}` | same |
| Transcription model | `AudioTranscriber.MODEL_TURBO` | `MODELS.transcribe` |
| Polish model | `TranscriptionPolisher.MODEL` | `MODELS.polish` |

> ⚠️ **Open divergence as of v2.8 (2026-08-19).** Android moved the polish model from `llama-3.1-8b-instant` to `openai/gpt-oss-20b`; Stow Web has not been updated. **The parity check will fail until it is.** This is not a false alarm — Groq stopped serving the Llama chat models in August 2026, so Stow Web's polish is broken in exactly the way Android's was, and the fix there is the same one-line change plus the reasoning parameters described in [polishing-prompt.md](polishing-prompt.md#the-polish-model-is-a-setting). Whisper is unaffected on both sides.
>
> Note that on Android the model is now a **user-editable setting** with `TranscriptionPolisher.MODEL` as its default. The contract is over the *default*, which is what the checker reads; a user who overrides it on one device has simply diverged from the web app on purpose, and no check can or should catch that.

Two behaviours were ported **from** Stow Web in v2.7, and their logic should stay aligned:

- **Retry-after parsing** — take the larger of the `Retry-After` header and the prose in the body (`try again in 2m59.56s`), round up, floor at 3 s, cap at 15 min. `RecordingService.retryAfterSeconds` ↔ `parseRetryAfter`.
- **Crash recovery** — checkpoint the recording the moment it stops, before the first upload, and offer it back on next launch. Android uses SharedPreferences plus the retained audio file; Web uses IndexedDB.

Also expected to stay aligned, but **not** yet machine-checked:

- Polish failure behaviour — a failed polish never yields partial or empty text; it reports why and leaves the raw transcript standing. Android v2.8 also rejects a `finish_reason == "length"` truncation, which Web should adopt.

- Jargon semantics — appended to the preset prompt, or substituted at `{{JARGON_LIST}}` if present; also sent as the Whisper biasing prompt.
- Daily usage wording and the 8 h / 28 800 s free-tier figure, so the two meters read interchangeably.
- Transcription request shape: `language=en`, `temperature=0`, and `prompt` omitted entirely when the jargon dictionary is empty.

## Feature parity

| Capability | Android | Web | Intent |
|---|---|---|---|
| Record → transcribe → polish | ✅ | ✅ | **Shared** |
| Polish presets, editable, two built-ins | ✅ | ✅ | **Shared** |
| Jargon dictionary | ✅ | ✅ | **Shared** |
| Daily free-tier usage meter | ✅ | ✅ | **Shared** — Web counts per-device only |
| Local history | ✅ JSON file | ✅ `localStorage`, 50 cap | **Shared intent**, different storage |
| Raw / polished toggle | ✅ | ✅ | **Shared** |
| Copy / share result | ✅ auto-copy | ✅ tap Copy | **Differs** — iOS needs a user gesture |
| Background recording | ✅ foreground service | ❌ | **Impossible on web** — browsers suspend hidden capture |
| Persistent notification with Stop | ✅ | ❌ | **Impossible on web** |
| Quick Settings tile | ✅ | ❌ | **Android only** |
| Pause / resume | ✅ | ❌ | **Not ported** |
| Upload retry after failure | ✅ | ✅ staged retry | **Shared** — Web retries per stage, Android re-uploads |
| Recording survives a crash | ✅ v2.7 | ✅ IndexedDB | **Shared** — ported from Web |
| Rate-limit countdown | ✅ v2.7 | ✅ | **Shared** — ported from Web |
| Route/mic reporting | ✅ v2.6 | ❌ | **Android only** — browsers do not expose this |
| Bluetooth headset mic | ❌ | ❌ | **Declined on Android**, impossible on web — both record from the device mic |
| Transcription quality warnings | ✅ v2.6 | ❌ | **Portable** — Web would need `verbose_json` |
| Retained audio + re-transcribe | ✅ v2.6 | ❌ | **Portable** |
| Edit result in place | ✅ | ❌ | **Not ported** |
| Search history | ✅ | ❌ | **Not ported** |
| Export all | ✅ | ❌ | **Not ported** |
| Re-polish a history entry | ✅ | ❌ | **Not ported** |
| In-app update check | ✅ | n/a | Web updates by reload |

## How drift is prevented

`stow-web/.github/check-prompt-drift.js` compares everything in the shared contract and fails on any difference, naming the first offending line. It lives in **one** repo — copying it would recreate the duplication it exists to catch.

It runs from both sides:

| Where | Workflow | When |
|---|---|---|
| stow-web | `prompt-drift.yml` | push touching `index.html`, PRs, Mondays 06:17 UTC |
| stow (Android) | `parity-check.yml` | push touching the three shared Kotlin files, PRs, Mondays 06:43 UTC |

The Android side clones stow-web and runs that repo's script against the local checkout, so there is still only one implementation.

Run it yourself:

```bash
node .github/check-prompt-drift.js ../stow    # from stow-web, against a local checkout
node .github/check-prompt-drift.js            # against mds08011/stow on GitHub
```

Kotlin sources are found by **filename**, never by package path — see the incident below.

## What went wrong on 2026-07-30

Worth recording, because it is the exact failure mode this page exists to prevent, and it happened in a single afternoon.

1. The Android package was renamed `com.example.stow` → `io.github.mds08011.stow`. The checker hardcoded the old path, so it stopped finding the file — reporting a missing file rather than drift.
2. In the same session the Clean prose prompt was revised (filler rule restructured; new rule 8 forbidding reformatting of numbers and units). Only Android got it.
3. The Whisper seed prompt `CAD, HVAC, structural load, thermodynamic, schematic` was removed from Android as wrong-domain noise crowding out real jargon. Stow Web kept sending it.

None of it was caught, because the only check ran weekly from the repo where the change *wasn't* happening. Three fixes followed: filename-based lookup, the reverse check on the Android side, and widening coverage past prompts to identifiers and models.

## When changing something shared

1. Change it in both repos, in the same sitting. The check will fail otherwise, and a red build a week later is a poor way to find out.
2. If the divergence is deliberate, update the tables above — an entry here is what turns "broken" into "decided".
3. If a shared Kotlin file is renamed or split, update `KT_FILES` in the checker.

## Roadmaps

Each repo keeps its own, because most work is platform-specific:

- Android: [roadmap.md](roadmap.md) — open items, declined features, release gates
- Web: the `Left out of v1` section of its README

Cross-cutting decisions belong **here**, not duplicated into both.
