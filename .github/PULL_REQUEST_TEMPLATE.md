## Task record

- **Task:** <!-- What the owner asked for -->
- **Implementer:** <!-- One assistant or person -->
- **Reviewers:** <!-- Other assistants or people -->
- **Owner approval required before merge:** Yes

## Purpose

<!-- What outcome does this pull request deliver? -->

## Live AI handoff

```yaml
handoff_version: 1
task: ""
updated_utc: YYYY-MM-DDTHH:MM:SSZ
status: PLANNED # PLANNED | IN_PROGRESS | BLOCKED | REVIEW_READY | COMPLETED
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

## Validation evidence

- [ ] Unit tests passed (link the CI run for the head commit)
- [ ] Debug APK builds
- [ ] Invariants in `docs/AI_CONTEXT.md` still hold
- [ ] No credentials, personal data or real photos added
- [ ] Docs and this handoff match the actual diff
- [ ] What was not verified is listed under `not_verified`

## UI review evidence

For changes under `app/src/main/**/ui/`, `res/`, an Activity or the manifest, replace every
placeholder below. Remove this section for non-UI work. The head must be the full current head
SHA. Reviewers open the images before a PASS. See `docs/UI_WORKFLOW.md`.

```ui-review
{
  "head": "TODO full current PR head SHA",
  "reference": "TODO comparable existing screen and shared components inspected",
  "screenshots": {
    "standard": "TODO HTTPS link",
    "large_font": "TODO HTTPS link, at least 1.5x",
    "compact": "TODO HTTPS link, 320dp wide"
  },
  "interactions": "TODO what was actually exercised, how, and what could not be",
  "accessibility": "TODO touch targets, labels, contrast: measured, inspected or not checked",
  "reviewer": "TODO named reviewer who will open the evidence",
  "exceptions": "TODO approved differences from the design system, or none"
}
```

## Review

Follow `AI_HANDOFF.md`: `IMPLEMENTER: READY <sha>`, then `VERDICT: PASS <sha>` or
`VERDICT: CHANGES <sha>`. Do not merge until the owner approves.
