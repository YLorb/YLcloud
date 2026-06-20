# YlCloud RAG Deployment

## Services

The local deployment uses:

- MySQL 8.3 on `3306`
- MinIO on `9000`, console on `9001`
- Qdrant REST on `6333`, gRPC on `6334`
- BGE model service on `8001`
- Spring Boot on `8080`

## Start Infrastructure

```bash
docker compose up -d mysql minio qdrant model-service
```

Build the Spring Boot jar:

```bash
mvn test
mvn -pl cloud-server -am package -DskipTests
```

Start the app through compose:

```bash
docker compose --profile app up -d ylcloud-app
```

## Chat Model

The model service exposes embedding and rerank locally. Chat generation uses an OpenAI-compatible gateway.

Set these variables before starting `model-service`:

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

If chat variables are missing, retrieval still works and the application returns a safe fallback answer with citations.

## Database Migration

Flyway runs migrations from `classpath:db/migration`.

- `V1__space_and_rag.sql` creates the space, versioning, and RAG tables.
- `V2__rag_task_progress.sql` adds task progress fields.

Existing databases are supported with `baseline-on-migrate=true`.

## Qdrant

Spring Boot initializes Qdrant at startup:

- collection: `ylcloud_rag_bge_m3_v1`
- vector size: `1024`
- distance: `Cosine`
- payload indexes: `spaceId`, `spaceFileId`, `documentId`, `status`

If an existing collection has a different vector size or distance, startup fails fast with a clear error.
