# How DriveLift works

## The life of one file

Every selected file becomes one row in a local database (`transfers` table) and moves through
these states. The database is the source of truth, so the pipeline survives the app being closed,
the phone rebooting, or the process being killed.

```
QUEUED -> UPLOADING -> UPLOADED -> VERIFIED -> REMOVED
   ^                      |
   +---- checksum or size mismatch, or Drive copy missing
```

| State | Meaning |
|---|---|
| `QUEUED` | Waiting for its turn |
| `UPLOADING` | Bytes are being sent to Drive |
| `UPLOADED` | Drive accepted the whole file; not yet trusted |
| `VERIFIED` | Drive's copy matches the phone's copy byte for byte |
| `REMOVED` | Deleted from the phone after you confirmed |
| `FAILED` | Cannot be finished by retrying: the phone file disappeared before it was uploaded or before its Drive copy could be checked |
| `CANCELLED` | You cancelled it |

## 1. Seeing your files

- **Phone**: `data/Media.kt` reads Android's MediaStore for every image and video and groups them
  by folder (Camera, Screenshots, WhatsApp Images and so on). A content observer refreshes the
  list when files change.
- **Drive**: `ui/DriveBrowser.kt` lists folders and files through the Drive v3 REST API
  (`drive/DriveClient.kt`). A folder is listed to its end, however many entries it has. The same
  browser is the folder picker.
  Tapping a folder opens it. Tapping a file opens it outside DriveLift, in the Google Drive app
  if it is installed, otherwise in a browser; DriveLift has no viewer of its own. The link holds
  only the file's id. The folder picker lists folders only.

## 2. Selecting in bulk

In a folder (`ui/PhoneScreens.kt`):

- Tap a thumbnail to select or deselect it.
- Hold a thumbnail and drag: everything between the first thumbnail and your finger is selected,
  and the grid scrolls by itself near the top and bottom edges.
- **Select day** on any date header, **Select all** in the top bar.
- On the folder list, the upload icon on a row selects the whole folder in one tap.

## 3. Choosing where files go

`ui/DestinationSheet.kt` offers three kinds of destination:

- **Auto-organize**: creates `DriveLift Backup` in My Drive with one sub-folder per phone folder.
- **Presets**: Drive folders you pinned earlier. Tick "Keep this folder as a preset" when picking.
- **Choose a Drive folder**: browse anywhere in My Drive, create a folder, and move there.

## 4. Uploading (`transfer/TransferWorker.kt`)

A WorkManager job, shown as an ongoing notification, uploads one file at a time using Drive's
**resumable upload** protocol:

1. Ask Drive for an upload session and store its address in the file's database row.
2. Stream the file. Progress is published about four times a second: bytes sent, a five-second
   rolling speed, and from those the time remaining.
3. When Drive answers with the finished file, the row becomes `UPLOADED`.

Only the unredacted original is uploaded. The app asks Android for the original file, which needs
the media-location permission; if that is not available the file waits and the reason is shown.
It is never replaced by a location-stripped copy. The capture time and modified time are set on
the Drive file.

### What happens when something goes wrong

There is no retry limit. A file keeps being retried until the problem is gone.

| Problem | Reaction |
|---|---|
| Connection drops mid-file | The job stops. Android restarts it when a connection returns, even after a reboot. The app asks Drive how many bytes it already has and **continues from that byte**. |
| Any other error (timeout, Drive 5xx, rate limit, Drive storage full) | Shown on screen with the reason, retried after 2s, 4s, 8s ... up to 60s between tries, forever. |
| Sign-in expired | One silent token refresh. If Google needs you, a notification asks you to open the app and reconnect; uploads continue by themselves afterwards. |
| Reconnecting is cancelled or fails | The account connected before stays the saved one. Transfers keep going to it. |
| Another Google account is chosen | Refused while any transfer is waiting, uploading, unverified or failed, because those belong to the first account's Drive. Once they are finished, cancelled or cleared, the switch is allowed and the pinned destinations of the first account are forgotten. |
| No account has been saved yet | No Drive call is made in the background. The app never lets Google pick an account for a transfer. |
| Upload session expired (after about a week) | A new session is opened and that one file restarts. |
| Phone file deleted before upload | The file is marked `FAILED`. Nothing else is affected. |
| Photo access revoked, limited to photos only, videos only or selected items, or media location denied | Treated as recoverable, not as the file being gone. The reason is shown and the file is retried until access is back. |
| App killed, phone restarted | On next start, unfinished rows are picked up automatically. |

You can also pause and resume everything, cancel one file or all waiting files, and restrict
uploads to Wi-Fi.

## 5. Verifying (`transfer/VerifyWorker.kt`)

After the uploads, a second job checks every `UPLOADED` file:

1. Fetch the Drive file's metadata: size, MD5 checksum, and whether it is in the trash.
2. Read the phone file again from disk and compute its MD5.
3. If size and MD5 both match, the file becomes `VERIFIED`.
4. If they do not match, the bad copy is moved to Drive's trash first, and only then does the
   file go back to `QUEUED`. If trashing fails, the file stays `UPLOADED` with its Drive reference
   and the cleanup is retried, also after a restart, so a bad copy is never left behind unnoticed.
5. If the Drive file is missing or already trashed, the file goes back to `QUEUED`.
6. If the phone file is gone before it could be compared, nothing was proven: the file becomes
   `FAILED` with that reason and the unchecked Drive copy is left alone. It is never shown as
   verified or removed.

The decisions live in `transfer/Verifier.kt`, separate from Android, and are covered by
`TransferSafetyTest`.

Verification errors (for example no network) are retried the same way uploads are.

## 6. Removing from the phone

Only `VERIFIED` rows are ever offered for removal. The Transfers screen shows a red
**Remove N verified files from phone** button, and by default opens the prompt itself when a batch
finishes verifying while the app is open. Android then shows its own confirmation dialog listing
the count; this is a platform rule for any app deleting media it did not create, and it is the
last safety check. After you confirm, the rows become `REMOVED`.

A verified file that is no longer in the phone's media list is closed out as `REMOVED` without a
prompt, but only while the app can see every photo and every video. If access was revoked, or
covers photos only, videos only or selected items, "not visible" does not mean "gone", so those
files go to Android's prompt instead.

## Source map

| File | Responsibility |
|---|---|
| `DriveLiftApp.kt` | App start, shared objects, restart of unfinished work |
| `data/Media.kt` | Phone photos and videos, permissions, opening original bytes |
| `data/Db.kt` | `transfers` and `presets` tables and their queries |
| `data/Prefs.kt` | Wi-Fi only, auto-offer removal, paused, connected account email |
| `drive/DriveAuth.kt` | Google sign-in and access tokens, pinned to the connected account |
| `drive/DriveClient.kt` | Drive REST calls: list, create folder, resumable upload, metadata, trash |
| `transfer/TransferRepository.kt` | Queueing, starting and stopping the jobs |
| `transfer/TransferWorker.kt` | The upload job |
| `transfer/VerifyWorker.kt` | The verification job: reads the phone file, computes MD5, retries |
| `transfer/Verifier.kt` | The verification decisions and the removal close-out rule |
| `transfer/Backoff.kt` | Retry timing and speed measurement |
| `transfer/LiveProgress.kt` | Live progress shared with the screens, error wording |
| `transfer/Notifications.kt` | Progress and result notifications |
| `ui/MainActivity.kt` | App shell, tabs, permission request |
| `ui/PhoneScreens.kt` | Folder list, photo grid, drag selection |
| `ui/DriveBrowser.kt` | Drive browser and folder picker |
| `ui/DestinationSheet.kt` | Destination chooser and presets |
| `ui/TransfersScreen.kt` | Progress, controls, removal |
| `ui/Theme.kt` | The one dark theme: every colour, type, formatting helpers |
| `ui/Tokens.kt` | Every size, gap, shape and duration; reduced-motion handling |
| `ui/Glass.kt` | Backdrop, glass panel and bar, the dot progress gauge |

## Permissions

| Permission | Why |
|---|---|
| Photos and videos | To list and read the files you choose |
| Media location | So GPS data inside photos is not stripped from the uploaded copy |
| Notifications | Upload progress and "verified" alerts |
| Foreground service (data sync) | Lets long uploads keep running with the screen off |
| Internet, network state | Talking to Drive and waiting for a connection |

## Building

```bash
./gradlew testDebugUnitTest assembleDebug
```

GitHub Actions runs the same command on every push and attaches the APK to the run.
