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
- Java 客户端编译及 310 项回归通过；Sandbox 不可用时配置为 fail-closed。
- 未执行：镜像构建、恶意样本、超时/资源/网络逃逸、重投/重启及 RAG E2E。`docker compose ps` 明确失败：Docker Desktop Linux engine pipe 不存在；宿主也无 `pytest` 命令。
- 结论：代码已实现但安全门禁未完成，状态保持 `pending-verification`；启动 Docker 后先运行 `scripts/build-file-preflight-sandbox.ps1`，再执行本文件全部负例。
