# Release signing — one-time setup

Stow's release APKs are signed with a single, stable keystore so that updates install **in place**. This page is the one-time setup the maintainer performs; after it, releases work exactly as before (Actions → *Build and Release APK* → enter a version tag).

## Why this exists

Before v2.5 the release workflow ran `./gradlew assembleDebug` on a fresh GitHub runner. Runners have no `~/.android/debug.keystore`, so the Android Gradle Plugin generated a **new random debug key on every run**. Consequences:

- Android refused every in-place upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, so the in-app updater could never actually install anything.
- Upgrading meant uninstalling first, which **deletes all local transcription history**.

A fixed keystore fixes both, permanently.

## Step 1 — Create the keystore (once, on your machine)

Requires a JDK (`keytool` ships with it).

```bash
keytool -genkeypair -v -keystore stow-release.keystore -alias stow -keyalg RSA -keysize 2048 -validity 10950
```

You will be prompted for a keystore password, a key password, and some identity fields (name, organisation, country — any sensible values are fine for a sideloaded app). `-validity 10950` is 30 years; a key that expires would strand you in exactly this situation again.

> **Back this file up somewhere safe and private** — a password manager attachment, an encrypted drive, wherever you keep things you cannot regenerate. If you lose it, you cannot ship an upgradable release again without another forced uninstall, and every user loses their history a second time. Do **not** commit it; `.gitignore` already excludes `*.keystore` and `*.jks`.

## Step 2 — Encode it for GitHub

```bash
base64 -w0 stow-release.keystore > stow-release.keystore.b64
```

On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("stow-release.keystore")) | Set-Content stow-release.keystore.b64 -NoNewline
```

## Step 3 — Add four repository secrets

On GitHub: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Value |
|---|---|
| `STOW_KEYSTORE_BASE64` | the entire contents of `stow-release.keystore.b64` |
| `STOW_KEYSTORE_PASSWORD` | the keystore password from step 1 |
| `STOW_KEY_ALIAS` | `stow` (or whatever `-alias` you used) |
| `STOW_KEY_PASSWORD` | the key password from step 1 |

Delete the `.b64` file afterwards; it is as sensitive as the keystore itself.

## Step 4 — Release as usual

Actions → **Build and Release APK** → *Run workflow* → enter the tag (e.g. `v2.5`).

The workflow decodes the keystore into the runner's temp directory, builds `assembleRelease`, runs `apksigner verify --print-certs` so a mis-signed APK fails the build rather than reaching users, publishes the Release, and deletes the keystore from the runner. If `STOW_KEYSTORE_BASE64` is missing the workflow fails immediately with a pointer back here, rather than silently shipping an unsignable build.

## The one unavoidable migration

Any device running a build released **before** this change is signed with a throwaway key, so the first signed release cannot install over it.

For that upgrade only:

1. In Stow, open **History → Export all** and save the export somewhere off-device.
2. Uninstall Stow.
3. Install the new release APK.

Every release after that installs in place and keeps its history.

## How local builds behave

`app/build.gradle.kts` reads the signing config from environment variables:

| Variable | Meaning |
|---|---|
| `STOW_KEYSTORE_FILE` | absolute path to the keystore |
| `STOW_KEYSTORE_PASSWORD` | keystore password |
| `STOW_KEY_ALIAS` | key alias |
| `STOW_KEY_PASSWORD` | key password |

If `STOW_KEYSTORE_FILE` is unset, the release build type simply has no signing config and produces an unsigned APK — no error, and `assembleDebug` is entirely unaffected. Nothing about day-to-day development changes.
