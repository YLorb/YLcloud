---
id: REQ-20261025-002
type: requirement
status: pending
priority: P1
created: 2026-10-25
updated: 2026-10-25
owner: unassigned
version: 2
tags: [ylcloud, agent, harness, workflow, langchain4j, langgraph4j]
---

# Workflow 工具化与 Java Harness

## 已确认目标

用户要求将 Workflow 从对话编排层调整为工具层，补充 harness，并为后续框架接入铺垫。2026-10-25 集中澄清后的明确选择：

1. 保留预定义工作流，作为 harness 可调用的复合工具，不再主导整个对话。
2. 目标框架为 LangChain4j 与 LangGraph4j。因此本方案以 Java 侧 harness 为边界，保留 Python Workflow 子流程执行服务。

这里的 harness 指整个 Agent 运行环境：组织上下文、调用模型、执行工具、控制预算和取消、保存恢复状态、校验并发布结果。LangChain4j 是模型接入适配层的候选实现，LangGraph4j 是后续步骤执行引擎的候选实现；框架不能替代项目的权限、证据和副作用控制。

## 基线与交付范围

- 检查工作区：`.worktrees/knowledge-trust-dev`，分支 `codex/knowledge-trust-dev`，基于本地 dev `7969871b5a63bd2f825fcfb9d61fdbf3f8e77ba1`。
- 此工作区包含 REQ-20261025-001 的未提交可信问答与一致性修改；本设计按该工作区当前源码分析。原工作区 `feat/upload` 保持独立。
- 本轮交付架构草案、代码影响范围、迁移顺序和验收门槛。尚未实现新的 harness，未执行相关测试，未提交、推送或部署。
- 用户已确认上述目标与框架方向；具体模块设计仍为待审核草案，不能将目标确认等同于所有实现细节已经确认。

## 继承的业务规则

- 知识库所有者和管理员统一配置，普通成员不可覆盖。
- 文件更新期间是否允许旧内容回答可配置，默认停用旧内容。
- 历史版本查看开关默认关闭；保留期限可配置，开启保留时明确期限。
- 政策强相关问题缺乏明确、适用依据时拒答；知识型内容允许明确标注推断。政策问题有充分依据时可以回答，延续 REQ-20261025-001 已记录的解释。
- 继续执行证据版本绑定、当前权限检查和最终回答验证。Workflow、模型适配器和图引擎均不得绕过。

## 功能范围

1. 对话入口转接 Java harness；同一个 run 只有一个步骤执行引擎。
2. 提供框架无关的 Run、ModelTurn、ToolCall、ToolResult、EvidenceRef 契约。
3. 实现有界的“模型决策 → 工具执行 → 观察结果 → 再决策”能力；最终回答经过现有可信问答边界。
4. 把工具注册、参数校验、当前授权、风险授权、幂等和执行后端从 Workflow 专属调用关系中解耦。
5. 将版本化预定义 Workflow 注册为复合工具，支持父子预算、取消传播、异步等待和恢复。
6. 保持既有公开查询 API 与鉴权契约，按 run 固定引擎类型和协议版本，兼容存量任务。
7. 为 LangChain4j ModelPort 与 LangGraph4j AgentEngine 适配保留边界，并定义兼容性验证要求。

## 非目标

- 不删除 Python Workflow 服务、预定义模板、历史运行记录或 sandbox-service。
- 不新增自研通用图 DSL、可视化编排器、多 Agent 协商或任意模型生成图执行能力。
- 不把此次改造扩大为向量库、文件系统、权限体系或整个 RAG 链路重写。
- 不承诺不同执行引擎的 checkpoint 可直接互换，不把数据库事务描述为外部副作用 exactly-once。
- 不在此次规划中锁定未经依赖验证的 4j 版本，也不配置新的模型密钥。

## 验收标准

| 编号 | 可判定的通过条件 |
|---|---|
| H1 | 同一测试任务可依据第一轮工具结果选择后续工具，且每一步可追踪；不再固定为一次顶层 Workflow 图执行 |
| H2 | 模型仅提出工具请求；未注册工具、错误 schema、超范围参数均在执行前拒绝 |
| H3 | 进程在工具成功后、checkpoint 提交前退出，恢复时读取同一逻辑调用结果，不重复副作用 |
| H4 | 父 run 超时、取消和预算耗尽传播到子 Workflow；恢复、重试和嵌套调用不重置额度 |
| H5 | 旧版本、已删除文件、撤销权限、历史正文与 checkpoint 中的旧工具结果，不能绕过当前授权和证据规则 |
| H6 | Workflow 只能执行已注册模板及固定版本，其输出不能直接完成聊天消息 |
| H7 | 新旧任务按创建时的 engineType 路由；切换默认引擎不改变存量任务恢复路径 |
| H8 | 现有 API、API Key scope、幂等受理、任务结果与 answerId 契约回归通过 |
| H9 | 后续更换 AgentEngine 不要求业务工具依赖 LangGraph4j 类型，ModelPort 不暴露 LangChain4j 类型到业务 API |
| H10 | 失败、拒答、待审批、待工具、结果未知分别可观测；原始模型草稿不作为已验证答案流出 |

## 风险与待验证事项

源码已确认存在缺口：当前模型返回协议没有原生工具调用；旧工具幂等身份绑定 executionId；旧 Workflow 历史与 chunk 工具需要接入新授权证据入口。源码检查不等于这些问题已在生产触发。

实施前需验证实际模型工具调用能力、4j 依赖矩阵，以及运行环境中的旧任务存量。真实数据库、模型、MQ、Workflow、sandbox 的联调结果未知。技术验证项由实施阶段解决，不要求用户替工程师选择未核实的依赖版本。

## 下游方案

[[10-规划与实施/ADR-20261025-002-Workflow工具化与Java-Harness方案]]

2026-10-25：两项产品和框架方向已确认；形成模块与迁移草案，状态 pending。未产生新 harness 实现或测试结果。

2026-10-25：用户要求先加入 TODO。已在现有 `.plans/implement-unified-knowledge-platform.todo.md` 登记 HAR-01 至 HAR-06；仅登记待办，不视为详细模块审核通过或实施开始。
