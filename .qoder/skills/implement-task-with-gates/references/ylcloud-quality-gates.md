# YLCloud task quality gates

Use this reference for implementation and review in the YLCloud repository.

## Repository evidence sources

Read the target files under:

- `docs/obsidian/10-规划与实施/` for requirements, ADRs, TASKs, and TEST specifications;
- `.plans/` for dependency order and rollout plans;
- `cloud-server/src/main/resources/db/migration/` for database history;
- `docker-compose.yml`, `docker-compose.hub.yml`, `.env.example`, and `.env.server.example` for environment parity;
- `scripts/` for deployment, backup, restore, and operational tests.

Use `rg` and `rg --files` for discovery. Check `git worktree list`, branch, HEAD, status, log, and the full diff from the agreed baseline before changing code.

## Standard command families

Adapt commands to the environment and TASK; do not omit a required family just because another family passed.

```text
git diff --check
mvn test
cd cloud-frontend && npm run test -- --run
cd cloud-frontend && npm run build
docker compose config --quiet
docker compose -f docker-compose.hub.yml config --quiet
bash -n scripts/backup.sh
bash -n scripts/restore.sh
bash -n scripts/deploy.sh
bash scripts/tests/deploy-sh-test.sh
```

Run focused tests before full suites. Run Docker-backed integration, browser E2E, accessibility, performance, soak, and restore/upgrade tests whenever the TASK or unified test specification requires them.

## Domain-specific blocking rules

### Account cancellation and deletion

- Enforce ownership and dependency checks before cancellation.
- Keep recovery and purge transitions transactional and restart-safe.
- Physically clear or durably enqueue every required file, object, message, memory, credential, webhook, and vector artifact.
- Do not count a loop iteration as cleanup unless a real service, mapper, or queue operation occurred.
- Do not mark `PURGED` while any required cleanup is failed, pending, or unverifiable.
- Persist step output and errors so retries cannot forget earlier failures.
- Test failure at each step, duplicate execution, scheduler restart, and tenant isolation.

### Data export

- Export the complete documented scope. Paginate large collections or disclose and test an intentional limit.
- Include actual required payloads, not metadata-only substitutes.
- Use a stable externally supplied master key in every production deployment.
- Fail startup or export closed when the key is missing or malformed; never generate a restart-volatile fallback for durable exports.
- Keep local and hub Compose secret mounts equivalent.
- Do not persist or log plaintext decryption keys in generic async-task results.
- Test encrypt, restart, unwrap, download expiry, ownership, key rotation policy, and large datasets.

### Security audit

- Cover login, authorization denial, owner/admin changes, grants, high-risk tools, API keys, webhooks, export, deletion, retry/cancel, DLQ, backup, restore, maintenance, upgrade, rollback, and retention changes as required.
- Store success and failure outcomes with actor, target, correlation, and useful detail.
- Test query authorization, tenant boundaries, retention cleanup, permanent categories, and write failure policy.

### Backup and restore

- Use a durable state sequence such as `RUNNING -> VERIFIED -> RESTORE_VERIFIED -> READY`.
- Archive integrity alone is not restore verification.
- Perform a real isolated restore of MySQL, MinIO, Qdrant, and configuration, plus representative business checks, before `READY`.
- Treat inability to record backup state or verify restore as fatal for a mandatory backup run.
- Never let deploy continue after missing or failed restore verification.
- Test corruption, missing archive, wrong hash, partial store failure, isolation cleanup, retention, and successful recovery.

### Maintenance, deploy, and rollback

- Ensure the deployment process and both old/new application containers observe the same durable maintenance marker. A host file and an unrelated Docker named volume are not shared state.
- Fail deployment if maintenance enablement cannot be confirmed.
- Block all write paths, including open/internal APIs and async submissions, while keeping only documented health/read/disable paths available.
- Validate both Compose variants and all secret/environment examples.
- Exercise success, preflight failure, migration failure, health failure, signal interruption, and rollback fixtures.
- Verify rollback restores the previous images and compatible application state.

### Frontend and final acceptance

- Add tests for every new page and critical interaction; existing unrelated Vitest tests are insufficient.
- Run real-browser E2E for user cancellation/recovery/export and admin audit/backup/maintenance flows.
- Verify keyboard access, focus, labels, contrast, loading, empty, error, and permission-denied states.
- Produce the traceability matrix and exact evidence required by the unified TEST document.

## Review severity

- `P0`: data loss, security bypass, false terminal success, unusable backup/restore, unsafe upgrade, production configuration omission. Block merge and release.
- `P1`: required acceptance behavior missing or untested, incorrect retry/idempotency, material tenant leak, inaccessible critical flow. Block TASK completion.
- `P2`: maintainability, observability, or noncritical UX defect. Fix in scope or record an explicitly accepted follow-up.

Passing compilation and legacy suites does not reduce a P0 or P1 finding without a test that exercises the affected behavior.
