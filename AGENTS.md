# AGENTS.md

Agent guidance for the `fortemate/dicechess-bot-gcp-onnx` repository.

## Issue management
<!-- dc-shared:issue-management v1 — keep identical across Fortemate repositories -->

- Use the native GitHub Issue Type as the canonical work classification:
  - `Bug` for unexpected or incorrect behavior.
  - `Feature` for a request, idea, or new user-visible capability.
  - `Task` for a specific piece of engineering, research, maintenance, or documentation work.
- Do not apply `bug` or `enhancement` labels to Issues merely to repeat their Type. Keep those labels for pull-request release classification. On Issues, labels describe only a technical domain or cross-cutting concern, and only existing repository labels may be used.
- Before creating or updating an Issue, search relevant Fortemate repositories across open and closed Issues for semantic duplicates. Read the live Types, field options, labels, assignees, relationships, and open milestones before mutation; never rely on cached IDs or invent metadata.
- GitHub-facing work items are English-only. Use the appropriate Issue Form when available, or `gh issue create --body-file <file>` for CLI creation; never pass a multiline body inline. Every Issue must contain `Context`, `Objective`, and a testable `Definition of Done`.
- Add every actionable Issue and pull request to the organization Project [Fortemate Engineering](https://github.com/orgs/fortemate/projects/1).
- Use Project `Status` only for workflow state:
  - `Backlog` means triaged but not committed for active work.
  - `Ready` means sufficiently defined and available to start.
  - `In progress` means someone is actively working on it.
  - `In review` means implementation is waiting for review or validation.
  - `Done` means the Issue is closed or the pull request is merged.
- Set the organization `Priority` Issue field by impact and urgency: `Urgent` for an immediate incident, security problem, or release blocker; `High` for important or blocking planned work; `Medium` for normal planned work; and `Low` for opportunistic backlog.
- Set the organization `Effort` Issue field to `Low`, `Medium`, or `High` as a relative implementation-and-verification estimate, not as priority or a time promise. Never replace either organization field with labels or duplicate Project fields; leave a value unset when current evidence does not justify it.
- Triage establishes Type, Priority, Effort, applicable labels, Project membership, Status, milestone, and relationships. Assign an Issue only when a person owns its next action, and assign the active owner before moving it to `In progress`; unassigned means no current owner, not low priority.
- Use parent/sub-issue relationships for independently actionable decomposition, `Blocking`/`Blocked by` for hard ordering dependencies, and `Relates to` for non-blocking associations. If the live UI or API cannot create a relation, add an explicit `Related: owner/repository#<id>` cross-reference. Do not simulate relationships with title prefixes, labels, or duplicate task lists.
- A pull request that fully completes an Issue must link it with `Closes #<id>` (or the cross-repository equivalent). Use a non-closing reference when the PR is only partial work.
- After every Issue or Project mutation, read the item back and verify its Type, fields, labels, assignee, relationships, Project membership, and Status. Report any metadata that the available API or UI could not set.
- The human owner reviews, approves, and merges pull requests. Agents never merge pull requests or execute releases.

<!-- /dc-shared:issue-management -->
