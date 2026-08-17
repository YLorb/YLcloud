---
id: TEST-20260817-103
type: test-requirement
status: pending-verification
priority: P0
created: 2026-08-17
updated: 2026-08-17
owner: unassigned
version: 1
tags: [sandbox, import, personal, rag, testing, security]
---

# Sandbox 预检与 Personal 目录导入测试要求

## 必选范围

1. 文件与多层目录快照、执行中来源变化、100 层目标计算、配额和重复预检。
2. 所有上传来源确实调用 Sandbox；服务不可用时不降级。
3. ATOMIC 单项失败整批零节点；SKIP_FAILED 继续且无无效空目录。
4. 恶意文件、压缩炸弹、路径穿越、主动内容、超时、超大输出、进程树和网络/凭据逃逸。
5. 重试、重复投递、Sandbox 销毁、应用重启和迟到结果。
6. 成功文件默认 RAG、目录不 RAG、失败文件零文档/零向量。

## 通过标准与记录

- 无未预检文件进入 ACTIVE/RAG，无 Sandbox 宿主权限或秘密泄露。
- 实现 Agent 创建独立执行记录并回链 TASK-20260817-003，包含真实容器和全链路证据。

## 执行记录（2026-08-17）

- 主 Compose 与 Sandbox runner Compose 静态配置均通过。
- Docker Desktop 4.82.0 / Engine 29.6.1 已由 `DESKTOP-VMOCLJT\\Win10` 启动；`file.preflight`、应用和 Sandbox service 镜像均成功构建并使用不可变 image ID。
- Java 回归 316 项全部通过；Sandbox 容器内 pytest 11 项全部通过。
- Win10 宿主真实 Docker 验证通过：Sandbox API 调用 `SUCCEEDED`，幂等重放复用原 span，subject 历史隔离正确；一次性工具容器验证了 `network=none`、只读根、UID 65532、`cap-drop ALL`、`no-new-privileges` 与 PID/CPU/内存限制。
- 全新数据库 `ylcloud_space_test` 从 V1 成功迁移至 V52，应用健康为 `UP`；导入相对路径唯一键已改为 SHA-256 路径哈希，避免 MySQL 3072 字节索引上限。
- 未执行：恶意文件/压缩炸弹/路径穿越样本、超时与超大输出、进程树逃逸、应用重启/迟到结果、MinIO/Qdrant/RabbitMQ/RAG E2E。生产形态还必须使用独立 rootless Docker 或隔离执行节点，不能把 Docker Desktop 主 Socket 暴露给 Sandbox 服务。
- 结论：基础 Docker 与迁移门禁通过，但完整 P0 安全和数据面门禁未完成，状态保持 `pending-verification`。
