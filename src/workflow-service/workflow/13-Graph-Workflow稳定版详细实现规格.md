---
id: ADR-20260721-006
title: Graph Workflow 稳定版详细实现规格
type: architecture-decision
status: implemented-pending-verification
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 2
tags:
  - graph-workflow
  - implementation-spec
  - sqlite
  - multiprocessing
  - testing
---

# Graph Workflow 稳定版详细实现规格

## 1. 背景与目标

当前项目已具备 Workflow v2 模型、v1 条件节点转边、NetworkX SCC/拓扑分析、同步 GraphWorkflowExecutor、基础 Loop/Merge 和 Graph 测试骨架，但执行器仍包含大量 Handler、路由、Retry、Loop、Trace 和 Tool 逻辑，尚未具备最终确认的 Outcome Edge、完整静态数据流、SQLite Run Control、Heartbeat、隔离 Worker、崩溃恢复与运行控制 CLI。

本规格将 ADR-20260721-001 至 ADR-20260721-005 转换为字段、接口、数据库、状态转换、文件改造和任务级验收要求。负责实现的 Agent 应按本文顺序修改代码，不应重新发明第二套执行器或绕开已确认决策。

## 2. 用户、场景与业务价值

### 2.1 用户

- Workflow 作者：编写 v1/v2 YAML/JSON。
- Runtime 维护者：注册 Tool、配置权限、风险、Timeout 和资源上限。
- 运维/调试用户：通过 CLI 查询、取消、重试和清理 Run。
- 后续 AI Planner：只能产生通过同一校验链的 Graph Workflow。

### 2.2 核心场景

```text
加载 Workflow
→ 编译并静态校验
→ 创建持久化 Run/Execution
→ 单 Run 内稳定拓扑执行
→ 多 Run 最多并发 4 个
→ 节点成功、跳过、失败、超时、取消产生明确 Outcome
→ Handler/Edge 恢复或到达 End
→ 保存最终结果、Context、Trace、节点输出和运行控制记录
```

### 2.3 业务价值

- 确定性：同一输入、同一活动边得到稳定节点顺序。
- 可解释：每个状态转换、路由、Retry 和恢复可追踪。
- 可恢复：主进程崩溃后在幂等安全条件下从 Start 重跑。
- 可控制：阻塞 Tool 可被整个进程树终止。
- 可兼容：v1 永久进入唯一 Graph Executor，旧 Trace 和失败语义不变。

## 3. 需求范围与非目标

### 3.1 范围

- 完成 TASK-20260721-005、007、012–019、021–031。
- 保留已完成 TASK-001–004、006、010、011 的外部行为，允许内部重构。
- Windows 10/11 与主流 Linux 上验证多进程隔离。
- SQLite 使用标准库 `sqlite3`，不新增 ORM。
- Tool IPC 只允许 JSON 兼容值。

### 3.2 非目标

- TASK-008 Checkpoint 中点恢复只保留研究任务。
- TASK-009 State Patch 在引入节点异步/并发前实施，本轮延期。
- TASK-020 Subflow、嵌套 Loop、动态 items、while/until、通用 break/continue、消息队列和分布式 Worker 延期。
- AI Graph Generator 延期。
- 第一版不做 CPU/内存 OS 限额，只限制时间、进程数、IPC 和 stdout/stderr 大小。

## 4. 验收标准

1. v1 和 v2 都只由 `GraphWorkflowExecutor` 执行；v1 CLI 输出和 Trace 兼容旧测试。
2. 相同 Graph 使用 node id 自然词典序得到稳定拓扑序。
3. Condition 成功边可同时激活；Default 仅在零条普通成功边命中时激活。
4. inactive 不传值；skipped 转发输入字段引用并按 success 路由。
5. Merge 只读取 success/skipped 活动来源，执行一次；inactive 候选不参与运行时输入。
6. 所有字段引用、默认值、nullable、动态 Map 和公共字段冲突在执行前校验。
7. Error、Timeout、End、Cancel 语义符合 ADR；Timeout Edge 每节点最多一条。
8. 第一次业务 Timeout 只额外重试一次；第二次进入 Timeout Edge；全局 Deadline 耗尽不重试。
9. Worker lost 只有幂等 Tool 重试；非幂等 Tool 直接 Timeout Edge。
10. 隔离 Tool 超时后，Windows/Linux 的 Worker 及其后代进程全部被终止。
11. SQLite 多进程竞争下，重复 Poll、Heartbeat、迟到结果不会覆盖终态。
12. 主进程崩溃后，幂等安全 Run 自动建立新 epoch 从 Start 重跑；不安全 Run 进入 abandoned。
13. 数据库保存完整逻辑 Context、Trace 和 node outputs；Secret 只保存引用或脱敏占位。
14. CLI 支持 list/show/cancel/cancel-node/retry/cleanup，并写入 README。
15. 实现 Agent 完成两轮自审、全部必选测试和独立测试文档后，任务才可标记 completed。

## 5. 现状分析、诊断与证据

### 5.1 已证实现状

- `graph_models.py` 的 `GraphEdge` 只有 source/target/condition/default/metadata，没有 edge_id、kind、priority、required。
- `graph_analyzer.py` 使用 `nx.DiGraph`，拒绝相同 source/target 的平行 Edge；结构分析与业务 Edge 尚未分离。
- `graph_executor.py` 同时负责 Node Handler、Retry、Merge、Loop、Edge 条件、输出、Tool 校验和 Trace，违反已确认的原子拆分目标。
- `ToolSpec` 只有 name/description/input_schema/permission/risk_level，不能描述 output_schema、隔离模式、入口、幂等性和 Secret。
- `ToolRegistry` 保存任意内存 callable；Windows spawn 无法安全重建闭包、lambda 或持有连接的对象。
- 当前没有 persistence、workers、RunManager、Poller、Migration 或 runs CLI 模块。

### 5.2 已证实根因

当前 Graph 代码是结构和同步执行骨架，尚未引入“编译后的 ExecutionPlan + OutcomeEvent + 持久化 Run Controller”的分层，因此无法在不继续膨胀 Executor 的情况下实现完整 Timeout、取消、恢复和兼容。

### 5.3 待实现时验证的假设

- Windows Job Object 能在当前 Codex/终端宿主可能已有 Job 的情况下完成嵌套关联；若 `AssignProcessToJobObject` 被宿主策略拒绝，隔离 Tool 必须安全失败，不能降级为只杀直接进程。
- SQLite WAL 在默认 4 Run、Heartbeat 2s 下锁竞争可接受；第一版只要求行为正确，但必须记录锁等待和 busy retry 指标。
- 当前 v1 测试能由 TraceAdapter 保持完全兼容；若测试依赖未文档化字段，需要在 Adapter 中补齐而不是修改 v1 外部结构。

### 5.4 未知项

架构未决事项已清零。实现期未知项只允许作为验证结果回写，不得擅自改变 Q1–Q19 决策。

## 6. 约束、假设与未决事项

### 6.1 硬约束

- Python 3.10+；Pydantic 2、NetworkX 3、Typer、Rich、pytest。
- 单机多进程；同一 Run 内同步节点执行。
- SQLite WAL、`synchronous=FULL`、每进程独立 connection。
- Runtime 默认最多 4 个并发 Run。
- 配置优先级 CLI > ENV > YAML > code defaults。
- 节点默认 Timeout 60s；全局最大 1800s。
- IPC 只允许 UTF-8 JSON bytes，禁止 pickle 业务对象。

### 6.2 风险接受

- SQLite 只有一个同时写事务；通过短事务、批量 Trace、CAS 和有限重试接受该限制。
- 强杀进程不会回滚外部副作用；自动 Retry/恢复受 Tool 幂等声明和稳定幂等键约束。
- 保存的 Context 不包含原始 Secret，因此审计快照不能作为无损 Checkpoint。
- Cleanup 失败不把 cancelled 改为 failed，仅记录 cleanup_errors。

### 6.3 未决事项

无。

## 7. 合理候选方案对比

候选方案已在 ADR-20260721-005 中完成比较并由用户全选 A。本规格固定采用：

- SQLite 多进程独立连接 + CAS，而非 DB Writer 进程或文件锁。
- 单活动 Poller + owner lease，而非无接管固定主进程。
- Pipe JSON bytes，而非 Queue/pickle 或 socket。
- Job Object/process group 管理进程树，而非只终止直接 Worker。
- v2 Trace + v1 Adapter，而非破坏 v1 输出。
- 应用 CleanupJob，而非更换数据库。

## 8. 用户选定方案与架构决策

### 8.1 总体分层

```text
Definition Layer
  Loader → SchemaNormalizer → V1ToV2Compiler

Compile Layer
  StructureValidator → LoopValidator → DataflowValidator
  → ToolPolicyValidator → BudgetValidator → ExecutionPlanCompiler

Runtime Layer
  RunManager → RunController → GraphWorkflowExecutor
  → ActivationTracker / JoinCoordinator / NodeHandlerRegistry / OutcomeRouter

Control Layer
  DeadlineController / Poller / Heartbeat / IsolatedProcessRunner

Persistence Layer
  SQLiteRunControlStore / TraceSink / ContextSink / CleanupJob
```

### 8.2 关键原则

- Pydantic 模型是配置 Schema 的单一事实来源，JSON Schema 从模型生成。
- NetworkX 只分析结构；业务 Edge 保存在独立 EdgeIndex。
- Executor 只编排，不直接实现 LLM/Tool/Merge/Loop Handler。
- 数据库 CAS 决定状态转换唯一胜者；内存锁不能替代 CAS。
- Runtime 每个边界都复检预算、Deadline、取消和 Tool 权限。

## 9. 系统边界、模块职责与关键流程

### 9.1 目标文件布局

```text
mini_agent_flow/
  engine/
    graph_models.py
    graph_compiler.py
    graph_analyzer.py
    execution_plan.py          # 新增
    schema_normalizer.py       # 新增
    dataflow_validator.py      # 新增
    activation.py              # 新增
    handlers/
      base.py                  # 新增
      start_end.py             # 新增
      llm.py                   # 新增
      tool.py                  # 新增
      merge.py                 # 新增
      loop.py                  # 新增
      cleanup.py               # 新增
    outcome.py                 # 新增
    deadline.py                # 新增
    run_controller.py          # 新增
    run_manager.py             # 新增
    runtime_config.py          # 新增
    graph_executor.py          # 缩减为编排器
    trace.py                   # v2 recorder + adapter/sink
    context.py                 # 来源隔离输出和受控发布
  persistence/
    __init__.py
    base.py
    sqlite_store.py
    migrations.py
    cleanup.py
    sql/
      0001_run_control.sql
      0002_audit_data.sql
  workers/
    __init__.py
    protocol.py
    isolated_process.py
    tool_worker.py
    process_tree.py
  tools/
    spec.py
    registry.py
    provider.py
  cli.py
```

### 9.2 编译流程

```text
WorkflowDefinition
→ v1: V1ToV2Compiler / v2: identity normalization
→ SchemaNormalizer：简化类型 → JSON Schema
→ NetworkXGraphAnalyzer：可达性、SCC、缩点、stable topo
→ DataflowValidator：字段 must/may 集合、Merge、Map、默认值
→ ToolPolicyValidator：注册、Schema、权限、风险、隔离、幂等
→ BudgetValidator
→ ExecutionPlanCompiler
→ immutable ExecutionPlan
```

`ExecutionPlan` 建议定义：

```text
ExecutionPlan
  plan_version
  workflow_id / version
  nodes_by_id: Mapping[str, CompiledNode]
  edge_index: EdgeIndex
  outer_order: tuple[str, ...]
  loop_regions: Mapping[str, CompiledLoopRegion]
  state_schema
  budgets
  source_map
  trace_compatibility
```

### 9.3 结构图与 EdgeIndex

为兼容多条 Error Edge，同时保持 NetworkX 边界：

- `nx.DiGraph` 仅保留唯一 `(source,target)` 结构投影，用于 SCC、可达性和拓扑排序。
- `EdgeIndex` 保存所有业务 Edge：`by_id`、`outgoing_by_source_kind`、`incoming_by_target`。
- 不再因相同 source/target 拒绝 Edge；只要求 edge_id 唯一。
- Error Edge priority 必须唯一；Timeout Edge 每来源最多一条。

### 9.4 单 Run 执行流程

```text
RunManager.acquire_run_slot(max=4)
→ Store.create_run + create_execution(epoch=1)
→ RunController.start
→ for representative in outer_order:
     ActivationTracker.resolve(node)
     if inactive: trace inactive; continue
     if pending cancel request: produce CANCELLED
     JoinCoordinator.assert_ready(all_activated)
     HandlerRegistry.execute(input envelope)
     validate output_schema
     persist node output / state / trace
     OutcomeRouter.route
→ aggregate active Ends
→ persist terminal status/result
→ release run slot
```

## 10. 接口、数据模型与状态设计

### 10.1 Graph 模型变更

`GraphEdge` 增加：

```text
id: str
kind: flow | default | error | timeout | end | cancel |
      loop_enter | loop_body_start | loop_return | loop_exit
priority: int = 100
required: bool = true
```

兼容规则：

- 缺少 id 时 Loader 以稳定 source/target/ordinal 生成，但 v2 保存时写回显式 id。
- 旧 `default: true` 标准化为 `kind: default`；同时声明且不一致时拒绝。
- 普通 Edge 指向 EndNode 自动标准化为 end；显式 kind 必须与目标角色一致。

`GraphBaseNode` 增加：

```text
input_schema: dict | simplified schema?
output_schema: dict | simplified schema?
skip_if: EdgeCondition?
publish_mapping: dict[str, str]
```

`GraphMergeNode` 增加 `join_policy: all_activated`，第一版不开放其他值。

### 10.2 Handler 协议

```text
NodeHandler.execute(
    node: CompiledNode,
    input: NodeInputEnvelope,
    runtime: NodeRuntimeContext,
) -> NodeExecutionResult
```

```text
NodeInputEnvelope
  values: JSON object
  provenance: field -> SourceReference
  activated_by: tuple[edge_id]
  outcome_event?: OutcomeEvent

NodeExecutionResult
  disposition: success | skipped
  outputs: JSON object
  publish_patch: JSON object
  safe_metadata: JSON object
  skip_reason?: string
```

Handler 不直接修改 WorkflowContext，不直接写数据库，不选择 Edge。

### 10.3 ActivationTracker

```text
ActivationToken
  edge_id
  source_node_id
  target_node_id
  source_status: success | skipped | recovered | cancelled
  field_refs
  sequence
```

`ActivationTracker` 保存每条实际命中 Edge 的 token。Join 的“等待”在同步 topo 第一版表现为：到达节点时所有结构前驱已终态，只计算其中哪些产生 token。

```text
no incoming token       → inactive
one or more token       → active
pending cancel request  → cancelled outcome，Handler 不执行
skip policy true        → skipped，转发 envelope
```

### 10.4 State 与输出

```text
WorkflowState
  public_state: dict
  node_outputs: node_id -> invocation_id -> dict
  input_envelopes: node_id -> NodeInputEnvelope
```

- Handler 输出先写 `node_outputs`。
- output_schema 校验成功后才允许 publish。
- publish_patch 只能写 state_schema 声明字段。
- 同名字段多个发布者必须经过共同 Merge。
- 动态键只允许写入 JSON Schema `additionalProperties` 明确开放的 Map 字段。
- 失败 attempt 的输出和 patch 不提交。

### 10.5 OutcomeEvent

```text
OutcomeEvent
  schema_version: 1
  event_id
  run_id
  execution_id
  invocation_id
  node_id
  outcome: success | error | timeout | cancelled
  reason_code
  attempt
  occurred_at_utc
  elapsed_ms
  deadline_at_utc?
  safe_error_type?
  safe_error_message?
  retryable
  handled
```

Error/Timeout/Cleanup Handler 只得到该对象和显式安全输入。

### 10.6 RuntimeConfig

```text
RuntimeConfig
  database_path
  max_concurrent_runs = 4
  default_node_timeout_seconds = 60
  max_duration_seconds = 1800
  poll_interval_seconds = 0.5
  heartbeat_interval_seconds = 2
  lease_seconds = 6
  cancel_grace_seconds = 2
  cleanup_timeout_seconds = 30
  sqlite_busy_timeout_seconds = 5
  sqlite_store_retries = 3
  cleanup_interval_seconds
  success_retention_days = 7
  failure_retention_days = 30
  isolated_worker_limit
  max_ipc_bytes
  max_debug_output_bytes
  debug_worker_output = false
```

加载顺序固定：code defaults → YAML → ENV → CLI。

### 10.7 SQLite DDL

`0001_run_control.sql` 至少创建：

```sql
CREATE TABLE schema_migrations (
  version INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  checksum TEXT NOT NULL,
  applied_at_utc TEXT NOT NULL
);

CREATE TABLE workflow_runs (
  run_id TEXT PRIMARY KEY,
  workflow_name TEXT NOT NULL,
  workflow_version TEXT NOT NULL,
  status TEXT NOT NULL,
  current_execution_id TEXT,
  restart_count INTEGER NOT NULL DEFAULT 0,
  started_at_utc TEXT NOT NULL,
  deadline_at_utc TEXT NOT NULL,
  finished_at_utc TEXT,
  result_summary_json TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL
);

CREATE TABLE run_executions (
  execution_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  epoch INTEGER NOT NULL,
  status TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  owner_lease_until_utc TEXT NOT NULL,
  started_at_utc TEXT NOT NULL,
  finished_at_utc TEXT,
  safe_to_restart INTEGER NOT NULL DEFAULT 1,
  version INTEGER NOT NULL DEFAULT 0,
  UNIQUE(run_id, epoch)
);

CREATE TABLE node_invocations (
  invocation_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  node_id TEXT NOT NULL,
  iteration_path_json TEXT NOT NULL DEFAULT '[]',
  attempt INTEGER NOT NULL,
  status TEXT NOT NULL,
  worker_id TEXT,
  idempotent INTEGER NOT NULL DEFAULT 0,
  idempotency_key TEXT,
  deadline_at_utc TEXT,
  lease_until_utc TEXT,
  heartbeat_at_utc TEXT,
  cancel_requested_at_utc TEXT,
  outcome_event_id TEXT,
  safe_error_summary TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  UNIQUE(execution_id, node_id, iteration_path_json, attempt)
);

CREATE TABLE cancel_requests (
  request_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  scope TEXT NOT NULL,
  target_node_id TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  reason TEXT,
  requested_at_utc TEXT NOT NULL,
  applied_at_utc TEXT,
  UNIQUE(execution_id, scope, target_node_id, status)
);

CREATE TABLE poller_leases (
  lease_name TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  lease_until_utc TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE control_events (
  event_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT,
  invocation_id TEXT,
  event_type TEXT NOT NULL,
  deduplication_key TEXT NOT NULL UNIQUE,
  safe_payload_json TEXT NOT NULL,
  occurred_at_utc TEXT NOT NULL
);
```

`0002_audit_data.sql` 至少创建：

```sql
CREATE TABLE trace_events (
  trace_event_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  node_id TEXT,
  status TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  payload_truncated INTEGER NOT NULL DEFAULT 0,
  occurred_at_utc TEXT NOT NULL,
  UNIQUE(execution_id, sequence)
);

CREATE TABLE context_snapshots (
  snapshot_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  redacted_context_json TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  created_at_utc TEXT NOT NULL,
  UNIQUE(execution_id, sequence)
);

CREATE TABLE node_outputs (
  output_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  invocation_id TEXT NOT NULL REFERENCES node_invocations(invocation_id) ON DELETE CASCADE,
  node_id TEXT NOT NULL,
  output_json TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  created_at_utc TEXT NOT NULL,
  UNIQUE(invocation_id)
);
```

必要索引：

```text
workflow_runs(status, deadline_at_utc)
workflow_runs(status, finished_at_utc)
run_executions(status, owner_lease_until_utc)
node_invocations(status, deadline_at_utc)
node_invocations(status, lease_until_utc)
cancel_requests(execution_id, status)
trace_events(execution_id, sequence)
```

### 10.8 SQLite 连接和 CAS

每个进程使用连接工厂：

```text
connect(timeout=5, isolation_level=None)
PRAGMA journal_mode=WAL
PRAGMA synchronous=FULL
PRAGMA foreign_keys=ON
PRAGMA busy_timeout=5000
```

状态转换模板：

```sql
BEGIN IMMEDIATE;
UPDATE node_invocations
SET status = :to_status,
    version = version + 1,
    updated_at_utc = :now
WHERE invocation_id = :id
  AND version = :expected_version
  AND status IN (:allowed_from_statuses);
COMMIT;
```

受影响行数必须为 1；为 0 时返回 `CAS_REJECTED`，调用者记录 stale/duplicate，禁止再次触发业务 Edge。`SQLITE_BUSY` 最多重试 3 次，使用短指数退避并受当前 Deadline 限制。

## 11. 异常、边界、幂等、降级和恢复

### 11.1 Outcome 路由顺序

```text
SUCCESS
→ 计算全部非 default FLOW
→ 命中全部激活；零命中才走唯一 DEFAULT
→ END 汇总

ERROR
→ 普通 RetryPolicy
→ 耗尽后按 priority 升序匹配 ERROR
→ 无命中 fail-fast

TIMEOUT
→ global deadline 已耗尽：不重试
→ 首次且幂等/安全：额外 Retry 一次
→ 第二次或不可重试：唯一 TIMEOUT
→ 无 TIMEOUT：timed_out

CANCELLED
→ 受限 CANCEL/Cleanup
→ 无 Cleanup：直接 cancelled
```

### 11.2 Timeout 与迟到结果

- 每个 attempt 使用独立 invocation_id。
- Poller、父进程 Timer、Provider 和 Worker 只能通过 CAS 提交终态。
- 已 timed_out/cancelled/failed 的 Invocation 拒绝后续 success。
- 迟到结果只写一条 `stale_result_rejected` control_event，不写 State/output。

### 11.3 Worker lost

```text
lease_until < db_utc_now
→ Poller CAS running → timed_out(reason=WORKER_LOST)
→ ToolSpec.idempotent=true：允许一次 Timeout Retry
→ false：直接 Timeout Edge
```

### 11.4 幂等键

副作用 Tool 声明 `idempotent=true` 时，Runtime 必须生成并传入：

```text
idempotency_key = hash(run_id, node_id, iteration_path, logical_attempt_group)
```

Timeout Retry 和 crash restart 复用相同 logical key；普通用户显式重新运行产生新 run_id 和新 key。ToolSpec 声明幂等但 Provider 不接受/使用幂等键时，注册失败。

### 11.5 主进程崩溃恢复

```text
Runtime 启动
→ 获取单 Poller owner lease
→ 查找 running execution 且 owner lease 过期
→ CAS old execution → abandoned
→ 检查成功副作用 Invocation
   → 全部具备稳定幂等键：run.restart_count+1，创建 epoch+1，从 Start 执行
   → 存在非幂等成功副作用：logical run → abandoned，等待 CLI retry
```

新 epoch 不复用旧 invocation_id、Trace sequence 或 node output；所有表以 execution_id 隔离。

### 11.6 数据库故障

- Store 写入失败后停止派发新节点和 Tool。
- 正在运行的隔离 Worker先设置取消，宽限期后终止进程树。
- 尝试在内存结果中返回 `RUN_CONTROL_STORE_UNAVAILABLE`；不得声称 Run 已可靠持久化。
- 数据库恢复不自动提交内存积压的业务结果，避免时间顺序和 CAS 失真。

### 11.7 CleanupJob

- Runtime 获得 cleanup lease 后周期运行；CLI 可 `--dry-run`。
- completed/completed_with_recovery 使用 7 天；failed/timed_out/cancelled/abandoned 使用 30 天。
- 每批最多固定数量 run_id，依赖 `ON DELETE CASCADE`，每批独立短事务。
- 清理运行中或无 finished_at 的 Run 属于错误，必须拒绝。
- 删除不会立即承诺缩小 DB 文件；WAL checkpoint/空间维护作为独立运维动作记录。

## 12. 安全、隐私与合规

### 12.1 ToolSpec 扩展

```text
ToolSpec
  name
  description
  input_schema
  output_schema
  permission
  risk_level
  execution_mode: inline | isolated_process
  loader: ImportableEntrypoint | ProcessToolProviderDescriptor
  idempotent
  supports_cancellation
  required_secret_names
  cleanup_allowed
```

- Workflow 只能引用已注册 name，不能提供 module/function。
- `isolated_process` 缺少 loader、非 JSON Schema、不可重建 Provider、不可序列化默认参数时注册失败。
- risk_level 达到 Runtime 阈值时强制 isolated_process。
- Cleanup Handler 只能选择 `cleanup_allowed=true` 且 `idempotent=true` 的 Tool。

### 12.2 Secret

- 注册时声明 `required_secret_names`。
- 父进程从 SecretProvider 读取本次所需值，通过仅本次 Worker 可见的最小输入传递。
- Trace、Context、Outcome、control_event、stdout/stderr 和 CLI 只保留 `<secret-ref:name>` 或 `<redacted>`。
- 子进程退出后关闭 IPC 并释放父进程内临时 secret 引用；不把完整环境变量复制给 Worker。

### 12.3 JSON IPC

```text
Parent request Pipe  ── UTF-8 JSON bytes ──> Worker
Parent result Pipe   <─ UTF-8 JSON bytes ─── Worker
Cancellation Event  ── boolean signal ─────> Worker
Start Gate Event    ── assigned-to-container → Worker begins Tool
```

- 请求和响应先验证 protocol_version、invocation_id、Schema 和 byte limit。
- `send/recv` 对象接口禁用；只使用 `send_bytes/recv_bytes`。
- debug stdout/stderr 有独立 byte limit，默认不捕获。
- 被强杀的通道立即丢弃，不复用 Pipe。

### 12.4 进程树

Windows：

1. 父进程创建 Job Object，设置 `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`，不允许 breakaway。
2. spawn Worker；Worker 首先等待 start gate，不执行 Tool。
3. 父进程用 `OpenProcess` + `AssignProcessToJobObject` 关联 Worker。
4. 关联成功后打开 start gate。
5. Timeout 时 `TerminateJobObject`；最终关闭 Job handle。
6. 关联失败时终止直接 Worker并返回安全错误，禁止降级执行 Tool。

Linux：

1. Worker 入口首先 `os.setsid()`，然后等待 start gate。
2. 父进程确认进程组后打开 gate。
3. Timeout 时 `os.killpg(pgid, SIGTERM)`；宽限后 `SIGKILL`。
4. `join` 并关闭全部文件描述符。

## 13. 性能、容量、可靠性与可观测性

### 13.1 固定预算

```text
nodes 200 / edges 500 / steps 1000 / loop iterations 100
LLM calls 100 / Tool calls 200
Context 10 MiB / Trace 20 MiB / duration 1800s
concurrent Runs 4
```

Context 超限立即失败；Trace 达到 20 MiB 后只保留摘要事件和 `trace_truncated=true`。

### 13.2 SQLite 写入策略

- Invocation 状态和 CAS 单条/小事务。
- Trace 按节点批量写，不和 Control CAS 共用长事务。
- Context 在已接受 State commit 后保存脱敏完整快照。
- Heartbeat 只更新 heartbeat/lease/version，不写完整 Trace；关键变化才写 control_event。

### 13.3 指标和日志

至少记录：

```text
active_runs
active_isolated_workers
sqlite_busy_retries_total
poller_lease_takeovers_total
deadline_expirations_total
worker_lost_total
stale_results_rejected_total
cleanup_deleted_runs_total
trace_truncations_total
```

第一版不设吞吐量验收目标，但行为测试不得出现无限 busy retry、僵尸进程或无界队列。

## 14. 兼容、迁移、发布与回滚

### 14.1 v1 兼容

- `Workflow` 先经 V1ToV2Compiler。
- v1 Condition 拆为条件 Edge + Default Edge。
- v1 不允许声明 skipped/Outcome 等 v2 字段。
- Compiler 在 ExecutionPlan 内增加隐藏 terminal handler；ERROR/TIMEOUT 记录脱敏事件后维持旧 fail-fast/timed_out。
- TraceRecorder 内部生成 v2；`V1TraceAdapter` 投影为当前 `TraceEvent.to_dict()` 外部结构。

### 14.2 Schema 迁移

- Migration 文件名和 version 永久不可变。
- 应用启动先校验 checksum，再运行缺失 migration。
- 数据库版本高于代码支持版本时拒绝启动。
- 第一版从无数据库升级，创建新 DB；若发现现有同名非空 DB 且无 migration 表，拒绝覆盖。

### 14.3 分阶段发布

```text
Phase 1 compile-only：新编译器/Validator，仍使用内存 Runtime
Phase 2 store shadow：SQLite 只镜像控制/Trace，不参与决策
Phase 3 control active：CAS/Poller/Heartbeat 成为状态来源
Phase 4 isolated worker：仅显式 Tool 开启
Phase 5 recovery/CLI：启用崩溃恢复和运维命令
```

每个 Phase 通过回归后再启用下一 Phase。Feature flag 只能控制新基础设施启用，不能产生两套业务语义。

### 14.4 回滚

- 代码回滚前停止新 Run，等待或取消活动 Run。
- 备份 SQLite 文件及 WAL/SHM 一致快照。
- SQL migration 不做自动 down；回滚到旧代码时恢复升级前数据库副本。
- 新 Runtime 未启用前保持现有 v1 CLI 入口可用。

## 15. 原子实施任务及依赖顺序

### TASK-20260721-012：唯一 Executor 与原子模块拆分

- 目标：把 `graph_executor.py` 拆为 ExecutionPlan、HandlerRegistry、ActivationTracker、OutcomeRouter 的薄编排架构。
- 依赖：TASK-001–004、010–011。
- 输入：现有 graph_models/analyzer/compiler/executor。
- 修改范围：新增 `execution_plan.py`、`activation.py`、`handlers/*`、`outcome.py`；缩减 graph_executor。
- 产物：v1/v2 唯一执行入口；现有成功路径行为不变。
- 不包含：SQLite、Worker、Timeout 强杀。
- 验收：Executor 不含具体 LLM/Tool/Merge/Loop 实现；原 Graph 测试通过。
- 测试：Handler 单测、ExecutionPlan 单测、v1/v2 回归。
- 风险/回滚：使用临时内部 adapter 逐 Handler 迁移；单次只迁移一个 Handler。
- 回写：更新任务状态并生成对应测试文档。

### TASK-20260721-013：Schema 标准化与输出契约

- 目标：简化类型统一为 JSON Schema，增加 output_schema/publish_mapping/动态 Map。
- 依赖：TASK-012。
- 修改范围：graph_models、schema_normalizer、workflow-v2 schema、Handler result validation。
- 产物：标准化 schema IR 与节点输出校验器。
- 不包含：完整路径数据流。
- 验收：违反 output_schema 立即契约失败，不进入 Error Edge。
- 测试：嵌套 object、array items、nullable/default、additionalProperties、非法输出。
- 风险/回滚：保留旧简化类型 parser；只替换内部表示。
- 回写：记录生成的 JSON Schema 和兼容差异。

### TASK-20260721-005 / 014：字段补齐与完整静态数据流

- 目标：计算每节点 must/may 字段集合和来源，验证所有引用路径。
- 依赖：TASK-013。
- 修改范围：新增 dataflow_validator，扩展 CompiledNode input bindings。
- 产物：DataflowReport，可指出缺失字段的节点、路径和候选来源。
- 不包含：运行时动态生成任意顶层字段。
- 验收：所有活动路径都能通过 required/default/nullable/Map 证明字段可用。
- 测试：多分支、Default、多 End、Loop 输出、Merge、缺失字段反例。
- 风险/回滚：Validator 可先 warning shadow，再切换为 error；正式启用后不得静默跳过。
- 回写：保存静态分析覆盖范围和已知限制。

### TASK-20260721-019：运行标识与 Trace v2/v1 Adapter

- 目标：引入 run_id/execution_id/invocation_id/iteration path、Trace schema version 和 v1 投影。
- 依赖：TASK-012。
- 修改范围：trace.py、ExecutionResult、CLI render。
- 产物：v2 TraceRecorder、V1TraceAdapter。
- 不包含：SQLite TraceSink，归 TASK-029。
- 验收：v1 旧字段/状态不变；v2 能区分 epoch、attempt、Loop iteration。
- 测试：golden trace、脱敏、截断、Adapter 回归。
- 风险/回滚：保留旧 `to_dict` 作为 Adapter 输出契约。
- 回写：记录 trace schema version。

### TASK-20260721-023 / 024 / 015：激活、Skipped、Join 与 Merge

- 目标：实现 inactive/skipped、输入转发、all_activated Join、Merge once。
- 依赖：TASK-012、013、014、019。
- 修改范围：activation、context、merge handler、trace、validator。
- 产物：ActivationToken、NodeInputEnvelope、JoinCoordinator。
- 不包含：节点并发等待。
- 验收：inactive 不传值；skipped 传入字段引用；Merge 省略 inactive，只读 success/skipped。
- 测试：skip_if、Handler SkipResult、Policy skip、分支汇合、缺失 default、同名 publish 冲突。
- 风险/回滚：兼容旧成功路径 token；禁止用公共 Context 猜测激活来源。
- 回写：记录每个状态的 Trace 示例。

### TASK-20260721-016 / 017：Loop Controller 与输出隔离

- 目标：按 ADR 完成 LoopReturnEvent、单层 LoopFrame、collectItem 和输出隔离。
- 依赖：TASK-015、019。
- 修改范围：loop handler、LoopRegion compiler、trace。
- 产物：LoopController、LoopFrame、LoopReturnEvent。
- 不包含：嵌套 Loop、动态 items、通用 break/continue。
- 验收：items 一次求值；所有返回原因回 Controller；外部只见 Loop 输出。
- 测试：空 items warning、分支 Merge collect、失败/取消、重复/迟到 return event。
- 风险/回滚：先保持现有简单 Loop case，通过后替换旧 `_execute_loop`。
- 回写：生成 Loop 测试文档。

### TASK-20260721-021 / 022：Outcome Edge 与预算

- 目标：实现 EdgeKind、OutcomeEvent、路由优先级、End 汇总和硬预算。
- 依赖：TASK-012、015、016、019。
- 修改范围：graph_models、compiler、analyzer、outcome、budget runtime。
- 产物：OutcomeRouter、BudgetGuard、v1 internal terminal handlers。
- 不包含：实际进程 Timeout，归 TASK-028。
- 验收：Error 多 Edge priority；Timeout 唯一；Default 不处理异常；多 End 汇总。
- 测试：路由矩阵、相同 priority 拒绝、死路、completed_with_recovery、所有预算边界。
- 风险/回滚：Outcome 路由先使用同步模拟错误注入验证。
- 回写：记录状态转换表。

### TASK-20260721-025：SQLiteRunControlStore 与 Migration

- 目标：落实本文 DDL、Store 接口、WAL、FULL、CAS 和单 Poller lease。
- 依赖：TASK-019、021。
- 修改范围：persistence/base、sqlite_store、migrations、SQL 文件。
- 产物：SQLiteRunControlStore、MigrationRunner、ConnectionFactory。
- 不包含：Poller 循环和 Worker。
- 验收：多进程 CAS 只有一个胜者；migration 可重复启动且 checksum 生效。
- 测试：真实 SQLite 集成、busy、crash transaction rollback、foreign key cascade。
- 风险/回滚：新 DB 文件；失败删除测试 DB，生产回滚恢复升级前备份。
- 回写：记录 migration version 和数据库测试结果。

### TASK-20260721-029：审计持久化与 CleanupJob

- 目标：持久化 Trace、Context、node outputs，并实施 7/30 天清理。
- 依赖：TASK-025。
- 修改范围：TraceSink、ContextSink、cleanup.py。
- 产物：批量 Trace 写入、脱敏 Context 快照、CleanupJob/dry-run。
- 不包含：Artifact Store 和原始 Secret。
- 验收：数据库可重建最终审计视图；过期终态分批删除；活动 Run 不删除。
- 测试：容量、脱敏、Trace 截断、保留边界、cascade。
- 风险/回滚：Cleanup 默认先 dry-run；启用前备份 DB。
- 回写：记录清理统计。

### TASK-20260721-026：Deadline Poller 与 Heartbeat Lease

- 目标：单活动 Poller、owner lease、Deadline 扫描、Worker lease 和取消请求。
- 依赖：TASK-025。
- 修改范围：run_manager、deadline、poller service。
- 产物：Poller、HeartbeatClient、LeaseCoordinator。
- 不包含：进程强杀。
- 验收：故障接管；deadline/lease 独立；重复扫描不重复 Outcome。
- 测试：假时钟单测 + SQLite 集成竞争测试。
- 风险/回滚：Poller 先 shadow 记录，不写终态；验证后启用 CAS。
- 回写：记录默认时间和接管结果。

### TASK-20260721-027：隔离 Tool Worker

- 目标：扩展 ToolSpec、JSON Pipe、Windows Job Object、Linux process group、终止和清理。
- 依赖：TASK-025、026。
- 修改范围：tools/spec/registry/provider、workers/*。
- 产物：ExecutableToolDescriptor、IsolatedProcessRunner、ProcessTreeController。
- 不包含：CPU/内存配额。
- 验收：阻塞 Tool 和后代进程可终止；未注册/不可序列化 Tool 注册失败。
- 测试：Windows/Linux、子进程树、IPC 超限、异常、Secret 最小化、残留句柄。
- 风险/回滚：仅显式 Tool 启用；关联进程容器失败时安全拒绝。
- 回写：生成平台兼容测试文档。

### TASK-20260721-028：Timeout Outcome 集成

- 目标：连接 Deadline、Provider timeout、Poller、Worker、Retry 和 Timeout Edge。
- 依赖：TASK-021、025–027。
- 修改范围：run_controller、tool/llm handlers、outcome。
- 产物：统一 Timeout 执行链。
- 不包含：消息队列。
- 验收：首次额外 Retry、第二次 Timeout Edge、全局 deadline 不 Retry、非幂等 Worker lost 不 Retry。
- 测试：Provider timeout、协作取消、强杀、迟到结果和无 Timeout Edge。
- 风险/回滚：按 Tool execution_mode 分批启用。
- 回写：记录 Timeout 状态矩阵。

### TASK-20260721-030：崩溃恢复与 Execution Epoch

- 目标：扫描遗留 execution、幂等门禁、新 epoch 从 Start 重跑。
- 依赖：TASK-025、027–029。
- 修改范围：run_manager、store recovery queries、idempotency protocol。
- 产物：RecoveryScanner、RestartSafetyEvaluator。
- 不包含：Checkpoint resume。
- 验收：旧 epoch 迟到结果不能污染新 epoch；不安全 Run abandoned。
- 测试：强制终止主进程、幂等/非幂等两类恢复、重复启动扫描。
- 风险/回滚：恢复功能默认 shadow 扫描，验证后开启自动重启。
- 回写：记录恢复决策证据。

### TASK-20260721-031：运行控制 CLI 与 README

- 目标：实现 runs list/show/cancel/cancel-node/retry/cleanup。
- 依赖：TASK-025、029、030。
- 修改范围：cli.py、README。
- 产物：命令、Rich 输出、退出码和示例。
- 不包含：Web UI/API。
- 验收：命令通过 Store/RunManager 服务层，不直接散落 SQL；cancel-node 支持 pending/running。
- 测试：Typer CliRunner、错误退出码、脱敏输出、dry-run。
- 风险/回滚：命令均可独立隐藏；cleanup 默认 dry-run。
- 回写：更新运行手册。

### TASK-20260721-007：安全边界终审

- 目标：完成实现后的第二轮安全审查。
- 依赖：TASK-012–031 全部适用任务。
- 修改范围：只修复审查发现，不扩展功能。
- 产物：安全审查记录。
- 验收：无任意 import、未授权 Tool、Secret 泄漏、IPC pickle、孤儿进程和危险清理。
- 测试：安全负例、权限、风险、日志、进程和文件边界。
- 风险/回滚：发现 P0/P1 时禁止发布。
- 回写：生成独立安全测试/审查文档。

## 16. 每项功能的测试范围与通过标准

| 功能 | 必选测试 | 通过标准 |
|---|---|---|
| 编译/Schema | 单元、回归、边界 | v1/v2 均生成确定 ExecutionPlan；非法配置执行前失败。 |
| Graph/数据流 | 单元、属性/组合、回归 | SCC、stable topo、字段 must/may、Merge 冲突结果确定。 |
| 激活/Skipped | 单元、集成 | inactive 无 token；skipped 转发来源；Merge once。 |
| Loop | 单元、集成、边界 | 空 items、失败、取消、重复事件符合 Controller 状态机。 |
| Outcome | 路由矩阵、异常 | Error priority、Timeout 唯一、End/死路/恢复状态正确。 |
| SQLite | 真实集成、并发、故障 | CAS 单胜者、Migration 幂等、busy 有界、故障安全失败。 |
| Poller/Lease | 假时钟、SQLite 集成 | 单活动 Poller、接管、重复扫描幂等。 |
| Worker | Windows/Linux 兼容、安全 | Worker及后代无残留，IPC/Secret/输出受限。 |
| Timeout | 集成、端到端 | 一次 Retry、第二次路由、迟到结果拒绝。 |
| 恢复 | 进程故障端到端 | 安全 Run 新 epoch；非幂等 Run abandoned。 |
| Cleanup | 集成、边界 | 7/30 天边界正确，running 不删除，dry-run 无写入。 |
| CLI | CliRunner、回归、安全 | 命令退出码稳定、输出脱敏、README 可执行。 |

全量回归必须覆盖现有 `tests/test_graph_workflow.py`、v1 sequential/retry/condition、Tool registry/provider、CLI、Level2 和 generator 测试。

## 17. 要求实现 Agent/LLM 生成的测试文档

实现 Agent 必须使用并回写 `TEST-20260721-002-Graph-Workflow稳定版测试要求.md`。规划阶段该文档状态为 pending；执行后补充实际结果并按门禁更新状态。测试文档至少包含：

- 测试目标和关联 TASK ID；
- 操作系统、Python、依赖、SQLite 版本；
- 测试前置条件和临时数据库位置；
- 测试数据与 Workflow 样例；
- 单元、集成、端到端、兼容和安全测试命令；
- 每项预期结果、实际结果和证据摘要；
- Windows/Linux 子进程树残留检查；
- SQLite busy/CAS/故障注入结果；
- v1 Trace golden 对比；
- 未通过项、风险和复测结果；
- 最终通过/不通过结论。

未执行的测试必须写“未执行”及原因，不能标记通过。实现完成但未全部验证时，任务状态只能为 pending-verification。

## 18. 风险、风险接受记录与后续观察项

| 风险 | 处理/接受方式 | 后续观察 |
|---|---|---|
| SQLite writer 争用 | 短事务、CAS、批量 Trace、3次 busy retry | busy retry 频率持续升高时评估 PostgreSQL。 |
| Windows Job 受宿主 Job 限制 | start gate；关联失败安全拒绝 | 记录宿主环境和错误码，不静默降级。 |
| Tool 声明虚假幂等 | 注册门禁、稳定 key、Provider契约测试 | 生产 Tool 需人工审查。 |
| 强杀遗留外部副作用 | 幂等/补偿；Outcome 明示 Timeout | 后续增加补偿节点设计。 |
| Context 快照扩大数据库 | 10 MiB 上限、保留期、CleanupJob | 大对象迁移到 Artifact Store。 |
| Trace v1 投影遗漏 | golden tests | 兼容层永久保留，版本化维护。 |
| 无 Checkpoint 的重跑成本 | 仅安全时从 Start 重跑 | 后续 TASK-008 研究 Checkpoint。 |
| 单 Run 同步限制吞吐 | 已接受 | 引入异步前必须完成 TASK-009 State Patch。 |

## 19. 官方资料来源与核验日期

核验日期：2026-07-21。

- [Python multiprocessing](https://docs.python.org/3/library/multiprocessing.html)：spawn、Pipe、Queue、terminate/kill 和资源清理约束。
- [Python os process management](https://docs.python.org/3/library/os.html#os.killpg)：POSIX `setsid`、进程组和 `killpg`。
- [Python sqlite3](https://docs.python.org/3/library/sqlite3.html)：连接、timeout、事务和线程约束。
- [SQLite Transactions](https://www.sqlite.org/lang_transaction.html)：单 writer 和 `BEGIN IMMEDIATE` 行为。
- [SQLite WAL](https://sqlite.org/wal.html)：WAL 并发边界和单机约束。
- [SQLite PRAGMA synchronous](https://www.sqlite.org/pragma.html#pragma_synchronous)：FULL durability 设置。
- [Microsoft Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)：进程树关联、TerminateJobObject 和 KILL_ON_JOB_CLOSE。

## 20. 实施启动门

需求和架构未决事项已经清零，本规格可直接交给实现 Agent。开始代码前，Agent 应：

1. 检查工作树并保护用户现有修改；
2. 从 TASK-012 开始，不并行修改同一核心文件；
3. 每个原子任务先补测试，再实现，再执行该任务测试；
4. 每个 Phase 后运行相关回归；
5. 完成正确性审查和安全审查；
6. 回写 TASK、测试文档、README 和项目进度；
7. 未通过全部门禁前不得宣称 Graph Workflow 稳定版完成。
