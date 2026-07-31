---
id: ADR-20260721-003
title: Outcome Edge 与 Timeout 模型
type: architecture-decision
status: completed
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 5
tags:
  - graph-workflow
  - edge
  - error-handling
  - timeout
---

# Outcome Edge 与 Timeout 模型

## 1. 背景与目标

Graph Workflow 当前只有节点成功后的普通 Edge 路由；节点最终失败会立即终止 Workflow，节点超时也没有真正的中断能力。新增需求要求补充：

- Error Edge：节点执行失败后的显式路由。
- Timeout Edge：节点超过执行期限后的显式路由。
- End Edge：活动路径正常结束的显式路由。

该变更会影响 NodeStatus、Retry 顺序、Edge IR、执行结果、Trace、Loop Controller 和 Timeout 基础设施，属于核心执行语义变更。

## 2. 现状诊断

### 已证实事实

- 当前 NodeStatus 只有 `pending / active / skipped / running / success / failed`。
- 当前 TraceStatus 只有 `success / failed / skipped`。
- 当前节点失败在 Retry 耗尽后直接抛出异常，不能路由到错误处理节点。
- 当前 Timeout 在 LLM/Tool 调用返回后比较耗时，无法中断仍在阻塞的调用。
- DeepSeek Provider 已具有底层 HTTP 请求超时，但该能力尚未成为统一 Provider 协议。
- 当前没有数据库 Run Store、Heartbeat、后台 Poller、消息队列或隔离 Worker。

### 根因

现有 Executor 按“节点成功后选择后继、失败立即终止”的单一结果模型实现，没有把节点执行结果编译为可路由的 Outcome Event。

### 已确认增量需求

- Error/Timeout Edge 成功处理并到达 End 后，Workflow 最终状态为 `completed_with_recovery`。
- Timeout 首次发生后使用源节点 RetryPolicy 重试一次，再次 Timeout 时产生 TimeoutError 并激活 Timeout Edge；不新增 RetryNode，不回退 Error Edge。
- Timeout 基础设施采用完整方案：Timer/Watchdog、CancellationToken、Provider Timeout、RunControlStore、数据库 Poller、Heartbeat/Lease 和独立 Worker Process。

## 3. Skipped 的控制流与数据流

状态语义已由用户修正：条件未命中、没有任何活动入边到达的节点是 `inactive`；`skipped` 表示节点已经激活，但明确选择不执行业务动作，并需要继续传递活动控制和上游信息。

`inactive`：

```text
节点从未被激活
→ 节点没有执行
→ 节点没有产生本次运行的新输出
→ 节点不能继续激活自己的后继
```

`skipped`：

```text
节点已激活
→ SkipPolicy 决定不调用业务 Handler
→ 状态 skipped
→ 不产生业务副作用
→ 传播活动控制 token
→ 传递上游数据或显式 passthrough outputs
```

- skipped 的出边按成功型路由参与 FLOW/DEFAULT/END 选择，但 Trace 保留 skipped 状态。
- global_state 和已存在的上游 node_outputs 不会因 skipped 被删除。
- skipped 默认把“本次激活时由上游传入该节点的全部字段引用”继续转发，但不复制整个公共 Context，也不伪造新的业务值。
- skipped 默认不创建自己的 `node_outputs`；当节点显式声明 publish/passthrough mapping 时，才允许把转发值登记为本节点输出。
- 结构前驱为 inactive 时不属于 Merge 的有效运行时来源。Merge 可以静态声明候选来源，但运行时只读取实际到达的 success/skipped token；inactive 候选被省略。
- Merge 的有效运行时来源状态仍只能是 success 或 skipped。

## 4. Outcome Edge 模型

### 4.1 NodeStatus

```text
pending
active
inactive
skipped
running
success
failed
timed_out
cancelled
```

`End` 是活动路径结果，不是普通业务节点的运行状态。路径必须通过 End Edge 到达 EndNode。

### 4.2 EdgeKind

```text
FLOW           节点成功后的普通路由
DEFAULT        没有普通成功 Edge 命中时的后备路由
ERROR          Retry 耗尽后的失败路由
TIMEOUT        Retry/Deadline 判定为超时后的路由
END            成功活动路径 → EndNode
LOOP_ENTER
LOOP_BODY_START
LOOP_RETURN
LOOP_EXIT
```

外部 YAML 可以继续使用统一 Edge 对象，内部标准化为带稳定 `edge_id` 和 `kind` 的 Control Edge。

```text
CompiledEdge
  edge_id
  source
  target
  kind
  condition?
  default?
  metadata
```

### 4.3 Default Edge（已确认）

- 只参与节点 success outcome 的路由。
- 先计算同一来源所有非 default FLOW Edge，并激活全部命中项。
- 零条 FLOW Edge 命中时，激活唯一 DEFAULT Edge。
- 每个来源最多一个 DEFAULT Edge。
- DEFAULT 不允许带 condition。
- DEFAULT 不是 Error/Timeout 的后备，也不是每个 Condition 的独立 else。
- 存在无条件 FLOW Edge 时，DEFAULT 永远不可达，Validator 必须拒绝。

### 4.4 Error Edge

- 仅在节点 RetryPolicy 已耗尽且结果为非 Timeout 失败时参与路由。
- Error Edge 的目标节点在外层 DAG 中正常参与拓扑排序。
- 没有可用 Error Edge 时保持 fail-fast。
- Error Handler 通过只读 `OutcomeEvent` 获取脱敏后的错误信息，不直接读取异常对象。
- 同一来源允许多条 Error Edge；priority 数字越小越先，选择第一条条件成立的 Edge；相同 priority 在静态校验时拒绝。

### 4.5 Timeout Edge

- 节点达到 Deadline 后生成 `TIMED_OUT` outcome。
- Timeout Edge 与 Error Edge 分开建模，便于采用不同的恢复、告警和补偿流程。
- Timeout 不回退到通用 Error Edge。第一次 Timeout 后使用源节点现有 RetryPolicy 重试一次；第二次 Timeout 生成 TimeoutError、状态 timed_out 并激活 Timeout Edge。没有 Timeout Edge 时终止 Workflow。
- 超时检测和底层调用终止是两个独立能力；Edge 路由不得宣称已经终止仍在运行的函数。
- 同一来源最多一条 Timeout Edge，Validator 必须强制该限制。

### 4.6 End Edge

- End Edge 只允许从 success 状态的节点指向 EndNode。
- EndNode 不允许任何出边。
- 活动 End Edge 代表一条活动路径正常结束。
- 至少一个 EndNode 被活动 End Edge 到达，Workflow 才能正常完成。
- 多个 End 同时激活时，运行结果汇总全部活动 End。
- v1/v2 中现有“普通 Edge 指向 EndNode”的配置由编译器自动标准化为 END Edge，保持永久兼容。

## 5. OutcomeEvent

```text
OutcomeEvent
  schema_version
  event_id
  run_id
  step_id
  node_invocation_id
  source_node_id
  source_edge_id?
  outcome
    SUCCESS
    ERROR
    TIMEOUT
    CANCELLED
  attempt
  occurred_at
  elapsed_seconds
  deadline_at?
  error_code?
  error_type?
  safe_error_message?
  retryable
  handled
```

OutcomeEvent 是 Edge Router 的输入。错误消息必须经过脱敏；原始异常和敏感 Provider 响应不能进入公共 Context。

Outcome Edge 的 condition 只允许读取 OutcomeEvent 的结构化安全字段；OutcomeEvent 本身不写入公共 State。

Error/Timeout 被显式处理并最终到达 End 时，Workflow 最终状态为 `completed_with_recovery`；运行结果必须汇总被处理的 OutcomeEvent，不能伪装成没有异常的普通 success。

## 6. Retry 与 Outcome Edge 顺序

```text
执行 Node
  ↓
成功 → FLOW / DEFAULT / END
  ↓
失败或超时
  ↓
检查 RetryPolicy、幂等性、调用预算和剩余总时长
  ↓
允许重试 → 新 attempt
不允许重试 → ERROR / TIMEOUT Edge
没有处理 Edge → fail-fast
```

失败 attempt 不提交公共 State。每次 attempt 具有独立 node_invocation_id；旧 attempt 的迟到结果不得覆盖已接受结果。

用户已确认总体顺序为“先 Retry，Retry 耗尽后再进行 Outcome 路由；全局 Deadline 耗尽时不再 Retry”。Timeout 的特殊规则是首次 Timeout 使用源节点 RetryPolicy 重试一次，第二次生成 TimeoutError 并激活 Timeout Edge，不新增显式 RetryNode。

Timeout Retry 与普通 `RetryPolicy.max_attempts` 独立：只要全局 Deadline、调用预算和幂等要求允许，第一次 Timeout 固定额外重试一次。Heartbeat lost 按 Timeout 处理；只有声明 idempotent 的 Tool 重试，非幂等 Tool 直接进入 Timeout Edge。

Loop Body 内部节点失败或超时时，必须先生成 LoopReturnEvent 返回 Loop Controller。只有 Controller 完成当前轮 Retry/失败决策后，才能把整个 Composite Loop 的 ERROR/TIMEOUT outcome 路由到外部 DAG；内部节点不能直接跳出 SCC。

## 7. Timeout 候选技术路线

### 7.1 系统 Timer / Watchdog

```text
monotonic deadline + Timer/Watchdog + CancellationToken
```

- 优点：轻量、无数据库、适合当前单进程同步 Runtime。
- 限制：Timer 只能设置取消信号，不能安全杀死任意 Python 线程。
- 适用：Provider 和 Tool 支持协作式取消，或只需要及时检测超时。

### 7.2 Heartbeat / Lease

```text
Worker 定期 heartbeat
Monitor 检查 lease_expires_at
失联后标记 timed_out / lost
```

- 优点：可以识别进程卡死、Worker 失联，适合未来多 Worker。
- 限制：需要并行监控者和共享 Run State；同样不能杀死当前进程内的阻塞线程。
- 适用：独立 Worker、消息队列或后台任务架构。

### 7.3 数据库定时轮询

```text
RunControlStore + deadline_at + Poller + cancel_requested
```

- 优点：状态持久、跨进程可见，容易衔接未来消息队列。
- 限制：引入数据库、轮询延迟和状态一致性问题；只能发出取消请求，不能单独保证终止函数。
- 适用：需要跨进程协调、恢复或运维查询的运行系统。

### 7.4 Provider 原生 Timeout

- HTTP/LLM/MCP 客户端直接接收剩余 Deadline。
- 优点：能在 I/O 层真正停止等待，成本最低。
- 限制：只能覆盖支持 Timeout 的 Provider，不能覆盖任意本地 Tool。

### 7.5 独立进程隔离

- 每次危险或不可取消的 Tool 调用放入独立 Worker Process。
- Deadline 到达后终止进程。
- 优点：能够强制结束任意本地阻塞代码。
- 限制：进程开销、序列化、资源清理和副作用一致性复杂。

### 7.6 可组合路线

```text
方案 A：轻量同步
  monotonic deadline
  + Provider 原生 timeout
  + Timer/Watchdog CancellationToken

方案 B：可持久协调
  方案 A
  + RunControlStore
  + 数据库 Poller
  + Worker Heartbeat/Lease

方案 C：强制终止
  方案 B
  + 独立 Worker Process
```

数据库轮询、Heartbeat 和 Timer 不是互斥方案：Timer 负责当前进程的低延迟提醒，Heartbeat 负责 Worker 存活检测，数据库负责跨进程状态协调，Provider timeout 或独立进程负责实际终止等待。

### 7.7 A 与选择性 C 同步实现

A 与独立进程隔离可以组合，而不强制同时引入数据库和 Heartbeat：

```text
统一 DeadlineController
  ├─ LLM / HTTP / MCP → Provider 原生 timeout + CancellationToken
  ├─ 可协作本地 Tool → inline + CancellationToken
  └─ 不可取消或高风险 Tool → isolated process + deadline terminate
```

这条路线能够同时覆盖低成本 I/O Timeout 和本地 Tool 强制终止，但当前项目存在明确改造点：

- ToolRegistry 当前保存任意内存 Python callable，没有可在新进程中重建的执行描述。
- Windows 子进程使用 spawn，闭包、lambda、绑定客户端/连接的 callable 不保证可序列化。
- 需要为 ToolSpec 增加 `execution_mode`、可导入 entrypoint 或独立 ProcessToolProvider。
- Tool 输入输出需要满足可序列化 Schema；子进程异常、stdout/stderr 和 Trace 需要安全回传。
- Deadline 后需要 terminate、join 和僵尸进程清理。
- 强制杀死进程不能回滚已经发生的外部副作用，写入 Tool 仍需要幂等键或补偿策略。

复杂度判断：

```text
A：中等
  统一 Deadline、CancellationToken、Provider 协议和 Outcome 路由

选择性 C：高
  ToolRegistry 执行描述、Windows spawn、IPC、序列化、终止和资源清理

A + 选择性 C：高
  可以原子拆分实施，但不应作为一个不可分割的大提交
```

合理的原子顺序是先完成 A 和统一 DeadlineController，再增加只对声明 `execution_mode: isolated_process` 的 Tool 启用的 C；未声明隔离模式的现有 Tool 保持兼容。

### 7.8 最终选择：完整运行控制方案

用户已选择完整方案，包括 RunControlStore、数据库 Poller、Heartbeat/Lease 和隔离 Worker。具体系统边界、数据模型、CAS、Worker 安全和原子任务见 ADR-20260721-004。

## 8. 已确认预算与超限策略

宿主 Runtime 提供不可突破的硬上限，Workflow 只能声明更小值：

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

- Context 超限立即失败，禁止截断业务状态后继续。
- Trace 超限停止追加详细事件，保留摘要并记录 warning。
- 预算在 Execution Plan 编译时检查，并由 Runtime 在每次 Node、Retry、Loop 迭代、Provider 调用和 State 提交前复检。

## 9. 静态校验规则

- Edge ID 唯一，来源和目标存在。
- ERROR、TIMEOUT Edge 不能从 Start/End 发出。
- END Edge 的目标必须是 EndNode，EndNode 不允许出边。
- DEFAULT 只属于 success 路由组。
- Outcome Edge 目标必须在外层拓扑或合法 Loop 区域内可达。
- Loop Body 的错误和超时不能通过普通 Edge 直接跳到 SCC 外部。
- Error/Timeout Handler 引用的 Outcome 字段必须通过 Schema 校验。
- AI 生成 Graph 的 Outcome Edge 暂不进入本轮范围。
- v1 格式禁止声明 skipped、Error、Timeout 等 v2 专属语义。
- output_schema 违反属于 Workflow 定义或 Handler 契约错误，立即终止，不进入 Error Edge。
- Error/Timeout Handler 自身失败时立即终止，不递归触发 Outcome Edge。
- Cancel Edge 只能进入受限 Cleanup Handler；仅允许宿主白名单幂等 Tool，禁止 LLM/Loop/Subflow，硬时限 30s，失败保持 cancelled 并附 cleanup_errors。

## 10. 原子实施方向

- 增加 Edge ID、EdgeKind、OutcomeEvent 和 timed_out/cancelled 状态。
- 从 GraphExecutor 拆出 OutcomeRouter、DeadlineController 和 CancellationToken。
- 把现有指向 EndNode 的 Edge 编译为 END Edge。
- 实现 Retry 耗尽后的 ERROR/TIMEOUT 路由。
- 将 Provider 原生 timeout 纳入统一 LLM/Tool 调用协议。
- 实现预算硬上限、Context 失败和 Trace 摘要截断。
- 根据最终 Timeout 技术选型实现 Timer、RunControlStore、Heartbeat 或 Worker 隔离。

## 11. 测试范围与通过标准

实现 Agent/LLM 必须覆盖：

- Success FLOW、DEFAULT、ERROR、TIMEOUT、END Edge 的路由和互斥范围。
- Retry 成功、Retry 耗尽后错误路由、失败 attempt 不提交 State。
- 没有处理 Edge 时保持 fail-fast。
- 至少一个 End 到达、多 End 汇总和活动死路失败。
- Timeout 检测与实际取消能力按所选技术路线分别验证。
- 重复/迟到 OutcomeEvent 不得覆盖已接受结果。
- Context/Trace/调用次数/总时长等所有预算边界。
- v1 指向 EndNode 的 Edge 兼容转换。

## 12. 决策状态

Q1–Q19 已于 2026-07-21 全部选择 A，本 ADR 未决事项清零并完成定稿。v1 自动 terminal handler 保持 fail-fast/timed_out；Cancel Cleanup 仅允许白名单幂等 Tool，硬时限 30s，失败仍保持 cancelled 并附 cleanup_errors。详细实现规格使用 ADR-20260721-006。
