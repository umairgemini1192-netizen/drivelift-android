# DriveLift AI handoff protocol

Stable workflow documentation. Do not rewrite it per task. Rules live in `docs/AI_CONTEXT.md`.

## Where the truth is

- The **pull request description** is the live handoff for a piece of work.
- Source, tests, CI runs, commits and the diff are the factual record.
- A claim made only in chat is not authoritative.

## Review markers

The implementer and the reviewer talk through pull request comments. A marker is the exact first
line of a comment. `<sha>` is the commit it refers to (7 to 40 hex characters).

| Marker | Who | Meaning |
|---|---|---|
| `IMPLEMENTER: READY <sha>` | Antigravity (or Claude) | This commit is ready for review |
| `VERDICT: CHANGES <sha>` | ChatGPT | Reviewed this commit; changes are required (listed below the marker) |
| `IMPLEMENTER: FIXED <sha>` | Antigravity (or Claude) | This commit answers the last `CHANGES` |
| `VERDICT: PASS <sha>` | ChatGPT | Reviewed this commit; no blocking findings |

Rules:

- A verdict applies only to the commit it names. Any later push makes an older `PASS` stale.
- A `CHANGES` stays open until a later `PASS` for the current head.
- A `PASS` is the reviewer's audit result. It is not a merge. Merging requires `VERDICT: PASS <sha>` for the exact current head and green required CI checks, and is performed by the implementer under the owner's authorization (or by the owner).
- Findings under `CHANGES` cite the file and line, the rule in `docs/AI_CONTEXT.md` they rest on,
  and what would resolve them.

Both assistants post through the owner's GitHub login, so the markers are a protocol between
cooperating assistants, not a security boundary.

## Live handoff fields

Kept in the pull request description (the template provides them):

```yaml
handoff_version: 1
task: ""
updated_utc: YYYY-MM-DDTHH:MM:SSZ
status: PLANNED | IN_PROGRESS | BLOCKED | REVIEW_READY | COMPLETED
implementer: ""
reviewers: []
objective: ""
decisions: []
files_changed: []
validation:
  commands: []
  results: []
not_verified: []
open_risks: []
next_action: ""
requires_owner_decision: false
```

## Freshness

A handoff more than seven days old is a dated snapshot. Re-check the branch head, diff, tests and
build before relying on it.

## Failure and disagreement

- **Failed validation:** set `status: BLOCKED`; record the failing command, its output, the
  suspected cause and the safe next action. Never report completion.
- **Interrupted work:** commit only coherent work, set `BLOCKED`, name the exact resume point.
- **Reviewer dispute:** pause the disputed change, cite the rule and the evidence on both sides,
  and ask the owner.

## Proportional ceremony

Code, schema, workflow and UI changes always use a branch and a pull request. A typo-level
documentation fix may be a direct commit only when the owner says so.
