---
id: TASK-20260817-003
type: implementation-task
status: pending-verification
priority: P0
created: 2026-08-17
updated: 2026-08-17
owner: unassigned
version: 1
tags: [space, import, sandbox, personal, rag]
---

# Sandbox 预检与 Personal 目录导入

## 目标与依赖

- 目标：所有 Space 导入先经过固定 Sandbox Tool，并支持 Personal 文件/目录快照和用户选择失败策略。
- 前置：TASK-20260817-001/002、现有 sandbox-service、统一异步任务、Space RAG。
- 下游：[[10-规划与实施/测试/TEST-20260817-103-Sandbox预检与Personal目录导入测试要求]]。

## 修改范围与产物

1. 新建导入 batch/item 表、Entity/Mapper/Service/Controller/VO 和统一任务 Handler。
2. Personal 目录展开为不可变清单；保存相对路径、来源节点、UUID、SHA-256、大小和目标父目录。
3. 所有单文件上传、Personal 导入和 Web 导入也进入同一批次；重复检查早于 Sandbox。
4. Sandbox 注册固定 `file.preflight` Tool；只读输入、固定镜像、无任意命令、资源/时间/输出限制，全部文件必检。
5. `ATOMIC` 任一失败销毁 Sandbox 且不创建节点；`SKIP_FAILED` 继续并仅创建成功文件所需目录。
6. 成功节点激活并提交后触发默认 RAG；失败/超时不得留下 RAG 文档或向量。
7. 批次接口返回逐项安全错误、进度和最终统计。

## 不包含

- 绕过 Sandbox、用户镜像/命令、失败文件强制导入、导入 Personal 目录本身为物理文件。

## 验收、测试与回滚

- Sandbox 不可用时明确失败，不在宿主机降级。
- 两种失败策略结果与持久清单一致；销毁 Sandbox 后任务可解释、可重试且不重复导入。
- 回滚停止新导入但保留批次证据；已激活文件继续正常知识化。
- 实现者必须生成完整测试、执行恶意文件/超时/逃逸/幂等/E2E 并创建独立记录。

## 实施记录（2026-08-17）

已实现固定 `file.preflight@1.0.0` 镜像、无网络 catalog、fail-closed 客户端、Personal 目录快照、持久批次/条目、`SPACE_FILE_IMPORT` 任务、两种失败策略和成功项幂等检查点。工具拒绝可执行文件、路径穿越、加密/膨胀归档、Office/归档主动内容及 PDF 主动内容。Docker 引擎未运行，真实容器安全测试未执行；兼容上传/Web 尚未迁移为持久单条批次，解决方案见 ADR 第 20 节。
