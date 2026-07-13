# Knowledge Pipeline API

This document describes the YLCloud KnowledgeOps API used by the knowledge engineering pipeline and frontend operations UI.

Base path:

```text
/api/space/{spaceId}/knowledge
```

Permission model:

- Member can read dashboard, facets, document items, and profiles.
- Admin can create tasks, retry tasks, update profiles, classify documents, and confirm reviews.

## Task APIs

### Run One Document

```http
POST /api/space/{spaceId}/knowledge/pipeline/documents/{documentId}/run
```

Creates a document profile task and runs it asynchronously.

### Run Whole Space

```http
POST /api/space/{spaceId}/knowledge/pipeline/run-all
```

Creates a space profile task and runs it asynchronously.

### List Tasks

```http
GET /api/space/{spaceId}/knowledge/pipeline/tasks
```

Returns recent Knowledge Pipeline tasks.

Task fields:

- `taskStatus`: `PENDING`, `RUNNING`, `SUCCESS`, `PARTIAL_SUCCESS`, `FAILED`
- `stage`: legacy stages plus pipeline stages such as `LOAD_CHUNKS`, `CHECK_INCREMENTAL`, `SYNC_RETRIEVAL_SOURCE`, `GENERATE_PROFILE`, `PARSE_PROFILE`, `NORMALIZE_PROFILE`, `VALIDATE_PROFILE`, `SCORE_PROFILE`, `REPAIR_PROFILE`, `SAVE_PROFILE`
- `progress`: UI progress percentage
- `totalCount`, `successCount`, `failedCount`
- `errorMessage`: terminal reason when task failed or partially succeeded
- `forceRebuild`: whether the task bypassed incremental skip checks.
- `terminalStage`: the stage where the task stopped, such as `CHECK_INCREMENTAL`.
- `terminalReason`: why the task stopped, such as `UNCHANGED_DOCUMENT`.
- `incrementalAction`: one of `FORCE_REBUILD`, `REBUILD_PROFILE`, `SYNC_RETRIEVAL_SOURCE`, or `SKIP_PROFILE`.
- `incrementalDetail`: human-readable explanation for the incremental decision.

`SKIP_PROFILE` and `SYNC_RETRIEVAL_SOURCE` are successful task outcomes, not failures. Historical rows may still expose the legacy `REBUILD_RETRIEVAL_ONLY` value.

### List Task Events

```http
GET /api/space/{spaceId}/knowledge/pipeline/tasks/{taskId}/events
```

Returns the append-only timeline for one knowledge pipeline task. Events are ordered by `id asc`.

Important fields:

- `stage`: pipeline stage, such as `GENERATE_PROFILE`, `PARSE_PROFILE`, `SCORE_PROFILE`, `REPAIR_PROFILE`, `SAVE_PROFILE`.
- `eventType`: `TASK_CREATED`, `STAGE_STARTED`, `STAGE_FINISHED`, `STAGE_FAILED`, `REVIEW_REQUIRED`, `TASK_FINISHED`.
- `eventStatus`: stage execution result, such as `RUNNING`, `SUCCEEDED`, `FAILED`.
- `errorCode`: structured failure code.
- `traceId`: execution trace id.
- `attemptNo`: retry/execution attempt number.
- `durationMs`: stage duration when available.

### Retry One Task

```http
POST /api/space/{spaceId}/knowledge/pipeline/tasks/{taskId}/retry
```

Creates a new retry task for failed or partially successful tasks.

### Retry Failed Tasks

```http
POST /api/space/{spaceId}/knowledge/pipeline/retry-failed
```

Retries recent failed and partially successful tasks in the space.

## Dashboard APIs

### Space Dashboard

```http
GET /api/space/{spaceId}/knowledge/dashboard
```

Returns:

- document count
- indexed document count
- profiled count
- failed profile count
- needs-review count
- average quality score
- category count
- tag count
- pending/running/failed task count
- top categories
- top tags
- recent failed tasks

## Document APIs

### List Knowledge Documents

```http
GET /api/space/{spaceId}/knowledge/documents
```

Query params:

- `category`
- `tag`
- `profileStatus`

Returns knowledge document items combining RAG document metadata and profile metadata.

Additional quality fields:

- `profileStatus`: `VALID`, `NEEDS_REVIEW`, `INVALID`, with legacy `SUCCESS` and `FAILED` still readable.
- `reviewStatus`: `NOT_REQUIRED`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `AUTO_FIXED`.
- `reviewReason`: comma-separated quality issue codes.
- `qualityIssueJson`: structured quality issue list.
- `sourceChunkCount`: number of chunks used to generate the profile.
- `repairAttempt`: local repair attempt count.

### Get Profile

```http
GET /api/space/{spaceId}/knowledge/documents/{documentId}/profile
```

Returns one document profile with summary, category, tags, keywords, questions, status, quality score, schema validation result, quality issues, repair metadata, source chunk statistics, `sourceSnapshotSignature`, and `sourceSnapshotRevision`.

### Update Profile

```http
PUT /api/space/{spaceId}/knowledge/documents/{documentId}/profile
```

Body:

```json
{
  "title": "Document title",
  "summary": "Manual summary",
  "category": "engineering",
  "tags": ["rag", "ops"],
  "keywords": ["retrieval", "pipeline"],
  "questions": ["How is this document used?"],
  "profileStatus": "SUCCESS"
}
```

### Classify Document

```http
PUT /api/space/{spaceId}/knowledge/documents/{documentId}/classify
```

Body:

```json
{
  "category": "product",
  "tags": ["roadmap", "requirements"]
}
```

### Mark Reviewed

```http
POST /api/space/{spaceId}/knowledge/documents/{documentId}/reviewed
```

Marks a `NEEDS_REVIEW` profile as `VALID` and writes an audit entry.

### List Profile Versions

```http
GET /api/space/{spaceId}/knowledge/documents/{documentId}/versions
```

Returns profile versions ordered by version number descending.

Important fields:

- `versionNo`: monotonic profile version number.
- `sourceType`: `LLM_GENERATED`, `LOCAL_REPAIRED`, `HUMAN_EDITED`, `RESTORED`, or failure/source-specific values.
- `profileSnapshot`: full JSON snapshot for that version.
- `qualityScore`: rule-based quality score for the version.
- `changeSummary`: short reason for version creation.

### Diff Profile Version

```http
GET /api/space/{spaceId}/knowledge/documents/{documentId}/versions/{versionId}/diff
GET /api/space/{spaceId}/knowledge/documents/{documentId}/versions/{versionId}/diff?compareTo={versionId}
```

Returns field-level diff instead of raw JSON text diff: summary, category, tags, keywords, questions, and quality score.

When `compareTo` is omitted, the selected version is compared with the current active version.

### Restore Profile Version

```http
POST /api/space/{spaceId}/knowledge/documents/{documentId}/versions/{versionId}/restore
```

Restores by creating a new profile version with `sourceType = RESTORED`; it does not mutate historical versions.

### Regenerate Profile

```http
POST /api/space/{spaceId}/knowledge/documents/{documentId}/regenerate
```

Creates a new async profile generation task.

### Reclassify

```http
POST /api/space/{spaceId}/knowledge/documents/{documentId}/reclassify
```

Creates a new async generation task to refresh generated category, tags, summary, and questions.

## Facet APIs

### Categories

```http
GET /api/space/{spaceId}/knowledge/facets/categories
```

### Tags

```http
GET /api/space/{spaceId}/knowledge/facets/tags
```

Facet response:

```json
{
  "name": "engineering",
  "count": 12
}
```
