# DriveLift AI Handoff Protocol (`AI_HANDOFF.md`)

> **Immutable Workflow & Governance Documentation:** Do not rewrite this protocol per task. Domain, schema, and architectural rules live in `docs/AI_CONTEXT.md`.

---

## 1. Source of Truth & Operational Posture
- **Live Handoff:** The Pull Request (PR) description is the primary live state tracker for an active unit of work, mirrored in Section 6 of this file for local CLI/workspace startup.
- **Factual Record:** Source code, tests, CI runs, commit history, and the exact `git diff` are the sole factual record.
- **No Chat Authority:** Claims, approvals, or status summaries made only in chat are non-binding.
- **Proactive Execution:** During active review or implementation work, follow relevant PR heads, implementer comments, and CI through to a definitive verdict or explicit blocker without repeated reminders. Never substitute status reports for pending audits.
- **Background Execution Honesty:** Continuous execution outside an active chat requires an explicitly configured scheduled mechanism (cron/hook/CI workflow); never claim background monitoring exists unless actually configured.

---

## 2. Mandatory Attribution (`Stance for:`)
Because all assistants (Claude, Antigravity, Gemini, ChatGPT) operate under the owner's GitHub credentials (`umairgemini1192-netizen`), every PR comment, review block, and handoff state update **MUST** explicitly declare who owns the immediate next action:

- **`Stance for: Claude`** — Code implementation, bug fixes, test repairs, or requested refactors assigned to Claude / Antigravity.
- **`Stance for: Gemini`** — Code/architecture audits, cross-branch conflict checks, or secondary verification owned by Gemini.
- **`Stance for: ChatGPT`** — Code/architecture audits, specification checks, or review verdicts owned by ChatGPT.
- **`Stance for: Umair`** — Final merge/release execution, product/scope trade-offs, rule disputes, or escalations where an implementer is blocked after 2 iterations.

---

## 3. PR Comment Protocol & Review Markers
Assistants communicate through Pull Request comments. Every protocol comment **MUST** begin with these exact two lines (`<sha>` must be the full 40-character commit hash from `git rev-parse HEAD`, or a minimum 12-character unambiguous prefix):

```text
<MARKER> <sha>
Stance for: [Claude | Gemini | ChatGPT | Umair]
```

| Marker | Emitted By | Meaning |
| :--- | :--- | :--- |
| `IMPLEMENTER: READY <sha>` | Antigravity / Claude | Initial implementation complete; commit `<sha>` is ready for audit. |
| `VERDICT: CHANGES <sha>` | ChatGPT / Gemini | Audit complete for `<sha>`; blocking defects found (detailed below header). |
| `IMPLEMENTER: FIXED <sha>` | Antigravity / Claude | Commit `<sha>` resolves all blockers from the prior `VERDICT: CHANGES`. |
| `VERDICT: PASS <sha>` | ChatGPT / Gemini | Audit complete for `<sha>`; zero blocking findings. |
| `ESCALATION: BLOCKED <sha>` | Any Assistant | Work cannot proceed due to rule dispute, repeated CI failure, or required owner decision. |

### Audit & Merge Gate Rules
1. **Exact-Head Binding:** A verdict applies strictly to the `<sha>` it names. Any subsequent commit, rebase, or force-push immediately invalidates an older `VERDICT: PASS`.
2. **Sticky Blockers:** A `VERDICT: CHANGES` state remains open and blocking until superseded by a `VERDICT: PASS <sha>` for the exact current branch head.
3. **Evidence-Backed Findings:** Every defect listed under `VERDICT: CHANGES` must cite:
   - Exact file path and line numbers (`path/to/file.ext:L10-L25`)
   - The specific rule violated in `docs/AI_CONTEXT.md`
   - The exact technical change required to resolve it
4. **Reviewer Independence:** Reviewers must never push code commits to an implementer's active PR branch or resolve review threads without verifying the fix in a new commit SHA, unless explicitly ordered by Umair.
5. **Merge Gate:** `VERDICT: PASS <sha>` is an audit clearance, not an automatic merge. Merging requires all three conditions:
   - `VERDICT: PASS <sha>` matching the exact current PR `headRefOid`
   - All required CI checks passing (green) on that exact `<sha>`
   - Execution by the authorized implementer under owner rules, or directly by Umair

---

## 4. Live Handoff Schema (PR Description & Local State)
Keep this YAML block at the top of every PR description (and sync to Section 6 below on local handoff). Always fetch the current PR body (`gh pr view <PR> --json body`) immediately before editing so concurrent updates are never overwritten.

```yaml
handoff_version: 2
task: ""
pr_number: 0
branch: ""
updated_utc: "YYYY-MM-DDTHH:MM:SSZ"
stance_for: "Claude | Gemini | ChatGPT | Umair"
status: "PLANNED | IN_PROGRESS | REVIEW_READY | CHANGES_REQUESTED | APPROVED | BLOCKED | COMPLETED"
head_sha: ""
last_reviewed_sha: ""
implementer: ""
reviewers: []
objective: ""
decisions: []
files_changed: []
validation:
  commands: []
  results: []
active_blockers: []
not_verified: []
open_risks: []
next_action: ""
requires_owner_decision: false
```

---

## 5. Freshness, Failures, Disputes & Ceremony

### Freshness Threshold
- Any handoff state with an `updated_utc` older than **7 days** is a historical snapshot. Before relying on it, run `git fetch origin`, inspect `git status` and `git log -n 5`, diff against `origin/main`, and re-run the build/test suite.

### Failure & Interruption Handling
- **Failed Validation:** Set `status: BLOCKED`. Record the exact failing command, error output, root-cause hypothesis, and safe recovery step under `validation.results` and `active_blockers`. Never report completion when build, lint, or tests fail.
- **Interrupted Work:** Commit only coherent, syntactically valid work on the feature branch. Set `status: BLOCKED` and document the exact resume file, line, and remaining steps in `next_action`.
- **Two-Strike Rework Cap & Reviewer Disputes:** If an implementer fails to resolve the same blocker across **2 consecutive** `IMPLEMENTER: FIXED` iterations, or if implementer and reviewer disagree on a rule in `docs/AI_CONTEXT.md`:
  1. Post `ESCALATION: BLOCKED <sha>` with `Stance for: Umair`.
  2. Set `status: BLOCKED` and `requires_owner_decision: true`.
  3. Present both technical positions with file/line citations and halt further commits until Umair decides.

### Proportional Ceremony
- All code, database schema/migrations, configuration, CI/CD workflow, and UI changes require a dedicated branch and Pull Request governed by this protocol.
- Typo-level documentation fixes may be committed directly to the default branch **only** when explicitly authorized by Umair in the current turn.

---

## 6. Active Workspace Handoff Ledger (Live Local State)

> **Instructions for Agents on Startup:** Read the active block below, compare `head_sha` and `last_reviewed_sha` against `git rev-parse HEAD` and `gh pr view`, and immediately execute the pending action assigned to your role if `stance_for` matches you.

```yaml
handoff_version: 2
task: "Initialize DriveLift AI Handoff v2 Protocol"
pr_number: 0
branch: "main"
updated_utc: "2026-11-17T18:50:00Z"
stance_for: "Umair"
status: "COMPLETED"
head_sha: "PENDING_COMMIT"
last_reviewed_sha: "N/A"
implementer: "Gemini"
reviewers:
  - "ChatGPT"
  - "Claude"
objective: "Establish deterministic multi-agent PR review markers, exact-SHA binding, and Stance routing."
decisions:
  - "Enforced mandatory two-line PR comment header (<MARKER> <sha> + Stance for:)."
  - "Added head_sha, last_reviewed_sha, and stance_for to handoff_version: 2 YAML."
  - "Capped repeated blocker rework loops at 2 attempts before escalating to Umair."
files_changed:
  - "AI_HANDOFF.md"
validation:
  commands: []
  results: []
active_blockers: []
not_verified: []
open_risks: []
next_action: "Assign next feature branch or open PR for implementation/audit."
requires_owner_decision: false
```
