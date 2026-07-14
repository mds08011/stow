# Stow

A lightweight, highly accurate Android dictation app built for speed, privacy, and battery efficiency.

Unlike heavy offline apps that drain battery and overheat your phone by running massive AI models locally, **Stow** acts as a lightning-fast API wrapper. It records your audio and offloads the heavy lifting to the [Groq API](https://groq.com/), utilizing the `whisper-large-v3-turbo` model for near-instant, desktop-grade transcriptions directly on your mobile device.

## Features
* **Massive AI Models, Zero Hardware Tax:** Uses Whisper Large-v3-Turbo without competing with Android's system processes for memory.
* **Background Recording (Foreground Service):** Safely minimize the app, turn off your screen, or use other apps like Google Maps while Stow continues to record completely uninterrupted in the background. Stop recording directly from the persistent notification.
* **No Artificial Limits:** Record as long as you need without hardcoded 30-second cutoffs.
* **UI Timer & Usage Tracker:** Keep track of your current recording duration with a live on-screen Chronometer, and easily monitor your total daily API usage directly on the main screen so you stay within the 8-hour Groq Free limit.
* **Tap-to-Toggle Interface:** Simple UI. Tap to start recording, tap to stop. No annoying "push-to-hold" mechanics.
* **Instant Clipboard:** Once transcribed, the text is automatically copied to your Android clipboard for immediate use in any other app.
* **Optional Post-Transcription Polish:** Clean up fillers, false starts, and grammar with a fast Groq chat model. Use a one-tap Polish button, or enable auto-polish in Settings. Jargon Dictionary terms are applied for project names and technical vocabulary.
* **Battery Friendly:** Your phone simply records audio and waits; all processing happens in the cloud.
* **Secure API Key Management:** API keys are never hardcoded. You manage your key directly within the app, securely saved using Android SharedPreferences.
* **Dynamic Version Display:** Always know exactly which build you are running with a clean, dynamically generated version label.
* **Editable Jargon Dictionary:** Save custom vocabulary to ensure highly accurate transcriptions for industry-specific terms. Accessed via its own dedicated button on the main screen.
* **In-App History Viewer:** Instantly view and copy your past transcriptions directly within the app.
* **Material Design Icon:** Modern, minimal, adaptive vector launcher icon that looks great on any home screen.

## Screenshots

Add screenshots under a `screenshots/` folder (or link images from GitHub Releases) and embed them here when available. Suggested captures:

| Screen | What to show |
|--------|----------------|
| **Main screen** | Large Start Recording button, mic indicator (Internal / Bluetooth), live chronometer, daily usage line, empty transcription area, version label, and toolbar icons (Jargon, Info, Settings). |
| **Recording notification** | Android status bar / notification shade while recording: "Stow — Recording in progress..." with the **Stop Recording** action. Optionally a second shot of "Uploading and Transcribing..." after stop. |
| **Jargon editor** | Custom Vocabulary / Jargon dialog with comma-separated terms (e.g. project names, CAD, HVAC) and Save / Cancel. |
| **History** | Transcription History dialog listing timestamped past entries from the local log file. |

Example markdown once image files exist:

```markdown
| Main | Recording | Jargon | History |
|------|-----------|--------|---------|
| ![Main](screenshots/main.png) | ![Notification](screenshots/notification.png) | ![Jargon](screenshots/jargon.png) | ![History](screenshots/history.png) |
```

## Prerequisites
* A [Groq Console](https://console.groq.com/) account (Free Tier provides extensive usage).
* Your unique Groq API Key. **Note:** When you open Stow for the first time, you will be prompted to paste your free Groq API key to use the app.

## Installation

### Download the APK (recommended for most users)

1. Open the [Stow Releases](https://github.com/mds08011/stow/releases) page on GitHub.
2. Download the latest `.apk` asset (for example, `stow-app-v2.1.apk`).
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
5. Enter the desired version number (e.g., v2.1) and click the green 'Run workflow' button.

Once triggered, the automated pipeline will build the app, rename the artifact dynamically (e.g., stow-app-v2.1.apk), and attach it to a new GitHub Release page for easy downloading.

## Privacy & Permissions Explained

Stow requires a few permissions to function smoothly and securely:

* **RECORD_AUDIO:** Essential for capturing your dictation.
* **INTERNET:** Required to securely transmit your audio to the Groq API for transcription (and optionally to polish text via chat completions).
* **POST_NOTIFICATIONS & FOREGROUND_SERVICE:** Android requires these to allow the app to continue recording in the background. This ensures your dictation isn't interrupted even if you minimize Stow, turn off your screen, or use other apps like Google Maps. The persistent notification lets you know Stow is actively recording and gives you a quick way to stop it.

## Troubleshooting

### Microphone or notification permission denied
Stow needs **Microphone** access to record and (on Android 13+) **Notifications** for the foreground recording service. If recording fails with a permission toast, open **Settings → Apps → Stow → Permissions** and grant Microphone and Notifications, then try again.

### Recording stops when the screen is off or another app is open
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
* "No active internet connection" means Wi‑Fi or mobile data is unavailable—reconnect and stop/start a new recording if needed.
* Unstable networks can fail mid-upload; switch networks and try a shorter clip to verify.
* Corporate or captive portals that block `api.groq.com` will prevent transcription and polish.

### Jargon dictionary not improving accuracy enough
* Add terms as a **comma-separated** list (e.g. `AcmeCorp, Kubernetes, HVAC, load path`).
* Prefer exact spellings and product names you use often; jargon is sent as a Whisper prompt and reused when polishing.
* Extremely noisy audio or heavy accents may still need a second pass—use **Polish** after transcription for grammar and filler cleanup.
* Very long jargon lists can dilute the prompt; keep the list focused on high-value terms.

### Polish fails or seems unchanged
* Polish requires the same Groq API key and network access as transcription.
* If polish fails, the raw transcript remains available; you can still copy and edit it.
* Disable **Auto-polish** in Settings if you prefer the original one-step clipboard flow.

### App will not install (sideload)
* Enable install from unknown sources for the app you used to open the APK.
* Download the full APK again if the file was truncated.
* Uninstall any older debug build with a different signing key before installing a release APK, if Android reports a signature conflict.
