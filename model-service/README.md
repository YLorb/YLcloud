# BGE Model Service

This service provides HTTP embedding and rerank APIs for the Spring Boot RAG flow.

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
CHAT_API_KEY=your-deepseek-key
CHAT_MODEL_NAME=deepseek-chat
CHAT_API_STYLE=chat_completions
GENERATE_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
GENERATE_API_KEY=your-ark-key
GENERATE_MODEL_NAME=doubao-seed-2-0-pro-260215
GENERATE_API_STYLE=responses
```

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
ylcloud_rag_bge_m3_v1
```

Run Qdrant locally:

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

The application uses Qdrant REST on `6333` for collection initialization and gRPC on `6334` for vector operations.
