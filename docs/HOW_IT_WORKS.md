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
| `FAILED` | Cannot be moved by retrying (the phone file disappeared) |
| `CANCELLED` | You cancelled it |

## 1. Seeing your files

- **Phone**: `data/Media.kt` reads Android's MediaStore for every image and video and groups them
  by folder (Camera, Screenshots, WhatsApp Images and so on). A content observer refreshes the
  list when files change.
- **Drive**: `ui/DriveBrowser.kt` lists folders and files through the Drive v3 REST API
  (`drive/DriveClient.kt`). The same browser is the folder picker.

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

The original bytes are uploaded unchanged. The photo's capture time and modified time are set on
the Drive file, and location data inside the photo is preserved.

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
| App killed, phone restarted | On next start, unfinished rows are picked up automatically. |

You can also pause and resume everything, cancel one file or all waiting files, and restrict
uploads to Wi-Fi.

## 5. Verifying (`transfer/VerifyWorker.kt`)

After the uploads, a second job checks every `UPLOADED` file:

1. Fetch the Drive file's metadata: size, MD5 checksum, and whether it is in the trash.
2. Read the phone file again from disk and compute its MD5.
3. If size and MD5 both match, the file becomes `VERIFIED`.
4. If they do not match, or the Drive file is missing or trashed, the bad copy is moved to Drive's
   trash and the file goes back to `QUEUED` to be uploaded again.

Verification errors (for example no network) are retried the same way uploads are.

## 6. Removing from the phone

Only `VERIFIED` rows are ever offered for removal. The Transfers screen shows a red
**Remove N verified files from phone** button, and by default opens the prompt itself when a batch
finishes verifying while the app is open. Android then shows its own confirmation dialog listing
the count; this is a platform rule for any app deleting media it did not create, and it is the
last safety check. After you confirm, the rows become `REMOVED`.

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
| `transfer/VerifyWorker.kt` | The verification job |
| `transfer/Backoff.kt` | Retry timing and speed measurement |
| `transfer/LiveProgress.kt` | Live progress shared with the screens, error wording |
| `transfer/Notifications.kt` | Progress and result notifications |
| `ui/MainActivity.kt` | App shell, tabs, permission request |
| `ui/PhoneScreens.kt` | Folder list, photo grid, drag selection |
| `ui/DriveBrowser.kt` | Drive browser and folder picker |
| `ui/DestinationSheet.kt` | Destination chooser and presets |
| `ui/TransfersScreen.kt` | Progress, controls, removal |
| `ui/Theme.kt` | Colours, type, formatting helpers |

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
