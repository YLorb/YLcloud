# Knowledge Pipeline Design Notes

> Implementation status (2026-07-15): the RAG readiness, optional knowledge profile switch,
> skipped terminal states, persisted batch counts, active-batch idempotency, cross-store compensation,
> and separated UI task sections described below are implemented. Backend tests (90), model-service
> tests (3), frontend production build, Flyway V21/V22, a real 5-document/609-chunk retry, and the P0
> upload/rebuild/profile/delete end-to-end acceptance all passed.

## RAG Readiness and Optional Knowledge Profile Contract

Knowledge profiling is an optional enhancement after the base RAG index becomes queryable. It is not evidence that document parsing, chunking, embedding, or vector indexing succeeded.

The product must expose three separate meanings instead of one ambiguous `SUCCESS`:

- `ACCEPTED`: the file was added and an asynchronous RAG task was created;
- `RAG_READY`: parsing, non-empty chunking, embedding, vector persistence, and consistency checks succeeded;
- profile result: `PROFILE_SUCCESS`, `PROFILE_PARTIAL_SUCCESS`, `PROFILE_FAILED`, or `PROFILE_SKIPPED_DISABLED`.

The required orchestration is:

```text
File added to knowledge base
  -> RAG task accepted
  -> parse
  -> validate non-empty text and chunks
  -> embed and validate count/dimension
  -> persist and verify vector index
  -> RAG_READY
  -> read latest knowledgeProfileEnabled setting
       -> disabled: record PROFILE_SKIPPED_DISABLED and stop
       -> enabled: submit profile work and aggregate terminal results
```

An empty chunk list is always a RAG failure. Vector-store code may treat an empty input as a no-op internally, but the orchestration layer must reject it before vector-store invocation. A RAG task must also fail if embedding count differs from the number of non-empty chunks, vector dimensions differ from the configured collection, or vector persistence cannot be verified.

Chunk/ref rows created before an embedding or vector-store failure must be transactionally rolled back, disabled, or explicitly retained as retryable intermediate data. They must not remain active while the document reports `chunk_count = 0` without an explainable intermediate state.

### Cross-store Consistency Contract

MySQL and Qdrant do not share an XA transaction. The implementation therefore uses a fail-closed,
recoverable Saga:

```text
MySQL begin: document=INDEXING/BUILDING, refs disabled
  -> parse and persist retryable physical chunks
  -> delete old Qdrant points and write new points
  -> exact Qdrant point-count verification
  -> one MySQL transaction: activate refs + document=SUCCESS/ACTIVE
  -> optional knowledge profile submission
```

Any failure after `BUILDING` moves the document to `FAILED/CLEANUP_PENDING`. A cleanup worker claims
`CLEANING`, deletes Qdrant points idempotently, verifies the count is zero, and commits `CLEAN`.
Startup and scheduled reconciliation compare every `SUCCESS/ACTIVE` document's `chunk_count`, active
ref count, and Qdrant document point count. A mismatch is isolated before cleanup, so stale data cannot
be retrieved while compensation is pending.

All DB retrieval routes require an enabled Space file, `SUCCESS/ACTIVE` document, enabled ref, and
enabled physical chunk. Qdrant matches are intersected with that DB-authorized candidate set. This is
the final safety boundary even when an external process crash leaves an orphan point temporarily.

### Knowledge Profile Enablement

Each knowledge base owns an independent `knowledgeProfileEnabled` setting:

- default: `true` for new and migrated knowledge bases;
- scope: per Space/knowledge base, not a global process environment variable;
- write permission: `OWNER` and `ADMIN` only;
- audit: record operator, old value, new value, and timestamp;
- runtime behavior: read the latest persisted value after RAG reaches `RAG_READY`;
- disabling does not delete historical profiles; it only prevents subsequent automatic profile generation or refresh;
- re-enabling affects newly completed or explicitly rebuilt documents. Existing RAG-ready documents require an explicit full profile rebuild unless a separate backfill action is requested.

Suggested schema/API change:

```text
space_rag_config.knowledge_profile_enabled = true
GET /api/space/{spaceId}/rag/config -> knowledgeProfileEnabled
PUT /api/space/{spaceId}/rag/config -> knowledgeProfileEnabled
```

### Batch Completion and User Notification

Profile notifications must be calculated from one batch or parent task, not from historical Space totals.

When profiling is enabled and all child document tasks are terminal, notify the initiating user:

```text
知识画像完成：成功 X 个文档，失败 Y 个文档
```

Status mapping:

- `X > 0, Y = 0`: `PROFILE_SUCCESS`;
- `X > 0, Y > 0`: `PROFILE_PARTIAL_SUCCESS`;
- `X = 0, Y > 0`: `PROFILE_FAILED`;
- no eligible RAG-ready documents: `PROFILE_SKIPPED` with reason `NO_ELIGIBLE_DOCUMENTS`, never plain success;
- profile disabled: `PROFILE_SKIPPED_DISABLED` with the message `RAG 索引已完成；知识画像已关闭，未执行画像增强`.

The notification and counts must be persisted so refresh, re-login, and cross-device access can recover the same final result. Failure details remain available from the task page and can be retried without rebuilding an already valid base RAG index.

The UI must present RAG indexing tasks and knowledge profile tasks as separate sections. A profile task must not be labeled as an index task, and a profile failure must be displayed as `RAG 已就绪、画像增强失败` when the base index remains queryable.

### Required Tests

- empty parse text and empty chunks fail before embedding;
- embedding HTTP failure, count mismatch, and dimension mismatch fail RAG indexing;
- Qdrant write failure leaves a consistent retryable state;
- default profile setting is enabled after fresh creation and migration;
- only OWNER/ADMIN can toggle the setting and every change is audited;
- disabled setting creates no profile task/version/event;
- enabled setting profiles only RAG-ready documents and reports exact batch counts;
- zero eligible documents becomes `SKIPPED/NO_ELIGIBLE_DOCUMENTS`;
- partial profile failure does not invalidate or remove the base RAG index;
- persisted completion notification survives refresh and re-login.

## Current Version Activation Policy

The current implementation creates a new profile version whenever the knowledge profile is generated, repaired, manually edited, failed, or restored.

For compatibility with the existing YLCloud knowledge pipeline, every newly created profile version is immediately written as both:

- `current_version_id`
- `latest_version_id`

This means a newly generated pipeline result becomes the active knowledge profile right away.

## Reserved Design Limitation

The current version model does not yet distinguish between:

- a generated candidate version
- a reviewed or approved published version
- the currently active version used by retrieval and UI display

This is acceptable for the current MVP because the existing product behavior expects generated profile results to become visible immediately.

However, if the next stage requires stricter knowledge governance, the pipeline should introduce an explicit publication model.

## Future Publishing Model

A future version should separate `latest_version_id` and `current_version_id` semantics:

- `latest_version_id`: the newest generated or edited profile version, including unreviewed candidates.
- `current_version_id`: the version currently published and used by the product.
- `published_version_id` or `active_version_id`: optional explicit alias if the model needs clearer naming.

Suggested version states:

- `CANDIDATE`: generated but not yet approved.
- `NEEDS_REVIEW`: generated with quality or policy concerns.
- `APPROVED`: reviewed and allowed to publish.
- `PUBLISHED`: active version used by retrieval and UI.
- `REJECTED`: reviewed and rejected.
- `RESTORED`: created from a historical version and published or pending review depending on policy.

## Required Behavior When Added

When this publishing model is implemented:

1. Pipeline generation should create a candidate version first.
2. A future V2 enhanced index should only use the current published version unless explicitly configured otherwise.
3. Human approval should promote a candidate version to current.
4. Restore should create a new restored version rather than mutating historical versions.
5. Audit logs should record approval, rejection, publication, and restore operations.
6. UI should clearly show current, latest, candidate, and historical versions.

## Current Decision

For now, YLCloud keeps the simpler immediate-activation strategy:

```text
Pipeline generates profile
  -> create profile version
  -> set current_version_id = new version id
  -> set latest_version_id = new version id
  -> profile becomes active immediately
```

This limitation is intentional and should be revisited when the product moves from MVP automation to governed knowledge operations.

## Incremental Update Strategy

The pipeline records the retrieval source snapshot associated with each knowledge profile:

- `source_file_hash`
- `source_parser_version`
- `profile_schema_version`
- `source_chunk_count`
- `source_character_count`
- `source_snapshot_signature`
- `source_snapshot_revision`

`source_snapshot_signature` is a SHA-256 signature over ordered chunk indexes, content hashes/content, normalized metadata, and parser version. Database chunk IDs are stored for traceability but are not part of the semantic signature.

When a document pipeline task runs, it executes `CHECK_INCREMENTAL` after `LOAD_CHUNKS` and before `GENERATE_PROFILE`.

The decision can be:

- `FORCE_REBUILD`: the user explicitly requested rebuild, so the profile pipeline continues.
- `REBUILD_PROFILE`: the profile is missing, invalid, failed, or the file hash/parser/schema changed.
- `SYNC_RETRIEVAL_SOURCE`: the file hash is unchanged but chunk IDs, metrics, or semantic signature changed, so the task synchronizes source snapshot metadata without regenerating the profile.
- `SKIP_PROFILE`: the file hash, parser version, schema version, and complete source snapshot are unchanged.

Skipped tasks are treated as successful async tasks. They record:

- `terminal_stage`
- `terminal_reason`
- `incremental_action`
- `incremental_detail`

Current terminal reasons:

- `UNCHANGED_DOCUMENT`: no profile rebuild is needed.
- `SOURCE_SNAPSHOT_SYNCED`: profile generation is skipped and source snapshot metadata is synchronized.

Source snapshot synchronization is transactional and uses `source_snapshot_revision` as an optimistic lock. Repeating an already-applied snapshot is an idempotent success. A concurrent different update fails with a conflict so the asynchronous task can be retried.

Historical task rows may still contain `REBUILD_RETRIEVAL_ONLY`, `BUILD_RETRIEVAL_ENHANCEMENT`, or `RETRIEVAL_ONLY`; these values are read-only compatibility aliases and are not emitted by new tasks.

## Retrieval Product Roadmap

- **V1, current:** only source chunks participate in retrieval. Profile, summary, tags, and generated questions remain knowledge-management data.
- **V2, planned:** build a separate enhanced index for summaries, FAQ, and approved profile data after base chunk retrieval is stable.
- **V3, reserved:** combine enhanced indexes with entity relations and GraphRAG/multi-hop retrieval. V1 does not implement graph storage or graph retrieval.

Manual profile rebuild actions use `force_rebuild = true` and bypass the skip rule.
