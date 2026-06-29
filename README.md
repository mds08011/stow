# Stow

A lightweight, highly accurate Android dictation app built for speed, privacy, and battery efficiency. 

Unlike heavy offline apps that drain battery and overheat your phone by running massive AI models locally, **Stow** acts as a lightning-fast API wrapper. It records your audio and offloads the heavy lifting to the [Groq API](https://groq.com/), utilizing the `whisper-large-v3-turbo` model for near-instant, desktop-grade transcriptions directly on your mobile device.

## Features
* **Massive AI Models, Zero Hardware Tax:** Uses Whisper Large-v3-Turbo without competing with Android's system processes for memory.
* **No Artificial Limits:** Record as long as you need without hardcoded 30-second cutoffs.
* **Tap-to-Toggle Interface:** Simple UI. Tap to start recording, tap to stop. No annoying "push-to-hold" mechanics.
* **Instant Clipboard:** Once transcribed, the text is automatically copied to your Android clipboard for immediate use in any other app.
* **Battery Friendly:** Your phone simply records audio and waits; all processing happens in the cloud.
* **Secure API Key Management:** API keys are never hardcoded. You manage your key directly within the app, securely saved using Android SharedPreferences.

## Prerequisites
* A [Groq Console](https://console.groq.com/) account (Free Tier provides extensive usage).
* Your unique Groq API Key. **Note:** When you open Stow for the first time, you will be prompted to paste your free Groq API key to use the app.

## How to Build (GitHub Codespaces / Windows)
This project is fully configured to be built locally or in the cloud.

1. **Clone the Repository or Open in Codespaces.**
2. **Compile the App:** Open the terminal in the project root and run the following command to compile the application:
   ```bash
   ./gradlew assembleDebug