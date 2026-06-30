# Knowledge Enhancement Chunk Filtering

This document defines when a chunk is allowed to participate in knowledge-enhanced retrieval.

## Scope

The filtering rule only applies to the knowledge enhancement routes:

- `profile_summary`
- `generated_question`

It does not change the existing base retrieval routes:

- vector retrieval
- BM25 retrieval
- keyword fallback
- metadata route
- title route
- structure route
- HyDE route
- step-back route

This keeps the original RAG behavior compatible while preventing low-quality chunks from amplifying profile or generated-question matches.

## Default Rules

A chunk can participate in knowledge-enhanced retrieval only when all of these conditions are met:

- `content` is not null.
- `trim(content)` length is greater than or equal to `knowledgeMinChunkChars`.
- `token_count` is null or greater than or equal to `knowledgeMinChunkTokens`.
- When `knowledgeSkipMetadataOnly` is enabled, the chunk metadata must not look like a metadata-only or fallback chunk.

Default values:

```text
knowledgeMinChunkChars = 80
knowledgeMinChunkTokens = 20
knowledgeSkipMetadataOnly = true
```

## Metadata-Only Detection

When `knowledgeSkipMetadataOnly` is enabled, a chunk is excluded from knowledge enhancement if its `metadata` contains any of these markers:

```text
metadata_only
metadata-only
"parser":"metadata"
"parser": "metadata"
"fallback":true
"fallback": true
```

The detection is case-insensitive.

## Configuration

The options are defined under `ylcloud.rag.retrieval`:

```yaml
ylcloud:
  rag:
    retrieval:
      knowledge-min-chunk-chars: 80
      knowledge-min-chunk-tokens: 20
      knowledge-skip-metadata-only: true
```

Tuning guidance:

- Increase `knowledge-min-chunk-chars` when profile or question enhancement returns noisy short fragments.
- Increase `knowledge-min-chunk-tokens` when short table headers, filenames, or OCR fragments are over-selected.
- Set `knowledge-skip-metadata-only` to `false` only for debugging or when metadata fallback chunks are known to contain enough useful semantic text.

## Implementation Notes

The filtering is enforced in `FileRagChunkMapper` for:

- `searchBySpaceAndKnowledgeProfile`
- `searchBySpaceAndKnowledgeQuestion`

`RagMultiRouteRetriever` reads the thresholds from `RagProperties.Retrieval` and passes them to both knowledge enhancement queries.

