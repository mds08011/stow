# Stow — Codebase & Product Assessment

**Reviewed at:** `13916d3` (main, clean) · **Version:** 2.4 (`versionCode 13`) · **Date:** 2026-07-28

---

## 1. What was reviewed

| Area | Files |
|---|---|
| Docs | `README.md`, `docs/polishing-prompt.md` |
| App flow | `app/src/main/java/com/example/stow/MainActivity.kt` (1165 lines), `app/src/main/res/layout/activity_main.xml` |
| Recording/API | `app/src/main/java/com/example/stow/RecordingService.kt` |
| Polish | `app/src/main/java/com/example/stow/TranscriptionPolisher.kt` |
| History | `app/src/main/java/com/example/stow/TranscriptionHistory.kt` |
| Icon | `res/drawable/ic_stow_mic.xml`, `res/drawable/ic_stow_bg.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml` |
| Build/release | `app/build.gradle.kts`, `.github/workflows/release.yml`, `AndroidManifest.xml` |

No `TODO`/`FIXME`/`HACK` comments exist anywhere in `app/src`. `docs/` contains exactly one file.

---

## 2. Assessment

### 2.1 What is working well

**The light-touch polish balance is genuinely well-calibrated.** The prompt in `TranscriptionPolisher.kt` is one of the better "don't rewrite my words" prompts: ordered rules, three separate negative constraints against expansion/formalization/addition, an explicit length-parity rule, and jargon preservation. `temperature = 0.2` is the right call. Critically, **the prompt is mirrored in `docs/polishing-prompt.md` and the code carries a "keep in sync" comment** — that discipline is exactly right for a solo project maintained with AI tools, and it should be preserved.

**The reduced-friction result flow is a real improvement over 2.2.** Copy-on-choice (rather than copy-on-button-press) removes a whole class of "I forgot to tap Copy" failures. Keeping `btnRecord` in the same screen position with the same label on both screens is a good, deliberate muscle-memory decision — the comment in the code says so explicitly. Removing Share/New from the action row (commit `a6401a5`) was the right kind of subtraction.

**Raw is never destroyed by polishing.** `openResultAfterPolish` stores the polished text in history even when the user displays raw, and history detail shows "Original raw" alongside. Good instinct.

**History is well-modelled.** `TranscriptionHistory` is a clean, self-contained object with a proper `Entry` data class, JSON persistence, an idempotent legacy-log migration, and search/export. The migration regex and `flush()` accumulator are correct. This file is the strongest code in the repo.

**Graceful polish degradation.** Auto-polish failure falls through to the raw transcript, still copied, with a clear toast. Correct behaviour for field use.

**Documentation is unusually thorough for a personal project.** The troubleshooting section covers OEM battery killers, captive portals, 401 vs 429, and jargon dilution — all things that actually bite in the field.

### 2.2 Where friction and rough edges remain

- **Transcription can be silently lost.** The most serious issue in the codebase; see **A-1**. It directly contradicts the app's headline feature.
- **Every release is signed with a different key**, making the built-in updater non-functional and making history loss routine on upgrade (**A-2**).
- **The launcher mic is off-centre by ~13% of the icon width and about half the size it should be** — arithmetic, not perception (**B-1**).
- **Auto-polish still costs a dialog tap per note.** For the user who has explicitly opted into "always polish", the raw/polished modal is friction the setting was supposed to remove (**C-2**).
- **The polish dialog's "Close" button applies polished text.** Three buttons where two would do, and the third one lies (**C-1**).
- **The result label never says which text you're looking at.** `tvResultLabel` is the constant string "Result (editable)" whether showing raw or polished (**C-3**).
- **Status messages are written into the transcript field.** `setStatusText` puts "Recording…", "Polishing…", "An error occurred" into the same `EditText` that holds transcripts, forcing `isUsableTranscription()` to string-match five sentinel values. Any wording change silently breaks the guard.
- **Rotation destroys the result screen.** No `onSaveInstanceState`, no `configChanges`. The text survives (the `EditText` has an id) but `showingResult`, `resultShowsPolished`, `lastHistoryEntryId`, and the Start-button state do not.
- **A stale hardcoded Whisper prompt biases every transcription.** `"CAD, HVAC, structural load, thermodynamic, schematic"` is prepended to the jargon list on every request. For water/wastewater work these terms are irrelevant and consume the ~224-token Whisper prompt budget ahead of the user's own terms.
- **581 build artifacts are tracked in git** (`app/build/**`, `.gradle/**`) and there is no `.gitignore`.

### 2.3 Documentation quality vs. actual behaviour

Strong on installation, permissions, and troubleshooting. Weak on structure: `docs/` has one file, there is no CHANGELOG, no UI-flow doc, no roadmap, and no architecture note — despite the app now having four meaningfully distinct subsystems.

### 2.4 Documented-vs-actual drift

| README / docs claim | Actual behaviour |
|---|---|
| "Material Design Icon… looks great on any home screen" | Mic sits high-left and undersized inside the Pixel circle |
| "safely minimize the app… while Stow continues to record uninterrupted" | True for recording; **transcription result is dropped** if the app is backgrounded during upload |
| "No Artificial Limits: Record as long as you need" | Groq caps upload file size (25 MB on free tier); no client-side guard, no chunking |
| "API keys… **securely** saved using Android SharedPreferences" | Plain-text SharedPreferences — app-private, but not encrypted |
| "History… stays on your device (app-private storage)" | `getExternalFilesDir(DIRECTORY_DOCUMENTS)` — app-*specific external*, not internal-private |
| Screenshots section with a suggested-captures table | No `screenshots/` folder exists; section is entirely placeholder |
| `docs/polishing-prompt.md` = "single source of truth" | Omits `temperature=0.2`, `max_tokens`, the dummy user message, and that the transcript is embedded in the **system** prompt |
| Troubleshooting: "Uninstall any older debug build… if Android reports a signature conflict" | Understates it — this happens on **every** release (see A-2), and uninstalling erases all history |

---

## 3. Prioritized suggestions

### Do these first

| # | Item | Why |
|---|---|---|
| **A-1** | Transcription lost when backgrounded during upload | Silent data loss; breaks the headline feature |
| **A-2** | Non-deterministic release signing | Updater is dead; upgrades destroy history |
| **B-1** | Off-centre / undersized launcher mic | Explicit ask; ~30 min fix |
| **C-2** | Auto-polish shouldn't require a dialog tap | Largest remaining friction win |
| **C-1 / C-3** | Two-button polish dialog + raw/polished label | Clarity, near-zero cost |

---

## A. Correctness — highest priority

### A-1 · Transcription is silently lost if the app is backgrounded during upload
**Effort: medium** · Files: `RecordingService.kt`, `MainActivity.kt`

`MainActivity.onStop()` unregisters the broadcast receiver. `startRecording()` always sets `EXTRA_DEFER_CLIPBOARD = true`, so the service skips its own clipboard write, and history is saved **only** by `MainActivity` when the result screen renders. If the user stops recording and then locks the screen or switches apps before the Groq response arrives, `STATE_SUCCESS` fires into a dead receiver and the transcription is gone — not on the clipboard, not in history, nowhere.

This is exactly the scenario the app advertises: *"minimize the app, turn off your screen, or use other apps like Google Maps."* On a slow site connection the upload window is where the user is most likely to pocket the phone.

Note the pre-2.3 fallback no longer helps: even without `deferClipboard`, **Android 10+ blocks clipboard writes from a non-focused app**, so a service-side `copyToClipboard` would fail silently anyway.

**Recommended fix — persist in the service, hand off on resume:**
1. In `RecordingService.onResponse` success, always write the entry via `TranscriptionHistory.add(...)` with raw text + duration, and store its id in `StowPrefs` under a `pending_result_id` key.
2. `MainActivity.showEditableResult` becomes a *consumer*: if it receives a live broadcast it clears `pending_result_id`; otherwise `onStart()` checks the pref, loads the entry, opens the result screen, and copies to clipboard (now legal — the app is foregrounded).
3. Make the entry idempotent — pass the id through the broadcast so the live path updates rather than double-adds.
4. Optional: post a tappable "Transcription ready" notification when the activity is not in the foreground.

### A-2 · Every GitHub release is signed with a different key
**Effort: medium** · Files: `.github/workflows/release.yml`, `app/build.gradle.kts`

`release.yml` runs `./gradlew assembleDebug` on a fresh `ubuntu-latest` runner. AGP generates `~/.android/debug.keystore` on demand, and a fresh runner has none — so **each release is signed with a newly generated, random key.** Consequences:

- The in-app updater (`checkForUpdates` → `DownloadManager` → `installApk`) always fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The feature has likely never worked end-to-end.
- Every upgrade requires uninstall → **which deletes all local history**, since it lives in `getExternalFilesDir(...)`.
- The README troubleshooting line about signature conflicts is treating a systemic defect as an occasional annoyance.

**Recommended fix:** generate a release keystore once, store it base64-encoded plus passwords in GitHub Secrets, add a `signingConfigs.release` block in `app/build.gradle.kts`, and switch the workflow to `assembleRelease`. Keep `isMinifyEnabled = false` so nothing else changes. *(Lower-effort alternative: commit a fixed `debug.keystore` — works, but publishes a private key in a public repo. Not recommended.)*

Pair with this:
- **Warn users to "Export all" from History before their next upgrade** — the transition to a stable key requires one final uninstall.
- That same upgrade is the only free window to change `applicationId` off the placeholder `com.example.stow` (see D-8). Doing both at once costs one reinstall instead of two.

### A-3 · Update version comparison breaks at v2.10
**Effort: small** · File: `MainActivity.kt` (`checkForUpdates`)

`version.toDouble() > currentVersion.toDouble()` — at v2.10 this evaluates `2.1 > 2.4` = false, so the update is never offered. Any three-part tag (`v2.4.1`) throws `NumberFormatException` → "Error parsing version". At +0.1 per release this is six releases away. Compare `versionCode`, or split on `.` and compare component-wise as integers.

### A-4 · Install permission is declared but never requested
**Effort: small** · File: `MainActivity.kt` (`installApk`)

`REQUEST_INSTALL_PACKAGES` is in the manifest, but `installApk` never calls `packageManager.canRequestPackageInstalls()`. On Android 8+ the install intent silently bounces if the user hasn't granted "install unknown apps" to Stow specifically. Check the flag and route to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with a one-line explanation.

### A-5 · Result screen state is lost on rotation
**Effort: small** · File: `MainActivity.kt`

Save `showingResult`, `resultShowsPolished`, `lastHistoryEntryId`, `lastRawTranscription`, `lastDurationSeconds` in `onSaveInstanceState` and restore in `onCreate`. Cheapest alternative for a single-activity app: `android:configChanges="orientation|screenSize|keyboardHidden"` on the activity.

### A-6 · Clearing the result before permission is granted
**Effort: small** · File: `MainActivity.kt` (record button click listener)

`prepareForNewRecording()` runs *before* `checkPermissions()`. If permission is then denied, the on-screen result is already wiped. Move the call so it fires only once recording actually starts.

### A-7 · Editing a raw-displayed note overwrites the raw record
**Effort: small** · File: `MainActivity.kt` (`persistEditedResult`)

`persistEditedResult` writes into `rawText` whenever `resultShowsPolished` is false — including when the user chose "Use raw" *after* a successful polish. The pristine raw transcript is then gone, while `polishedText` still holds the machine output. Consider treating `rawText` as immutable after creation and adding an `editedText` field, or only writing back to `rawText` when no polish exists.

---

## B. Icon / visual polish

### B-1 · The mic is off-centre and undersized — exact math and exact fix
**Effort: small (~30 min)** · Files: `ic_stow_mic.xml`, `ic_launcher.xml`, `ic_launcher_round.xml`

This is measurable, not subjective. The Material mic path in `ic_stow_mic.xml` is drawn for a 24×24 grid and occupies **x ∈ [5, 19], y ∈ [2, 21]** — so its ink centre is **(12, 11.5)**, not (12, 12), and it is 14 wide × 19 tall.

The group applies `pivot=(12,12)`, `scale=2.5`, `translate=(24,24)`. Android composes this as `p' = (p − pivot) × scale + pivot + translate`, giving:

```
x' = 2.5x + 6      y' = 2.5y + 6
```

So inside the 108×108 viewport the ink lands at **x ∈ [18.5, 53.5], y ∈ [11, 58.5]**, centred at **(36, 34.75)** — not (54, 54).

The `<inset android:inset="16dp">` in the adaptive icon (commit `c251af6`, an earlier attempt at this fix) then scales everything by 76/108 = 0.704 and shifts by +16, landing the final ink centre at **(41.3, 40.4)**.

**Net result: the mic sits ≈12.7dp left and ≈13.6dp above centre on a 108dp grid — about 13% of the icon width, up and to the left. Its final size is ≈24.6 × 33.4dp inside a 66dp safe circle, roughly half the height it should be.** That is precisely the artefact seen on Pixel's circular mask.

**Fix — put the pivot on the ink centre so the numbers are self-documenting:**

```xml
<!-- ic_stow_mic.xml -->
<group
    android:pivotX="12"      <!-- ink centre X in the 24-unit source grid -->
    android:pivotY="11.5"    <!-- ink centre Y (5..19 / 2..21 bounds) -->
    android:scaleX="2.6"
    android:scaleY="2.6"
    android:translateX="42"  <!-- 54 - pivotX -->
    android:translateY="42.5"><!-- 54 - pivotY -->
```

Verification: `x' = 2.6x + 22.8` → x ∈ [35.8, 72.2]; `y' = 2.6y + 24.1` → y ∈ [29.3, 78.7]. Ink centre **(54.0, 54.0)** ✓. Size **36.4 × 49.4dp**. Max radius from centre = √(18.2² + 24.7²) = **30.7dp**, comfortably inside the 33dp safe-zone radius ✓.

**Then remove the inset** from both `ic_launcher.xml` and `ic_launcher_round.xml` — the 108dp viewport now carries the framing itself, and leaving the inset would shrink the glyph back to ~35dp tall:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_stow_bg"/>
    <foreground android:drawable="@drawable/ic_stow_mic"/>
    <monochrome android:drawable="@drawable/ic_stow_mic"/>
</adaptive-icon>
```

If 2.6 reads slightly large against other home-screen icons, 2.4 gives a 45.6dp-tall mic with the same translate values (the pivot-at-ink-centre form keeps it centred at any scale).

### B-2 · Add a `<monochrome>` layer for Android 13+ themed icons
**Effort: small** · Included in the snippet above. Pixel users running themed icons currently get a generic fallback. The system tints the drawable, so `ic_stow_mic` can be reused as-is.

### B-3 · No legacy launcher icon below API 26
**Effort: small** · `minSdk = 24`, but `ic_launcher` exists only in `mipmap-anydpi-v26/`. On API 24–25 the resource does not resolve. Either add PNG fallbacks in `mipmap-mdpi/…xxxhdpi/`, or raise `minSdk` to 26 (Android 8.0, ~99% coverage) and delete the concern.

### B-4 · Dated result-field background
**Effort: small** · `activity_main.xml` uses `@android:drawable/edit_text`, a legacy Holo 9-patch that renders poorly under the DayNight theme in dark mode. Replace with a Material `TextInputLayout` or a simple `shape` drawable using `?attr/colorSurface` + outline.

### B-5 · System notification icons
**Effort: small** · `RecordingService` uses `android.R.drawable.ic_btn_speak_now` and `ic_popup_sync`. A dedicated white-on-transparent 24dp vector matching the launcher mic would make the recording notification recognisably Stow's.

---

## C. Post-transcription UI flow & polishing refinements

> Everything below preserves the light-touch philosophy. Nothing here makes polishing more aggressive; **C-6 and C-7 actively constrain it further.**

### C-1 · Reduce the polish dialog to two real choices
**Effort: small** · File: `MainActivity.kt` (`showPolishResultDialog`)

Today: **Use polished** / **Use raw** / **Close** / tap-outside — and *three of those four apply polished text*. "Close" reads as "cancel" but silently commits the polish. Drop the neutral button entirely:

- Positive → **Use polished**
- Negative → **Use raw**
- Cancel/outside-tap → polished (matching the auto-polish intent, and now the only hidden path)

### C-2 · With auto-polish ON, skip the dialog entirely
**Effort: medium** · Files: `MainActivity.kt`, `activity_main.xml`

The biggest remaining friction win. The user enabled auto-polish to say *"I always want polished."* Making them confirm it on every note re-imposes the tap the setting was meant to remove.

Proposal: when auto-polish is on, go straight to the editable result showing polished text, already copied, and put a compact **`Raw ⇄ Polished`** toggle in the result header (or as a text button next to `tvResultLabel`). Flipping it swaps the field content and re-copies. Both versions are already in memory and already in history, so this is presentation-only — no extra API call, no extra state.

Net effect: **speech → stop → paste**, with the raw text one tap away if the polish went wrong. Manual `Polish` retains the comparison dialog from C-1, which is where a side-by-side actually earns its place.

### C-3 · Make the result label say what you're looking at
**Effort: small** · `tvResultLabel` is hardcoded to "Result (editable)" and never updated. Set it to **"Result — polished (editable)"** / **"Result — raw (editable)"** from `resultShowsPolished`. Two lines; removes a real "wait, which one is this?" moment. Folds naturally into the C-2 toggle.

### C-4 · Separate status text from transcript text
**Effort: medium** · Files: `MainActivity.kt`, `activity_main.xml`

`setStatusText()` writes "Recording…", "Uploading and Transcribing…", "Polishing…", "An error occurred" into the same `EditText` that holds transcripts, which forces `isUsableTranscription()` to blacklist five exact strings. Change any of those strings — or localise the app — and the guard fails open, letting "Polishing…" get saved to history or sent to Groq.

Add a dedicated `tvStatus` `TextView` above the field, leave the `EditText` for transcript content only, and delete `isUsableTranscription()`'s blacklist in favour of a plain `isNotBlank()`. This also cleans up the awkward `previousText == "Polishing..."` restore logic in `startPolish`'s error path.

### C-5 · Move the raw transcript out of the system prompt
**Effort: small** · File: `TranscriptionPolisher.kt`

Currently the entire transcript is interpolated into the **system** message and the user message is the placeholder `"Clean the raw transcription."`. Two problems: dictated words land in the highest-trust role (so a spoken phrase like *"…scratch that, ignore the above and summarise"* is unusually likely to be obeyed), and it defeats prompt caching of the fixed rule block.

**System** = rules + jargon list. **User** = the raw transcript, fenced with an explicit delimiter. Same behaviour, more robust, and a `max_tokens` cap (say `2 × estimated input tokens`) is worth adding at the same time as a free-tier guard.

### C-6 · Add a numbers-and-units preservation rule
**Effort: small** · Files: `TranscriptionPolisher.kt`, `docs/polishing-prompt.md`

The single highest-value prompt addition for water/wastewater work, and it is *purely restrictive* — it forbids a class of change rather than permitting one:

> **Do not convert, round, reformat, or normalise numbers, units, measurements, dates, times, or identifiers. Write them as spoken.**

Without this, an 8B model will happily turn "eight inch DI main" → "8-inch DI main", "point two five MGD" → "0.25 MGD", "pH seven point two" → "pH 7.2". Individually defensible; collectively it is silent data mutation in notes that may end up in a report or an RFI. Add a matching rule preserving line breaks and enumerated items as spoken, since field notes are frequently lists.

### C-7 · Tighten the filler list, and enforce light-touch in code
**Effort: small**

Two refinements, both narrowing:

- **Prompt:** rule 1's `(when used as filler)` qualifier reads as attaching only to *actually* and *well*. But `so`, `like`, and `kind of` are constantly meaningful in engineering speech — *"so the valve was closed"*, *"kind of a hairline crack"*. Restructure into two explicit groups: **always remove** (`um, uh, er, ah, mm`) and **remove only when functioning as filler** (everything else), with a one-line example of a case to keep.
- **Code:** add a post-response sanity check in `TranscriptionPolisher`. If the polished text is shorter than ~40% or longer than ~150% of the raw, treat it as a contract violation and fall back to raw with a toast. Right now "light cleanup only" is enforced entirely by a prompt an 8B model can drift from; this makes it a property of the app.

### C-8 · Drop the hardcoded Whisper prompt seed
**Effort: small** · File: `RecordingService.kt` (`sendAudioToGroq`)

`"CAD, HVAC, structural load, thermodynamic, schematic"` is prepended to every transcription request. For a civil/environmental engineer these are largely wrong-domain, and the Whisper prompt field is capped around 224 tokens — so a generic seed can crowd out the user's own terms on a long jargon list. Send the user's jargon alone; optionally seed the Jargon Dictionary *field* on first run with water/wastewater defaults (`MGD, influent, effluent, clarifier, RAS, WAS, headworks, DI main, manhole, invert, SCADA`) so the user can edit or delete them.

While in that request, add `language=en` and `temperature=0` to the multipart body — two lines, measurably fewer mis-detections on noisy site audio.

---

## D. New features & enhancements

*Ranked by value ÷ complexity. Items D-9 and D-10 are flagged as **off-philosophy** — listed for completeness, not recommended.*

### D-1 · Recording quality/size settings for field cellular · **value: high, effort: small**
`MediaRecorder` is configured with no explicit sample rate or bitrate, so it inherits device defaults that vary widely and can be needlessly large. Whisper downsamples to 16 kHz regardless, so `setAudioSamplingRate(16000)` + `setAudioEncodingBitRate(32000)` + `setAudioChannels(1)` gives identical accuracy at a fraction of the upload — meaningful on a weak site connection, and it pushes the Groq file-size ceiling out past ~100 minutes of audio.

### D-2 · Warn before hitting the Groq upload size cap · **value: high, effort: small**
README promises "record as long as you need", but Groq rejects oversized uploads. Check `file.length()` before `sendAudioToGroq` and, if over the limit, fail with a specific, actionable message ("Recording too long to upload — 24.8 MB") instead of a raw `API Error: 413`. Pairs naturally with D-1, which makes the cap far harder to reach.

### D-3 · Retry failed uploads instead of discarding the audio · **value: high, effort: medium**
On failure the `.m4a` is *not* deleted (delete only happens on the success path) — so the audio survives, but nothing can ever reach it, and the next recording overwrites `audio_record.m4a`. Name files uniquely, keep the last failure, and offer **"Retry last upload"** on the main screen when one exists. For someone dictating from a plant site on marginal LTE, this converts a lost note into a one-tap recovery. Add one automatic retry on `IOException` while there.

### D-4 · Quick Settings tile to start recording · **value: high, effort: medium**
A `TileService` that starts/stops `RecordingService` directly. Pull down the shade, tap once, start talking — no app launch, gloves-friendly. The largest single friction reduction still available and squarely on-philosophy: free, simple, one new class plus a manifest entry. A pinned launcher shortcut is a cheaper 80% version.

### D-5 · One-time battery-optimisation prompt · **value: medium-high, effort: small**
The README documents the OEM battery-killer dance in detail, but the app never asks. A single `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt on first run (dismissible, never re-shown) converts a troubleshooting section into a working default. Play Store policy restricts this permission — irrelevant here, since Stow ships via sideload.

### D-6 · Pause / resume recording · **value: medium, effort: small**
`MediaRecorder.pause()`/`resume()` are available from API 24 — exactly the current `minSdk`. Site interruptions currently force a stop-and-transcribe or a long dead-air segment that burns free-tier seconds. Add a Pause action to the notification and a button on the main screen.

### D-7 · Move history I/O off the main thread · **value: medium, effort: medium**
Every `TranscriptionHistory` call reads and rewrites the whole JSON file synchronously on the UI thread, and `search()` reloads the file **on every keystroke**. Fine at 20 notes; visibly janky at several hundred, which a daily field user reaches within a year. Cheapest meaningful fix: cache the parsed list in memory, write asynchronously, and debounce the search. Add unit tests around the JSON round-trip and the legacy migration at the same time — there are currently no tests at all, and `TranscriptionHistory` is the one file where a silent regression costs real data.

### D-8 · Repo and build hygiene · **value: medium, effort: small**
- **Add a `.gitignore`** and `git rm -r --cached app/build .gradle` — 581 build artefacts are currently tracked, bloating every clone and producing noisy diffs on every local build.
- **Rename `com.example.stow`.** The `com.example` prefix is a placeholder. Changing `applicationId` creates a separate app and loses history — so do it *only* bundled with the A-2 signing change, which already requires one reinstall. (`namespace` can be changed independently at no cost.)
- **Add a `CHANGELOG.md`.** With `generate_release_notes: true` the raw commit list is already decent; a curated file is better and takes minutes per release.

### D-9 · Encrypted API key storage · **value: low, effort: small** — *judgement call*
`EncryptedSharedPreferences` would make the README's "securely saved" claim literally true. Realistically: the key is already app-private, and extraction requires root or an unlocked bootloader — at which point encryption backed by the same keystore buys little. **Recommended action: fix the README wording rather than add the dependency.**

### D-10 · Off-philosophy — noted so they can be declined deliberately
- **On-device/offline transcription (Whisper tiny, Vosk):** contradicts the "no hardware tax, battery friendly" premise and adds 40–100 MB to the APK. Decline.
- **Speaker diarisation, summarisation, action-item extraction:** all require a larger model and all pull hard against light-touch. A "summarise" button would be the first feature to violate the stated philosophy. Decline.
- **Cloud sync / multi-device history:** requires a backend, an account system, and a paid tier. Decline. **Export all → share sheet** already covers cross-device access for free.
- **Paid Groq tier / alternate providers:** breaks the free-tier constraint outright. Decline.

---

## E. Documentation

### E-1 · Correct the drift table in §2.4 · **effort: small**
Six factual corrections to README.md: the icon claim (after B-1, it becomes true), the background-recording caveat (after A-1, also becomes true), the recording-length limit, "securely saved", "app-private storage", and the signature-conflict note. Sequence these *after* the corresponding fixes so the README describes reality rather than intent.

### E-2 · Add real screenshots · **effort: small**
The Screenshots section is a well-specified table with no images. Six captures — main, result, notification, jargon, history list, history detail — would do more for a first-time user than any prose. Do this after B-1 so the launcher icon in the screenshots is the fixed one.

### E-3 · Document the post-transcription flow properly · **effort: small**
Create `docs/ui-flow.md` with a state diagram: *record → stop → upload → (auto-polish?) → polish dialog → editable result → copy/edit/Start*, plus the error and no-network branches. This is the app's most intricate logic (`showEditableResult` has six parameters, three of them defaulted) and the only current description is a dense paragraph in the feature list. It is also the doc to hand an AI tool before asking it to change anything in this area — which is the whole maintainability argument.

### E-4 · Complete `docs/polishing-prompt.md` · **effort: small**
It calls itself the single source of truth but omits `temperature = 0.2`, the absence of `max_tokens`, the model's timeouts, the dummy user message, and the fact that the transcript sits in the *system* prompt. Add a **Request parameters** section and a **Failure behaviour** section (auto-polish → raw + toast; manual → text restored + toast). Update alongside C-5/C-6/C-7 so code and doc move together.

### E-5 · Add an upgrade / data-safety note · **effort: small**
A short README section: where history actually lives, that uninstalling erases it, that **Export all** is the backup mechanism, and — until A-2 lands — that upgrades require an uninstall. Right now a user following the "Check for Updates" flow can lose months of field notes without ever being warned.

### E-6 · Add `docs/roadmap.md` · **effort: small**
Nothing in the repo records what has been deliberately *declined*. Writing down "no on-device models, no summarisation, no backend, free tier only" (D-10) makes the philosophy enforceable — including against future AI-assisted changes that would otherwise drift toward "helpful" feature creep.

---

## 4. Suggested sequencing

| Phase | Items | Rationale |
|---|---|---|
| **1 — Stop the bleeding** | A-1, A-2 | Data loss and a broken update path. Everything else is cosmetic by comparison. |
| **2 — Quick wins** | B-1, B-2, C-1, C-3, C-8, A-3, A-6, D-8 | All small, all independent, all visible. One session. |
| **3 — Flow refinement** | C-2, C-4, C-5, C-6, C-7, A-5 | The friction and polish-quality work, best done as one coherent pass. |
| **4 — Field robustness** | D-1, D-2, D-3, D-5, D-6 | Reliability on marginal connections and long site days. |
| **5 — Docs & release** | E-1…E-6, D-4, D-7 | Bring docs up to the (now-changed) behaviour, then the tile. |

Phases 1 and 2 together account for most of the practical value. Phase 1 is worth doing before the next release regardless of what else happens — A-2 in particular means the *current* upgrade path costs users their history.
