---
id: TASK-20260722-002
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 1
tags: [workflow, fastapi, docker]
---
# FastAPI 服务化与 Docker 镜像
- 目标：将 `new_project` 从 CLI 扩展为 FastAPI 内部服务，保留 CLI 兼容。
- 依赖：TASK-20260722-001。
- 产物：API controller/application service 分层、`/health`/`/ready`、Run API、异常映射、Python 3.11 slim Dockerfile、非 root 运行。
- 不包含：记忆业务 Tool 和多副本。
- 验收：容器启动、健康、优雅停止；请求大小/超时/并发上限生效；前端无直连端口。
- 风险/回滚：API 线程和引擎线程竞争；首版单 Uvicorn 进程，关闭 Compose service 即回滚。
- 回写：镜像大小、启动命令、运行用户、健康证据和已知限制。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

