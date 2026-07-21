---
id: TASK-20260722-002
type: implementation-task
status: pending-verification
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: Codex
version: 3
tags: [workflow, fastapi, docker]
---
# FastAPI 服务化与 Docker 镜像
- 目标：将 `new_project` 从 CLI 扩展为 FastAPI 内部服务，保留 CLI 兼容。
- 依赖：TASK-20260722-001。
- 产物：API controller/application service 分层、`/health`/`/ready`、Run API、数据库驱动的未完成 Run 调度器、异常映射、Python 3.11 slim Dockerfile、非 root 运行。
- 不包含：记忆业务 Tool 和多副本。
- 验收：容器启动、健康、优雅停止；只有 Run 与初始 Execution 提交 MySQL 后才返回 `202`；提交后立即杀进程并重启仍能恢复执行；请求大小/超时/并发上限生效；前端无直连端口。
- 风险/回滚：API 线程和引擎线程竞争；首版单 Uvicorn 进程，关闭 Compose service 即回滚。
- 回写：镜像大小、启动命令、运行用户、健康证据和已知限制。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]
- 测试记录：[[10-规划与实施/测试/TEST-20260722-003-TASK-002-FastAPI与Docker测试记录]]

## 2026-07-22 实施结果

- 已增加 FastAPI application/service 分层、Run API、`/health`、`/ready`、生命周期启动/停止、稳定错误信封、请求超时、并发上限和流式 Body 大小门禁；未配置持久化时 fail-closed，仅健康但不就绪。
- 已增加 Python 3.11 slim 单 Uvicorn 进程镜像，使用固定 UID/GID `10001` 非 root 用户、exec-form CMD 和容器 Healthcheck；本地镜像大小 `68,990,312` bytes。
- API/安全测试 `6 passed`，Python 全量回归 `274 passed`；镜像构建、非 root 用户、健康状态和优雅停止通过。
- 正确性与安全审查已完成：chunked 请求在 ASGI receive 层计数，校验错误不回显问题/Context，未知异常不回显内部信息，Run path 强制 UUID。
- TASK-003 尚未接入 MySQL durable store，因此“成功返回 202 前已提交 MySQL、进程重启恢复”仍待验证；本任务状态为 `pending-verification`，不得提前标记完成。
