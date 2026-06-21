# RAG Agent Test Guide

This guide is for another Agent/LLM that needs to verify the RAG features on Linux. The target test environment is Ubuntu 24.04 with Maven, JDK 21, and Docker installed.

Run all commands from the repository root:

```bash
cd /path/to/ylcloud
```

The project is compiled with Java release 17 settings. JDK 21 is acceptable and does not need to be downgraded.

## Scope

The acceptance scope covers:

- Document parsing and chunking: structured parsing, heading-aware chunks, parent-child chunks, table preservation, layout/OCR/VLM routes, and fixed-window fallback.
- Embedding profile: default `BAAI/bge-small-zh-v1.5`, 512 dimensions, and optional `BAAI/bge-m3` profile.
- Query Rewrite: Rewrite LLM, HyDE, Step-back, Multi Query expansion, and recent chat history.
- Multi-route retrieval: vector retrieval, BM25 with IK tokenization, SQL keyword fallback, metadata/title/structure routes, Multi Query, HyDE, and Step-back.
- Ranking: RRF coarse ranking, `TOP-5` rerank candidates, and Cross-Encoder rerank.

## Environment Check

Command:

```bash
java -version
mvn -version
docker --version
docker compose version
```

Expected result:

- `java -version` reports JDK 21 or another compatible JDK.
- `mvn -version` succeeds.
- `docker --version` succeeds.
- `docker compose version` succeeds.

## Fast Local Verification

### 1. Java Unit Tests

Command:

```bash
mvn test
```

Expected result:

- Build ends with `BUILD SUCCESS`.
- Test summary shows `Failures: 0, Errors: 0`.
- Current expected count is at least `24` tests.
- Query Rewrite fallback tests intentionally log warnings such as `generation unavailable`; these warnings are expected and must not fail the build.

Covered behavior:

- `StructuredChunkerTest`
  - Heading context is preserved in child chunk content and metadata.
  - Parent-child chunks are generated.
  - Tables are preserved and large Markdown tables repeat headers when split.
  - Missing blocks fall back to fixed-window chunking.
  - Fixed-window fallback uses 800-character windows with 100-character overlap.
- `QueryRewriteServiceTest`
  - Rewrite, Multi Query, HyDE, and Step-back prompts are invoked.
  - Recent chat history is included in prompts.
  - LLM failures produce warnings and local fallback retrieval queries.
- `Bm25KeywordRetrieverTest`
  - IK tokenizer is loaded.
  - Exact tokens such as model names, numbers, versions, standards, and error codes are preserved.
- `RagMultiRouteRetrieverTest`
  - Vector, BM25, Multi Query, HyDE, Step-back, keyword, and metadata routes are used.
  - Default route limits are `TOP-20`.
- `RagCandidateMergerTest`
  - RRF uses route rank, not raw route score.
  - Multiple route hits improve RRF ranking.
  - `YLCLOUD_RAG_RRF_K` changes score magnitude and keeps rank-order semantics.
- `RagRerankServiceTest`
  - Rerank output obeys configured top-k.
  - Disabled rerank still returns a configured top-k prefix.
  - Empty rerank responses fall back to original RRF order.

### 2. Python Service Syntax Check

Command:

```bash
python3 -m py_compile model-service/bge_service.py document-parser-service/parser_service.py
rm -rf model-service/__pycache__ document-parser-service/__pycache__
```

Expected result:

- `py_compile` exits with code `0`.
- No syntax errors are printed.
- Temporary `__pycache__` folders are removed.

## Configuration Verification

### 1. Default Acceptance Model Profile

Command:

```bash
git grep -n 'BAAI/bge-small-zh-v1.5\|BAAI/bge-reranker-v2-m3\|YLCLOUD_RAG_EMBEDDING_DIMENSION'
```

Expected result:

- `application.yml` uses `YLCLOUD_RAG_EMBEDDING_MODEL:BAAI/bge-small-zh-v1.5`.
- `application.yml` uses `YLCLOUD_RAG_EMBEDDING_DIMENSION:512`.
- `application.yml`, `application-dev.yml`, `docker-compose.yml`, `model-service/README.md`, and `docs/rag-deployment.md` all use `BAAI/bge-reranker-v2-m3`.
- `docs/rag-bge-m3.env.example` exists for optional `BAAI/bge-m3`.

### 2. No Obsolete Rerank or Fusion Configuration

Command:

```bash
git grep -n 'bge-reranker-base\|multi-route-bonus\|YLCLOUD_RAG_MULTI_ROUTE_BONUS\|vlm-enabled: false'
```

Expected result:

- No matches.
- `git grep` exits with code `1` because no obsolete strings are present.

### 3. RAG Runtime Flags

Command:

```bash
git grep -n 'YLCLOUD_RAG_RRF_K\|YLCLOUD_RAG_RERANK_CANDIDATE_TOP_K\|YLCLOUD_RAG_RERANK_TOP_K\|YLCLOUD_RAG_TOKENIZER_PROVIDER'
```

Expected result:

- `YLCLOUD_RAG_RRF_K=60`.
- `YLCLOUD_RAG_RERANK_CANDIDATE_TOP_K=5`.
- `YLCLOUD_RAG_RERANK_TOP_K=5`.
- `YLCLOUD_RAG_TOKENIZER_PROVIDER=ik`.

## Docker-Level Smoke Tests

Use these tests when Docker and external model/API credentials are available.

### 1. Start Required Services

Command:

```bash
docker compose up -d qdrant model-service document-parser-service
docker compose ps
```

Expected result:

- `qdrant`, `ylcloud-model-service`, and `ylcloud-document-parser-service` are running.
- Health checks eventually report healthy.

### 2. Model Service Health

Command:

```bash
curl -s http://127.0.0.1:8001/health
```

Expected result:

- JSON contains `"status":"ok"`.
- `embeddingModel` is `BAAI/bge-small-zh-v1.5` when the compose default profile is used.
- `rerankModel` is `BAAI/bge-reranker-v2-m3`.
- `generateEnabled` is `true` only when query rewrite API credentials are configured.

### 3. Document Parser Health

Command:

```bash
curl -s http://127.0.0.1:8002/health
```

Expected result:

- JSON contains `"service":"document-parser-service"`.
- `ocrEnabled` is `true`.
- `vlmEnabled` is `true` only when VLM-compatible base URL and API key are configured.
- `pymupdfAvailable` should be `true` for layout PDF parsing.
- `tesseractAvailable` should be `true` for OCR quality validation.

## HTTP API Smoke Tests

### 1. Rerank Cross-Encoder Endpoint

Command:

```bash
curl -s -X POST http://127.0.0.1:8001/rerank \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "How does RAG multi-route retrieval rank results?",
    "documents": [
      "RRF fuses retrieval results by route-local rank.",
      "The upload API accepts file metadata parameters.",
      "Cross-Encoder rerank compares the query and chunk as a pair."
    ],
    "topK": 2
  }'
```

Expected result:

- JSON contains `model`.
- `results` has exactly `2` items.
- Each result has `index` and `score`.
- If `OFFLINE_FALLBACK=true`, model starts with `offline-fallback:`. This is acceptable only for connectivity tests, not quality validation.

### 2. Embedding Endpoint

Command:

```bash
curl -s -X POST http://127.0.0.1:8001/embed \
  -H 'Content-Type: application/json' \
  -d '{
    "texts": ["retrieval augmented generation"],
    "normalize": true
  }'
```

Expected result:

- JSON contains `dimension`.
- Acceptance profile should return dimension `512`.
- `vectors[0]` should have 512 numeric values.

### 3. Parser Layout Endpoint

This endpoint requires either a reachable `objectUrl` or parser-service inline test content. The inline base64 variable is read by the parser-service process, so set it before starting the service. Setting it only in the curl shell will not affect an already running Docker container.

Local command with inline base64:

```bash
export DOCUMENT_PARSER_INLINE_BASE64="$(printf '# Title\n\n| Name | Value |\n|---|---|\n| A | 1 |' | base64 -w 0)"
cd document-parser-service
python3 -m uvicorn parser_service:app --host 127.0.0.1 --port 8002
```

In another shell:

```bash
cd /path/to/ylcloud
curl -s -X POST http://127.0.0.1:8002/parse/layout \
  -H 'Content-Type: application/json' \
  -d '{
    "fileUuid": "agent-test",
    "fileHash": "hash-1",
    "fileName": "test.md",
    "fileType": "text/markdown",
    "parserVersion": "structured-v1"
  }'
```

For Docker-based testing, upload a small fixture to MinIO or another reachable HTTP location and pass its presigned URL as `objectUrl`.

Expected result:

- JSON contains `"success":true`.
- `blocks` is not empty.
- `fullText` contains `Title` and table content.

### 4. Parser OCR Endpoint

Use the same inline payload setup as layout, but call `/parse/ocr`.

Command:

```bash
curl -s -X POST http://127.0.0.1:8002/parse/ocr \
  -H 'Content-Type: application/json' \
  -d '{
    "fileUuid": "agent-test",
    "fileHash": "hash-1",
    "fileName": "test.md",
    "fileType": "text/markdown",
    "parserVersion": "structured-v1"
  }'
```

Expected result:

- JSON contains `"success":true` for text-like content.
- For real scanned PDFs/images, `blocks[*].source` should include OCR-derived content if OCR dependencies are installed.

### 5. VLM Page Endpoint

Command:

```bash
curl -s -X POST http://127.0.0.1:8002/parse/page \
  -H 'Content-Type: application/json' \
  -d '{
    "fileUuid": "agent-test",
    "fileHash": "hash-1",
    "fileName": "page.png",
    "fileType": "image/png",
    "parserVersion": "structured-v1"
  }'
```

Expected result:

- If VLM credentials are configured and content is supplied, response should contain `"success":true` and parser `vlm-page`.
- If VLM credentials are missing, response should contain `"success":false` and an error message explaining that VLM parser is disabled. This is acceptable for credential checks but not for final VLM quality validation.

## End-to-End Acceptance Scenarios

These scenarios require the backend app, Qdrant, MinIO, model-service, and document-parser-service.

### Scenario A: Regular Structured Document

Input document:

- A DOCX or Markdown file with at least two heading levels, paragraphs, and one table.

Method:

1. Upload the file to a RAG-enabled space.
2. Trigger or wait for indexing.
3. Query a paragraph under a nested heading.
4. Query a table value.

Expected result:

- Parse result uses structured parser, not metadata fallback.
- Chunk metadata includes heading path.
- Parent and child chunks are inserted.
- Table content is preserved in Markdown-like form.
- Query results cite chunks from the correct heading/table area.

### Scenario B: Scanned or Dirty PDF

Input document:

- A scanned PDF or image-only PDF with visible title and paragraph text.

Method:

1. Upload the file to a RAG-enabled space.
2. Trigger indexing.
3. Check parser logs or parse result.
4. Ask a question whose answer appears only in the scanned text.

Expected result:

- Layout parser attempts first.
- OCR parser is used when extracted text quality is low.
- VLM parser is used only when OCR output remains low quality and VLM credentials are configured.
- Retrieved chunks contain OCR/VLM-derived text.

### Scenario C: Query Rewrite and Multi-Route Retrieval

Input:

- A conversation history where the user asks a follow-up question with pronouns or abbreviations.

Method:

1. Send a query with recent chat history.
2. Inspect logs for rewrite planning.
3. Confirm vector/BM25/multi-query/HyDE/step-back routes are used.

Expected result:

- Logs include query rewrite start and finish.
- Rewritten question is generated when LLM is available.
- Multi Query produces 3 to 5 variants.
- HyDE document and Step-back query are included in retrieval routes.
- BM25 and vector retrieval are both invoked with `TOP-20` defaults.

### Scenario D: RRF and Rerank

Method:

1. Create documents where one chunk ranks highly in vector search and another ranks highly in BM25.
2. Ask a query involving both semantic intent and exact product/model identifiers.
3. Inspect retrieval logs and final results.

Expected result:

- Route-local exact/token matches influence BM25 ranking.
- RRF fuses by route rank, not raw route score.
- Only `YLCLOUD_RAG_RERANK_CANDIDATE_TOP_K` candidates enter rerank.
- Rerank returns at most `YLCLOUD_RAG_RERANK_TOP_K` chunks.
- Final answer context contains chunks with better query/chunk relevance after Cross-Encoder rerank.

## Failure Interpretation

- `mvn test` fails: treat as code regression. Fix before E2E testing.
- Python `py_compile` fails: parser/model service cannot be trusted to start.
- `8001/health` unavailable: model-service container or port mapping failed.
- `8002/health` unavailable: document-parser-service container or port mapping failed.
- `OFFLINE_FALLBACK=true`: connectivity tests may pass, but embedding/rerank quality is not validated.
- `vlmEnabled=false`: VLM credentials are missing; VLM fallback is not validated.
- `tesseractAvailable=false`: OCR quality validation is incomplete.
