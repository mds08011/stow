# Stow

A lightweight, highly accurate Android dictation app built for speed, privacy, and battery efficiency. 

Unlike heavy offline apps that drain battery and overheat your phone by running massive AI models locally, **GroqDictate** acts as a lightning-fast API wrapper. It records your audio and offloads the heavy lifting to the [Groq API](https://groq.com/), utilizing the `whisper-large-v3-turbo` model for near-instant, desktop-grade transcriptions directly on your mobile device.

## Features
* **Massive AI Models, Zero Hardware Tax:** Uses Whisper Large-v3-Turbo without competing with Android's system processes for memory.
* **No Artificial Limits:** Record as long as you need without hardcoded 30-second cutoffs.
* **Tap-to-Toggle Interface:** Simple UI. Tap to start recording, tap to stop. No annoying "push-to-hold" mechanics.
* **Instant Clipboard:** Once transcribed, the text is automatically copied to your Android clipboard for immediate use in any other app.
* **Battery Friendly:** Your phone simply records audio and waits; all processing happens in the cloud.

## Prerequisites
* A [Groq Console](https://console.groq.com/) account (Free Tier provides 2,000 requests/day).
* Your unique Groq API Key.

## How to Build (GitHub Codespaces)
This project is fully configured to be built in the cloud using GitHub Codespaces, requiring no local Android Studio installation.

1. **Open the Codespace:** Click the green `<> Code` button on this repository and launch a Codespace. The included `.devcontainer` will automatically provision a Linux environment with Java and the Android SDK.
2. **Add Your API Key:** Open `app/src/main/java/com/example/groqdictate/MainActivity.kt` and replace the `GROQ_API_KEY` placeholder string with your actual API key.
3. **Compile the App:** Open the Codespace terminal and run the following command to compile the application:
   ```bash
   ./gradlew assembleDebug