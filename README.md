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
* **Battery Friendly:** Your phone simply records audio and waits; all processing happens in the cloud.
* **Secure API Key Management:** API keys are never hardcoded. You manage your key directly within the app, securely saved using Android SharedPreferences.

## Download
You can download the initial build here: [stow-app-v0.1](app/build/outputs/apk/debug/app-debug.apk)

## Prerequisites
* A [Groq Console](https://console.groq.com/) account (Free Tier provides extensive usage).
* Your unique Groq API Key. **Note:** When you open Stow for the first time, you will be prompted to paste your free Groq API key to use the app.

## How to Build (GitHub Codespaces / Windows)
This project is fully configured to be built locally or in the cloud.

1. **Clone the Repository or Open in Codespaces.**
2. **Compile the App:** Open the terminal in the project root and run the following command to compile the application:
   ```bash
   ./gradlew assembleDebug
   ```

## How to Trigger a New Release

This project uses a GitHub Actions workflow to automatically compile and publish the Android APK.

To trigger a new release, use the following Git tag commands in your terminal:

```bash
git tag v1.0 # Replace 'v1.0' with the desired version number
git push origin v1.0 # Ensure the pushed tag matches the local tag you just created
```

Once the tag is pushed to GitHub, the automated pipeline will build the app, rename the artifact dynamically (e.g., stow-app-v1.0.apk), and attach it to a new GitHub Release page for easy downloading.

## Privacy & Permissions Explained

Stow requires a few permissions to function smoothly and securely:

* **RECORD_AUDIO:** Essential for capturing your dictation.
* **INTERNET:** Required to securely transmit your audio to the Groq API for transcription.
* **POST_NOTIFICATIONS & FOREGROUND_SERVICE:** Android requires these to allow the app to continue recording in the background. This ensures your dictation isn't interrupted even if you minimize Stow, turn off your screen, or use other apps like Google Maps. The persistent notification lets you know Stow is actively recording and gives you a quick way to stop it.