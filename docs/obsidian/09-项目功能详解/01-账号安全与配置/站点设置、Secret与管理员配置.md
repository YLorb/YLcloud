---
title: 站点设置、Secret 与管理员配置
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - configuration
  - secrets
  - admin
  - security
  - deployment
---

# 站点设置、Secret 与管理员配置

## 产品目标

站点名称、注册策略、RAG 参数和模型端点需要可管理，但凭据不能出现在前端、数据库配置快照、Compose 明文变量或 Git。配置系统因此区分公开设置、管理员设置、部署参数和 Secret。

## 配置路径

- SiteSettingController 提供无需登录的公开站点设置，仅返回安全白名单字段。
- AdminSettingController 由 AdminPermissionService 保护，读写平台设置。
- SiteSettingService 负责持久化、默认值和类型转换。
- SpaceRagService.getConfig/updateConfig 管理知识库级 topK、temperature、模型选择和 knowledgeProfileEnabled，并记录 before/after 与 changedFields。
- SecretFileEnvironmentPostProcessor 从 *_FILE 引用读取敏感值。

## 为什么分层

站点标题适合数据库热更新；线程数、端口和 collection 维度影响进程或数据结构，通常要求部署时配置；API Key 是高敏感数据，必须由 Secret 管理。若全部放进一个 settings 表，就会产生越权读取、日志泄露和“界面修改后到底何时生效”的歧义。

## 关键数据与接口

| 功能 | 入口/存储 |
|---|---|
| 公开设置 | GET /api/site/public-settings |
| 管理设置 | /api/admin/settings |
| Space RAG 设置 | GET/PUT /api/space/{spaceId}/rag/config |
| 配置审计 | space_rag_config_log |
| 通用站点设置 | site_setting |
| 前端 | SettingsPanel、KnowledgeSettingsView |

## 变更语义

配置更新必须校验范围，例如 topK 上限、chunkOverlap 小于 chunkSize、temperature 合法。数据库写入和配置日志应同事务提交。影响当前索引结构的设置不能只改值，应明确提示需要重建。

## Secret 原则

- Secret 文件不加入 Git；示例文件只提供字段名。
- 应用日志、健康接口和错误响应不得回显 Secret。
- Secret 轮换后需要说明是否热生效或重启生效。
- 曾经暴露的 Key 必须在供应商侧作废，迁移到 Secret 不会让旧值重新安全。

## 当前限制

## Admin Settings 实施状态

管理员入口统一为 `/admin/settings`，页面分为站点信息、权限系统、文件与存储、AI 与 RAG。普通用户直接访问得到明确的权限错误，不读取管理员配置。文件与存储区按角色编辑 USER/ADMIN 总配额，并以 MB 编辑单文件上限；`0 MB` 表示不限制单文件大小。当前账号自降权、自停用和最后一个启用管理员均受后端保护。

部分数据库配置到 model/parser/Qdrant 运行时的动态应用语义尚未完全收口，需要后续定义热加载失败时的回滚和健康状态。
