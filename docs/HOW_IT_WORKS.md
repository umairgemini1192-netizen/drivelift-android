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
- **Thumbnails** come from Android's own thumbnail store (`data/MediaThumbnails.kt`), which
  makes each one once and keeps it. Only when Android has none for a file is the original
  decoded, as before.
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

## Storage insights (`ui/StorageScreen.kt`, `data/StorageInsights.kt`)

Open it from the Storage row on the Phone tab. It measures two things, each labelled with its
scope and the time it was read:

- **Phone photos and videos DriveLift can see**: the same MediaStore listing the Phone tab uses.
  The total, photos against videos by count and bytes, the folders by size, and the largest files
  (tap one to open it in the viewer). With "selected photos only" access the figures cover only
  the selected photos, and the screen says so.
- **Google Drive quota** of the connected account (`about.storageQuota`): used and total, read
  when Drive connected or when you tap refresh. The quota is shared by Drive, Gmail and Google
  Photos, so it is not DriveLift's usage alone.

Not covered: documents, audio, downloads, APK files, and other apps' files and caches. Measuring
them needs a scoped-storage design of its own, planned for later. No permission was added.

### Google Drive folders (`ui/DriveFolderInsights.kt`, `data/DriveFolderSizes.kt`)

Tap Count Drive folders. DriveLift reads every item the connected account can see that is not in
the trash, 1,000 per page (`files.list`, fields `id,name,mimeType,size,parents,ownedByMe`), and
builds the folder tree from the parents in memory, so a large Drive costs one request per 1,000
items, not one per folder. Read only: nothing on Drive is changed. The count shows items and pages
read, has a Stop button that ends it after the current page, and keeps going if you leave the
screen.

- Each folder shows its name, file count and size. Totals include every file in subfolders, and
  each file is counted once in a folder however many of its paths lead there (older Drive data
  can give a file several parents). The eight largest folders are listed first; Show more adds 25
  at a time. Tap a folder to list the folders inside it and the files directly in it.
- Only items inside My Drive count. Shared drives and items shared with you but not added to My
  Drive are not counted; the screen names how many such items there are.
- Shortcuts are counted as links and never followed, so the file a shortcut points to is not
  counted twice.
- Google Docs, Sheets, Slides and similar files report no size. They are counted, and the screen
  says how many have no size; they add nothing to the sizes.
- Bytes in files other people own are included in the folder sizes and named separately, since
  they do not use this account's quota.
- These are file sizes in folders, not the quota above, which Drive, Gmail and Google Photos share.

A page that fails is tried again twice. If it still fails, or you press Stop, or the count reaches
500,000 items, the figures read so far are shown marked Incomplete with the reason, and Count
again starts over. If nothing could be read the screen says why and offers Try again, or
Reconnect for a sign-in problem. A count belongs to the account it started for: if the saved
account changes it is stopped and discarded.

### Duplicate candidates

Tap Check for duplicates. Files are first grouped by exact byte size; only files that share a
size with another file are read. Each of those is read in full (the original bytes, as the
verifier reads them) and its SHA-256 checksum computed, in a background job with a progress bar
and a Stop button; it keeps going if you leave the screen and stops if the app is closed. Files
with the same size and the same checksum form a group. Names are never used, files that could not
be read are left out of every group, and empty files are skipped. A group is a candidate, not a
statement that a copy is safe to delete.

### Cleanup

Nothing is selected after a check. Inside a group you tick the copies you want gone; the last
unticked copy of a group cannot be ticked, so every group keeps one. Copies that are part of a
transfer (waiting, uploading, being checked, or verified and waiting for removal) cannot be
ticked and are marked.

Move to trash first reads every ticked copy and the copies meant to stay again, and checks
transfers again. DriveLift's confirmation then names the copies that go, their count and total
size, the copy that stays in each group, and every copy left out with its reason (part of a
transfer, content changed or unreadable, would leave no copy, no longer on the phone). Confirming
reads the copies again. Then, in one step right before Android is asked, DriveLift reads the
transfers and the phone listing again and reserves the copies going to the trash and the copy
each group keeps (`transfer/PhoneFileFence.kt`, one lock shared with queuing transfers):

- a copy a transfer now holds is left out;
- a group whose kept copy is gone, changed size, is in an open removal request on Transfers, or
  is being trashed sends nothing;
- until Android has answered (including while its dialog is open), a reserved copy cannot be
  queued or retried for Drive, and a kept copy cannot be put in a removal request or another
  trash request.
- one phone trash request is open at a time: a cleanup confirmed while another is still open
  sends nothing and says so, so each answer applies to its own request.

Only copies that pass go to the released Phone trash flow (`MediaStore.createTrashRequest`, see
"Moving phone files to the trash"): without Media management Android shows its own dialog after
DriveLift's, results are read back per file, and files stay recoverable from Android's trash for
a limited time. There is no automatic cleanup. Another app editing or deleting a file after that
last check is outside what DriveLift can prevent.

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

## Searching and sorting a folder

A Phone folder and a Drive folder each show a search field and a sort button above the files.
The search matches part of a name, ignoring case, and only looks at the folder on screen: the
photos and videos DriveLift already lists for a Phone folder, or the entries of the Drive folder
for the connected account. Nothing else is read and no new permission is asked for. When nothing
matches, the folder says so. The search only filters what is listed: files selected before stay
selected even when the search hides them. Select all and Select none (and a range drag) add or
remove only the matching files shown; Clear empties the whole selection. The selection count, its
size and every confirmation (move, trash) always cover all selected files, shown or not.

Sort choices: date (newest or oldest first), name (A to Z or Z to A, numbers in natural order so
IMG_2 comes before IMG_10) and size (largest or smallest first). The choice is kept separately for
Phone folders and Drive folders across restarts. Phone folders start newest first and show day
headers only in date order; Drive folders start by name, as before, and keep folders above files.
Files with the same date keep the listing's own order, and name and size ties fall back to name,
so the order is the same every time.

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
appears at the top of the Phone tab and of My Drive (also while Drive is not connected, since
with photo access off the Phone tab shows only the access screen); it opens one list, phone items
first, then Google Drive. A folder opens in its own tab; a photo, video or Drive file opens in the viewer.
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
| Drive not connected (or signed out) | Not listed: counted in "N favorites hidden" |
| Starred with another Google account | Not listed: counted in "N favorites hidden". Never looked up |
| Drive item found (also after a move to another Drive folder) | Opens |
| In Drive's trash / not found / access refused | Unavailable, with that reason |
| The lookup failed (offline, authorization expired, Drive asked to slow down) | Unavailable, with Try again |

Account isolation: a Drive favorite is shown (name, type, place, icon) only while the account it
was starred with is connected. Favorites of another account, and every Drive favorite while Drive
is not connected, stay out of the list, its counts, labels and messages; one **N favorites
hidden** row says how many there are and offers **Remove**, which asks first and removes them all
without naming any. The account key is a SHA-256 for matching, not encryption: the saved name is
still in the app-private database, which is why the screen keeps it out of sight. Connecting that
account again brings its favorites back.

Drive favorites of the connected account are looked up one by one with `files.get` and a token
proven to belong to that account; nothing is sent for favorites of any other account. An
unavailable favorite stays listed until the user removes it. Limits: phone favorites cover what
DriveLift lists (photos and videos); a folder renamed on the phone gets a new bucket id, so its
favorite shows as unavailable; a Drive folder opened from Favorites appears directly under My
Drive in the path line, whatever its real parent is.

## Renaming one file

Select exactly one file, in a Phone folder or in the Drive tab, and tap **Rename** beside the
count in the selection bar. A solid dialog shows the current name with the part before the ending
selected. The new name is checked as it is typed and **Rename** stays off, with the reason under
the field, while it is empty, unchanged, `.` or `..`, contains `/` or control characters, or is
longer than 255 bytes. A name already in the folder is never used: DriveLift says so and the user
picks another; nothing is overwritten. Cancel renames nothing.

Only the name changes. The bytes are not touched, a phone file keeps its MediaStore address (URI)
and a Drive file keeps its id, so transfer rows still point at the same file.

- **Phone** (`data/Rename.kt`, `ui/Rename.kt`): the ending (`.jpg`, `.mp4`) must stay the same,
  because Android ties it to the file's type and would change the name itself; storage-unsafe
  characters (`\ : * ? " < > |`) and a leading dot are refused. Names in the folder are the listed
  photos and videos plus every file MediaStore shows DriveLift there, compared without letter case
  like the phone's storage. DriveLift sets `DISPLAY_NAME`; for a file it does not own Android asks
  first (`MediaStore.createWriteRequest`, shown above the lock like the trash request), and a
  refusal renames nothing. Afterwards DriveLift reads the name back and reports what Android
  saved, including a name Android changed or one that could not be checked.
- **Drive** (`drive/DriveClient.kt` `renameFile`): before `files.update` with the new `name`,
  DriveLift checks the file is still in the folder shown and that no other item there has that
  name (Drive itself would allow two); Drive's answer must show the new name on the same id. A
  changed ending is allowed with a note: the file's content and type stay the same. A name a
  transfer is still uploading is refused, so an upload never lands beside a renamed file of the
  same name. Like trash and move, the rename runs only for the account connected at
  confirmation and blocks account switching while it runs. Folders cannot be selected and are not
  renamed in this version.

A file a transfer still depends on is refused with the reason: on the phone a file in a
`QUEUED`, `UPLOADING`, `UPLOADED` or `VERIFIED` transfer, in Drive a copy being uploaded or checked
or verified and waiting for removal. The transfers are read again just before the name is sent.

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
| `drive/DriveClient.kt` | Drive REST calls: list, create folder, resumable upload, metadata, trash, move, rename |
| `data/Rename.kt` | Rename name checks, plans and outcomes; MediaStore name updates |
| `ui/Rename.kt` | Rename dialog, phone rename flow, Android write-consent host |
| `transfer/TransferRepository.kt` | Queueing, starting and stopping the jobs |
| `transfer/TransferWorker.kt` | The upload job |
| `transfer/VerifyWorker.kt` | The verification job: reads the phone file, computes MD5, retries |
| `transfer/Verifier.kt` | The verification decisions and the removal close-out rule |
| `transfer/Backoff.kt` | Retry timing and speed measurement |
| `transfer/LiveProgress.kt` | Live progress shared with the screens, error wording |
| `transfer/Notifications.kt` | Progress and result notifications |
| `ui/MainActivity.kt` | App shell, tabs, permission request |
| `ui/PhoneScreens.kt` | Folder list, photo grid, drag selection |
| `ui/StorageScreen.kt` | Storage insights: breakdown, Drive quota, largest files, duplicate cleanup |
| `data/StorageInsights.kt` | Breakdown, size-then-SHA-256 duplicate grouping, cleanup plan |
| `ui/DriveFolderInsights.kt` | Storage: Google Drive folder counts, more folders, open a folder |
| `data/DriveFolderSizes.kt` | Drive folder totals from one flat listing, paged listing with stop reasons |
| `transfer/PhoneFileFence.kt` | Reserves a phone trash request against queuing, removal and the copy a duplicate cleanup keeps |
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
