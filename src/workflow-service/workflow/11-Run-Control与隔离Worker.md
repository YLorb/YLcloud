---
id: ADR-20260721-004
title: Run Control 与隔离 Worker
type: architecture-decision
status: completed
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 4
tags:
  - graph-workflow
  - timeout
  - sqlite
  - heartbeat
  - worker
  - persistence
---

# Run Control 与隔离 Worker

## 1. 已确认范围

第一版运行环境为单机多进程，允许多个 Workflow Run 同时执行；单个 Run 内仍按稳定拓扑序同步执行节点，不开放节点并发。

完整 Timeout 基础设施为：

```text
monotonic Deadline
+ Timer / Watchdog
+ CancellationToken
+ Provider 原生 Timeout
+ SQLite RunControlStore
+ Deadline Poller
+ Worker Heartbeat / Lease
+ 独立 Tool Worker Process
```

已确认：

- 主进程崩溃后，目标行为是从 Start 重新执行 Workflow，不做中间 Checkpoint 恢复。
- 支持取消整个 Run，也支持取消指定节点。
- 数据库使用 SQLite；数据访问层第一版使用标准库 `sqlite3`，通过 Repository/Store 接口隔离。
- Schema 使用版本化 SQL Migration。
- 数据库保存运行控制元数据、完整 Trace、完整 Context 和节点输出。
- 成功记录默认保留 7 天；失败、超时记录默认保留 30 天；全部可由 Runtime 配置。
- 允许多 Run 并发，默认上限 4；每进程独立 connection，以短事务、CAS 和有限 busy retry 控制并发。
- 单机同时只有一个活动 Poller；Poller 使用 owner lease，故障后允许其他 Runtime 接管。
- 数据库不可用时停止派发新任务；正在运行的任务进入安全失败流程，不继续假装 Timeout/Lease 可靠。
- 数据库时间统一存 UTC；进程内耗时和剩余 Deadline 使用 monotonic clock。

## 2. 系统边界

```text
CLI / Host
  └─ RunManager
       ├─ GraphWorkflowExecutor（每个 Run 内同步）
       ├─ RunController
       │    ├─ DeadlineController
       │    ├─ CancellationToken
       │    └─ OutcomeRouter
       ├─ SQLiteRunControlStore
       ├─ DeadlinePoller
       └─ IsolatedProcessRunner
            └─ Tool Worker Process
```

- `GraphWorkflowExecutor` 只消费编译后的 ExecutionPlan，并保持确定性节点顺序。
- `RunManager` 控制多 Run 并发上限、生命周期和主进程恢复扫描。
- `RunController` 控制一个 Run 的 Deadline、取消、Invocation、Retry 和终态。
- `SQLiteRunControlStore` 是运行控制与审计数据的单一持久化入口。
- `DeadlinePoller` 只生成幂等控制请求，不直接执行业务 Edge。
- `IsolatedProcessRunner` 创建、监控并回收单次 Tool 调用进程。

后台 Poller、Heartbeat 和多个 Run 并发不改变“单个 Run 内节点顺序执行”的约束。

## 3. 建议模块拆分

```text
mini_agent_flow/
  engine/
    graph_executor.py          # 仅编排 ExecutionPlan
    outcome.py                 # OutcomeEvent、OutcomeRouter
    deadline.py                # 有效 Deadline 与 CancellationToken
    run_controller.py          # 单 Run 生命周期
    run_manager.py             # 多 Run 调度与恢复
    runtime_config.py          # 默认值、环境变量、YAML、CLI 合并
  persistence/
    base.py                    # RunControlStore 协议
    sqlite_store.py            # sqlite3 实现
    migrations.py              # 版本化 Migration runner
    cleanup.py                 # 保留期清理任务
  workers/
    protocol.py                # JSON IPC envelope
    isolated_process.py        # 父进程管理器
    tool_worker.py             # 可导入的 spawn 入口
    process_control.py         # terminate/join/kill/进程树控制
```

现有 `ToolRegistry`、`ToolSpec` 和 Provider 继续保留；Graph Executor 不直接包含 Tool 子进程、数据库 SQL 或 Node Handler 实现。

## 4. SQLite 实现约束

SQLite 与“单机多进程”边界一致，但必须接受“同一时刻只有一个 writer”的事实。实现要求：

- 每个进程创建自己的 SQLite connection，禁止跨进程共享 connection。
- 启动时设置 `PRAGMA journal_mode=WAL`、`PRAGMA foreign_keys=ON` 和有限 `busy_timeout`。
- 所有状态转换使用短事务；事务内禁止调用 LLM、Tool、网络或等待子进程。
- 所有终态写入使用 `status + version/attempt` 条件更新；受影响行数为零即视为 stale/duplicate。
- Heartbeat 合并更新，避免每次心跳产生独立 Trace 大写入。
- Trace、Context 和节点输出与控制状态分表；大 JSON 写入不能持有控制状态事务。
- 数据库锁超时不得无限重试；超过 Store 重试预算后进入数据库故障策略。

建议初始化：

```text
journal_mode = WAL
foreign_keys = ON
busy_timeout = Runtime 配置值
synchronous = FULL
```

官方依据：

- [Python sqlite3 文档](https://docs.python.org/3/library/sqlite3.html)
- [SQLite Write-Ahead Logging](https://sqlite.org/wal.html)

## 5. 数据模型

### 5.1 workflow_runs

```text
run_id TEXT PRIMARY KEY
workflow_id TEXT
workflow_version TEXT
status TEXT
current_execution_id TEXT?
started_at_utc TEXT
deadline_at_utc TEXT
finished_at_utc TEXT?
restart_count INTEGER
cancel_requested_at_utc TEXT?
cancel_reason TEXT?
version INTEGER
created_at_utc TEXT
updated_at_utc TEXT
```

Run 终态集合沿用已确认集合，不增加 `worker_lost`；Worker lost 是 Invocation/Outcome 原因，不是 Run 最终状态。

### 5.2 run_executions

```text
execution_id TEXT PRIMARY KEY
run_id TEXT
epoch INTEGER
status TEXT
owner_id TEXT
owner_lease_until_utc TEXT
safe_to_restart BOOLEAN
version INTEGER
started_at_utc TEXT
finished_at_utc TEXT?
UNIQUE(run_id, epoch)
```

每次首次执行或崩溃后从 Start 重跑都创建新 execution epoch；旧 epoch 的迟到结果不能进入新 epoch。

### 5.3 node_invocations

```text
invocation_id TEXT PRIMARY KEY
execution_id TEXT
node_id TEXT
iteration_path_json TEXT
attempt INTEGER
status TEXT
worker_id TEXT?
deadline_at_utc TEXT?
lease_until_utc TEXT?
heartbeat_at_utc TEXT?
cancel_requested_at_utc TEXT?
outcome_event_id TEXT?
safe_error_summary TEXT?
version INTEGER
created_at_utc TEXT
updated_at_utc TEXT
UNIQUE(execution_id, node_id, iteration_path_json, attempt)
```

### 5.4 control_events

```text
event_id TEXT PRIMARY KEY
run_id TEXT
invocation_id TEXT?
event_type TEXT
deduplication_key TEXT UNIQUE
safe_payload_json TEXT
occurred_at_utc TEXT
```

### 5.5 trace_events

```text
trace_event_id TEXT PRIMARY KEY
run_id TEXT
sequence INTEGER
node_id TEXT?
status TEXT
schema_version TEXT
payload_json TEXT
payload_truncated INTEGER
occurred_at_utc TEXT
UNIQUE(run_id, sequence)
```

### 5.6 context_snapshots 与 node_outputs

```text
context_snapshots
  snapshot_id
  run_id
  sequence
  redacted_context_json
  byte_size
  created_at_utc

node_outputs
  output_id
  run_id
  node_id
  attempt
  output_json
  byte_size
  created_at_utc
```

当前持久化数据用于审计和重启判定，不自动获得 Checkpoint 恢复语义。保存完整逻辑 Context，但 Secret/API Key 只保存引用或脱敏占位；不保存可无损恢复的原始秘密。

### 5.7 schema_migrations

```text
version INTEGER PRIMARY KEY
name TEXT
checksum TEXT
applied_at_utc TEXT
```

Migration 只能前向执行；启动时发现数据库版本高于代码支持版本时拒绝启动。每个 migration 在事务内执行并校验 checksum。

## 6. Store 接口与 CAS

```text
RunControlStore
  migrate()
  create_run(record)
  get_run(run_id)
  compare_and_set_run(run_id, expected_version, from_statuses, to_status)
  create_invocation(record)
  claim_invocation(invocation_id, worker_id, lease_until)
  heartbeat(invocation_id, worker_id, expected_version, lease_until)
  request_run_cancel(run_id, reason)
  request_node_cancel(run_id, node_id, reason)
  compare_and_set_invocation(...)
  list_expired_invocations(now_utc, limit)
  append_control_event(event)
  append_trace_batch(events)
  save_context_snapshot(snapshot)
  save_node_output(output)
  list_recoverable_runs()
  delete_expired_records(cutoff_by_status)
```

所有会触发 Retry、Timeout Edge、Error Edge、Cancel 或终态的操作必须由 CAS 决定唯一胜者。Trace 记录 CAS 胜负，但失败的 CAS 不得再次触发业务事件。

## 7. Deadline、Heartbeat 与 Timeout

有效 Deadline：

```text
effective_deadline = min(global_deadline, node_deadline, runtime_hard_deadline)
```

- 全局 Deadline 已耗尽时立即 Timeout，不再 Retry。
- Timeout 与普通 RetryPolicy 独立：第一次 Timeout 固定允许一次 Timeout Retry；第二次 Timeout 生成 TimeoutError 并路由 Timeout Edge。
- Heartbeat lost 按 Timeout 处理，不增加 `worker_lost` Run 状态；只有声明 idempotent 的 Tool 才执行一次 Timeout Retry，非幂等 Tool 直接进入 Timeout Edge。
- `deadline_at` 决定业务期限；`lease_until` 只决定 Worker 是否仍存活，Heartbeat 不能延长业务 Deadline。
- Poller、父进程 Timer 和 Worker 同时发现到期时，只允许一个 CAS 获胜。
- 数据库不可用时停止新派发；当前 Invocation 请求协作取消并安全失败。

固定默认值：节点 60s、Poll 500ms、Heartbeat 2s、Lease 6s、cancel grace 2s、DB busy timeout 5s、Store 重试 3 次。Runtime 可配置更严格值，Workflow 不能突破宿主硬上限。

## 8. Worker 进程与统一 Tool 调用类型

已选择每次隔离 Tool 调用新建一个进程，完成后销毁。满足以下任一条件时隔离：

- `ToolSpec.execution_mode == isolated_process`；
- Tool 风险等级达到 Runtime 配置阈值。

Tool 可通过两种方式描述：

```text
ImportableToolEntrypoint(module, function)
ProcessToolProvider(provider_type, provider_config_ref, tool_name)
```

注册时统一标准化为：

```text
ExecutableToolDescriptor
  tool_id
  loader_kind
  loader_payload
  input_schema
  output_schema
  permission
  risk_level
  idempotent
  required_secret_names
```

Graph Executor 最终只调用同一 `ToolInvoker.invoke(descriptor, json_input, runtime_context)` 协议，不感知 entrypoint 或 Provider 差异。

- 不可在子进程重建或输入输出不可 JSON 序列化的 Tool 在注册时拒绝。
- Workflow 配置不能提供 Python module/function 路径；只能引用宿主已注册 Tool ID。
- 子进程仅得到本次 Tool 明确需要的 secret，不获得完整环境变量、Context 或 Registry。
- 第一版限制时间和 stdout/stderr/IPC 输出大小；内存、CPU 等 OS 资源限制进入后续任务。
- stdout/stderr 仅在 Runtime debug 模式捕获，并仍需大小限制与脱敏。

## 9. IPC 协议

业务协议只允许 JSON 兼容数据：null、boolean、number、string、array、object。禁止 pickle 业务对象。

```text
ToolRequest
  protocol_version
  invocation_id
  tool_id
  input_json
  deadline_at_utc
  required_secret_names

ToolResponse
  protocol_version
  invocation_id
  status: success | error | cancelled
  output_json?
  safe_error?
  stdout_summary?
  stderr_summary?
```

每次调用使用一对单向 `multiprocessing.Pipe`，以 `send_bytes/recv_bytes` 传递 UTF-8 JSON envelope；取消使用共享 Event/独立控制通道。禁止调用会隐式 pickle 任意业务对象的接口。

## 10. 终止与清理

已确认终止顺序：

```text
set CancellationToken
→ 等待 cooperative grace period
→ terminate direct child
→ join(timeout)
→ kill direct child（仍存活时）
→ 关闭 IPC、回收句柄、落 Trace
```

Python 的 `terminate()` 不执行目标进程的 finally/exit handler，也不会自动终止其后代进程；进程正在使用 Pipe 或锁时强制终止还可能破坏通道。因此隔离 Worker 必须进入进程树容器：Windows 使用 Job Object，Linux 使用独立 process group；父进程终止整个容器并丢弃本次 IPC 通道。

官方依据：[Python multiprocessing 文档](https://docs.python.org/3/library/multiprocessing.html)。

## 11. Skipped、Inactive 与 Merge

- `inactive`：节点未激活，不执行、不传值；为每个 inactive 节点记录 Trace。
- `skipped`：节点已激活，但由 `skip_if`、Handler `SkipResult` 或 Runtime/Policy 跳过；必须记录 skip reason。
- skipped 的出边按 success outcome 计算，继续遵守显式 Graph 路由。
- skipped 默认不创建自己的业务输出，仅把上游传给它的字段继续作为 passthrough references；若节点显式配置 publish/passthrough mapping，则允许写入本节点 `node_outputs`。
- Merge 读取 skipped 来源时先使用 passthrough，缺失则使用 default，两者都不存在时失败。
- inactive 不产生运行时来源。Merge 可以静态声明候选来源，但运行时只读取实际到达的 success/skipped token；inactive 候选被省略，Join 使用 `all_activated`。

## 12. Outcome、取消与兼容

- 同一节点多条 Error Edge 按 priority 从小到大计算，第一条条件成立的 Edge 生效；相同 priority 在静态校验时拒绝。
- Timeout Edge 每节点最多一条；不参与多候选优先级竞争。
- Outcome Edge 条件只能读取结构化、脱敏的 OutcomeEvent 字段；OutcomeEvent 不进入公共 State。
- Error/Timeout Handler 只能读取 OutcomeEvent 和显式安全输入，不能读取完整 State/Context。
- Error/Timeout Handler 自身失败时立即终止 Workflow，不递归进入新的 Outcome Edge。
- End Edge 同时支持显式 `kind: end` 和“目标是 EndNode”的自动识别；若两者同时存在必须一致。
- running/pending 节点均可取消；pending 节点被调度到时直接产生 CANCELLED outcome。
- Cancel Edge 只允许进入受限 Cleanup Handler；仅能调用宿主白名单幂等 cleanup Tool，禁止 LLM/Loop/Subflow，硬时限 30s。Cleanup 失败后 Run 仍为 cancelled，并汇总 cleanup_errors。
- 节点输出违反 output_schema 视为 Workflow 定义/Handler 契约错误，立即终止，不进入 Error Edge。
- v1 格式禁止声明 skipped、Error、Timeout 等 v2 语义；Compiler 生成内部 terminal handler，记录脱敏 Outcome 后保持旧 fail-fast/timed_out 结果。
- v1 永久通过 V1ToV2Compiler 进入唯一 GraphWorkflowExecutor。

## 13. 崩溃恢复

启动时 `RunManager` 扫描 `running/cancel_requested` 且 owner lease 已过期的 Run：

```text
发现遗留 Run
→ 标记 previous execution abandoned
→ restart_count + 1
→ 创建新的 execution epoch
→ 从 Start 重新执行
```

旧 epoch 的 Invocation、Heartbeat、Outcome 和结果全部视为迟到事件，不得写入新 epoch。只有旧 epoch 中所有已成功副作用 Tool 都声明并实际使用稳定幂等键时才自动从 Start 重跑；否则标记 abandoned 并等待人工 retry。

## 14. 保留期与清理

目标保留期：

```text
success / completed_with_recovery: 7 days
failed / timed_out / cancelled:     30 days
Runtime 配置可覆盖
```

SQLite 不提供独立后台 TTL/分区调度器，因此建议实现应用级 `CleanupJob`：

```text
按终态和 finished_at_utc 分批选择过期 run_id
→ 按外键依赖顺序分批 DELETE
→ 每批短事务提交
→ WAL checkpoint / 文件空间维护按运维配置执行
```

清理任务不得与执行事务长时间争用 writer；默认在启动后低频运行，并提供 CLI 手动触发与 `--dry-run`。该应用级 CleanupJob 是 SQLite 第一版的保留期实现。

## 15. CLI 与配置

第一版增加并在 README 记录：

```text
mini-agent-flow runs list
mini-agent-flow runs show RUN_ID
mini-agent-flow runs cancel RUN_ID
mini-agent-flow runs cancel-node RUN_ID NODE_ID
mini-agent-flow runs retry RUN_ID
mini-agent-flow runs cleanup [--dry-run]
```

配置同时支持代码默认值、YAML、环境变量和 CLI 参数；覆盖优先级固定为 CLI > 环境变量 > YAML Runtime 配置 > 代码默认值。敏感信息不得出现在 YAML、CLI 输出、Trace 或 control_events。

## 16. 测试范围

第一版只以行为正确性为性能验收，不设吞吐量指标。必须覆盖：

- SQLite migration、WAL、多进程独立连接、锁超时和 CAS。
- 多 Run 同时执行；单 Run 节点仍严格按稳定拓扑序。
- Deadline、Heartbeat、Lease、Timer 竞争下仅一个终态获胜。
- Windows 与 Linux 的 spawn、JSON IPC、终止、句柄和残留进程清理。
- SQLite 故障使用真实集成测试，不仅使用 Fake Store。
- Timeout 固定重试一次、全局 Deadline 不重试、迟到结果拒绝。
- Context/Trace/NodeOutput 持久化、脱敏、10 MiB/20 MiB 限制。
- 主进程崩溃扫描、execution epoch 隔离和副作用幂等保护。
- v1 编译兼容、v1 禁止 skipped 声明、自动 Outcome Handler。
- CLI list/show/cancel/cancel-node/retry/cleanup 与 README 示例。

## 17. 原子实施任务

### TASK-20260721-025：SQLiteRunControlStore 与 Migration

- Store 协议、表结构、Migration、独立连接、WAL、CAS。
- 不包含 Poller、Worker 和 Outcome 路由。

### TASK-20260721-026：Deadline Poller 与 Heartbeat Lease

- Deadline 扫描、Heartbeat、Lease、取消请求、重复事件去重。

### TASK-20260721-027：隔离 Tool Worker

- ExecutableToolDescriptor、JSON IPC、spawn、终止、清理和 debug 输出。

### TASK-20260721-028：Timeout Outcome 集成

- Timeout → 固定一次 Retry → TimeoutError → Timeout Edge。

### TASK-20260721-029：运行审计持久化与保留期

- 完整 Trace、Context、node outputs、脱敏、容量限制和 CleanupJob。

### TASK-20260721-030：崩溃重启与 Execution Epoch

- 遗留 Run 扫描、从 Start 重启、迟到结果隔离和幂等门禁。

### TASK-20260721-031：运行控制 CLI

- list/show/cancel/cancel-node/retry/cleanup 和 README。

## 18. 决策状态

Q1–Q19 已于 2026-07-21 全部选择 A，本 ADR 未决事项清零并完成定稿。详细实现规格使用 ADR-20260721-006。
