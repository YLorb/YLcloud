# Knowledge Pipeline Design Notes

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
