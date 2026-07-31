---
id: ADR-20260721-005
title: Graph Workflow 稳定版完整实施方案
type: architecture-decision
status: completed
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 2
tags:
  - graph-workflow
  - implementation-plan
  - sqlite
  - worker
  - compatibility
---

# Graph Workflow 稳定版完整实施方案

## 1. 目标与本轮边界

本方案把已确认的 Graph Workflow Engine 稳定版拆成可直接实施的模块、数据结构、执行顺序和测试范围。

本轮完成：

- Workflow v2 显式 edges、v1 永久兼容编译。
- 唯一核心 GraphWorkflowExecutor。
- 稳定拓扑序、激活状态、Condition Edge、Join、Merge、End。
- 显式 Loop Composite 与单层 LoopFrame。
- State Schema、节点 output_schema、静态字段数据流校验。
- Error、Timeout、End、受限 Cancel Outcome Edge。
- 单机多进程、多 Run 并发、SQLite RunControlStore。
- Timer、Heartbeat、Poller、隔离 Worker 和 Timeout Retry。
- Trace、Context、node outputs 持久化及运行控制 CLI。

本轮不完成：

- 单个 Run 内异步/并发节点执行。
- State Patch 并发提交。
- 嵌套 Loop、while/until、动态追加 items、break/continue 的通用语法。
- 子流程节点；本轮结束后单独讨论。
- 消息队列、分布式 Worker、生产级多机数据库。
- AI Graph Generator；待稳定版完全落地后再开始。
- 完整 Checkpoint 中点恢复；主进程崩溃目标为从 Start 重跑。

## 2. 单一执行链

```text
YAML / JSON / v1 Workflow
→ Loader
→ SchemaParser
→ V1ToV2Compiler（v2 直接标准化）
→ GraphStructureValidator
→ LoopSccValidator
→ StaticDataflowValidator
→ ToolPolicyValidator
→ BudgetValidator
→ ExecutionPlanCompiler
→ GraphWorkflowExecutor
→ RunController / NodeHandler / OutcomeRouter
→ Result + State + Trace + RunControlStore
```

校验阶段固定顺序：

```text
Schema 解析与标准化
→ Graph 结构校验
→ Loop SCC 校验
→ 静态字段数据流校验
→ Tool 权限与风险校验
→ 执行预算校验
→ Execution Plan 编译
→ Runtime 复检与强制执行
```

v1 和 v2 不拥有两套 Executor。v1 的兼容逻辑只能出现在 Compiler/Trace Adapter，不能渗入各 Node Handler。

## 3. 核心模型

### 3.1 Workflow v2

```text
GraphWorkflow
  schema_version: 2
  id / name / metadata
  input_schema
  state_schema
  nodes[]
  edges[]
  budgets?
  runtime_policy?
```

简化类型 `string/integer/number/boolean/array/object/any` 在解析阶段统一转换为 JSON Schema。动态字段只允许位于显式 Map/object 字段的 `additionalProperties` 区域；普通节点输出仍必须有明确 output_schema。

### 3.2 CompiledNode

```text
node_id
node_type
input_bindings
output_schema
publish_mapping
skip_policy?
retry_policy
timeout_policy?
tool_descriptor?
loop_region_id?
```

### 3.3 CompiledEdge

```text
edge_id
source
target
kind: flow | default | error | timeout | end | cancel | loop_*
condition_ast?
priority?
required
```

Condition 放在 Edge；v1 Condition 节点由 Compiler 拆成条件线并保留 source mapping。

### 3.4 运行状态

```text
NodeStatus
  pending / active / inactive / skipped / running
  success / failed / timed_out / cancelled

RunStatus
  pending / running / completed / completed_with_recovery
  failed / timed_out / cancelled / abandoned
```

不增加 `worker_lost` RunStatus；Worker lost 作为 Outcome reason 记录。

## 4. Graph 与 Loop 编译

NetworkX 只负责：

- 节点/边结构装载；
- 可达性和孤立节点；
- Tarjan SCC；
- SCC 缩约；
- 外部 DAG 拓扑排序。

业务语义、State、Edge 条件、Retry 和 Handler 不放进 NetworkX。

稳定顺序：每次从当前零入度候选集合按 node id 词典序取出。普通 inactive 节点仍保留在拓扑序中，执行时记录 inactive Trace 后跳过。

任何成环 SCC 必须：

- 归属于一个显式 Loop；
- 只有一个 Loop Controller；
- 入口、body_exit、continue、break、error/cancel return 都可静态识别；
- 外部缩约为一个 Composite Node；
- 第一版不允许嵌套 Loop。

Loop items 在进入时求值一次。每轮独立 LoopFrame；内部输出不发布到外部 State。分支先各自产生结果，在内部 Merge 后统一生成 collectItem，Loop 只收集 collectItem。

## 5. 激活、Skipped、Join 与 Merge

### 5.1 激活 token

Edge Router 为命中的 Edge 产生 activation token。节点是否执行只取决于运行时是否收到有效 token，不因它出现在拓扑序中就执行。

### 5.2 inactive

- 本次 Run 没有活动入边到达。
- 不执行 Handler，不传递控制或数据。
- 记录 inactive Trace。

### 5.3 skipped

- 节点已经激活。
- 由 `skip_if`、Handler `SkipResult` 或 Runtime/Policy 决定不执行业务动作。
- 默认转发本次由上游传入的全部字段引用，不复制整个公共 State。
- 默认不创建本节点业务输出；显式 publish/passthrough mapping 时才登记为本节点输出。
- 出边按 success 路由规则计算。

### 5.4 Join

普通 Join 使用 `all_activated`：只等待本次 Run 实际激活的结构前驱。未激活节点仍在拓扑序中，但不会阻塞 Join。

### 5.5 Merge

Merge 每次 Run 只执行一次，运行时来源只能是 success 或 skipped：

```text
success → 使用声明输出
skipped → passthrough → default → 两者均无则失败
inactive → 不读取、不传值
```

多个节点发布同名公共字段时，Compiler 必须验证它们在共同 Merge 节点显式合并；否则拒绝 Workflow。Merge 成功后才发布最终公共字段。

## 6. State 与字段契约

- `state_schema` 是公共 State 的单一事实来源。
- 节点 `output_schema` 必须显式声明。
- 每个节点先写来源隔离区 `node_outputs[node_id][invocation]`。
- 只有 `publish_mapping` 或 Merge 可以写公共 State。
- 下游最终要求的变量即使某条前驱路径不产生，也必须通过 default、nullable、optional 或 Merge 规则在 Schema 中变得可用。
- output_schema 违反属于 Handler/Workflow 契约错误，立即终止；不进入业务 Error Edge。
- 同步第一版继续使用受控 Context；引入异步/节点并发前必须改为 State Patch + 冲突检测。

## 7. Outcome 与路由顺序

```text
Node Handler
→ 成功：FLOW 条件全部求值
   → 命中一条或多条：全部激活
   → 零条命中：唯一 DEFAULT
   → END：结束活动路径
→ 非 Timeout 失败：普通 RetryPolicy → ERROR Edge
→ Timeout：固定额外 Retry 一次 → TIMEOUT Edge
→ Cancel：受限 CANCEL/Cleanup Edge
```

Default Edge 只属于 success 路由；不能替代 Error/Timeout。

Error/Timeout 成功处理并最终到达 End 时，Run 为 `completed_with_recovery`，并汇总所有已处理 OutcomeEvent。多个 End 同时激活时全部汇总。活动路径无法到达 End 时失败。

OutcomeEvent 只作为 Outcome Router 和 Handler 的只读安全输入，不进入公共 State。Error/Timeout Handler 自身失败立即终止，避免递归错误路由。

## 8. Deadline 与隔离 Worker

```text
effective_deadline = min(global, node, runtime hard limit)
```

调用前、重试前、State 提交前均复检剩余 Deadline 和预算。全局 Deadline 耗尽时立即 Timeout，不重试。

LLM/HTTP/MCP 优先使用 Provider 原生 timeout + CancellationToken。本地不可取消、高风险或显式声明的 Tool 使用每次调用一个独立进程。

Tool 的 entrypoint 或 ProcessToolProvider 在注册时统一为 ExecutableToolDescriptor；Workflow 只能引用 Tool ID。IPC 只传 UTF-8 JSON envelope。终止顺序和 SQLite 运行控制细节见 [[11-Run-Control与隔离Worker]]。

## 9. 数据库与主进程恢复

第一版使用 SQLite + `sqlite3` + SQL migrations：

- 单机多进程，每个进程独立 connection；
- WAL、foreign_keys、busy_timeout、短事务；
- CAS 保证 Poller/Timer/Worker 竞争只接受一个结果；
- 保存控制记录、完整 Trace、Context 审计快照和节点输出；
- 成功保留 7 天，失败/超时/取消保留 30 天；
- 应用 CleanupJob 分批删除过期记录；
- 主进程启动扫描遗留 Run，并以新 execution epoch 从 Start 重跑。

重跑前必须检查副作用 Tool 的幂等性；最终安全门禁见本方案未决问题。

## 10. 运行预算、安全与脱敏

宿主硬上限：

```text
max_nodes: 200
max_edges: 500
max_steps: 1000
max_loop_iterations: 100
max_llm_calls: 100
max_tool_calls: 200
max_context_bytes: 10 MiB
max_trace_bytes: 20 MiB
max_duration_seconds: 1800
```

- Workflow 只能降低上限，不能提高。
- Context 超限立即失败。
- Trace 超限停止详细事件，保留摘要并 warning。
- 敏感字段在日志、Trace、OutcomeEvent、stdout/stderr 和 CLI 中统一脱敏。
- Tool 注册时校验 Schema、权限、风险、execution_mode、幂等性和 secret 白名单。
- 子进程只得到显式需要的 secret。

## 11. 实施批次与验收

### 批次 A：编译与静态校验

- v1/v2 标准化、ExecutionPlan、稳定 topo、SCC/Loop 校验。
- State/output schema、动态 Map、字段路径和 Merge 冲突检查。
- 验收：非法环、不可达 End、字段缺失、同名发布冲突在执行前失败。

### 批次 B：原子 Runtime

- NodeHandlerRegistry、ActivationTracker、JoinCoordinator、MergeHandler、OutcomeRouter。
- Executor 只做编排，不包含具体 Handler 逻辑。
- 验收：多条件边全激活、inactive/skipped、Merge once、多 End 汇总。

### 批次 C：Loop Composite

- LoopRegion、LoopFrame、LoopReturnEvent、Controller 状态机和 collectItem。
- 验收：正常、失败、取消、空 items warning、迟到事件、无嵌套 Loop。

### 批次 D：RunControlStore

- SQLite migrations、Repository、CAS、Trace/Context/output 持久化。
- 验收：Windows/Linux 多进程集成测试，重复/迟到写入不能覆盖终态。

### 批次 E：Timeout 与 Worker

- DeadlineController、Timer、Heartbeat、Poller、JSON IPC、隔离 Worker。
- 验收：第一次 Timeout 重试、第二次 Timeout Edge、全局 Deadline 不重试、阻塞进程可回收。

### 批次 F：恢复、清理与 CLI

- execution epoch、主进程恢复扫描、CleanupJob、runs CLI 和 README。
- 验收：崩溃重启无旧结果污染；保留期和取消命令可观察、可审计。

### 批次 G：两轮自审

正确性审查：需求覆盖、边界、Context/Trace/输出、测试和异常处理。

安全审查：任意代码加载、Tool 授权、子进程密钥、日志脱敏、AI Workflow 校验、进程与文件清理。

## 12. 已确认决策记录（Q1–Q19 全选 A）

用户于 2026-07-21 明确确认 Q1–Q19 全部选择 A，未决事项清零：

| 编号 | 最终决策 |
|---|---|
| Q1 | 每进程独立 SQLite connection；短 `BEGIN IMMEDIATE` 事务、version/attempt CAS、有限 busy retry。 |
| Q2 | 单机单活动 Poller；使用 owner lease，原 Poller 故障后允许其他 Runtime 接管。 |
| Q3 | 每次调用使用单向 Pipe；`send_bytes/recv_bytes` 传 UTF-8 JSON，取消使用共享 Event/控制通道。 |
| Q4 | 节点默认 60s、Poll 500ms、Heartbeat 2s、Lease 6s、cancel grace 2s、DB busy timeout 5s、Store 重试 3 次。 |
| Q5 | Worker lost 按 Timeout；只有声明 idempotent 的 Tool 才重试一次，非幂等 Tool 直接 Timeout Edge。 |
| Q6 | SQLite 使用应用级 CleanupJob 分批删除，并提供 CLI dry-run。 |
| Q7 | 仅当已成功副作用均可安全重放时自动从 Start 重启；否则标记 abandoned，等待人工 retry。 |
| Q8 | 保存完整逻辑 Context；Secret/API Key 只保存引用或脱敏占位，快照仅用于审计。 |
| Q9 | 终止整个进程树：Windows Job Object，Linux 独立 process group。 |
| Q10 | 配置优先级固定为 CLI > 环境变量 > YAML Runtime 配置 > 代码默认值。 |
| Q11 | 内部统一生成 v2 Trace；v1 通过 TraceAdapter 投影为原结构。 |
| Q12 | v1 编译器生成内部 terminal handler；记录脱敏 Outcome 后保持原 fail-fast/timed_out 结果。 |
| Q13 | Merge 静态声明候选来源；运行时只读取实际到达的 success/skipped token，省略 inactive。 |
| Q14 | priority 数字越小越优先且禁止重复；Error 可多条，Timeout 每节点最多一条。 |
| Q15 | running/pending 节点均可取消；pending 节点到达时生成 CANCELLED outcome。 |
| Q16 | Cleanup 仅允许白名单幂等 Tool，禁止 LLM/Loop/Subflow，硬时限 30s；失败后仍为 cancelled，并附 cleanup_errors。 |
| Q17 | SQLite 固定 WAL + `synchronous=FULL`。 |
| Q18 | Runtime 默认最多 4 个并发 Run；隔离 Worker 总数另受硬上限控制。 |
| Q19 | Error Handler 只能读取 OutcomeEvent 和显式安全输入，不能读取完整 State/Context。 |

## 13. 决策完成记录

上述决策已全部确认：

- 回写 ADR-20260721-003 与 ADR-20260721-004；
- 本 ADR 已完成定稿；
- 具体字段、接口、SQL、状态转换、任务依赖和测试门禁见 ADR-20260721-006；
- 后续实现 Agent 从 TASK-20260721-025 开始执行；
- 每个代码批次完成后执行正确性审查、安全审查和对应测试。

## 14. 关联文档

- [[08-Graph-Workflow图工作流引擎]]
- [[09-Loop-Controller运行时模型]]
- [[10-Outcome-Edge与Timeout模型]]
- [[11-Run-Control与隔离Worker]]
- [[04-待完成任务与路线图]]
