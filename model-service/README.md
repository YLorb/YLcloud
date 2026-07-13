# BGE Model Service

This service provides HTTP embedding and rerank APIs for the Spring Boot RAG flow.

## Default Acceptance Configuration

The current project acceptance profile keeps the lightweight Chinese BGE pair:

```text
EMBEDDING_MODEL_NAME=BAAI/bge-small-zh-v1.5
RERANK_MODEL_NAME=BAAI/bge-reranker-v2-m3
FALLBACK_DIMENSION=512
```

Spring Boot should use the matching Qdrant collection:

```text
YLCLOUD_RAG_EMBEDDING_DIMENSION=512
YLCLOUD_RAG_QDRANT_COLLECTION_NAME=ylcloud_rag_bge_small_zh_v15
```

`OFFLINE_FALLBACK=true` is useful only for connectivity checks. Set `YLCLOUD_MODEL_SERVICE_OFFLINE_FALLBACK=false` when validating retrieval quality.

## Retrieval Defaults

Spring Boot uses the model service for vector retrieval and rerank, then merges it with local keyword routes:

```text
YLCLOUD_RAG_VECTOR_TOP_K=20
YLCLOUD_RAG_BM25_TOP_K=20
YLCLOUD_RAG_MULTI_QUERY_TOP_K=20
YLCLOUD_RAG_HYDE_TOP_K=20
YLCLOUD_RAG_STEP_BACK_TOP_K=20
YLCLOUD_RAG_RRF_K=60
YLCLOUD_RAG_RERANK_CANDIDATE_TOP_K=5
YLCLOUD_RAG_RERANK_TOP_K=5
YLCLOUD_RAG_TOKENIZER_PROVIDER=ik
```

BM25 keyword retrieval runs inside `cloud-server` with IK Analyzer plus exact token preservation for models, numbers, standards, versions, and error codes. `cloud-server` fuses route rankings with RRF before sending the configured candidate top K to this service for Cross-Encoder rerank. The model service is not required for BM25, but it is required for vector, HyDE-vector, and rerank quality validation.

## Start

```bash
pip install -r requirements.txt
uvicorn bge_service:app --host 0.0.0.0 --port 8001
```

## APIs

`POST /embed`

```json
{
  "texts": ["document chunk"],
  "normalize": true
}
```

`POST /rerank`

```json
{
  "query": "question",
  "documents": ["candidate chunk"],
  "topK": 5
}
```

`GET /health`

Returns model names, cache directory, fp16 mode, and whether chat generation is enabled.

`POST /chat`

The chat endpoint and generation endpoint can use separate upstream models. Configure chat for DeepSeek and generation for Ark Responses API:

```bash
CHAT_BASE_URL=https://api.deepseek.com
CHAT_API_KEY_FILE=/run/secrets/llm_api_key
CHAT_MODEL_NAME=deepseek-chat
CHAT_API_STYLE=chat_completions
GENERATE_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
GENERATE_API_KEY_FILE=/run/secrets/rag_query_api_key
GENERATE_MODEL_NAME=doubao-seed-2-0-pro-260215
GENERATE_API_STYLE=responses
```

Each `*_FILE` variable takes precedence over its plain environment variable. The referenced file contains only the secret value.

```json
{
  "question": "question",
  "contexts": ["[1] document chunk"],
  "systemPrompt": "answer only from context",
  "maxTokens": 1024,
  "temperature": 0.2
}
```

## Docker

```bash
docker compose up -d qdrant model-service
```

## Qdrant

Spring Boot now checks and creates the collection automatically at startup.
The default Spring Boot collection name is:

```text
ylcloud_rag_bge_small_zh_v15
```

The default vector size is `512` for `BAAI/bge-small-zh-v1.5`.

For a stronger `BAAI/bge-m3` profile, use `docs/rag-bge-m3.env.example` and rebuild the Qdrant collection/reindex documents.

Run Qdrant locally:

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

The application uses Qdrant REST on `6333` for collection initialization and gRPC on `6334` for vector operations.
