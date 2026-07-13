# Knowledge Enhancement Retrieval Boundary

## V1 Current Behavior

YLCloud V1 retrieves only source chunks through these routes:

- vector retrieval
- BM25 retrieval
- keyword fallback
- metadata and title retrieval
- structural retrieval
- HyDE and step-back retrieval

Knowledge profile fields and generated questions are management data. They do not directly select source chunks and are not written to a separate retrieval index.

The former `profile_summary` and `generated_question` SQL routes were removed because they coupled profile metadata to source chunk selection without creating a real enhanced index. Consequently, low-quality or metadata-only chunks cannot be amplified by those routes in V1.

## Reserved V2 Filtering Contract

The existing `knowledgeMinChunkChars`, `knowledgeMinChunkTokens`, and `knowledgeSkipMetadataOnly` settings are retained as deprecated compatibility fields. V1 does not read them.

When V2 introduces a separate enhanced index for summaries, FAQ, or approved profile data, indexing must reject entries whose source chunk:

- has blank content;
- is shorter than the configured character or token threshold;
- is marked as metadata-only or parser fallback;
- belongs to an unpublished or rejected profile version.

V2 must apply filtering before embedding and index writes, not by joining profile metadata back to arbitrary source chunks during a user query.
