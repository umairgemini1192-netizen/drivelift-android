# Task record: ZIP browse (read only)

**Issue:** #42 (owner roadmap, pillar "Archives"), indexed by #14.
**Design:** `docs/design/scoped-storage-saf.md`, section 8, slice 3.
**Branch:** `claude/fm-zip-browse-8d2473`. Builds on PR #46 (merged as 40013bc).
**Status:** implemented, draft pull request, awaiting ChatGPT audit. Nothing merged.

## Scope of this increment

- `ZipBrowse.list` reads a ZIP through a `ZipSource` (length plus a stream at any offset) and
  returns every entry (path, folder flag, bytes read). It writes nothing to storage.
- Integrity (review of 1f12149, VERDICT: CHANGES): the end record must be complete and its
  directory must sit exactly before it. The central directory is parsed in full and is the
  authority: entry count, names (through `ArchiveEntryPath`), sizes, encryption, compression
  method and ZIP64 are checked. The entry data is then read and must match the directory entry
  by entry, with nothing extra and nothing missing.
- `ZipBrowse.open` returns the bytes of one named file entry, refusing an entry larger than the
  caller's cap instead of truncating it.
- Every entry name goes through `ArchiveEntryPath.check`. Any refused name stops the whole job.
- Every limit in `ArchiveLimits` (entries, folder depth, bytes read) is checked while the entry
  data is read, not from header sizes.
- A file that does not start with a ZIP signature is refused, because `ZipInputStream` would
  otherwise report it as an empty archive. A four-byte end signature alone is refused.

## Not in this increment (kept for later slices)

- No UI and no screen. No file picker, no entry for the file manager, no Settings change.
- No extraction, no staging, no publication to a user folder (slice 4).
- No encrypted entries, no ZIP64 test, no Unicode-name test against real archives.
- No device or emulator run. The tests build synthetic archives in memory with `ZipOutputStream`.

## Decisions

- Pure Kotlin on the JDK `java.util.zip` package. No new dependency.
- A refused entry fails the whole job. No partial listing is shown as complete.
- Version bumped to preview.27 (build 26) per AGENTS.md. The build-id assertion in
  `ScreenshotTest` is updated to match.

## Validation

- Tests for the revised ZIP code were written but NOT run: the PC had about 1.3 GB free and the
  Kotlin and test JVMs crashed with native out-of-memory before compiling. Earlier revision
  (1f12149): 22 of 22 passed locally, before the VERDICT fix.
- CI is the test evidence for this head. Full `testDebugUnitTest` with screenshots and
  `assembleDebug`: CI only.
