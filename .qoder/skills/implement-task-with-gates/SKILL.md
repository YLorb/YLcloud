---
name: implement-task-with-gates
description: Implement and verify one atomic software TASK at a time from repository requirement, ADR, task, test, and rollout documents. Use for YLCloud TASK implementation, interrupted-task continuation, code-quality rechecks, acceptance testing, release readiness, or one-commit-per-TASK delivery where correctness, rollback safety, and evidence-backed completion are mandatory.
---

# Implement Task With Gates

Deliver a TASK only when its code, tests, operational path, documentation, and Git history satisfy the written acceptance criteria. Treat passing legacy tests as regression evidence, not proof that new behavior works.

## Establish the baseline

1. Read the target TASK completely.
2. Read every directly referenced requirement, ADR, test specification, dependency TASK, and rollout document needed to interpret it.
3. Inspect the current branch, HEAD, worktrees, status, recent commits, and diff range. Preserve unrelated or user-owned changes.
4. Build an acceptance matrix before editing:
   - requirement or invariant;
   - implementation location;
   - positive test;
   - denial, failure, retry, or rollback test;
   - operational evidence;
   - status.
5. If resuming interrupted work, derive the baseline from Git and artifacts. Never rely on a prior completion claim alone.

For this repository, read [references/ylcloud-quality-gates.md](references/ylcloud-quality-gates.md) before implementation or review.

## Implement one atomic TASK

1. Keep the change within the TASK scope and its necessary dependencies.
2. Reuse established project architecture, naming, error handling, authorization, transaction, async-task, and migration patterns.
3. Implement end-to-end behavior. Do not replace required work with comments, counters, mock success, warnings, placeholder data, or UI-only wiring.
4. Make failure closed:
   - do not advance terminal state when required cleanup, persistence, verification, or notification failed;
   - preserve enough durable state to retry after process restart;
   - propagate mandatory gate failures instead of logging and continuing.
5. Make async and distributed work idempotent. Define ownership, deduplication key, retry behavior, terminal state, and compensation.
6. Keep local and production configurations equivalent. Update all relevant Compose variants, environment examples, secret mounts, health checks, scripts, and rollback paths together.
7. Use additive database migrations. Check constraints, indexes, uniqueness, existing-data compatibility, and rollback consequences.
8. Update implementation documents only with evidence that actually exists.

## Write proof-oriented tests

Add tests for the new behavior in the same TASK commit. Cover the smallest useful combination of:

- happy path and observable result;
- authorization and tenant isolation;
- invalid input and boundary values;
- dependency failure and fail-closed behavior;
- retry, idempotency, duplicate delivery, and restart recovery;
- transactional partial failure;
- concurrency or stale-version protection;
- migration and serialization contracts;
- production configuration parity;
- UI loading, success, empty, error, disabled, and permission states;
- rollback or restore behavior when the TASK changes operations.

Prefer a focused test that would fail before the implementation over a broad test that merely executes code. Never alter an existing expectation solely to make a regression suite green without proving the behavior change is intended.

## Run gates in layers

Run focused tests first, then the complete gates required by the TASK:

1. Static checks: changed-file review, forbidden placeholders, `git diff --check`.
2. Backend: compile and focused tests, then the full Maven suite.
3. Frontend: focused component tests, full Vitest suite, TypeScript and production build.
4. Configuration: validate every supported Compose file with production-like required variables.
5. Scripts: syntax checks, deterministic fixture tests, and failure-injection cases.
6. Integration: start real dependencies when required and verify migrations, APIs, queues, storage, restart, and rollback.
7. Acceptance: run the exact E2E, accessibility, load, soak, backup/restore, or upgrade scenarios named by the test specification.

Record commands, exit codes, test counts, environment, and relevant artifacts. If a required gate cannot run, mark the TASK blocked or incomplete; do not silently downgrade it.

## Review before commit

Inspect the complete TASK diff, not only the last fix. Search for:

- empty implementations and comment-only branches;
- swallowed exceptions and `|| true` on mandatory operations;
- warnings followed by success;
- state transitions before durable work finishes;
- fixed-size export queries without pagination or truncation reporting;
- secrets generated ephemerally or exposed in task results/logs;
- host/container paths that are not actually shared;
- local Compose changes missing from production Compose;
- new production code with no new tests;
- documents claiming evidence that was not generated.

Resolve every blocking finding and rerun affected gates.

## Commit exactly once per TASK

1. Confirm the worktree contains only the target TASK and intentional prerequisites.
2. Stage explicit files; do not stage unrelated changes.
3. Run `git diff --cached --check` and review the staged diff.
4. Commit only after all mandatory gates pass, using a message such as `feat(TASK-010): implement account deletion and export`.
5. Verify the commit and clean status.
6. Move to the next TASK only after the current TASK commit exists.

Do not merge a review branch because it compiles. Merge only when the acceptance matrix is complete and no release-blocking finding remains.

## Report the outcome

Lead with one status: `PASS`, `BLOCKED`, or `FAIL`.

Include:

- TASK and commit;
- implemented acceptance items;
- exact test and operational evidence;
- unresolved findings with file and line;
- rollback readiness;
- whether merge or release is authorized.

Never describe partial implementation as complete.
