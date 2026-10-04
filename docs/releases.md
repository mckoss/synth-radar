# Builds and releases

CI (`.github/workflows/ci.yml`) runs on every push and pull request: Python tool tests, Flutter
format/analyze/test, and an arm64 release APK. Each run uploads the APK as a workflow
artifact. Some runs also **publish a GitHub Release** with the APK attached:

| Trigger | Release | Tag |
|---|---|---|
| Push to `main` | Normal release (becomes "latest") | `build-<run number>` |
| Push a tag `v*` (e.g. `v0.2.0`) | Normal release | the tag |
| **Actions → CI → Run workflow** on any branch (release box checked) | Pre-release | `build-<run number>` |
| Push to any other branch, or a pull request | None (artifact only) | – |

Version name is `0.1.<run number>` and version code is the run number, so every build installs
over the previous one, as long as all builds are signed with the same key (below).

## Installing on the phone

Always-current download link for the newest build from `main`:

<https://github.com/mckoss/synth-radar/releases/latest/download/synth-radar.apk>

Open it on the Pixel. The first time, allow installs from your browser and tap **Install
anyway** if Play Protect warns. Each release also has a versioned copy
(`synth-radar-0.1.N.apk`).

## One-time setup: a release signing key

Android only installs an update over an existing app if both are signed with the **same key**.
Without a key in the repository secrets, CI signs each build with a throwaway debug key. Then
every new build has to be uninstalled and reinstalled, and **uninstalling deletes the
recordings stored in the app**. So set up a key once, on your Mac:

```sh
# 1. Create the key (pick a strong password; keep this file backed up somewhere safe).
keytool -genkeypair -v -keystore ~/synth-radar-release.jks \
  -alias synthradar -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Synth Radar"

# 2. Store it in the repository's Actions secrets (requires the GitHub CLI: brew install gh).
base64 -i ~/synth-radar-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R mckoss/synth-radar
gh secret set ANDROID_KEYSTORE_PASSWORD -R mckoss/synth-radar   # paste the password
gh secret set ANDROID_KEY_ALIAS -R mckoss/synth-radar --body synthradar
```

`ANDROID_KEY_PASSWORD` is optional; it defaults to the keystore password, which is what
`keytool` uses unless you chose a different one. Without the CLI, add the same secrets under
**GitHub → repository → Settings → Secrets and variables → Actions → New repository secret**.

If you already installed a debug-signed build, uninstall it once (share any recordings you want
to keep first), then install the first release-signed build. From then on, updates install in
place.

Never commit the keystore or `app/android/key.properties`; both are git-ignored.

## Building locally

```sh
cd app
flutter build apk --release --target-platform android-arm64
```

To sign locally with the release key, create `app/android/key.properties`:

```properties
storeFile=/Users/<you>/synth-radar-release.jks
storePassword=...
keyAlias=synthradar
keyPassword=...
```
