# DriveLift

An Android app that moves the photos and videos on your phone into Google Drive, proves each
copy is intact, and only then removes the originals from the phone.

1. **Browse** every photo and video folder on the phone, and browse your Google Drive.
2. **Select** files: tap, hold and drag across a range, select a whole day, a whole folder, or everything.
3. **Choose a destination**: auto-organize, one of your preset folders, or any Drive folder (you can create one on the spot).
4. **Transfer** with live progress (percent, speed, time left, per-file state). Errors are retried until they clear.
5. **Verify**: a second job compares each phone file with its Drive copy by MD5 checksum and size.
6. **Remove**: verified files, and only verified files, are removed from the phone after you confirm Android's own prompt.

## Status

| Check | Result |
|---|---|
| Compiles, debug APK builds | Yes, locally and in GitHub Actions |
| Unit tests (retry timing, resume offset, speed meter, verification and removal safety, full folder listing) | 19 of 19 pass |
| Run on a real phone against a real Google account | Not yet. No device was attached when this was built |
| Colour contrast and touch-target audit | Not measured |

The first real run needs the one-time Google Cloud setup below. Start with a few unimportant files.

## Get it on your phone

1. Do the one-time setup in [docs/GOOGLE_SETUP.md](docs/GOOGLE_SETUP.md) (about ten minutes, free).
2. Download the APK: open the **Actions** tab of this repository, open the latest green run,
   and download the `drivelift-debug-apk` artifact. Or build it yourself:
   ```bash
   ./gradlew assembleDebug
   ```
   The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
3. Copy the APK to the phone and install it (Android asks you to allow installs from that source).
4. Open DriveLift, allow access to photos and videos, tap **Connect** and choose your Google account.

Requires Android 11 or newer.

## What it does not do

- **It does not read your Google Photos cloud library.** Google removed that ability for third-party
  apps on 31 March 2025; an app can now only see items it uploaded itself. DriveLift works on the
  files stored on the phone, which are the same device folders Google Photos shows under
  "On this device".
- **It does not delete anything from Google Photos' cloud.** If Google Photos backup is on, a backed-up
  copy stays in Google Photos after the phone file is removed. That is separate storage from Drive.
- **It does not delete silently.** Android requires a confirmation dialog for an app to delete media it
  did not create. DriveLift opens one dialog for the whole batch.

## How it works

See [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md) for the full pipeline, the retry rules, the
verification rules and a map of the source code.

## Project layout

```
app/src/main/java/com/drivelift/app/
  DriveLiftApp.kt          application + shared singletons
  data/                    phone media (MediaStore), Room database, settings
  drive/                   Google sign-in and the Drive REST client
  transfer/                upload worker, verify worker, retry logic, notifications
  ui/                      Compose screens
keystore/debug.keystore    shared debug signing key (see docs/GOOGLE_SETUP.md for why)
.github/workflows/         CI: tests + APK on every push
```
