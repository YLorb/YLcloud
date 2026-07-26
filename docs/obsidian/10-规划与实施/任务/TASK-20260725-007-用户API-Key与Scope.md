---
id: TASK-20260725-007
type: implementation-task
status: completed
priority: P0
created: 2026-07-25
updated: 2026-07-26
owner: codex
version: 1
tags: [api-key, scope, security]
---
# 用户 API Key 与 Scope
- 目标/需求：实现用户统一创建的 API Key，以及云盘/知识库独立开关、目录根范围、Space 快照范围、有效期和高风险 Agent 能力。
- 依赖/输入：TASK-002/006；现有 JWT 拦截器、文件树、Space 权限。
- 修改范围/产物：Key/Scope 表、一次显示明文、前缀+强哈希认证、撤销与最后使用时间；管理员期限/数量策略。
- 不包含：公共 API 资源 Controller、Webhook。
- 验收：实际权限为 Key Scope、用户实时权限和站点策略交集；全选不包含未来 Space；目录移动不能扩大授权；明文无法再次查询。
- 测试/通过：哈希、过期、撤销、目录逃逸、Space 权限撤销、数量限制和高风险隔离通过。
- 风险/回滚：Key 泄露；限流、审计、即时撤销；回滚时禁用 Key 认证入口。
- 回写：V39 已实现 `ylk_<12字符前缀>_<43字符随机密钥>`，创建时仅返回一次明文，服务端只保存前缀和 BCrypt；Scope 为 `DRIVE_READ/WRITE`、`KNOWLEDGE_RETRIEVE/AGENT`，云盘绑定稳定目录 ID，Space 为创建时可见快照。每次认证/使用与账号状态、站点策略、当前目录归属和 Space 成员权限取交集；Key 撤销同步撤销 TASK-006 高风险能力。详见 [[10-规划与实施/测试/TEST-20260725-009-TASK-007-用户API-Key与Scope测试记录]]。
- 测试：[[10-规划与实施/测试/TEST-20260725-001-统一知识平台实施测试要求]]
