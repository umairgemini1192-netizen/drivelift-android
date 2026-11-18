# DriveLift shared AI context

**Version:** 1.0
**Effective date:** 2026-11-17
**Final decision authority:** the repository owner

This file is the single, provider-neutral engineering rulebook for every assistant that works on
DriveLift (Claude, ChatGPT, or any other). `CLAUDE.md` and `AGENTS.md` only point here. Read this
file, the pull request you are working on, and the relevant source before planning or editing.

## Roles

- **Implementer: Antigravity** (temporary/active takeover from Claude). Writes code, tests and docs on a branch, opens the pull request, merges only after ChatGPT posts `VERDICT: PASS <sha>` for the exact current head and required CI checks pass, and publishes preview releases.
- **Reviewer: ChatGPT.** Performs independent audit and review against this file and keeps the app uniform. Does not push code or merge.
- **Owner.** Decides anything the two disagree on, and sets merge and release rules.

The protocol they use to talk to each other is in `AI_HANDOFF.md`.

## Authority order

1. The owner's current explicit instruction.
2. Current source, tests, schema, and build evidence in this repository.
3. This file.
4. The pull request description (execution state only).
5. Any assistant's memory, chat history or inference.

A lower item never silently overrides a higher one. When two sources conflict, pause the disputed
change, cite both, explain the options and risks, and ask the owner.

## Product identity

DriveLift moves the photos and videos stored on an Android phone into Google Drive, proves each
copy is intact, and only then removes the originals from the phone. It is a personal tool for one
user. `README.md` describes the product; `docs/HOW_IT_WORKS.md` describes the pipeline.

## Mandatory architecture

- Kotlin, Jetpack Compose, Material 3. Single `app` module, package `com.drivelift.app`.
- Layers by folder: `data/` (MediaStore, Room, settings), `drive/` (sign-in and Drive REST),
  `transfer/` (workers, retry, notifications), `ui/` (Compose). `ui/` never calls Drive or Room
  directly except through `AppViewModel`.
- Room is the source of truth for transfers. Byte-level progress is in memory only
  (`LiveProgress`); the database is written on real state changes.
- Background work runs in WorkManager under the unique names `drivelift-upload` and
  `drivelift-verify`.
- Drive access uses Google Identity Services `AuthorizationClient` and plain HTTPS (OkHttp). No
  client id, client secret, API key or refresh token is stored in the app or the repository.
- Shared objects come from `Graph` in `DriveLiftApp.kt`. No dependency-injection framework.

## Invariants that must never break

These protect the user's files. A change that touches them is high risk and needs an explicit
`VERDICT: PASS` for its exact commit.

1. **State machine.** `QUEUED -> UPLOADING -> UPLOADED -> VERIFIED -> REMOVED`. `FAILED` is only
   for things no retry can fix. `CANCELLED` is the user's choice. No state is skipped.
2. **Nothing is removed unverified.** Only `VERIFIED` rows are ever offered for removal, and
   `VERIFIED` requires both the size and the MD5 checksum of the phone file to equal Drive's.
   A file that could not be compared is never shown as verified or removed. A file that cannot
   be read right now is retried, not treated as gone.
3. **Removal is confirmed by the user.** Deletion goes through `MediaStore.createDeleteRequest`.
   When the owner has granted Android's Media management access (`MANAGE_MEDIA`, owner decision
   on issue #24), DriveLift first shows its own text-only confirmation listing the verified files
   and Android then completes the request without its picture preview; otherwise Android's own
   dialog confirms. The app never deletes media without that confirmation and never asks for
   broad storage management access (`MANAGE_EXTERNAL_STORAGE`).
   **Scope (issue #36):** invariants 2 and 3 govern migration cleanup, the removal of uploaded
   originals offered on Transfers. A user-directed delete from the Phone file manager is a
   separate operation: the user selects the files, DriveLift names them with count and size, and
   they go to Android's trash through `MediaStore.createTrashRequest` (recoverable for a limited
   time), never deleted outright. Confirmation follows invariant 3 (DriveLift's text-only dialog
   with Media management, otherwise Android's). It needs no Drive copy or `VERIFIED` status and
   never implies one exists. Files in a `QUEUED`, `UPLOADING`, `UPLOADED` or `VERIFIED` transfer
   are left out and named, so no transfer or removal step acts on a trashed source.
4. **Retries have no limit.** Recoverable errors are retried forever with capped backoff. A
   dropped connection resumes from the byte Drive confirms, never from an assumed offset.
5. **A bad copy is never left looking good.** A size or checksum mismatch trashes the Drive copy
   and only then requeues the file. The Drive reference is kept until the trash call succeeds.
6. **Original bytes.** Only the unredacted original is uploaded. There is no fallback to a
   location-stripped stream.
7. **One row per phone file.** `mediaUri` is unique in `transfers`.

Enforce these in code and tests, not only in this document.

## Explicitly prohibited

- Reading or writing the Google Photos cloud library (not available to third-party apps).
- Any deletion on Google Drive other than (a) automatically trashing a copy that failed
  verification, or (b) moving files the user selected to Drive's trash after a confirmation that
  names them (file manager, issue #36). Permanent deletion and emptying the trash are never
  requested. Folders, and files a transfer still depends on (`UPLOADING`, `UPLOADED`,
  `VERIFIED`), are left out of (b).
- Analytics, crash reporting, ads, or any network call to a host other than Google's APIs.
- Storing tokens, passwords, or account identifiers anywhere except the account email kept in
  app-private preferences to pin background token requests.
- Emoji anywhere (UI, code, comments, docs, commit messages). Em-dashes in UI copy.

## Native design system

One theme, one source of truth. Every screen follows these rules. The look was set by the owner
on 17 November 2026: futuristic dark glass.

1. **One dark theme.** A deep indigo night sky that warms to violet at the bottom, with
   translucent glass panels on it. There is no light theme. All raw colour values live in
   `ui/Theme.kt` and nowhere else; screens read `MaterialTheme.colorScheme` and `GlassPalette`.
   Android XML resources cannot read Compose colours, so the launcher icon and the window
   theme repeat values from `ui/Theme.kt`; `LauncherIconTest` fails if the icon uses any other.
2. **Colour by meaning.** Coral (primary) is the one accent and marks the main action. Rose red
   (error) is used only for removal and failure. Violet and sky blue are atmosphere (backdrop,
   progress gauge), never an action.
3. **Glass.** `GlassBackdrop` is drawn once at the root, and once more inside a full-screen
   dialog because a dialog is its own window. Screens and scaffolds on it are transparent.
   Panels and rows use `GlassPanel` or `Modifier.glass`. Pinned bars use `Modifier.glassBar`,
   which is denser. Sheets, dialogs and menus stay solid (`surfaceContainer` roles) so text on
   them is readable. Do not nest a glass panel inside a glass panel. Inside a glass panel, flat
   or tinted control wells and capsule pills are allowed for buttons, step controls and badges:
   they use semantic surface and border tokens and have no glass effect of their own (owner
   decision, issue #32).
4. **Tokens.** Spacing, sizes, shapes and durations come from `ui/Tokens.kt`. No raw `dp` or
   millisecond values in screens. A new token needs a reason stated in the pull request.
5. **Motion.** Only state feedback (selection, progress). It uses `AppMotion.spec`, so it snaps
   when the phone has animations turned off. Nothing decorative moves.
6. **One thing leads** on each screen: the display-size figure, title or the dot gauge on
   Transfers, or the Play/Pause control on the video player. Glow and gradient are reserved
   for the backdrop and that lead element. Ordinary panels, wells and pills use plain token
   borders and no glow.
7. Every interactive element has a touch target of at least `Tokens.TouchTarget` (48 dp) and a
   content description or visible label. A secondary circular well or compact pill may draw a
   smaller visual container (40 to 44 dp) only when its touch target is still at least 48 dp
   (`Modifier.minimumInteractiveComponentSize()` or equivalent padding).
8. Layout must hold at font scale 1.5 and on a 320 dp wide phone. A row with text beside a
   button stacks them from `Tokens.STACK_AT_FONT_SCALE`.
9. Every screen handles loading, empty, error and populated states.
10. UI copy is plain and specific: what happened, why, what to do. No marketing words.
11. **Tabular digits.** Every timecode, playback speed, transfer figure (bytes, rate, time
    left, percentage) and numeric counter uses tabular numerals (`fontFeatureSettings = "tnum"`,
    set in the theme's text styles or a shared style), so digits do not shift as they change.
12. **Text emphasis by token.** Secondary and muted text uses the theme's colour roles (for
    example `onSurfaceVariant`), never an ad hoc opacity on a colour.
13. **Layout follows the content.** Vertical list screens (Transfers, Settings, folder lists)
    stay lists; no dashboard or Bento grid is imposed on them. Design guidance written for
    other products (currency, analytics, subscription examples) does not apply here.

The owner's reference image set a direction, not a feature list. Do not add subscriptions,
upgrade prompts, other cloud providers or iOS chrome because a reference shows them.

How to build and prove a UI change is in `docs/UI_WORKFLOW.md`.

## Engineering rules

- One concern per pull request. No unrelated refactors.
- Every change goes through a branch and a pull request. `main` is updated by merging a pull request only after ChatGPT posts `VERDICT: PASS <sha>` for the exact current head and required CI checks pass.
- Pin dependency versions. State deliberate upgrades in the pull request.
- Do not claim something works without evidence: a test, a build, a screenshot, or a device run.
  State plainly what was not checked.
- Failed validation means status `BLOCKED` with the failing output, never "done".
- Never commit credentials, tokens, personal data, or real photos. Test data is synthetic.

## Open decision: debug signing key in the repository

`keystore/debug.keystore` is committed so builds from a PC and from GitHub Actions share one
SHA-1 fingerprint, which is what the Google Cloud OAuth client is bound to. This is a deliberate,
documented exception to "never commit signing material". It holds while the repository is private
and the app is not published. The alternative is to store the keystore as a GitHub Actions
secret. Changing this is the owner's decision; do not change it in passing.

## What is not verified yet

Keep this list honest and update it when it changes.

- The app has not been run on a physical phone or against a real Google account.
- Colour contrast and touch-target sizes have not been measured.
- Screenshot tests render on the JVM. Thumbnails are generated gradient pictures and the Drive
  listing is made-up entries from a fake. They do not show real photographs or video frames,
  the real Drive service, animation, scrolling, or Android's own dialogs (permission, Google
  consent, removal).
- Glass here is a translucent fill with a lit edge. There is no live blur of what is behind it.
