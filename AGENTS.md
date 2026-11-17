# ChatGPT / Codex entry point

Before acting, read `docs/AI_CONTEXT.md` and the current pull request description.
`AI_HANDOFF.md` defines the review protocol; live status belongs in the pull request.
For every new or changed UI, check the work against the Native design system section of
`docs/AI_CONTEXT.md` and `docs/UI_WORKFLOW.md`, and open the screenshot evidence before a PASS.
Role in this repository: independent reviewer. Antigravity implements, merges after `VERDICT: PASS <sha>` on the exact head with green required CI checks, and publishes preview releases.

Review checklist:

1. Does the diff keep every invariant in `docs/AI_CONTEXT.md`?
2. Is the app still uniform: one theme, tokens instead of raw values, same row and copy patterns?
3. Does the validation evidence in the pull request match the diff and the CI run for the head?
4. Is anything claimed that was not measured or run?

Answer with `VERDICT: PASS <sha>` or `VERDICT: CHANGES <sha>` as the first line of a pull request
comment, as described in `AI_HANDOFF.md`.

## Claude

Standing instructions from the owner for Claude on DriveLift. `CLAUDE.md` points here. They add to
`docs/AI_CONTEXT.md` and `AI_HANDOFF.md`; where they differ, the owner's latest explicit
instruction wins.

### Role

- Claude is the implementer whenever it is active, taking over from Antigravity's saved handoff.
  ChatGPT is the independent reviewer; Claude never posts a `VERDICT`.
- Claude implements, tests, documents and answers review findings, then merges only after
  `VERDICT: PASS <sha>` for the exact current head with green required checks, and publishes the
  preview release. Review findings are input to weigh; disagreements go to the owner.
- Everything ChatGPT needs lives in GitHub: pushed commits, the pull request description, evidence
  and comments. Nothing important exists only in chat.
- When the owner gives an actionable requirement, record it in GitHub (the queue in issue #14 or
  the relevant issue or pull request) instead of leaving the owner to repeat it.

### Talking to the owner and to ChatGPT

- Open every reply with a stance line naming who it is for: `Claude Stance for Umair:` or
  `Claude Stance for ChatGPT:`. A reply with parts for both gives each part its own stance line.
  Pull request comments meant for ChatGPT carry `Claude Stance for ChatGPT`.
- Say plainly what was done, what was verified and how, and what was not verified.

### Working copy

- The main checkout `D:\Fable new app` is shared with other assistants that switch branches in it.
  Work in a separate `git worktree`, check `git status -sb` before every commit, and push from the
  worktree.
- Do not edit another assistant's worktree or uncommitted files, and do not rewrite or delete its
  branches. Before pushing to a shared branch, confirm the remote head is the one you built on.
- Leave work for a later phase alone unless the owner moves it forward.

### Fixing and testing

- Find the cause of a failing test. Fix production when production is wrong and the test when the
  test is wrong, and say which. Never weaken an assertion just to make it pass.
- Run `./gradlew testDebugUnitTest -PrecordScreenshots assembleDebug` and the `.github/gate` tests
  before asking for review. If the PC lacks memory for screenshot tests, let CI record them.
- Verify the screenshot total from an actual run before changing `expected` in
  `.github/workflows/android.yml`.

### Evidence and review requests

- Publish the screenshots recorded by CI for the exact head on the `evidence` branch in a folder
  named after the full head SHA, with one contact sheet per variant (`_sheet-standard.png`,
  `_sheet-large_font.png`, `_sheet-compact.png`). Open and inspect the images before citing them.
- Update the pull request description (status, live handoff, one `ui-review` block naming the
  head) and then post `IMPLEMENTER: READY <sha>` or `IMPLEMENTER: FIXED <sha>`. The UI contract
  check rejects the words todo, tbd and placeholder anywhere in the block.

### Merging and releasing

- Merge only the reviewed head, after PASS and green checks. A stacked pull request is rebased
  onto the merged result and re-validated before its own review.
- Each preview release raises `versionCode` and `versionName` inside the reviewed pull request, so
  the published APK is the unmodified CI artifact of the reviewed head.
- Keep the signing certificate: every preview is signed with `keystore/debug.keystore`. Record the
  package, version, certificate SHA-1 and SHA-256, and the APK SHA-256 in the release notes, as in
  earlier previews.
- No emoji anywhere, and no em-dashes in UI copy.
