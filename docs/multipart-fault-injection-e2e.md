# Multipart Upload Fault-Injection E2E

Status: **PASSED IN REAL COMPOSE ENVIRONMENT (2026-07-16)**

Latest evidence: `outputs/multipart-fault-injection/multipart-fi-1784131822-f7d965.json`

- Missing chunk: rejected with task/metadata state `1,1,0,0`.
- Wrong whole-file digest: rejected with state `1,0,0` and zero final-object versions.
- Eight concurrent merges: one success, seven conflicts, converged state `2,1,1`.
- Hard process exit: chunk 0 survived restart and the resumed upload converged to `2,1,1`.
- Metadata rollback: injected failure state `1,1,0`, retry state `2,1,1`, composed object reused.
- Acceptance cleanup: passed with no reported cleanup error.

This suite validates multipart upload invariants across the application process, MySQL, and MinIO. Run it only against an isolated or disposable Compose environment. It deliberately kills and restarts the application container and directly injects one acceptance-owned metadata conflict into MySQL.

## Covered failures

| Scenario | Injection | Required invariant |
| --- | --- | --- |
| Missing chunk | Upload chunk 0 of 2, then request merge | HTTP 400; task remains `UPLOADING`; uploaded chunk remains resumable; no final object or file metadata |
| Wrong digest | Declare an incorrect whole-file MD5, upload valid chunks, then merge | Composed object is streamed through MD5/SHA1/SHA256 validation; merge rolls back; final object is compensated; no file metadata |
| Concurrent merge | Submit eight merge requests for the same completed upload | Only HTTP 200/409 outcomes; every success references one UUID; exactly one `file_info` and one active `user_file` row |
| Process exit | Hard-kill the app after the first chunk commits | Restart preserves the committed chunk; the second chunk can be uploaded; merge converges to one file |
| Metadata rollback | Preinsert a conflicting `file_info.file_uuid` after all chunks upload | Compose succeeds but metadata transaction rolls back to `UPLOADING`; no `user_file` leaks; removing the injected conflict and retrying reuses the composed object and commits once |

The script uses unique user, file, upload, and object identifiers. Its `finally` block removes acceptance-owned upload rows, file metadata, MinIO object versions, temporary chunks, the temporary personal space, and the temporary user. It never serializes the password or bearer token.

## Run

Start a freshly built isolated stack, then run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/multipart-fault-injection-e2e.ps1 `
  -BaseUrl http://127.0.0.1:5173 `
  -MysqlContainer ylcloud-mysql `
  -MinioContainer ylcloud-minio `
  -AppContainer ylcloud-app `
  -ConfirmDisposableEnvironment
```

Optional parameters:

- `-ConcurrentMergeRequests 8` controls the merge race width and must be at least 2.
- `-OutputDirectory outputs/multipart-fault-injection` changes the JSON report directory.

## Pass criteria

The command exits with code 0 and emits JSON with:

```json
{
  "status": "PASSED",
  "scenarios": {
    "missingChunk": { "passed": true },
    "wrongDigest": { "passed": true },
    "concurrentMerge": { "passed": true },
    "processExit": { "passed": true },
    "metadataRollback": { "passed": true }
  },
  "cleanup": { "passed": true }
}
```

Reports are written under `outputs/multipart-fault-injection/`. A failed cleanup is a failed suite, even if all functional assertions passed.

## Implementation constraint

No production fault-injection endpoint or runtime switch is introduced. Faults are injected only through acceptance-owned requests, a hard container stop, and a temporary unique-key conflict in the disposable database.
