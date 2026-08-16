# Pipeline Release Validation Handoff

Status: **PENDING VALIDATION**

This document is the execution contract for the validation Agent. The implementation provides automated checks, but the checks in this document have not yet been executed against a fresh real environment.

## Scope

The release gate covers:

- Flyway V17/V21/V22 application, legacy profile compatibility, the knowledge-profile toggle, and cross-store vector state.
- Full ordinary Maven reactor tests before real-MySQL integration tests.
- Upload -> RAG -> chunk -> embedding -> profile -> retrieval.
- `SYNC_RETRIEVAL_SOURCE` source snapshot writes.
- Sequential idempotency, concurrent idempotency, conflict handling, and transaction rollback.
- Pipeline event visibility, failed-task retry, manual classification, `NEEDS_REVIEW` approval, and audit records.
- Legacy task action/stage/reason readability.
- OWNER versus outsider permission behavior through the existing P1 acceptance suite.

The following explicitly remain out of scope until separately approved:

- changing Qdrant Java client `1.17.0` or Qdrant server `1.15.4`;
- upgrading Flyway or pinning/changing MySQL `8.3`;
- V2 enhanced indexes, LLM automatic approval, and GraphRAG.

## Safety Boundary

Run this validation only against a disposable or isolated Compose environment.

The acceptance scripts create users, spaces, documents, pipeline tasks, profile versions, and audit rows. They also delete their own uploaded files to verify MinIO, MySQL, and Qdrant cleanup. Do not point the scripts at production.

## Prerequisites

1. Docker Desktop is available.
2. The YLCloud Compose stack is healthy.
3. The application has started after the current migrations were built into the image.
4. MySQL is reachable from the host on the configured host port.
5. Model and parser credentials required by the selected environment are configured.
6. `mvn`, `curl.exe`, and Windows PowerShell are available.

Recommended isolated startup:

```powershell
docker compose -p ylcloud-pipeline-release up -d --build
docker compose -p ylcloud-pipeline-release ps
```

If an isolated project name changes container names, either remove `container_name` overrides for the validation deployment or pass the actual MySQL container name to the acceptance command.

## Single Command

From the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/pipeline-release-acceptance.ps1 `
  -BaseUrl http://127.0.0.1:5173 `
  -MysqlContainer ylcloud-mysql `
  -MinioContainer ylcloud-minio `
  -AppContainer ylcloud-app `
  -QdrantBaseUrl http://127.0.0.1:6333 `
  -MysqlJdbcUrl "jdbc:mysql://127.0.0.1:3306/ylcloud?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
```

Pass the actual container names and Qdrant host URL when the isolated validation stack uses non-default targets.

The command performs these suites in order:

1. V17/V21/V22 Flyway history and required schema-column checks.
2. Full `mvn test` reactor.
3. `KnowledgeProfileWriteServiceMysqlIT` against real MySQL.
4. `scripts/p0-acceptance.ps1`.
5. `scripts/p1-deploy-acceptance.ps1`.
6. Optional multipart fault injection when both `-RunMultipartFaultInjection` and `-ConfirmDisposableEnvironment` are supplied.

Reports and logs are written under:

```text
outputs/pipeline-release-acceptance/
```

## Real MySQL Integration Assertions

The MySQL integration suite is disabled during ordinary `mvn test`. The release script enables it with environment variables and runs it explicitly.

Expected tests:

- `legacyRevisionZeroProfileSynchronizesInRealMysql`
  - legacy `signature = null`, `revision = 0` becomes a signed revision 1 snapshot;
- `concurrentIdenticalSnapshotsAreIdempotent`
  - both requests succeed and revision remains 1;
- `concurrentDifferentSnapshotsAllowOneWriter`
  - exactly one request succeeds and one reports `409 Conflict` semantics;
- `runtimeFailureAfterUpdateRollsBackTransaction`
  - an injected post-update failure rolls back signature, revision, and chunk fields.

To run only this suite:

```powershell
$env:YLCLOUD_PIPELINE_MYSQL_TEST = "true"
$env:YLCLOUD_PIPELINE_MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/ylcloud?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:YLCLOUD_PIPELINE_MYSQL_USER = "ylcloud"
$env:YLCLOUD_PIPELINE_MYSQL_PASSWORD = "<environment password>"
mvn -pl cloud-server -am "-Dtest=KnowledgeProfileWriteServiceMysqlIT" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Remove the four environment variables after execution. Never include the database password in the report.

## P0 Assertions

The updated P0 suite must report all of the following:

- DOCX structured parsing and retrieval succeeded.
- Knowledge profile is enabled by default; disabling it leaves RAG indexing successful and creates no profile task.
- Initial profile task reports one successful and zero failed documents.
- A successful indexed document is `ACTIVE` in the V22 vector-state model.
- Initial Pipeline task exposed core stage events.
- A legacy profile with null signature/revision 0 selected `SYNC_RETRIEVAL_SOURCE`.
- Source signature is nonblank and revision becomes 1.
- Title, summary, and profile version were not changed by source synchronization.
- Repeating the same RAG rebuild selected `SKIP_PROFILE` and did not increment revision.
- Manual category appears in category facets.
- `NEEDS_REVIEW` approval ends as `VALID/APPROVED`.
- Audit contains `PROFILE_EDIT` and `PROFILE_APPROVE`.
- Retrying an injected failed task creates a successful replacement task.
- A persisted legacy `REBUILD_RETRIEVAL_ONLY` task remains readable.
- Async-task aggregation and MinIO/MySQL/Qdrant cleanup assertions still pass.

## Permission Assertions

The P1 suite must continue to prove:

- unauthenticated access returns HTTP 401;
- an outsider is denied Space access with HTTP 403;
- invalid input, missing resources, and conflicts return 400/404/409;
- version history remains `[2,1]` after the first update.

## Manual Review Items

The validation Agent must inspect these items even when all scripts pass:

- no Profile or Generated Question retrieval SQL route exists;
- no production-only test endpoint or failure-injection switch was added;
- generated reports contain no password, token, or provider API key;
- only acceptance-owned fixtures were deleted;
- task events show the actual terminal stage and reason;
- V17/V21/V22 are each applied once and a second application restart does not rerun them.

## Pass Criteria

Mark the release gate passed only when:

- the aggregate report status is `PASSED`;
- all four MySQL integration tests pass;
- P0 and P1 scripts exit successfully;
- the application restarts successfully after validation;
- no credential appears in reports or logs;
- manual review items are confirmed.

Any missing real-environment execution remains **PENDING VALIDATION**, even if unit tests and compilation pass.

## Validation Report Template

```markdown
# Pipeline Release Validation Report

- Date:
- Commit / working-tree identifier:
- Environment:
- Validator:
- Aggregate report path:

## Results

- [ ] V17/V21/V22 applied and restart-safe
- [ ] Knowledge-profile default/disabled behavior
- [ ] V22 vector-state invariant
- [ ] Legacy profile first synchronization
- [ ] Sequential idempotency
- [ ] Concurrent identical snapshots
- [ ] Concurrent different snapshots
- [ ] Transaction rollback
- [ ] Upload -> RAG -> profile -> retrieval
- [ ] Pipeline stages and terminal reasons
- [ ] Failed-task retry
- [ ] Manual classification and facets
- [ ] NEEDS_REVIEW approval
- [ ] Audit records
- [ ] Permission matrix
- [ ] Legacy task values readable
- [ ] Credential scan clean

## Failures

Record the exact failed assertion, relevant task IDs, report/log paths, and whether data cleanup completed.

## Decision

- [ ] PASSED
- [ ] FAILED
- [ ] BLOCKED
```
