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
