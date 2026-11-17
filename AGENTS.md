# DriveLift Multi-Agent Operating Manual (`AGENTS.md`)

> **Precedence:** This file governs assistant roles, stance attribution, and execution mechanics for DriveLift[cite: 1]. Domain and architecture rules live in `docs/AI_CONTEXT.md`[cite: 1]; PR comment markers and state schemas live in `AI_HANDOFF.md`[cite: 1]. Where rules conflict, Umair's latest explicit instruction wins[cite: 1].

---

## 1. Universal Rules (All Assistants)

### Mandatory Stance Attribution
Every chat reply, PR comment, review block, and handoff entry **MUST** open with an explicit stance line naming who is speaking and who owns the next action[cite: 1]:
- `Claude Stance for [Umair | ChatGPT | Gemini]:`[cite: 1]
- `ChatGPT Stance for [Umair | Claude | Gemini]:`
- `Gemini Stance for [Umair | Claude | ChatGPT]:`

If a message addresses multiple recipients, split the response into distinct sections and give each section its own stance line[cite: 1]. State plainly what was done, what was verified (and how), and what remains unverified[cite: 1].

### Proactive Execution & Honesty
- **Follow Through to Verdict/Completion:** During active implementation or review work, proactively track relevant PR heads, implementer comments, and CI runs through to a definitive verdict (`VERDICT: PASS` / `VERDICT: CHANGES`) or explicit blocker (`ESCALATION: BLOCKED`) without repeated owner reminders[cite: 1]. Never substitute repeated status reports for pending audits or fixes.
- **No Unverified Claims:** Never claim a command, test, or screenshot verification was run if it was not executed against the exact commit SHA[cite: 1].
- **Scheduled Monitoring Truth:** Continuous execution outside an active chat session requires an actively configured scheduled mechanism (cron/hook/CI workflow). Never claim background monitoring is running unless actually configured.

---

## 2. ChatGPT / Codex / Gemini Entry Point (Independent Reviewer)

### Role & Pre-Flight
- **Role:** Independent Reviewer[cite: 1]. Antigravity or Claude implements, merges after `VERDICT: PASS <sha>` on the exact head with green required CI checks, and publishes preview releases[cite: 1].
- **Pre-Flight Read:** Before acting, read `docs/AI_CONTEXT.md`, `AI_HANDOFF.md`, and the current Pull Request description (`gh pr view <PR> --json body,headRefOid,statusCheckRollup,comments`)[cite: 1].
- **UI Verification:** For every new or modified UI surface, verify the diff against the Native Design System section of `docs/AI_CONTEXT.md` and `docs/UI_WORKFLOW.md`[cite: 1]. Open and visually inspect the full-SHA screenshot contact sheets on the `evidence` branch (`_sheet-standard.png`, `_sheet-large_font.png`, `_sheet-compact.png`) before issuing a `PASS`[cite: 1].
- **Strict Independence:** Never push commits to an implementer's active PR branch or bypass merge gates unless explicitly instructed by Umair.

### Review Checklist
1. **Invariants:** Does the diff preserve every architectural, data, and domain invariant in `docs/AI_CONTEXT.md`[cite: 1]?
2. **Design System Uniformity:** Is the app uniform—single theme, design tokens instead of raw hardcoded values, and consistent row/copy patterns (zero emojis, zero em-dashes in UI copy)[cite: 1]?
3. **Evidence Alignment:** Does the validation evidence in the PR description and `evidence` branch match the exact `headRefOid` diff and CI run[cite: 1]?
4. **Honesty Check:** Is anything claimed in the PR body or comments that was not actually measured or executed[cite: 1]?

### Issuing Verdicts
Post your audit result as a PR comment starting with these exact two lines (`<sha>` must be the full 40-character commit hash of the audited head)[cite: 1]:

```text
VERDICT: PASS <sha> | VERDICT: CHANGES <sha> | ESCALATION: BLOCKED <sha>
ChatGPT Stance for [Claude | Umair]:
```

- Every finding under `VERDICT: CHANGES` must cite the exact file and line range (`path/to/file.kt:L10-L25`), the violated rule in `docs/AI_CONTEXT.md`, and the exact fix required.

---

## 3. Claude / Antigravity Entry Point (Implementer)

Standing instructions from Umair for Claude on DriveLift (`CLAUDE.md` points here)[cite: 1].

### Role & Source of Truth
- **Active Implementer:** Claude is the implementer whenever active, resuming seamlessly from Antigravity's or a prior session's saved handoff[cite: 1].
- **No Self-Review:** ChatGPT/Gemini is the independent reviewer; Claude **never** posts a `VERDICT` marker[cite: 1].
- **Lifecycle Ownership:** Claude implements, tests, documents, and resolves review findings, then merges **only** after receiving `VERDICT: PASS <sha>` for the exact current head with all required CI checks green, and publishes the preview release[cite: 1].
- **Dispute Escalation:** Treat review findings as structured input to weigh against `docs/AI_CONTEXT.md`[cite: 1]. If a finding conflicts with project rules or fails after 2 consecutive fix attempts, halt commits and post `ESCALATION: BLOCKED <sha>` with `Claude Stance for Umair:`[cite: 1].
- **GitHub as Single Record:** Everything the reviewer needs must live in GitHub—pushed commits, the PR description, screenshot evidence, and PR comments[cite: 1]. Nothing important may exist only in chat[cite: 1].
- **Requirement Capture:** When Umair gives an actionable requirement in chat, immediately record it in GitHub (the backlog queue in Issue #14, or the active Issue/PR) so Umair never has to repeat it[cite: 1].

### Working Copy & Worktree Discipline
- **Shared Main Checkout:** The root directory `D:\Fable new app` is shared across assistants that switch branches inside it[cite: 1]. Always work inside a dedicated `git worktree`[cite: 1].
- **Pre-Commit & Push Hygiene:** Run `git status -sb` before every commit and push strictly from your own worktree[cite: 1]. Confirm the remote branch head matches the exact commit you built upon before pushing (`git fetch origin`)[cite: 1].
- **Hands Off Other Worktrees:** Never edit another assistant's worktree or uncommitted files, and never force-rewrite or delete another assistant's branches[cite: 1].
- **Scope Discipline:** Leave work scheduled for a later phase untouched unless Umair explicitly moves it forward[cite: 1].

### Fixing, Testing & Screenshot Gates
- **Root-Cause Fixes Only:** Diagnose the true cause of any failing test[cite: 1]. Fix production code when production is wrong, and fix the test when the test expectation is outdated—and state plainly which one you changed and why[cite: 1]. **Never** weaken or delete an assertion just to make a test pass[cite: 1].
- **Required Pre-Review Gate:** Before requesting review, run:
  1. `./gradlew testDebugUnitTest -PrecordScreenshots assembleDebug`[cite: 1]
  2. All `.github/gate` verification tests[cite: 1]
  *(Note: If the local PC lacks sufficient RAM for screenshot recording, push the branch and let GitHub Actions CI record the screenshots before requesting review[cite: 1].)*
- **Screenshot Count Integrity:** Always verify the actual screenshot count from a completed test run before modifying `expected` in `.github/workflows/android.yml`[cite: 1].

### Evidence Publishing & Review Requests
1. **Publish Contact Sheets:** Publish the screenshots recorded by CI for the exact 40-character head SHA onto the `evidence` branch under `<full-40-char-sha>/`, containing one contact sheet per variant[cite: 1]:
   - `_sheet-standard.png`[cite: 1]
   - `_sheet-large_font.png`[cite: 1]
   - `_sheet-compact.png`[cite: 1]
   Open and visually inspect every image yourself before citing it[cite: 1].
2. **Update PR Description:** Fetch the latest PR body first (`gh pr view <PR> --json body`), then update the live handoff YAML (`handoff_version: 2`) and include a single `ui-review` block naming the exact head SHA[cite: 1].
   - **Strict UI Contract Gate:** The automated UI contract check rejects the words `todo`, `tbd`, and `placeholder` (case-insensitive) anywhere inside the block[cite: 1].
3. **Emit Ready/Fixed Marker:** Post a PR comment opening with these exact two lines[cite: 1]:

```text
IMPLEMENTER: READY <sha> | IMPLEMENTER: FIXED <sha>
Claude Stance for ChatGPT:
```

### Merging, Stacked PRs & Preview Releases
- **Exact-Head Merge Gate:** Merge **only** the exact reviewed head SHA, and **only** after both `VERDICT: PASS <sha>` and green required CI checks are confirmed[cite: 1].
- **Stacked PRs:** If a second PR is stacked on the merged branch, rebase it onto the newly merged `main` head, re-run full validation, regenerate `evidence` screenshots for the new SHA, and request a fresh review[cite: 1].
- **In-PR Version Bump:** Increment both `versionCode` and `versionName` *inside* the reviewed PR before final review so the published release APK is the unmodified CI artifact built directly from the reviewed head[cite: 1].
- **Signing & Release Notes:** Sign every preview build with `keystore/debug.keystore`[cite: 1]. In the GitHub Release notes, record (matching earlier preview releases)[cite: 1]:
  - Application `package` ID[cite: 1]
  - `versionName` and `versionCode`[cite: 1]
  - Signing certificate `SHA-1` and `SHA-256` fingerprints[cite: 1]
  - Published APK file `SHA-256` checksum[cite: 1]
- **On-Phone Checklist:** With every release, tell the owner in chat exactly what to check on the phone: the version label to confirm, each new feature to try step by step, what a pass looks like, and what to report back. Do not rely on the release notes alone.
- **Copy Hygiene:** Zero emojis anywhere in code, commits, PRs, or release notes, and zero em-dashes (`—`) in UI copy[cite: 1].
