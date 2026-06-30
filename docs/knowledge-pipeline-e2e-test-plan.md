# Knowledge Pipeline E2E Test Plan

This document is intended for another Agent or LLM to run in a real YLCloud environment.

## Test Documents

Prepare these files:

1. `rag-ops-guide.md`
   - 800+ Chinese or English characters.
   - Contains sections about RAG indexing, chunking, retrieval, and operations.

2. `metadata-only-short.txt`
   - Very short content, less than 80 characters.
   - Intended to verify that knowledge-enhanced retrieval does not use low-quality chunks.

3. `classification-sample.md`
   - Contains obvious category and tags, such as product requirements, roadmap, and release planning.

4. `failure-sample.bin`
   - Unsupported or intentionally invalid content.
   - Intended to verify failure handling and retry behavior.

## Setup

1. Start backend and frontend.
2. Log in as an admin user.
3. Create or select one Space.
4. Ensure RAG configuration is enabled.
5. Open the KnowledgeOps page.

## Test Cases

### 1. Upload And Auto Profile

Steps:

1. Upload `rag-ops-guide.md` into the Space.
2. Wait for RAG indexing to complete.
3. Open async task list.
4. Select the Space.
5. Verify a Knowledge Pipeline task appears.
6. Wait until task is `SUCCESS` or `PARTIAL_SUCCESS`.

Expected:

- RAG document is indexed.
- Knowledge profile is generated.
- Dashboard `profiledCount` increases.
- Document item has summary, category, tags, quality score, and profile status.

### 2. Low Quality Chunk Filter

Steps:

1. Upload `metadata-only-short.txt`.
2. Wait for indexing and profile task.
3. Ask a question that matches generated tags or category.
4. Inspect retrieval logs or behavior.

Expected:

- The short chunk should not be selected through `profile_summary` or `generated_question`.
- Existing base retrieval may still find it if the original retrieval routes match.

### 3. Manual Classification

Steps:

1. Open `classification-sample.md` in KnowledgeOps.
2. Click manual classification.
3. Set category to `product`.
4. Set tags to `roadmap,requirements`.
5. Refresh facets.

Expected:

- Category facet contains `product`.
- Tag facets contain `roadmap` and `requirements`.
- Filtering by category or tag shows the document.

### 4. Regenerate Profile

Steps:

1. Select a successfully profiled document.
2. Click regenerate profile.
3. Watch async task list.

Expected:

- A new Knowledge Pipeline task is created.
- Profile is updated after task completion.

### 5. Retry Failed Tasks

Steps:

1. Upload `failure-sample.bin` or force a profile failure.
2. Open async task list.
3. Verify terminal node and terminal reason are shown.
4. Click retry.
5. Click batch retry.

Expected:

- Single retry creates a new task.
- Batch retry creates retry tasks for failed or partial tasks.
- Terminal reason remains visible on failed tasks.

### 6. Dashboard

Steps:

1. Open KnowledgeOps dashboard.
2. Compare counts with uploaded documents and profiles.

Expected:

- `documentCount`, `indexedCount`, `profiledCount`, `failedProfileCount`, and `needsReviewCount` match the space data.
- Average quality score is visible.
- Recent failed tasks are listed.

