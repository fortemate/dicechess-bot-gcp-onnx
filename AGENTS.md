# AGENTS.md

Agent guidance for the `fortemate/dicechess-bot-gcp-onnx` repository.

## Definition of Done — before every commit

<!-- dc-shared:definition-of-done v1 — keep identical across Fortemate Scala repositories -->

1. Format: `mise run format`. If `mise` is not on PATH: `~/.local/bin/mise exec -- sbt scalafmtAll`.
2. Gate: `mise run check` — the same command CI runs. If part of it cannot run in your sandbox (for
   example Docker for Testcontainers), run `mise exec -- sbt 'scalafmtCheckAll; Test/compile'` plus every
   suite that can run, and list what you skipped in the pull request.
3. Never publish unformatted Scala or code that does not compile: CI rejects both, and every red run
   costs a review cycle.

Sandboxed agents (Jules): the toolchain is provisioned by `scripts/jules-setup.sh` (Java, sbt, scalafmt
via mise). If a tool is missing, run `bash scripts/jules-setup.sh` instead of installing tools ad hoc.

<!-- /dc-shared:definition-of-done -->

## Issue management
<!-- dc-shared:issue-management v7 — keep identical across Fortemate repositories -->

- Classify work with the native GitHub Issue Type: `Bug` (unexpected or incorrect behavior), `Feature` (request, idea, new user-visible capability), `Task` (a specific piece of engineering, research, maintenance or documentation work). Labels on Issues name a technical domain or cross-cutting concern only, never repeat the Type, and must already exist in the repository.
- Never commit to a repository's default branch. Name branches you control `<type>/<short-description>` or `<type>/<issue-id>-<short-description>` with a type from `task|feat|bug|refactor|chore|docs|ci|test|perf`. A branch that carries an Issue id must be closed by its pull request (`Closes #<id>`, or `Closes owner/repository#<id>` across repositories); partial work uses a non-closing reference. Before dispatching an external tool, read the repository's live PR-policy workflow: a tool-managed branch name is acceptable only when that policy allows it and the pull request closes the delegated leaf Issue — never edit a workflow to make a generated branch pass. A delegated pull request and its commits close only their leaf Issue, never a parent or sibling.
- GitHub-facing text is English-only. Every Issue has `Context`, `Objective` and a testable `Definition of Done`; create it with `gh issue create --body-file <file>`, never with an inline multi-line body, and search open and closed Issues across Fortemate repositories for duplicates first. Every actionable Issue (never a pull request) belongs to the organization Project [Fortemate Engineering](https://github.com/orgs/fortemate/projects/1); triage (Type, `Execution tier`, `Status`, `Priority`, labels, relationships, assignee) and the mandatory read-back after every mutation follow the `github-issue-workflow` skill in `fortemate-internal/skills/`.
- `jules` is a live execution trigger, not a label. Jules, Antigravity, CI, delegated subagents and any agent without the current user's explicit task-scoped authorization never apply, reapply or remove it. Dispatch qualification, monitoring, feedback (only a submitted comment starting with `@jules`; every other comment by the triggering user wakes the session too), takeover, the audit-marker rule for closed Issues and the "no bare `#N` in a spec" rule are the `jules-delegation` skill; a repository must pass the `jules-repo-readiness` skill before its first dispatch.
- The human owner reviews, approves and merges pull requests. Agents never merge pull requests or execute releases.

<!-- /dc-shared:issue-management -->
