# UI implementation and review workflow

Authority: `docs/AI_CONTEXT.md`, section **Native design system**. This file explains how to
carry out and prove a UI change. It does not add rules.

## Before editing

1. Read the design system section and the pull request.
2. Name the comparable existing screen (Phone folder list, folder grid, Drive browser, Transfers,
   destination sheet) and read its current source. An existing screen is an example, not
   permission to copy a known defect.
3. Reuse what is already shared: the theme in `ui/Theme.kt`, the tokens in `ui/Tokens.kt`, and
   the backdrop, panel, bar and gauge in `ui/Glass.kt`. Do not create a local variant of
   something that exists.

## Prove the change

- Render the real production composables with synthetic data. Never use real photos, file names
  or account details.
- Capture each affected screen in three conditions: standard, font scale 1.5, and a compact
  320 dp wide phone. If the app ships more than one theme, capture each theme.
- Cover the states the screen can be in: loading, empty, error, populated.
- Say which checks were automated, which were done on an emulator or phone, and which were not
  done. A still image does not verify animation, scrolling, or a system dialog.
- Do not write a contrast ratio or "passes WCAG" unless it was measured, and say with what.

## Screenshot tests

Screenshots are rendered on the JVM with Roborazzi and Robolectric, in GitHub Actions, with no
phone and no emulator. `ScreenshotTest` drives the real `AppRoot`, view model, Room database and
theme, fed by a fake media store with generated pictures, seeded transfer rows, and a fake
`DriveFolders` that stands in for Google Drive.

- Tests live in `app/src/test/java/com/drivelift/app/ui/`.
- Add a scenario as one `@Test` that calls `eachVariant`; it is captured at standard size, font
  scale 1.5 and 320 dp wide. Raise `expected` in `.github/workflows/android.yml` by three.
- A sheet or dialog is its own window. Pass `overlay = true` so the whole screen is captured,
  or `ownWindow = true` for a full-screen dialog opened over a sheet. Font scale is set on the
  device (`RuntimeEnvironment.setFontScale`), so those windows scale too.
- After the text of a field inside a dialog changes, Compose test calls that wait for idle never
  return under Robolectric. Run frames with `pump`, touch the window directly and capture; see
  `openNewFolderDialog`.
- The launcher icon has its own test, `LauncherIconTest`. It renders the icon's real layers,
  cut to a circle, a squircle and a rounded square, and at launcher sizes from 48 to 192
  pixels, and writes `launcher-icon-standard.png` and `launcher-icon-compact.png`. Text size
  does not apply to an icon, so there is no large-font image for it.
- A local run needs about 2 GB of free memory. One scenario at a time
  (`--tests "*ScreenshotTest.driveListing"`) needs less. If it cannot start, let CI record instead.
- Record with `./gradlew testDebugUnitTest -PrecordScreenshots`. Images are written to
  `app/build/outputs/screenshots/`.
- CI records them on every pull request, fails if an expected image is missing, and uploads them
  as the `screenshots` artifact.
- Never change a test or delete an image just to make a failure go away.

## Evidence in the pull request

A pull request that changes production UI carries exactly one fenced `ui-review` JSON block (see
the pull request template). It records the full head SHA, HTTPS links for the three screenshot
conditions, the reference screen, what was exercised, accessibility notes, the named reviewer,
and any approved exceptions.

Artifact links expire and need sign-in, so publish the images on the `evidence` branch in a
folder named after the head SHA that produced them, and link to that commit. Do not add image
commits to the implementation branch.

A push makes the evidence stale: record again and update the block.

The **UI contract** check (`.github/gate/ui_contract.py`) validates that the block exists, is
complete, and names the current head. It cannot judge the images. The reviewer must open them.
