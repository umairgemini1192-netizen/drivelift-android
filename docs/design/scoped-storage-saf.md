# Scoped storage and the Storage Access Framework (SAF)

**Issue:** #42 (owner roadmap, pillars "Unified storage" and "Archives"), indexed by #14.
**Status:** design and first code slice. No user-visible behaviour changes in this pull request.
**Authority:** `docs/AI_CONTEXT.md` (invariants, prohibited list). Where this document and that
file disagree, `AI_CONTEXT.md` wins and this document must be corrected.

## 1. Why this comes first

The Phone file manager reads only through MediaStore. That reaches photos and videos, and the
app's current permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`,
`ACCESS_MEDIA_LOCATION`, `MANAGE_MEDIA`) do not grant general access to documents, archives, APKs
or other non-media files on shared storage or on an SD card or USB drive.

The roadmap needs two things MediaStore cannot give:

1. **Accessible non-media roots:** Downloads, Documents, an SD card and USB-OTG storage, each
   chosen by the user, for browsing, search, storage breakdown and archive handling.
2. **Archive handling:** ZIP browse, create and extract (then TAR, 7z and RAR as separate
   increments), which reads and writes files that are not media.

The owner has explicitly excluded `MANAGE_EXTERNAL_STORAGE` (AI_CONTEXT, Invariant and Permissions
rules). So access to non-media roots must come from the user granting a folder through Android's
document picker. This document designs that path.

## 2. Access model

| Source | Mechanism | Grant held by the app |
|---|---|---|
| Phone media (images, video) | MediaStore, unchanged | Existing media permissions |
| A user-chosen folder (Downloads, Documents, SD card, USB-OTG) | `ACTION_OPEN_DOCUMENT_TREE` | Persisted tree URI (`takePersistableUriPermission` with read, and write only when a feature needs it) |
| A single archive the user opens | `ACTION_OPEN_DOCUMENT` | Read grant for that URI, kept only as long as the job needs it |
| Google Drive | Existing Drive REST client, unchanged | Existing Google sign-in |

Rules:

- Each grant is requested by the user, one root at a time, with a plain-language reason. The app
  never asks for a root in the background.
- Persisted grants are recorded in app-private storage with a label (for example "Downloads on
  Phone" or "SD card"), the tree URI and the date granted. A grant is never reused for another
  root.
- The app checks each persisted grant before use. A revoked, missing or unmounted root is shown
  as **unavailable** with a "reconnect" action, and its files are not listed from a cache.
- Revoking a grant in Android's settings removes it from the app's list on the next check and
  deletes any staged archive data for it (see section 5).
- Storage roots are never inferred from paths. The app works only with URIs the user granted.

## 3. Supported-source and format matrix (initial)

| Source | Browse | Search | Storage breakdown | Archive read | Archive write | Notes |
|---|---|---|---|---|---|---|
| Phone media | yes (existing) | yes (existing) | yes (existing) | n/a | n/a | unchanged |
| Downloads / Documents (tree grant) | planned, slice 2 | planned | planned | planned | planned | SAF listing is slower; paginate |
| SD card (tree grant) | planned, slice 2 | planned | planned | planned | planned | Unavailable when unmounted |
| USB-OTG (tree grant) | planned, slice 2 | planned | planned | planned | planned | Read-only in first release unless proven |
| Drive | existing | existing | existing (duplicates separate) | not in scope | not in scope | Archives on Drive need download first |
| ZIP | n/a | n/a | n/a | planned, slice 3 | planned, slice 4 | No ZIP64 or encrypted entries until tested |
| TAR, 7z, RAR | n/a | n/a | n/a | not started | not started | Each needs dependency and licence review |

Native archive parsers and any third-party library need dependency, licence and resource review
before they are added, as the owner's roadmap requires.

## 4. Path and containment rules (implemented in this slice)

`app/src/main/java/com/drivelift/app/data/ArchiveEntryPath.kt` applies these rules to every entry
name from an archive before anything is written:

- Entry names are untrusted. Absolute paths, drive letters, empty components, `.` and `..`
  components, NUL and control characters, and backslashes are refused, not repaired.
- A name or path that ends with a space or a dot is refused, since Android storage does not keep
  it unchanged.
- Limits: 1024 bytes per path, 255 bytes per component (UTF-8), counted in bytes.
- `ArchiveLimits` caps entries (10,000), folder depth (32) and expanded bytes (4 GB) by default.
  These are counted as the stream is read. The header's claimed size is never trusted.

Still to design before slice 3 (ZIP extraction):

- **Links.** Symbolic links and hard links inside archives are not created. An entry that is a
  link is refused and reported. Test: a link to `../` and a link to an absolute path.
- **No overwrite.** An extracted file never replaces an existing file. The job picks a new name
  or asks the user. Test: the same entry twice, and an existing file at the target name.
- **Containment after mapping.** Each accepted component is mapped into the job's own staging
  folder, and the final canonical path is checked to still start with that folder.

## 5. Staging, job ownership and recovery

- Every archive job has an id and its own staging folder in app-private storage
  (`filesDir/archive-jobs/<id>/`). Nothing is written straight into a user's folder until the job
  is complete and the user has confirmed the target.
- Staging is cleaned up when the job finishes, is cancelled, or is found orphaned at startup
  (older than the job's recorded start, with no live worker).
- A job that is interrupted (process death, reboot, revoked grant) is shown in the job list as
  **interrupted** with its counts. It is not resumed silently and never reports success.
- Staged bytes are not media and are not shown in the Phone gallery.

## 6. Failure and recovery tests (planned, per increment)

- Revoked grant during a listing: the root shows **unavailable**, no stale files.
- Unmounted SD card: same as above, with the "reconnect" action.
- Archive entry refused (traversal, absolute, link): the job reports the refusal and writes nothing
  outside staging.
- Limits reached (entry count, depth, expanded size): the job stops and says so.
- Process death during extraction: on next start the job is **interrupted**, staging is removed,
  and no partial file is left in a user folder.
- Name collision on extraction: no overwrite; the user sees the collision named.

## 7. Permissions and copy

- No new broad permission. `MANAGE_EXTERNAL_STORAGE` remains prohibited.
- The picker reason is plain: "Choose a folder so DriveLift can list its files. You can remove
  this access in Android settings." No marketing wording.
- Any screen added by a later slice follows `docs/AI_CONTEXT.md` (Native design system) and
  `docs/UI_WORKFLOW.md`. This slice adds no screen.

## 8. Slice plan

1. **This pull request.** Design record, `ArchiveEntryPath` and `ArchiveLimits` with unit tests.
   Pure Kotlin. No manifest, UI or storage changes.
2. **Root grants.** `ACTION_OPEN_DOCUMENT_TREE` grant store (persist, list, check, revoke
   handling), with Robolectric tests and a fake provider. Needs UI evidence (root picker, unavailable
   state) under `docs/UI_WORKFLOW.md`.
3. **ZIP browse** (read only) through the root grant, using the path rules above.
4. **ZIP create and extract** with staging, no-overwrite and recovery.
5. **Time filters** are a separate small increment, not part of this queue.

## 9. Not verified in this slice

- Nothing here has run on a phone or an emulator. No SAF provider was exercised.
- `ACTION_OPEN_DOCUMENT_TREE` behaviour on the SD card and USB-OTG across Android versions is
  unverified. These need a Pixel check before any claim.
- Real ZIP files (including ZIP64, encrypted and Unicode-name archives) were not tested.

## 10. Open questions for the owner and reviewer

- Should USB-OTG be read-only in the first release? (Recommended: yes, until a write path is proven.)
- Is a per-root "remember and stop asking" acceptable, or should each new session ask again?
  (Recommended: remember, with a visible list to revoke.)
