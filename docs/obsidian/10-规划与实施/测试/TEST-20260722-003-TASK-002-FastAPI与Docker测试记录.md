---
id: TEST-20260722-003
type: test-record
status: pending-verification
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: Codex
version: 1
tags: [workflow, fastapi, docker, security]
---
# TASK-002 FastAPI 与 Docker 测试记录

## 测试目标

验证 Workflow HTTP 服务壳、健康/就绪、Run 路由、生命周期、大小/超时/并发门禁、脱敏异常和 Python 3.11 非 root 容器；持久化 202 与重启恢复留待 TASK-003。

## 前置条件与数据

- Workflow 项目 `.venv` 已安装 `fastapi 0.139.2`、`uvicorn 0.51.0` 及开发依赖。
- Docker Desktop Linux engine 可用。
- 使用 TASK-001 的合法 RunCreateRequest；FakeRunService 仅用于 HTTP controller/application service 契约测试，不作为 durable store 验收证据。

## 执行步骤与实际结果

| 内容 | 命令/方式 | 预期 | 实际结果 |
|---|---|---|---|
| API 专项测试 | `pytest tests/test_service_api.py` | 健康、就绪、202 路由、限制、脱敏错误通过 | `6 passed` |
| Python 全量回归 | 独立 SQLite 与 basetemp 执行全部 tests | 无回归 | `274 passed`；1 个 FastAPI TestClient 上游弃用警告 |
| 依赖一致性 | `python -m pip check` | 无冲突 | 通过 |
| 镜像构建 | `docker build -t ylcloud-workflow:task002 .` | Python 3.11 slim 构建成功 | 通过，`68,990,312` bytes |
| 运行用户 | `docker exec ... id` | 非 root | `uid=10001(workflow) gid=10001(workflow)` |
| 健康检查 | `/health` 与 Docker Health | 返回 UP/healthy | 通过 |
| 优雅停止 | `docker stop -t 10` | Uvicorn lifespan 正常退出，临时容器清理 | 通过 |

## 正确性审查

- 未配置 durable store 时 `/ready` 返回 503，Run API 返回稳定 `SERVICE_NOT_READY`，不会伪造可靠 202。
- Lifespan 对称调用 service start/stop；retry 返回 202，cancel/get/result 路径固定在 `/internal/v1`。
- 请求总超时覆盖等待并发槽与应用执行；health/ready 不被业务并发耗尽阻断。
- UUID、Header、契约版本和 Pydantic 字段均在进入 application service 前校验。

## 安全审查

- Content-Length 先拒绝，chunked/无长度请求在 ASGI receive 流累计到上限即中止，不完整缓存超大 Body。
- 422 只返回 location/type/message，不回显 input、问题、短期 Context 或 Pydantic ctx 对象。
- 500 返回固定错误，不泄露堆栈、数据库或 Secret；OpenAPI/Docs 默认关闭。
- 镜像以非 root 用户运行，使用 exec-form CMD；`.dockerignore` 排除 Git、虚拟环境、SQLite、测试与本地文档。

## 待验证项与结论

- 未执行：真实 MySQL 提交后 202、进程终止后恢复、数据库 Poller。原因：属于 TASK-003 产物。
- 未执行：Compose 内部端口和只读根文件系统。原因：属于 TASK-011 产物。
- 其余 TASK-002 实现和测试通过；当前状态必须保持 `pending-verification`，TASK-003 与 TASK-011 证据补齐后才能更新为 `completed`。
