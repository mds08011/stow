# Stow

A lightweight, highly accurate Android dictation app built for speed, privacy, and battery efficiency.

Unlike heavy offline apps that drain battery and overheat your phone by running massive AI models locally, **Stow** acts as a lightning-fast API wrapper. It records your audio and offloads the heavy lifting to the [Groq API](https://groq.com/), utilizing the `whisper-large-v3-turbo` model for near-instant, desktop-grade transcriptions directly on your mobile device.

**Current version: 2.6**

## Features
* **Massive AI Models, Zero Hardware Tax:** Uses Whisper Large-v3-Turbo without competing with Android's system processes for memory.
* **Background Recording (Foreground Service):** Safely minimize the app, turn off your screen, or use other apps like Google Maps while Stow continues to record completely uninterrupted in the background. Stop recording directly from the persistent notification.
* **Nothing Is Lost If You Walk Away:** The transcription is saved to history the moment it arrives, even if Stow is not on screen. Lock your phone or switch apps while the upload finishes and you get a **Transcription ready** notification; tapping it (or just reopening Stow) restores the result screen with the text copied to your clipboard.
* **No Artificial Limits:** Record as long as you need without hardcoded 30-second cutoffs. Audio is captured at 16 kHz mono (what Whisper uses anyway), so an hour of dictation is roughly 14 MB — well inside Groq's upload limit and quick to send on patchy site data.
* **Pause & Resume:** Interrupted mid-note? Pause from the notification or the main screen and pick up where you left off. Paused time is excluded from the note's duration and your daily usage.
* **Upload Retry:** A dropped connection retries once automatically. If it still fails, the audio is kept and a **Retry last upload** button appears on the main screen — a lost note becomes one tap instead of a re-dictation.
* **UI Timer & Usage Tracker:** Keep track of your current recording duration with a live on-screen Chronometer, and easily monitor your total daily API usage directly on the main screen so you stay within the 8-hour Groq Free limit.
* **Tap-to-Toggle Interface:** Simple UI. Tap to start recording, tap to stop. No annoying "push-to-hold" mechanics.
* **Honest Microphone Indicator:** The main screen reports the microphone the recorder is **actually** using, read back from the recorder after it starts — e.g. *Mic: Phone mic · 16 kHz*. Every note stores its microphone and sample rate, visible in history detail and included in exports.

  > **Stow currently records from the phone's microphone.** It does not yet route audio to a Bluetooth headset mic, even when one is connected — a connected headset is used for playback only. Earlier versions displayed "Bluetooth Mic" whenever a headset was merely paired, which was misleading; see [docs/audio-investigation-2026-07.md](docs/audio-investigation-2026-07.md). For best results, keep the phone within arm's reach and out of a pocket while dictating.

* **Quick Settings Tile:** Add Stow to your quick settings and start or stop a recording straight from the notification shade — no app launch, works with gloves on. Add it via the pencil/edit button in your quick settings panel.
* **Editable Result Screen:** After you choose raw or polished (or when transcription finishes without polish), Stow **immediately copies** the selected text to the clipboard and opens an editable multi-line result field so you can fix typos if needed. The field is labelled **Result — raw (editable)** or **Result — polished (editable)** so you always know which version you are looking at. The bottom action is **Copy** only (refresh the clipboard after edits). A **Start** button at the top (same style and position as the main recording screen) begins a brand-new recording and clears the current editable text.
* **Optional Post-Transcription Polish:** One-tap **Polish** button or optional auto-polish in Settings; choose **Use Raw** or **Use Polished** to copy that text and open the editable result screen. Uses a free-tier-friendly Groq chat model and applies Jargon Dictionary terms for project names and technical vocabulary. See [docs/polishing-prompt.md](docs/polishing-prompt.md) for the prompt design and message structure.
* **Polish Presets:** Polish behavior is driven by named, editable presets. Two ship built in:
  * **Clean prose** — the original light cleanup: removes fillers, fixes spelling/grammar/punctuation, applies only very light rephrasing. Does not rewrite, expand, or change the speaker’s voice.
  * **Task capture** — turns a jumbled dictated capture into Markdown, splitting spoken action items into `- [ ]` checkboxes grouped by job (or Personal / Unsorted) and leaving everything else as prose under **Notes**.

  Pick the preset from the compact selector next to **Polish**; the choice is remembered and is what auto-polish uses. With auto-polish on there is **no confirmation step** — you land straight on the editable result with the polished text already copied, and a **Show raw / Show polished** toggle in the result header if you want the other version. Add your own presets, edit any prompt, rename, and delete custom ones under **Settings → Polish presets…**. The two built-ins can be reset to their shipped text but never deleted.
* **Battery Friendly:** Your phone simply records audio and waits; all processing happens in the cloud.
* **Your Own API Key:** Keys are never hardcoded. You manage your key inside the app, stored in app-private `SharedPreferences` on your device. It is not encrypted at rest, and it never leaves the phone except in calls to Groq.
* **Dynamic Version Display:** Always know exactly which build you are running with a clean, dynamically generated version label (shows **v2.6** for this release).
* **Editable Jargon Dictionary:** Save custom vocabulary to ensure highly accurate transcriptions for industry-specific terms. Accessed via its own dedicated button on the main screen.
* **Full Local History:** Structured on-device history for each note (timestamp, duration when available, raw text, and polished text when polished). Browse a clean list with date/time and preview, **search by keyword**, open a detail view to copy, share, re-polish, edit/save, delete, or export a single note. Export the entire history as plain text via the system share sheet. Storage is local only (JSON under app Documents; legacy plain-text logs are migrated automatically).
* **Material Design Icon:** Minimal adaptive vector launcher icon, correctly centred inside the circular mask, with a monochrome layer for Android 13+ themed icons.

## Screenshots

_Captures pending — see [screenshots/README.md](screenshots/README.md) for the shot list. The table below starts rendering as soon as the files are added._

| Main | Result | History | Detail |
|------|--------|---------|--------|
| ![Main](screenshots/main.png) | ![Result](screenshots/result.png) | ![History](screenshots/history.png) | ![Detail](screenshots/detail.png) |

## Documentation

| Doc | Contents |
|-----|----------|
| [docs/ui-flow.md](docs/ui-flow.md) | How a recording becomes text: state diagram, who owns each transition, error branches |
| [docs/polishing-prompt.md](docs/polishing-prompt.md) | Polish preset system, both built-in prompts verbatim, request parameters, failure behaviour |
| [docs/release-signing.md](docs/release-signing.md) | One-time keystore and GitHub Secrets setup for releases |
| [docs/roadmap.md](docs/roadmap.md) | Principles, what is planned, and what has been deliberately declined |
| [docs/parity.md](docs/parity.md) | What this app shares with [Stow Web](https://github.com/mds08011/stow-web), where they deliberately differ, and how drift is caught |
| [docs/assessment-2026-07.md](docs/assessment-2026-07.md) | Full codebase review the current work plan came from |
| [docs/audio-investigation-2026-07.md](docs/audio-investigation-2026-07.md) | Why dictations garbled in July 2026 — audio routing findings and fix plan |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

## Prerequisites
* A [Groq Console](https://console.groq.com/) account (Free Tier provides extensive usage).
* Your unique Groq API Key. **Note:** When you open Stow for the first time, you will be prompted to paste your free Groq API key to use the app.

## Installation

### Download the APK (recommended for most users)

1. Open the [Stow Releases](https://github.com/mds08011/stow/releases) page on GitHub.
2. Download the latest `.apk` asset (for example, `stow-app-v2.6.apk`).
3. On your Android device, open the downloaded file (from Chrome Downloads, Files, or your email client).
4. If prompted, allow installation from that source:
   * **Android 8+:** Tap **Settings** when asked to allow installs from this app, enable **Allow from this source**, then return and install.
   * Or go to **Settings → Apps → Special app access → Install unknown apps** and enable it for your browser/file manager.
5. Confirm the install and open **Stow**.
6. On first launch, paste your Groq API key when prompted.

Stow is not distributed on the Play Store by default; sideloading from GitHub Releases is the normal install path.

### Build from source

1. Clone the repository or open it in GitHub Codespaces / Android Studio.
2. From the project root, compile a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install the generated APK from `app/build/outputs/apk/debug/` with `adb install` or by copying it to the device.

## How to Trigger a New Release

This project uses a GitHub Actions workflow to automatically compile and publish the Android APK.

You can trigger a new release directly from the GitHub web interface:

1. Go to the GitHub repository in a web browser.
2. Click the 'Actions' tab.
3. Click 'Build and Release APK' on the left sidebar.
4. Click the 'Run workflow' dropdown button on the right side.
5. Enter the desired version number (e.g., v2.6) and click the green 'Run workflow' button.

Once triggered, the automated pipeline will build a **signed** release APK, verify the signature, rename the artifact dynamically (e.g., stow-app-v2.6.apk), and attach it to a new GitHub Release page for easy downloading.

Releasing requires four repository secrets holding the signing keystore. This is a one-time setup — see [docs/release-signing.md](docs/release-signing.md). Without them the workflow fails immediately rather than publishing an APK that cannot be upgraded in place.

## Upgrading & data safety

History lives in app-specific storage on your device. It is **not** backed up to any server, and **uninstalling Stow deletes it**. Before any upgrade that requires an uninstall, open **History → Export all** and save the export via the share sheet.

From v2.5 onward every release is signed with the same key, so updates install in place and history survives. Upgrading *from* a pre-v2.5 build is the one exception and needs a single uninstall/reinstall — export first.

## Privacy & Permissions Explained

Stow requires a few permissions to function smoothly and securely:

* **RECORD_AUDIO:** Essential for capturing your dictation.
* **INTERNET:** Required to securely transmit your audio to the Groq API for transcription (and optionally to polish text via chat completions).
* **POST_NOTIFICATIONS & FOREGROUND_SERVICE:** Android requires these to allow the app to continue recording in the background. This ensures your dictation isn't interrupted even if you minimize Stow, turn off your screen, or use other apps like Google Maps. The persistent notification lets you know Stow is actively recording and gives you a quick way to stop it, and a second notification tells you when a transcription finished while you were in another app.

History and notes stay on your device, in app-specific storage (`Android/data/io.github.mds08011.stow/files/Documents`). Other apps cannot read it, but it is **not** backed up anywhere and **clearing app data or uninstalling deletes it** — use **History → Export all** to keep a copy. Stow does not upload history to any server beyond the transcription and polish API calls you initiate.

## Troubleshooting

### Microphone or notification permission denied
Stow needs **Microphone** access to record and (on Android 13+) **Notifications** for the foreground recording service. If recording fails with a permission toast, open **Settings → Apps → Stow → Permissions** and grant Microphone and Notifications, then try again.

### Recording stops when the screen is off or another app is open
Stow asks once on first launch whether to run unrestricted, which handles this on most devices — if you tapped **Not now**, or your OEM needs more, work through the steps below.

Aggressive battery savers can kill background work. While recording, keep Stow's notification visible and:
1. Open **Settings → Apps → Stow → Battery** (wording varies by OEM).
2. Set battery usage to **Unrestricted** / disable battery optimization for Stow.
3. On some devices (Xiaomi, Huawei, Samsung, OnePlus), also allow **Autostart** or exclude Stow from "sleeping apps" lists.
4. Do not swipe Stow away from Recents if your OEM treats that as force-stop.

### API key errors or empty transcriptions
* Confirm the key in Stow **Settings** matches a valid key from [console.groq.com](https://console.groq.com/).
* Ensure the key has not been revoked and that your Groq account still has quota.
* HTTP 401 usually means an invalid or missing key; regenerate the key and paste it again.
* HTTP 429 means rate or daily limits were hit; wait and retry, or check usage on the main screen (8h free-tier reference).

### Network / upload errors
* **Your audio is not lost.** When an upload fails the recording stays on the device and a **Retry last upload** button appears on the main screen — reconnect and tap it. Stow also retries once by itself before giving up.
* "No active internet connection" means Wi‑Fi or mobile data is unavailable—reconnect and use **Retry last upload**.
* Corporate or captive portals that block `api.groq.com` will prevent transcription and polish.
* "Recording too large to upload" means the clip exceeded Groq's ~25 MB limit (over two hours at Stow's bitrate). The audio is kept, but it needs splitting before it can be sent.
* The saved audio is cleared once any transcription succeeds, so only the most recent failure is ever retryable.

### Permissions and notifications
* **POST_NOTIFICATIONS** is required for the recording notification, the pause/resume controls, and the "Transcription ready" hand-off. Denying it on Android 13+ blocks recording entirely.

### Transcription is garbled, repetitive, or contains phrases you never said
Stow now flags this itself: when the transcription model reports poor decoding, the result screen shows **"Transcription may be unreliable"** with a reason, and the note is marked ⚠ in history. The reasons map to what went wrong:

| Warning | Meaning |
|---|---|
| **repetitive output** | The model looped — a classic response to low-quality audio |
| **mostly silence or noise** | It heard little actual speech; the mic was too far away or the environment too loud |
| **low confidence** | It was unsure throughout, usually distance or background noise |

The usual cause is microphone distance.

**To investigate a bad note:** open it in **View History → More…**. While the recording is still on device you get **Share audio** (pull the .m4a off the phone) and **Re-transcribe…** (re-run it through `whisper-large-v3`, the slower non-turbo model, and compare side by side before deciding whether to replace). Stow keeps the last 5 recordings for a week; clear them any time from Settings.

* **Check the mic line** on the main screen or in the note's history detail. If it says *Phone mic*, the audio came from the handset — a connected Bluetooth headset is **not** used for recording.
* Keep the phone out of a pocket and within arm's reach. A phone recording from a jacket pocket in a moving car produces exactly this failure.
* Repetition ("and then add that in, and then add that in"), fluent-but-wrong words, and phrases from nowhere are all signatures of degraded input rather than a bug in the transcription request.
* Add project vocabulary to the Jargon Dictionary — it will not rescue bad audio, but it helps borderline recordings.

### Jargon dictionary not improving accuracy enough
* Add terms as a **comma-separated** list (e.g. `MGD, influent, clarifier, RAS, headworks, DI main, invert, SCADA, P2-141`).
* Your terms are the **only** thing sent as the Whisper prompt — Stow no longer prepends a generic word list, so nothing competes with your vocabulary for the prompt's limited budget.
* Prefer exact spellings, project names, and job numbers you use often; jargon is sent as a Whisper prompt and reused when polishing.
* Extremely noisy audio or heavy accents may still need a second pass—use **Polish** after transcription for light filler, spelling, and grammar cleanup.
* Very long jargon lists can dilute the prompt; keep the list focused on high-value terms.

### Polish fails or seems unchanged
* Polish requires the same Groq API key and network access as transcription and is designed to stay within Groq free-tier limits.
* Polish behavior depends on the selected preset. **Clean prose** only does light cleanup (fillers, spelling/grammar/punctuation, minimal rephrasing) and will not heavily rewrite or expand short notes; **Task capture** deliberately restructures the capture into Markdown checkboxes and headings. Check the preset name next to the **Polish** button if the output is not what you expected. See [docs/polishing-prompt.md](docs/polishing-prompt.md).
* If polish fails, the raw transcript is still copied and offered on the editable result screen.
* Disable **Auto-polish** in Settings if you prefer to go straight from transcription to the editable result screen with the raw text (still auto-copied).

### History search, export, or re-polish
* History is local only. Clearing app data removes notes.
* **Export all** and **Export note** use the Android share sheet (save to Files, email, messaging, etc.).
* **Re-polish** uses the same light polish prompt as the main **Polish** button and needs network access.
* Older installs that only had a plain-text log are migrated into structured history on first open of the new history UI.

### App will not install (sideload)
* Enable install from unknown sources for the app you used to open the APK.
* Download the full APK again if the file was truncated.
* **Upgrading from a pre-v2.5 build needs a manual uninstall.** Releases before v2.5 were each signed with a throwaway key, and the app's package ID changed from `com.example.stow` to `io.github.mds08011.stow` — so Android sees v2.5 as a different app rather than an update, and may leave both installed. Open **History → Export all** and save the export, then uninstall the old Stow, install the new APK, and re-enter your API key and jargon terms. One-time only: v2.5 and later install over each other normally. See [docs/release-signing.md](docs/release-signing.md).
