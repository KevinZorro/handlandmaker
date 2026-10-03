# Distributing test builds (Firebase App Distribution)

`.github/workflows/distribute.yml` builds a signed release APK and sends it to the Firebase tester
group on every push to `master`. It can also be run by hand (Actions → *Distribute to testers* →
*Run workflow*) with release notes. Testers get an email and install the app from the
**App Tester** app on their phone.

`versionCode` is the workflow run number, so every build installs as an upgrade over the previous one.

## One-time setup

### 1. Firebase project and app
1. [Firebase console](https://console.firebase.google.com) → *Add project* (e.g. `signaco`).
   Google Analytics is not needed.
2. *Add app* → Android, package name `com.google.mediapipe.examples.handlandmarker` (the
   `applicationId` in `app/build.gradle`). Skip downloading `google-services.json`: App
   Distribution does not need it.
3. *Project settings* → *General* → copy the **App ID** (`1:…:android:…`).
4. *Release & Monitor* → *App Distribution* → *Get started*. Under *Testers & Groups*, create a
   group with the alias `equipo` and add the team's emails.

### 2. Service account (lets CI upload)
1. [Google Cloud console](https://console.cloud.google.com/iam-admin/serviceaccounts), same project →
   *Create service account* (e.g. `github-distribution`), role **Firebase App Distribution Admin**.
2. *Keys* → *Add key* → JSON. Keep the file out of the repo and delete it after step 4.

### 3. Release keystore (keeps updates installable)
All builds must be signed with the same key, or Android refuses to update the installed app.
```bash
keytool -genkeypair -v -keystore signaco-release.jks -alias signaco \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 signaco-release.jks   # macOS: base64 -i signaco-release.jks
```
Store the `.jks` and its passwords in a password manager: losing them means testers must uninstall
to get new builds. `*.jks` is git-ignored.

### 4. GitHub secrets
Repository → *Settings* → *Secrets and variables* → *Actions*:

| Secret | Value |
|---|---|
| `FIREBASE_APP_ID` | App ID from step 1.3 |
| `FIREBASE_SERVICE_ACCOUNT` | Full contents of the JSON key from step 2 |
| `SIGNING_KEYSTORE_BASE64` | Output of `base64` in step 3 |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | `signaco` (or the alias you chose) |
| `SIGNING_KEY_PASSWORD` | Key password |

Optional variable `FIREBASE_TESTER_GROUPS`: comma-separated group aliases (default `equipo`).
The workflow fails at its first step and names any missing secret.

## For testers
Accept the invitation email, install App Tester when prompted, then install SignaCO from it.
New builds show up there; Android may ask to allow installs from App Tester.
