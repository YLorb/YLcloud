# YLcloud 稳定说明文档

基线版本：`version0.11: 基本完成主体框架`  
基线提交：`5038fb66241a7979f1ad4aa044d5d4d15acdb612`  
整理日期：`2026-06-18`

## 1. 项目定位

YLcloud 是一个个人云盘与知识库系统，核心能力包括：

- 用户注册、登录、JWT 鉴权。
- 文件上传、目录管理、秒传、版本相关能力。
- Workspace / Space 空间管理。
- 文件导入空间后建立 RAG 索引。
- 基于 Qdrant 向量库、模型服务和外部 LLM 的知识库问答。
- React 前端页面与 Spring Boot 后端服务。

当前状态可以描述为：主体框架已经基本跑通，前后端、对象存储、向量库、模型服务、RAG + LLM 主链路已经完成验证。

## 2. 技术栈

### 后端

- Java：项目配置 `java.version=17`，本机启动脚本默认使用 Java 21 运行 jar。
- Spring Boot：`3.3.5`
- Web：`spring-boot-starter-web`
- 参数校验：`spring-boot-starter-validation`
- ORM / SQL 映射：MyBatis Spring Boot Starter `3.0.3`
- 数据库：MySQL 8.3，当前本机也兼容 MariaDB 场景。
- 数据迁移：Flyway MySQL `10.10.0`
- 鉴权：JWT，依赖 `jjwt 0.11.5` 与 `java-jwt 4.4.0`
- 密码加密：Spring Security Crypto
- 对象存储：MinIO Java SDK `8.5.13`
- 文档解析：Apache Tika `2.9.2`
- 向量库客户端：LangChain4j Qdrant `1.16.2-beta26`

### 前端

- React：`18.3.1`
- TypeScript：`5.6.3`
- Vite：`5.4.11`
- 图标：`lucide-react`
- 文件哈希：`hash-wasm`

### 基础服务

- MySQL：`mysql:8.3`
- MinIO：`RELEASE.2025-04-22T22-12-26Z`
- Qdrant：`qdrant/qdrant:v1.15.4`
- model-service：本仓库 `model-service` 目录构建
- 默认 embedding 模型：`BAAI/bge-small-zh-v1.5`
- 默认 rerank 模型：`BAAI/bge-reranker-v2-m3`
- 默认 LLM：`deepseek-chat`，通过 OpenAI-compatible API 调用

## 3. 服务端口

| 服务 | 默认端口 | 说明 |
| --- | ---: | --- |
| 后端 Spring Boot | `8080` | 主服务暂不使用 80 端口 |
| 前端 Vite | `5173` | 本地开发页面 |
| model-service | `8001` | embedding / rerank / chat 适配服务 |
| MinIO API | `9000` | 对象存储 API |
| MinIO Console | `9001` | 对象存储控制台 |
| Qdrant REST | `6333` | 向量库 REST API |
| Qdrant gRPC | `6334` | 向量库 gRPC API |
| MySQL | `3306` | 业务数据库 |

## 4. 一键启动方式

推荐使用仓库脚本同时启动依赖、后端和前端：

```bash
./scripts/start-dev.sh
```

脚本行为：

- 启动 Docker 依赖：MinIO、Qdrant、model-service。
- 等待 MinIO、Qdrant、model-service 健康检查通过。
- 构建 `cloud-server` jar。
- 以 `SERVER_PORT` 指定的端口启动后端，默认 `8080`。
- 启动前端 Vite 服务，默认 `5173`。
- 日志写入 `/tmp/ylcloud-dev/backend.log` 和 `/tmp/ylcloud-dev/frontend.log`。

可选环境变量：

```bash
export YLCLOUD_LLM_API_KEY="你的 API Key"
export YLCLOUD_LLM_BASE_URL="https://api.deepseek.com"
export YLCLOUD_LLM_MODEL="deepseek-chat"
export SERVER_PORT=8080
export YLCLOUD_FRONTEND_PORT=5173
./scripts/start-dev.sh
```

停止方式：

```bash
kill $(cat /tmp/ylcloud-dev/backend.pid)
kill $(cat /tmp/ylcloud-dev/frontend.pid)
```

Docker 依赖可单独启动：

```bash
docker compose up -d minio qdrant model-service
```

## 5. 已验证结果

当前已完成的关键验证：

- 前端构建：`npm run build` 通过。
- 后端构建：`mvn -pl cloud-server -am package -DskipTests` 通过。
- model-service 健康检查：`GET /health` 返回 200。
- model-service chat：可通过 OpenAI-compatible API 返回测试答案。
- 后端 RAG 查询：`POST /api/space/{spaceId}/rag/query` 可返回 LLM 生成答案。
- 自动化 RAG + LLM 新空间测试通过。
- 权限异常测试通过：无空间权限时返回 HTTP 403，body code 也是 403。
- Workspace 导入文件测试通过：使用用户可见的物理 `file_info.file_id` 导入空间成功。

## 6. 重要修复记录

### Mapper 命名重复

现象：

- MyBatis Mapper 中存在同名方法或语义接近的方法，容易造成 XML / 注解映射混淆。

修复方向：

- 将方法名按业务语义拆开，例如按 `fileUuid + parentId`、`hash + parentId`、`parent + uuid` 等维度命名。
- 同步检查 Service 层调用，保证调用方和 Mapper 方法名一致。

面试追问重点：

- MyBatis 方法名不是 SQL 的唯一依据，但 Mapper 接口、XML statement id、注解方法之间必须保持清晰的一一对应。
- 这类问题容易在编译期不暴露，到运行时才出现绑定异常或调用到错误 SQL。

### Workspace 导入文件 ID 不一致

现象：

- 用户输入了前端可见的正确文件 ID，但后端提示“只能导入当前用户可读取的文件”。

原因：

- 前端或上传接口暴露的是物理文件表 `file_info.file_id`。
- 原 `SpaceFileService.importUserFile` 更偏向按用户文件树 `user_file.ID` 查询。
- 当两个 ID 不一致时，用户实际有权限，但后端查不到对应 `user_file` 记录。

修复方向：

- 在 `FileInfoMapper` 增加通过物理 `file_info.file_id` 反查当前用户可读 `user_file` 的查询。
- `SpaceFileService.importUserFile` 先按物理 fileId 解析，失败后再兼容旧的 `user_file.ID`。
- 该修复是最小修改，保留旧调用方式。

### RAG 默认模型写死

现象：

- 新建空间 RAG 配置曾写入固定模型名 `langchain4j-ready`，与实际部署模型不一致。

修复方向：

- 新建空间默认 RAG 配置改为读取 `RagProperties`。
- 与 Docker Compose 中的 `YLCLOUD_RAG_EMBEDDING_MODEL`、`YLCLOUD_RAG_RERANK_MODEL` 保持一致。

### 权限异常 HTTP 状态码

现象：

- 权限类业务异常可能以普通业务错误返回，自动化测试难以区分认证、权限和业务失败。

修复方向：

- 全局异常处理对权限类 `BaseException` 返回 HTTP 403，并保持 body code 为 403。

### RAG 重建重复键

现象：

- 空间文件重建索引时，`space_rag_chunk_ref` 可能出现重复键冲突。

修复方向：

- 对 chunk ref 插入使用幂等 upsert 语义。
- 已存在引用时更新状态、空间文件 ID 和更新时间，而不是直接失败。

## 7. 常见错误与排查口径

### 后端启动失败：`BindException: 地址已在使用`

含义：

- 后端要绑定的端口已经被其他进程占用，常见是 `8080` 或误用 `80`。

排查：

```bash
ss -ltnp | grep ':8080'
```

处理：

- 停掉已有进程，或临时换端口：

```bash
SERVER_PORT=8081 ./scripts/start-dev.sh
```

### model-service 连接被重置

可能原因：

- 容器刚启动，模型尚未加载完成。
- Hugging Face 下载受限或模型缓存不完整。
- 容器进程存活但应用未真正 ready。

排查：

```bash
docker compose logs -f model-service
curl http://127.0.0.1:8001/health
```

如果看到 unauthenticated HF Hub warning，通常只是提示未配置 `HF_TOKEN`，不一定是失败。若长期不下载或健康检查失败，需要检查网络、缓存卷和模型名。

### LLM 返回“无法从当前知识库回答”

可能原因：

- 检索阶段没有召回相关 chunk。
- 文件尚未完成索引。
- 提问内容与知识库内容无关。
- embedding 维度、Qdrant collection 配置或模型配置不一致。
- LLM 正常，但系统根据检索结果判断无法回答。

排查：

- 查看 RAG task 是否 `SUCCESS`。
- 确认 `file_rag_chunk` 和 `space_rag_chunk_ref` 有记录。
- 确认 Qdrant collection 名、向量维度和当前 embedding 模型一致。
- 使用一个文件中明确出现的句子做测试问题。

### Docker Compose 提示 `no configuration file provided`

含义：

- 当前目录不是项目根目录，找不到 `docker-compose.yml`。

处理：

```bash
cd /home/yl-orb/project/YLcloud
docker compose ps
```

### MySQL / MariaDB 兼容

当前项目 SQL 以 MySQL 为目标，MariaDB 在常规 DDL / DML 上通常可运行，但需要关注：

- JSON、索引、时间字段默认值差异。
- `on duplicate key update` 行为。
- Flyway MySQL 驱动兼容性。
- 字符集、排序规则与大小写敏感配置。

面试回答建议：

- 开发和部署基线应以 MySQL 8.3 为准。
- MariaDB 可作为本地兼容环境，但正式环境不建议混用，避免迁移脚本和 SQL 方言差异。

## 8. 核心数据流

### 文件上传

1. 前端计算或上传文件信息。
2. 后端写入 `file_info` 物理文件元信息。
3. 后端写入 `user_file` 用户文件树记录。
4. 文件内容进入 MinIO。

### 导入 Space

1. 用户选择文件导入 Space。
2. 后端校验用户是否能读取该文件。
3. 后端写入 `space_file`。
4. 创建或更新 `space_rag_document`。
5. 生成 RAG indexing task。

### RAG 问答

1. 用户向 Space 提问。
2. 后端调用 model-service 生成 query embedding。
3. Qdrant 检索候选 chunk。
4. rerank 模型重排候选结果。
5. 后端构造上下文并调用 LLM。
6. 返回 answer、hitChunkIds、citations。

## 9. 面试容易被追问的问题

### 为什么需要 `file_info` 和 `user_file` 两张表？

`file_info` 表示物理文件和内容哈希，适合做秒传、去重、存储定位。`user_file` 表示用户视角下的文件树，包含目录、父子关系、用户权限和展示路径。同一个物理文件可以被多个用户或多个目录引用。

### 为什么导入 Workspace 时会出现 fileId 混淆？

因为物理文件 ID 和用户文件树 ID 不是同一个概念。前端更容易展示物理文件 ID，但权限校验需要落到用户可读的 `user_file` 记录。修复后后端同时兼容两种 ID。

### 为什么 RAG 需要 Qdrant？

普通数据库不适合做高维向量近邻搜索。Qdrant 负责按 embedding 相似度召回候选文本块，并通过 payload 过滤空间、文件、状态等条件。

### 为什么还需要 rerank？

embedding 召回负责找“可能相关”的 chunk，rerank 负责对候选结果做更精细排序。这样可以提高最终传给 LLM 的上下文质量。

### 为什么 LLM 不能直接读文件？

LLM API 只接收本次请求中的上下文。项目通过解析文件、切分 chunk、向量化、检索、重排，把最相关内容放入 prompt，避免把整库内容直接塞给 LLM。

### 为什么权限错误要返回 403？

认证失败是 401，已登录但无权访问是 403。明确状态码有利于前端处理、自动化测试和安全审计。

### 为什么启动脚本不使用 80 端口？

80 端口通常需要 root 权限，并且容易与系统服务冲突。本地开发使用 8080 更安全，部署时可通过 Nginx 或网关映射到 80/443。

### Docker 部署模型服务还是直接部署更方便？

Docker 更适合本项目，因为依赖固定、端口清晰、缓存卷可复用，并且能和 MinIO、Qdrant 一起编排。直接部署适合需要精细控制 GPU、驱动、模型缓存路径的服务器。

## 10. 当前风险与后续建议

- RAG 的效果依赖模型下载、embedding 维度、Qdrant collection 配置三者一致。
- model-service 的外部 LLM 调用依赖 `YLCLOUD_LLM_API_KEY`，测试时不要在日志或文档中写明真实 key。
- MariaDB 可以本地测试，但建议正式部署统一 MySQL 8.3。
- 当前自动化验证以主链路 smoke test 为主，后续应补充 Service 层和 Mapper 层集成测试。
- 前端仍需要更完整的端到端测试覆盖，例如登录、上传、导入 Space、提问、删除文件。
- Qdrant client 与 server 小版本存在兼容警告时通常不阻断启动，但正式部署建议保持版本一致。

## 11. 快速验收清单

```bash
docker compose ps
curl http://127.0.0.1:8001/health
curl http://127.0.0.1:6333/collections
curl http://127.0.0.1:9000/minio/health/live
```

```bash
cd cloud-frontend
npm run build
```

```bash
cd /home/yl-orb/project/YLcloud
mvn -pl cloud-server -am package -DskipTests
```

最终人工验收建议：

- 注册 / 登录。
- 上传一个包含明确测试句子的 `.txt` 文件。
- 导入 Space。
- 等待 RAG task 成功。
- 用文件中的明确句子提问。
- 验证回答内容和 citations 是否指向正确文件。
