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
- **Choose a Drive folder**: opens the full-screen folder picker at the app root to browse anywhere in My Drive, create a folder, and move there.

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

## Viewing photos and videos on the phone

In a Phone folder, a tap opens a photo or video full screen inside DriveLift, with the same
viewer as Drive: pinch, pan and double-tap zoom for photos (upright from the EXIF orientation),
and the same player for videos (Step, speed, skips, frame capture). Files are read through their
content URI on the phone; no Google account, token or network is involved. Back or Close returns
to the same folder and scroll position. Photos larger than 32 MB, or in a format the phone cannot
decode, show a notice with **Open in another app**.

Selecting: hold a thumbnail to select it (hold and drag selects a range); while anything is
selected, taps add or remove items. **Select all** and **Select day** work as before. With
TalkBack, a thumbnail's click opens it and its **Select** action selects it.

## Moving phone files to the trash

In a Phone folder, select files (hold a thumbnail) and tap the trash icon in the bottom bar, or
open a photo or video and tap the trash icon at the top. The files go to Android's trash
(`MediaStore.createTrashRequest`): they leave the phone's folders, and Android keeps them for a
limited time, usually 30 days, before deleting them for good. Restoring them needs a gallery app
that shows the phone's trash. Nothing on Google Drive changes, and no Google account or network
is needed.

Confirmation: with Media management allowed (Settings), DriveLift lists the files by name with
count and size and Android then completes the request without its preview. Without it, Android
shows its own dialog. Cancel changes nothing.

Files that are part of a transfer (waiting, uploading, being checked, or verified and waiting for
removal on Transfers) are left out of the batch and named; finish or cancel them in Transfers
first. DriveLift remembers exactly which files it sent to Android. After Android completes the
request, it reads each of those files back from MediaStore and reports how many are confirmed in
the trash, how many are still on the phone and how many could not be checked (for example after
media access was withdrawn). Files that left the folder before the request was sent are not
sent and are reported as such. A file missing from the listing is never counted as trashed.

## Moving Drive files to the trash

In the Drive tab, hold a file to select it (tap more to add them, or use Select all) and tap the
trash icon in the bottom bar; or open a file and tap the trash icon at the top. DriveLift names
the files with count and size and asks before doing anything. Confirmed files are moved to Google
Drive's trash with the connected account (`files.update trashed=true`): Google keeps them for 30
days, where they can be restored from Drive's trash, then deletes them for good. DriveLift never
deletes permanently or empties the trash, and nothing on the phone changes.

Left out and named: folders (trashing one would take everything inside it), and files a transfer
still depends on (being uploaded or checked, or verified and waiting for removal on Transfers),
so phone removal never relies on a copy that has gone to the trash. If the phone original was
already removed after verification, the confirmation warns that the Drive copy is the only one.
Each file is moved separately and the result is reported per file; files that moved disappear
from the folder and their thumbnails are dropped from the cache.

The batch belongs to the account that was connected when the confirmation was shown. Switching
accounts is refused while it runs. Each request is sent with a token for that account, which
Drive itself confirms belongs to it (also after a token refresh). If the saved account changes
anyway, the remaining files are not tried and are reported as not tried.

A trash and the phone-removal flow never act on the same Drive copy at once
(`transfer/DriveCopyFence.kt`). While a copy is being trashed, the verifier cannot mark it
verified, and Transfers leaves its phone file out of any removal request. A copy trashed during
this session is never verified again; the next check finds it in the trash and uploads the file
again. While a removal request is open (including while the user is deciding), a trash leaves
the copies it relies on alone.

## Moving Drive files to another folder

In the Drive tab, select files and tap **Move**. Choose the folder in the Drive folder picker and
tap **Move here**; DriveLift then names the files, their size and the destination path and asks
before doing anything. Confirmed files are moved one by one (`files.update` with `addParents` and
`removeParents`). Each keeps its Drive id, content and history: nothing is copied, deleted or
changed on the phone. Access can change: Drive applies the new folder's sharing to a moved file
and drops access it had only through the old folder, so a private file moved into a shared
folder becomes visible to that folder's people. The confirmation says so. Before each file DriveLift reads where it is in Drive; a file
that is no longer in the folder it was selected in is reported and left alone, and Drive's answer
must show it in the new folder and not the old one.

Left in place and named: folders, and files a transfer still depends on (being uploaded or checked,
or verified and waiting for removal), so a transfer always finds its copy where it put it.
Choosing the folder the files are already in moves nothing. Like a trash, the batch runs only for
the account that was connected at confirmation, account switching is refused while it runs, and
on a change the remaining files are reported as not tried.

## Favorites

Tap the star on a phone folder (Phone tab), a Drive folder (Drive tab), or at the top of the
viewer of a phone photo or video or a Drive file. Once something is starred, a **Favorites** row
appears at the top of the Phone tab and of My Drive; it opens one list, phone items first, then
Google Drive. A folder opens in its own tab; a photo, video or Drive file opens in the viewer.
Tapping the filled star (or Remove) takes an item out of favorites. A favorite is only a pointer:
starring or removing never copies, moves or changes the file.

Favorites are kept in the app's database (`favorites` table, `data/Favorites.kt`) and survive
restarts. Phone files are keyed by their MediaStore id (so a file moved to another phone folder
is still found), phone folders by their bucket id. Drive items are keyed by the Drive id plus a
one-way SHA-256 of the account email they were starred with; the email itself is not stored in
the database. The database went from version 1 to 2 with a real migration that only adds this
table, so updating keeps the transfer ledger; the old wipe-on-upgrade fallback is gone (only a
downgrade, an older build installed over a newer one, starts a fresh database).

Each time the list is shown DriveLift works out where every favorite is now:

| Situation | Shown as |
|---|---|
| Phone file or folder in the current listing | Opens |
| Phone listing still loading | Checking |
| Photo access off | Unavailable: allow access |
| Partial photo access and not visible | Unavailable: not visible with the current access (not called deleted) |
| Phone file gone from a full listing (deleted, trashed, recopied by another app) | Unavailable |
| Phone folder with no photos or videos (emptied, renamed or deleted) | Unavailable |
| Drive not connected | Unavailable: connect |
| Starred with another Google account | Unavailable: connect that account. Never looked up |
| Drive item found (also after a move to another Drive folder) | Opens |
| In Drive's trash / not found / access refused | Unavailable, with that reason |
| The lookup failed (offline, authorization expired) | Unavailable, with Try again |

Drive favorites of the connected account are looked up one by one with `files.get` and a token
proven to belong to that account; nothing is sent for favorites of any other account. An
unavailable favorite stays listed until the user removes it. Limits: phone favorites cover what
DriveLift lists (photos and videos); a folder renamed on the phone gets a new bucket id, so its
favorite shows as unavailable; a Drive folder opened from Favorites appears directly under My
Drive in the path line, whatever its real parent is.

## Viewing Drive videos

Drive videos stream inside DriveLift in their original quality (Google's API serves only the
original file). **Step back** and **Step forward** move by about one frame. They are shown
while the video plays too; a tap pauses it first and the picture stays on the stepped frame.

Step speed: each step is a precise seek, so the player decodes from the nearest earlier keyframe
up to the target frame; on a Drive video that may also mean fetching media again. To keep this
bounded, only one step seek runs at a time. Taps made while it runs move the target on (so five
quick taps still land five frames on) and the newest target is sought as soon as the current frame
is shown. A step counts as done only when a frame whose own presentation time is that step's target is
shown (Media3 reports each frame's timestamp), so a late frame from an older seek cannot release
a newer one. Taps that return to the frame already on screen need no second seek. The player keeps 15 seconds
of already-streamed media behind the playing position, so a step or skip back within it does not
fetch from Drive again. A step that shows no frame within 2 seconds stops holding back the next one;
that timer belongs to its own step and cannot affect a later one.

Step latency has not been measured on a phone yet. Each step writes to the local log only
(`adb logcat -s DriveLiftStep`; nothing leaves the phone): its target, how many taps it covers, and
the time from its first tap, its last tap and its seek to the frame. Timed-out and superseded steps
are logged as such.

Known limit: a step is computed from the video's reported frame rate (30 fps when the file does
not report one). For constant-frame-rate video each step lands exactly on the neighbouring frame.
For variable-frame-rate video (phones record this way in low light) or a file with no reported
rate, a step can occasionally repeat or skip a frame. Exact adjacent-frame navigation needs the
real timestamp of each frame and is not finished; it is tracked on issue #21.

## Source map

| File | Responsibility |
|---|---|
| `DriveLiftApp.kt` | App start, shared objects, restart of unfinished work |
| `data/Media.kt` | Phone photos and videos, permissions, opening original bytes |
| `data/Db.kt` | `transfers` and `presets` tables and their queries, database version |
| `data/Favorites.kt` | `favorites` table, migration 1 to 2, where each favorite is now |
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
| `ui/Favorites.kt` | Favorites list, star button, way in from Phone and Drive |
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
