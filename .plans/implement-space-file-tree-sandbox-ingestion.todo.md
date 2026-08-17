# Implement Space File Tree, Sandbox Ingestion, Authorization and Deletion

## Summary

把 Space 从平铺文件页升级为完整文件树，并一次性补齐四角色授权、节点关系 capability、全文件 Sandbox 预检、Personal 目录导入、同内容唯一位置、整个 Space 搜索和不可撤销异步删除。保留 `user_file`/`space_file` 双模型，共享前端展示层而不共享授权或知识生命周期。

## Type

Feature + Refactor + Security/Consistency Enhancement

## Source Issue/Task

- REQ-20260725-001
- ADR-20260817-001
- 用户于 2026-08-16～2026-08-17 确认的 Space 文件树、角色、Sandbox、重复内容和删除规则。

## Original Requirements

| # | Requirement | Plan Step(s) |
|---|---|---|
| 1 | 保留 Personal/Space 双逻辑模型，复用文件树体验 | 8, 9 |
| 2 | Space 普通文件默认知识化，Personal 默认不知识化 | 6, 7 |
| 3 | OWNER/ADMIN/MEMBER/VIEWER 四角色 | 2 |
| 4 | MEMBER 只管理本人文件或全本人子树 | 2, 4, 7 |
| 5 | 文件级 ACL 仅预留统一授权入口 | 2 |
| 6 | 目录最大 100 层 | 3 |
| 7 | 目录重命名、移动、祖先链和循环校验 | 3 |
| 8 | 搜索整个 Space 并按权限过滤 | 4, 9 |
| 9 | Personal 支持文件/目录导入 | 5, 6, 9 |
| 10 | 所有导入文件先经 Sandbox | 5, 6 |
| 11 | 用户选择整批失败或跳过失败项 | 6, 9 |
| 12 | 同内容在同一 Space 只有一个有效/隔离节点 | 3, 6 |
| 13 | 重复时拒绝，不移动已有文件 | 3, 6, 9 |
| 14 | 历史重复只报告、不合并 | 1, 3 |
| 15 | 删除前弹窗统计与名称确认 | 7, 9 |
| 16 | 删除先隔离再异步清理，不可撤销/取消 | 7 |
| 17 | MEMBER 只能给本人文件上传/恢复版本 | 2, 9 |
| 18 | 导入复用物理内容，不重新上传 | 6 |
| 19 | Space 专属知识/版本/贡献者/预览不回退 | 9 |
| 20 | 实现与测试文档必须可独立验证并回写 | 10 |

Coverage Check: 20/20 requirements mapped, 100%.

## Status

Implemented, pending verification。主体代码、单元回归、前端构建和 Compose 静态校验已完成；真实 Docker/Sandbox/MySQL/Qdrant/RabbitMQ 门禁及 ADR 第 20 节列出的兼容/性能项完成前，不重命名为 `.done.md`。

## Context

`space_file` 已是目录树，但 `SpacesPage` 平铺根目录；角色前后端不一致；修改目录、Sandbox 导入和可靠删除契约缺失。只改前端会继续产生权限、重复向量和长事务风险。

## Current State

- 后端：`SpaceFileController/Service/Mapper` 支持 list/tree/folder/import/upload/remove/version/preview/download。
- 删除：`SpaceFileService.removeTree` 同步递归。
- 授权：写操作普遍 `requireAdmin`，无 VIEWER、节点关系 capability。
- 前端：`SpacesPage.tsx` 不维护 folderId，上传/导入不传 parentId。
- Sandbox：固定预置 Tool 控制面已存在，但文件导入未接入。
- 异步：统一任务中心、RabbitMQ、租约、重试、Handler 模式已存在。

## Desired State

- Space 按层浏览、100 层限制、全局搜索、完整目录操作。
- 后端计算 capability；四角色和 MEMBER 子树规则一致应用。
- 所有导入经 Sandbox，批次可解释，成功后才知识化。
- 同内容唯一位置，历史重复只读报告。
- 删除确认后立即隔离，由不可取消统一任务最终收敛。

## CLAUDE.md Requirements

### Naming Conventions

- 影响目录不存在相关 `CLAUDE.md`；遵循现有 Java PascalCase、Spring `*Controller/*Service/*Mapper`、React PascalCase 组件和 Flyway `V*__*.sql`。
- 不创建 `v2/new/temp/simple` 等临时实现文件。

### Architecture Requirements

- MySQL 为事实源；外部副作用使用状态、Outbox、幂等、版本和对账。
- Controller 只做协议适配；授权和不变量在 Service。
- 前端隐藏不能代替后端授权。
- 不改已执行 Flyway；新增下一个未占用版本。

### Type Requirements

- Java DTO/VO/Entity 明确类型；公共契约不用裸 `Map`。
- TypeScript 不新增 `any`；外部边界如需 `unknown` 必须立即验证并缩窄。

## Existing Types

### Reuse

- `SpaceFile`, `SpaceFileVO`, `SpaceFolderCreateDTO`, `SpaceFileImportDTO`。
- `Space`, `SpaceMember`, `SpaceConstant`, `AccessSubject`, `ResourceAction`。
- `UnifiedAsyncTask`, `TaskCreateCommand`, `TaskHandler`, `TaskExecutionContext`。
- `FileItem`, `SpaceFile`, `FilePreview`, `FileVersion` TypeScript 类型。

### Create

- Java：`SpaceFileAction`, `SpaceFileAccessService`, capability/location/rename/move/import/deletion DTO/VO，导入/删除 batch 与 item 实体。
- TypeScript：`ExplorerNode`, `ExplorerCapability`, `FileExplorerAdapter`, import/deletion batch 类型。

## Impact Analysis

### Files to Modify

- `cloud-common/.../SpaceConstant.java`：VIEWER。
- `cloud-server/.../SpaceMemberService.java`, `SpacePermissionService.java`：角色与授权入口。
- `cloud-server/.../SpaceFileController.java`, `SpaceFileService.java`, `SpaceFileMapper.java`：新契约并移除同步递归删除。
- `cloud-pojo/.../SpaceFile.java`, `SpaceFileVO.java`：状态、版本、depth/hash/capability。
- `cloud-server/.../async/worker/*`：导入/删除 Handler 注册。
- `sandbox-service` catalog/config：固定 `file.preflight` Tool。
- `cloud-frontend/src/api.ts`, `types.ts`, `features/files/FilesPage.tsx`, `features/spaces/SpacesPage.tsx`, `styles/app.css`。
- 相关 Controller/Service/Mapper/React 测试与 OpenAPI 文档。

### Files to Create

- 最终名的授权、导入和删除 Service/Mapper/DTO/VO/Entity。
- `cloud-frontend/src/components/file-explorer/FileExplorer.tsx` 等共享展示组件及 adapter。
- 新 Flyway 迁移和专项测试类。

### Files to Delete

- 不删除整文件；删除 `SpaceFileService.removeTree` 及 `SpacesPage` 旧平铺文件表实现。

### Breaking Changes

- VIEWER 从前端伪选项变为正式角色。
- 现有导入/上传会异步经过 Sandbox；Web UI 同步升级。公共版本化 API 必须保持契约或升主版本。
- DELETE 返回异步任务而非同步布尔值；无版本 UI 同步升级。

## Implementation Steps

### Step 1: Expand schema and report historical data

**Files**: new Flyway migration, Mapper report queries.

- 再次枚举迁移号后创建下一个版本。
- 增加 nodeVersion/depth/contentHash/lifecycleState/deletionBatchId、batch 表和 content guard。
- 回填 depth/hash；生成超深和重复报告，零自动删除/合并。

### Step 2: Implement four-role access service

**Files**: `SpaceConstant`, member/permission services, new `SpaceFileAccessService`.

- 实现动作枚举与 capability。
- MEMBER 普通文件按 createdBy；子树操作查询全部有效/隔离后代。
- 统一 REST、版本、Workflow Tool 和开放 API 调用。

### Step 3: Implement tree invariants and mutations

**Files**: Space file Controller/Service/Mapper and DTO/VO.

- ancestors、rename、move、depth/path 批量更新、nodeVersion CAS。
- 使用迭代/CTE，不使用无界 Java 递归。
- 实现 content guard、409 定位和历史重复只读报告。

### Step 4: Implement authorized Space-wide search

- 分页搜索名称/路径，返回受权祖先定位和 capability。
- 排除隔离/删除节点；为未来 ACL 只依赖 AccessService。

### Step 5: Add fixed Sandbox preflight tool

**Files**: sandbox catalog, tool image/config, client contracts.

- 固定镜像/entrypoint；只读输入、无秘密、资源/网络/输出限制。
- 定义稳定逐文件结果 schema 和安全错误码。

### Step 6: Implement import batches

- 固化 Personal 文件/目录清单；防重/配额/深度检查早于 Sandbox。
- ATOMIC 和 SKIP_FAILED 状态机、幂等任务、重启恢复。
- 成功事务创建目录/节点/引用，提交后 RAG；失败零知识副作用。
- 将旧 upload/import/web 入口内部路由到同一批次实现，删除直接创建 ACTIVE 节点路径。

### Step 7: Implement irreversible async deletion

- 影响预览、确认令牌、事务锁定与整树 REMOVAL_PENDING。
- 创建不可取消统一任务，逐节点检查点清理 RAG/向量/配额/引用/物理对象。
- 删除同步 `removeTree`，迟到任务核对 batch/version。

### Step 8: Extract shared File Explorer

- 从 `FilesPage` 抽取纯展示和导航组件；Personal adapter 保持现有能力。
- 类型转换只在 adapter，组件不判断后端角色。

### Step 9: Integrate Space UI

- URL 保存 `space/tab/folder`；按层查询、面包屑、全局搜索定位。
- 当前目录上传/导入；Personal 目录选择器与失败策略。
- capability 驱动操作；接入重复定位、删除统计、批次进度、知识/版本/贡献者/预览。
- 删除旧平铺表代码和 VIEWER 伪契约。

### Step 10: Validate and hand back evidence

- 为五个任务生成完整用例和独立测试记录。
- 执行单元、真实 MySQL、Sandbox 容器、RabbitMQ/Qdrant/MinIO 集成、故障注入、前端构建和浏览器 E2E。
- 回写 TASK、TEST、ADR 和总计划状态。

## REMOVAL SPECIFICATION

### Code to Remove

- `SpaceFileService.removeTree`：由删除批次和统一任务 Handler 替代。
- `SpaceFileService` 中 upload/import/web 直接激活并立即调用 RAG 的路径：由导入批次提交阶段替代。
- `SpacesPage.tsx` 的根目录平铺 `visibleFiles` 表和不带 parentId 的 mutations：由 Space adapter/FileExplorer 替代。
- 前端独有但后端不接受的 VIEWER 处理：由共享正式角色类型替代。
- 主浏览对 `/tree` 的任何使用：改为按层 list；保留 API 仅供受控兼容调用。

### Removal Checklist

- [x] 无同步递归删除入口
- [x] 无绕过 Sandbox 的 Space 导入入口
- [x] 无复制角色字符串的文件授权判断
- [x] 无旧 Space 根目录平铺浏览
- [x] 无不带当前 parentId 的 Space 创建/上传/导入
- [x] `rg` 验证旧递归删除符号已清理

## Anti-Patterns to Avoid

- 不运行新旧导入/删除路径双写。
- 不保留宿主机预检 fallback。
- 不用前端 capability 代替后端授权。
- 不用文件名/路径判断相同内容。
- 不自动合并历史重复。
- 不为内部兼容保留临时 `v2/new` 类；外部版本化 API 除外。

## Validation Criteria

### Pre-Implementation

- [ ] 重新确认 Flyway 最大版本与相关 AGENTS/CLAUDE 文件
- [ ] ADR-20260817-001 和五项任务/测试要求已读
- [ ] 所有新 DTO/VO/Entity/TS 类型确定
- [ ] Sandbox Tool schema 和统一任务类型确定

### Post-Implementation

- [ ] 20/20 要求均有实现和证据
- [ ] REMOVAL SPEC 全部完成
- [x] `mvn test` 通过（312 项）
- [ ] Sandbox `pytest` 和真实容器安全门禁通过
- [x] `npm run build` 通过；仓库未提供本任务独立前端测试命令
- [ ] MySQL/MinIO/Qdrant/RabbitMQ/Sandbox 集成与故障注入通过
- [ ] 浏览器 E2E 和 100 层/大批量性能门禁通过
- [ ] 无新增 `any`，无未经验证的 `unknown`
- [x] 实现和测试状态回写文档

## Ready to Implement

依赖顺序：TASK-20260817-001 → TASK-20260817-002 → TASK-20260817-003 与 TASK-20260817-004 → TASK-20260816-003。不得先只改 Space 页面。
