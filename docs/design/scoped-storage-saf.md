# Scoped storage and the Storage Access Framework (SAF)

**Issue:** #42 (owner roadmap, pillars "Unified storage" and "Archives"), indexed by #14.
**Status:** design and first code slice. No user-visible behaviour changes and no archive
execution in this pull request.
**Authority:** `docs/AI_CONTEXT.md` (invariants, prohibited list). Where this document and that
file disagree, `AI_CONTEXT.md` wins and this document must be corrected.
**Minimum SDK:** 30 (Android 11). The picker restrictions below are baseline behaviour, not a
future-version edge case.

## 1. Why this comes first

The Phone file manager reads only through MediaStore. That reaches photos and videos. The app's
permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`,
`ACCESS_MEDIA_LOCATION`, `MANAGE_MEDIA`) do not grant general access to documents, archives, APKs
or other non-media files.

The roadmap needs two things MediaStore cannot give:

1. **Accessible non-media locations** chosen by the user, for browsing, search, storage breakdown
   and archive handling.
2. **Archive handling:** ZIP browse, create and extract (then TAR, 7z and RAR as separate
   increments), which reads and writes files that are not media.

The owner has excluded `MANAGE_EXTERNAL_STORAGE`. So access to non-media locations comes only from
the user granting a folder through Android's document picker. No all-files permission is requested
by any slice in this design.

## 2. Access model

| Source | Mechanism | Grant held by the app |
|---|---|---|
| Phone media (images, video) | MediaStore, unchanged | Existing media permissions |
| A user-chosen folder | `ACTION_OPEN_DOCUMENT_TREE`, restricted as in section 2.1 | Persisted tree URI (`takePersistableUriPermission`), read, and write only when a feature needs it |
| One archive the user opens (for example a Downloads ZIP) | `ACTION_OPEN_DOCUMENT` | Read grant for that URI, kept only as long as the job needs it |
| Google Drive | Existing Drive REST client, unchanged | Existing Google sign-in |

### 2.1 Picker restrictions (Android 11 and later, applies at minSdk 30)

The document picker does not offer every location as a tree grant. Per the Android storage
reference (https://developer.android.com/training/data-storage/shared/documents-files#grant-access-directory),
the following cannot be granted as a directory tree:

- The primary storage root (internal storage as a whole).
- The Download directory itself. A user-chosen subfolder of Download can be granted.
- `Android/data` and `Android/obb` (and their contents) for any app.
- Removable (SD card) volume roots on devices where the picker restricts them. Subfolders on those
  volumes can still be granted where the picker allows.

Consequences for the design:

- A "Downloads" source is a **permitted user-selected subfolder**, not the Download root. The UI
  says so and asks the user to pick a folder inside it.
- Individual Downloads archives are opened one by one with `ACTION_OPEN_DOCUMENT`, not through a
  tree grant.
- Documents and other ordinary folders are local folders with a tree grant. They are not restricted
  storage roots.
- Each source shows its own capability and unavailable message, such as "This location cannot be
  granted by Android. Choose a folder inside it." Nothing claims a whole-storage view.
- Every behaviour above is to be checked on a Pixel with the picker before any claim. Until then
  it is design only.

### 2.2 Grant rules

- Each grant is requested by the user, one location at a time, with a plain-language reason.
  The app never asks in the background.
- Persistent grants are user-chosen and have **visible revoke management**: a list in app settings
  showing each label (for example "Camera folder on Phone"), the tree URI, the date granted, and a
  control to remove it. Removing it also deletes that location's staged archive data (section 5).
  This is the owner's requested access model; it is not a new gate.
- Each grant is checked before use. A revoked, missing or unmounted location is shown as
  **unavailable** with a "reconnect" action. Its files are not listed from a cache.
- USB-OTG is **read-only** in the first release. This is a documented support limit until a write
  path is proven on a device.
- Storage roots are never inferred from paths. The app works only with URIs the user granted.

## 3. Supported-source and format matrix (initial)

| Source | Browse | Search | Storage breakdown | Archive read | Archive write | Notes |
|---|---|---|---|---|---|---|
| Phone media | yes (existing) | yes (existing) | yes (existing) | n/a | n/a | unchanged |
| User-chosen Downloads subfolder (tree grant) | planned, slice 2 | planned | planned | planned | planned | Download root itself not grantable (2.1) |
| Individual Downloads archive (`ACTION_OPEN_DOCUMENT`) | n/a | n/a | n/a | planned, slice 3 | n/a | Per-file grant, no folder listing |
| Documents or other local folder (tree grant) | planned, slice 2 | planned | planned | planned | planned | SAF listing is slower; paginate |
| SD card folder (tree grant) | planned, slice 2 | planned | planned | planned | planned | Volume root may be restricted (2.1); unavailable when unmounted |
| USB-OTG folder (tree grant) | planned, slice 2 | planned | planned | planned | not offered | Read-only first release |
| Google Drive | existing | existing | Whole-account quota only | not in scope | not in scope | Current Drive storage view is whole-account quota. No category or duplicate scan exists yet; those are later slices. Archives on Drive need download first |
| ZIP | n/a | n/a | n/a | planned, slice 3 | planned, slice 4 | No ZIP64 or encrypted entries until tested |
| TAR, 7z, RAR | n/a | n/a | n/a | not started | not started | Each needs dependency and licence review |

Native archive parsers and any third-party library need dependency, licence and resource review
before they are added.

## 4. Path and containment rules (implemented in this slice)

`app/src/main/java/com/drivelift/app/data/ArchiveEntryPath.kt` applies these rules to every entry
name from an archive before anything is written:

- Entry names are untrusted. Absolute paths, drive letters, empty components, `.` and `..`
  components, NUL and control characters, and backslashes are refused, not repaired.
- A component that ends with a space or a dot is refused, because Android storage does not keep
  that name unchanged. Ordinary dotted file names such as `photo.final.jpg` are accepted; only a
  trailing dot or space is refused.
- Limits: 1024 bytes per path and 255 bytes per component (UTF-8), counted in bytes.
- `ArchiveLimits` (entries 10,000, folder depth 32, expanded bytes 4 GB by default) is a **pure
  counter check**. It does not prove that a streaming parser enforces byte or entry limits. A future
  parser must enforce them as it reads, and its tests must show that.

Tests still to write with the consuming code (slices 3 and 4), not in this pull request:

- **Links:** symbolic and hard links are not created. A link entry is refused and reported. Tests:
  a link to `../` and a link to an absolute path.
- **No overwrite at creation:** an extracted file never replaces an existing file. Tests: the same
  entry twice, and an existing file at the target name.
- **Containment after mapping:** each accepted component is mapped under the job's staging folder,
  and the canonical result must still lie inside it.
- **Streaming limits:** the parser stops at the entry, depth and byte limits while reading, not from
  the header's claimed sizes.

## 5. Staging, publication, journal and recovery

Extraction has two separate phases. Both need recovery, and the second is the harder one.

### 5.1 Private staging (phase 1)

- Each archive job has an id and its own folder in app-private storage
  (`filesDir/archive-jobs/<id>/`). Entries are extracted there first.
- Staging is removed when the job finishes, is cancelled, or is found orphaned at startup.
- Staged bytes are not media and are not shown in the Phone gallery.

### 5.2 Publication to a user folder (phase 2)

Copying completed staging into a SAF destination is a long, **non-atomic** write. It can die midway.
So no promise of universal atomicity is made.

- **Durable journal.** Before any destination write, the job records in its app-private database
  each destination it intends to create: the job id, the parent tree URI, the intended name, and
  whether it creates a directory or a file. Status per row: `PLANNED`, `CREATING`, `CREATED`,
  `COMPLETE`, `FAILED`.
- **Ownership.** A row is recorded as owned only after the provider returns the created URI.
  Directory ownership is recorded the same way.
- **Returned-name verification.** The provider may auto-suffix a name (for example `name (1).jpg`).
  The job reads back the name the provider returned and records that, not the requested name.
- **Collision at creation time.** If the name already exists when creating, the job does not
  overwrite it. It picks a new name or reports the collision to the user before continuing.
- **Cleanup only of owned output.** On cancel or recovery the job deletes only URIs it recorded as
  `CREATED` or `CREATING` that it can show it created. It never deletes a file that existed before
  the job, and never a file the user has since changed (checked by size and modification time
  against the journal). If ownership cannot be shown, the output is left and reported.
- **Process death and reconciliation.** On next start the job is shown as **interrupted** with its
  counts. Reconciliation reads each journal row against the provider before any cleanup. A job is
  never resumed silently and never reports success while a row is not `COMPLETE`.
- **Provider without a safe publish strategy.** If a destination provider cannot support the check
  above (for example it cannot report a created URI or a returned name), that write mode is rejected
  for that source. Otherwise the job reports explicitly that the output is **incomplete**. It does
  not promise atomic publication.

## 6. Failure and recovery tests (planned, per increment)

These are design requirements now. No extraction is implemented or approved by this pull request.

- Revoked read grant during a listing: the location shows **unavailable**, with no stale files.
- Unmounted SD card: the same, with the "reconnect" action.
- Archive entry refused (traversal, absolute, link): the job reports the refusal and writes nothing
  outside staging.
- Limits reached (entry count, depth, expanded size) while streaming: the job stops and says so.
- **Mid-copy death during publication:** the journal row is `CREATING`. On restart the job
  reconciles it, removes only owned output, and reports the job as interrupted.
- **Revoked write grant during publication:** the job stops at the next destination write, reports
  the incomplete output, and removes only owned output it can still reach.
- **Provider auto-suffix:** the returned name is recorded and reported; the requested name is not
  assumed.
- **Concurrent collision:** a file of the same name appears between planning and creation. The job
  does not overwrite it and reports the collision.
- Process death during private staging: staging is removed at next start and no partial file is
  left in a user folder.

## 7. Permissions and copy

- No new broad permission. `MANAGE_EXTERNAL_STORAGE` remains prohibited.
- The picker reason is plain: "Choose a folder so DriveLift can list its files. You can remove this
  access in app settings or Android settings." No marketing wording.
- Any screen added by a later slice follows `docs/AI_CONTEXT.md` (Native design system) and
  `docs/UI_WORKFLOW.md`. This slice adds no screen.

## 8. Slice plan

1. **This pull request.** Design record, `ArchiveEntryPath` and `ArchiveLimits` with unit tests.
   Pure Kotlin. No manifest, UI or storage changes.
2. **Location grants.** Tree-grant store (persist, list, check, revoke management, unavailable and
   restricted-location messages per 2.1), with Robolectric tests and a fake provider. Needs UI
   evidence (location picker, revoke list, unavailable state) under `docs/UI_WORKFLOW.md`.
3. **ZIP browse** (read only), from a tree grant or a single-file grant, using the rules in section 4.
4. **ZIP create and extract** with staging, the publication journal (5.2), no-overwrite and the
   recovery tests in section 6.
5. **Time filters** are a separate small increment, not part of this queue.

## 9. Not verified in this slice

- Nothing here has run on a phone or an emulator. No SAF provider was exercised.
- The picker restrictions in 2.1 are taken from the Android reference and are not yet checked on a
  Pixel or any other device. Every claim in 2.1 is to be confirmed there before it is shipped.
- Behaviour of removable (SD card) and USB-OTG volumes across Android versions is unverified.
- Real ZIP files (including ZIP64, encrypted and Unicode-name archives) were not tested.
- The local Gradle test run on the author's PC crashed the JVM. CI is the test evidence.

## 10. Decisions and open items

Decided in this design (no further owner gate is needed to implement these):

- Persistent user-chosen grants with visible revoke management (section 2.2).
- USB-OTG is read-only in the first release (section 2.2).

Open for the owner only if a disputed decision arises:

- None blocking. Slices 2 to 4 may refine the publication journal details after device tests.
