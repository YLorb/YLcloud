# YLcloud

YLcloud 是一个包含文件存储、空间协作和 RAG 知识库的全栈项目。Docker Compose 会构建前后端并启动 MySQL、MinIO、Qdrant、模型服务和文档解析服务。

## 快速启动

1. 创建本地环境文件。

   ```powershell
   Copy-Item .env.example .env
   .\scripts\migrate-secrets.ps1
   ```

2. 至少替换 `.env` 中的 MySQL、MinIO 开发默认值。JWT 会在首次迁移时安全生成；平台 API Key 写入 `.secrets/` 对应文件，不再保存在 `.env`。

3. 构建并启动。

   ```powershell
   docker compose up -d --build
   ```

4. 查看状态。

   ```powershell
   docker compose ps
   ```

前端地址为 `http://127.0.0.1:5173`，后端地址为 `http://127.0.0.1:8080`，MinIO 控制台为 `http://127.0.0.1:9001`。

所有宿主机端口都可以通过 `.env` 中的 `YLCLOUD_*_HOST_PORT` 覆盖。MySQL、MinIO、Qdrant、模型服务、解析服务和后端默认只绑定 `127.0.0.1`；仅前端默认绑定 `0.0.0.0`。

## 服务器部署

服务器部署使用独立环境文件，部署检查会拒绝模板密码、无效或重复端口：

```powershell
Copy-Item .env.server.example .env.server
# 编辑 .env.server，替换全部 replace-* 值
# 从 *.example 创建 Secret 文件，或迁移已有 .env
.\scripts\migrate-secrets.ps1 -EnvFile .env.server
.\scripts\deploy-check.ps1
docker compose --env-file .env.server up -d --build
docker compose --env-file .env.server ps
```

若服务器已有服务占用默认端口，只需修改对应 `YLCLOUD_*_HOST_PORT`，无需修改 Compose 文件。

JWT、DeepSeek、Ark、RAG Query 和 VLM Key 通过 Compose Secret 以只读文件挂载。Java、model-service 和 parser-service 均优先读取 `*_FILE`，并保留普通环境变量回退以兼容非 Compose 部署。真实 Secret 文件不会被 Git 跟踪；`.secrets/README.md` 与 `*.example` 会正常提交。

账号密码只能通过 HTTPS 传输，后端使用 BCrypt 单向哈希，不能改为可逆加密。未来用户自带的 Provider API Key 如需持久化，应使用独立主密钥加密，并与平台级 `.secrets/` 隔离。

## 自动初始化

应用每次启动都会幂等地检查以下资源：

- Flyway 执行 `db/migration` 中的全部数据库迁移和默认站点配置。
- MinIO 创建 `YLCLOUD_MINIO_BUCKET` 指定的 bucket，并启用对象版本控制。
- Qdrant 创建指定 collection，校验向量维度和距离类型，并创建 payload indexes。
- RAG 模型、解析和 Qdrant 地址默认从部署环境变量读取，避免容器错误连接自身的 `127.0.0.1`。
- RAG 上次进程遗留的超时任务会被标记为失败，不会盲目重放。

所有初始化都不会清空已有数据。资源配置与现有数据不一致时，应用会失败并输出明确错误，避免在错误状态下继续运行。

## 初始管理员

项目不内置固定管理员密码。如需在空数据库首次启动时自动创建管理员，请在 `.env` 中设置：

```dotenv
YLCLOUD_BOOTSTRAP_ADMIN_ENABLED=true
YLCLOUD_BOOTSTRAP_ADMIN_USERNAME=admin
YLCLOUD_BOOTSTRAP_ADMIN_PASSWORD=<at-least-12-characters>
YLCLOUD_BOOTSTRAP_ADMIN_NICKNAME=YLcloud Administrator
```

初始化只在 `users` 表为空时执行，并会同时创建用户根目录、默认个人空间和 RAG 配置。重启不会覆盖密码或重复创建用户。

## 模型运行模式

`YLCLOUD_MODEL_SERVICE_OFFLINE_FALLBACK=true` 是开发连通模式，可在未下载真实模型时启动全部服务。真实 RAG 验收时应设为 `false`，并额外验证 embedding 和 rerank 模型已下载、加载且输出维度正确。
