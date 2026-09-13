---
id: ADR-20260721-002
title: Loop Controller 运行时模型
type: architecture-decision
status: completed
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 4
tags:
  - graph-workflow
  - loop
  - state-machine
  - runtime
---

# Loop Controller 运行时模型

## 1. 背景与目标

Graph Workflow 已使用显式 Loop Controller 标记合法循环 SCC，并在外部 DAG 中把整个 Loop 区域缩约为一个复合节点。本决策进一步定义每轮迭代如何统一进入 Controller、如何返回 Controller，以及如何处理正常完成、continue、break、失败、取消、重试和未来并行分支事件。

本轮实现范围仍为固定 `foreach item in resolved_items`、同步执行、单层 LoopFrame。`CONTINUE`、`BREAK` 和并行事件处理先进入数据模型与状态机设计，通过 feature gate 禁止在本轮 Workflow 配置中启用。

## 2. 已确认约束

- Loop Controller 是每轮迭代的统一入口、汇合点和调度中心。
- 每轮无论正常完成、continue、break、失败还是取消，都必须向 Controller 提交显式 `LoopReturnEvent`。
- Controller 只根据事件中的 `arrival_reason` 决策，不能从前驱节点或节点状态反推控制语义。
- 正常业务路径必须通过 `body_exit` 返回 Controller。
- 普通 Flow Edge 不得从 Loop Body 直接连接 Controller。
- Loop 内部分支先产生各自结果，经内部 Merge 统一生成 `collectItem`；Loop 只收集 `collectItem`。
- Loop 内部临时产物使用迭代私有命名空间，对外只发布 Loop Controller 的最终输出。
- 外部 Graph 只看到一个 Composite Loop Node，内部回边不破坏外部 DAG。

## 3. 节点与 Edge 模型

### 3.1 Loop Controller Node

```text
LoopControllerNode
  id
  items
  item_var
  index_var?
  body_entry
  body_exit
  collect
    source = body_exit.collectItem
    target
    output_schema
  max_iterations
  iteration_retry?
  feature_flags
    allow_continue = false
    allow_break = false
    allow_parallel_branches = false
```

`items` 在进入 Loop 时只求值一次并形成只读快照。动态追加、删除或重排任务不属于本轮范围，登记为后续能力。

### 3.2 Edge 分类

每条编译后 Edge 必须具有稳定且唯一的 `edge_id`。v1 编译器为兼容 Workflow 生成确定性 ID。

```text
FLOW
  普通节点之间的数据/控制边，不允许指向 Loop Controller

LOOP_ENTER
  外部 DAG → Loop Controller

LOOP_BODY_START
  Loop Controller → body_entry

LOOP_RETURN
  body_exit / 显式控制节点 → Loop Controller
  必须声明 arrival_reason

LOOP_EXIT
  Loop Controller → 外部 DAG 后继
```

本轮唯一允许由 Workflow 作者声明的 `LOOP_RETURN` 是：

```text
body_exit → controller [arrival_reason = BODY_COMPLETED]
```

`CONTINUE`、`BREAK` 的控制 Edge 保留在 IR 设计中，但本轮 Validator 必须拒绝。`FAILED`、`CANCELLED` 由 Runtime 为每个节点编译合成控制返回通道，不要求用户在 YAML 中逐项声明。

## 4. LoopReturnEvent

```text
LoopReturnEvent
  schema_version
  event_id
  run_id
  loop_id
  loop_invocation_id
  iteration
  attempt
  branch_id?
  source_node_id
  source_edge_id
  arrival_reason
    BODY_COMPLETED
    CONTINUE
    BREAK
    FAILED
    CANCELLED
  collect_item?
  error?
  retryable
  occurred_at
  controller_version
  deduplication_key
```

- `source_edge_id` 永远显式存在。失败或取消没有作者声明 Edge 时，Execution Plan 为节点生成稳定的合成 Return Edge ID。
- `deduplication_key` 至少由 `loop_invocation_id + iteration + attempt + branch_id + source_edge_id + arrival_reason` 构成。
- `controller_version` 用于未来持久化或并行模式下的 compare-and-set；当前同步内存执行仍保留该字段。
- `collect_item` 只能由正常汇合后的 `body_exit` 返回。Controller 不读取任意分支的中间结果作为 Loop 输出。

## 5. IterationFrame

```text
IterationFrame
  loop_invocation_id
  iteration
  attempt
  item
  index
  state
  node_states
  active_edges
  current_outputs
  expected_return_tokens
  received_event_ids
  accepted_return_events
  collect_item?
  controller_version
```

`current_outputs` 是本轮唯一可见的 Loop Body 输出命名空间。每轮开始和重试开始时创建新命名空间，禁止读取上一轮或上一次失败尝试的同名节点输出。本轮结束后只把需要审计的快照写入 Trace；公共 Context 只保留 Loop 的最终收集结果。

## 6. Loop Controller 状态机

```text
CREATED
  → INITIALIZING
  → ITERATION_READY
  → BODY_RUNNING
  → WAITING_RETURN
  → DECIDING
      → ITERATION_READY      下一轮
      → RETRY_WAIT           当前轮重试
      → COMPLETING           items 耗尽或 BREAK
      → FAILING              不可重试失败
      → CANCELLING           收到取消
  → COMPLETED | FAILED | CANCELLED
```

状态转换只能由 Controller 接受一个未重复、未迟到且与当前 `iteration/attempt` 匹配的 `LoopReturnEvent` 后触发。普通节点不得直接修改 Controller 状态。

## 7. 执行流程

### 7.1 正常完成

1. Controller 解析一次 `items` 并保存快照。
2. items 为空时记录 warning，输出空列表并进入 `COMPLETED`。
3. Controller 创建 IterationFrame，激活 `body_entry`。
4. 每个业务分支写入自己的迭代私有输出。
5. 内部 Merge 等待所有结构来源进入终态，仅把 `success` 或 `skipped` 作为有效运行时来源；`inactive` 来源不参与合并，如字段被声明引用则使用 default/optional。
6. `body_exit` 通过 `LOOP_RETURN` 发出 `BODY_COMPLETED` 事件，携带 `collectItem`。
7. Controller 校验并追加 `collectItem`，销毁当前轮临时输出。
8. 有下一项则创建下一轮，否则发布 Loop 最终输出并激活 `LOOP_EXIT`。

### 7.2 Continue（预留，首版禁用）

1. 显式 Continue 控制节点发出 `CONTINUE` 事件。
2. Controller 停止接收本轮新的业务结果，并取消仍在运行的兄弟分支。
3. 默认不收集当前轮；未来若需要收集，必须增加显式策略，不能推断。
4. Controller 等待取消确认或进入隔离状态后再开始下一轮。

### 7.3 Break（预留，首版禁用）

1. 显式 Break 控制节点发出 `BREAK` 事件。
2. Controller 取消本轮其余分支。
3. 默认不收集当前轮，保留此前已完成轮次的结果。
4. Controller 进入 `COMPLETING`，发布 Loop 最终输出并激活外部后继。

### 7.4 Failed

1. Node Handler 捕获最终失败并通过合成 Return Edge 发出 `FAILED`。
2. Controller 检查 RetryPolicy、剩余预算、Tool 幂等能力和全局截止时间。
3. 允许重试时丢弃本次 attempt 的临时输出，`attempt + 1`，重新执行同一 iteration。
4. 不允许重试时进入 `FAILED` 并按本轮 fail-fast 策略终止 Workflow。

### 7.5 Cancelled

1. Run Controller 或节点取消通道发出 `CANCELLED`。
2. Loop Controller 停止派发新任务并取消当前轮仍在运行的分支。
3. 收到取消确认或超过取消等待期限后进入 `CANCELLED`。
4. 当前不实现持久化恢复；取消结果只进入运行结果和 Trace。

## 8. 静态校验规则

- 一个循环 SCC 必须且只能包含一个 Loop Controller。
- Loop 必须具有唯一外部 `LOOP_ENTER`，至少一个 `LOOP_EXIT`。
- Controller 必须具有唯一 `LOOP_BODY_START` 并指向 `body_entry`。
- `body_exit` 必须具有唯一 `BODY_COMPLETED` Return Edge。
- 普通 FLOW Edge 不得指向 Controller。
- 删除 Controller 后，当前版本的 Loop Body 必须是 DAG。
- 所有可能正常完成的活动路径必须到达内部 Merge，再到达 `body_exit`。
- `collectItem` 必须由汇合节点生成，并通过声明的 output schema 校验。
- Loop Body 中间输出不得 publish 到公共 State。
- Loop Body 内部失败或超时必须先返回 Controller，不能通过 Error/Timeout Edge 直接跳出 SCC；Controller 完成 Retry 和本轮状态决策后，再路由 Composite Loop 的外部 Outcome Edge。
- `edge_id`、节点 ID、合成 Return Edge ID 必须在 Execution Plan 中唯一。
- 本轮拒绝 CONTINUE、BREAK、嵌套 Loop、动态 items 和并行分支配置。
- Loop 外部只允许引用 Controller 声明的最终输出。

## 9. 并行分支、重试、重复与迟到事件

并行执行当前不实现，但状态机预留以下规则：

- Controller 派发并行分支时生成 `expected_return_tokens`；普通完成必须收齐本轮所需 token 才能进入内部 Merge。
- Loop 级 CONTINUE/BREAK 只能由显式控制节点发出，普通分支完成不能隐式代表控制语义。
- 同一 `event_id` 或 `deduplication_key` 的重复事件只接受一次，其余记录为 duplicate。
- 事件的 iteration 或 attempt 小于当前值时视为 late event，只记录审计信息，不修改状态。
- 事件的 controller_version 不匹配时拒绝直接转换状态，交由 Controller 重新读取当前状态后判断。
- BREAK、不可重试 FAILED、CANCELLED 会触发兄弟分支取消；在取消完成或隔离前不得开始下一轮。
- 重试必须创建新的 attempt 和临时输出空间；旧 attempt 的迟到成功事件不得覆盖新 attempt。
- 未来并行模式下的冲突优先级暂定为 `CANCELLED > 不可重试 FAILED > BREAK > CONTINUE > BODY_COMPLETED`，正式启用并行前必须再次确认。

## 10. 原子实施方向

- 增加稳定 Edge ID 与编译后 Control Edge 分类。
- 把 Loop 执行从 GraphExecutor 拆到独立 LoopExecutor 与 LoopController。
- 增加 IterationFrame 和 LoopReturnEvent。
- 将 Loop Body 输出从全局 node_outputs 迁移到 current_outputs。
- 增加内部 Merge → collectItem → body_exit 规则。
- 增加空 items warning、事件去重和迟到事件保护。
- 首版 feature gate 明确拒绝 continue、break、嵌套、动态 items 和并行配置。

## 11. 测试范围与通过标准

实现 Agent/LLM 必须覆盖：

- 正常单轮、多轮、空 items 和 collectItem 聚合。
- 分支结果先 Merge、再由 body_exit 返回 collectItem。
- 上一轮和失败 attempt 的输出不会泄漏到当前轮。
- 普通 Edge 直连 Controller、非法 Return Edge 和缺失 Edge ID 被拒绝。
- 重复事件不重复追加结果，迟到事件不改变当前状态。
- Failed 重试清理临时输出，预算耗尽后 fail-fast。
- 本轮 continue、break、嵌套、动态 items 和并行配置被明确拒绝。
- 空 items 产生 warning，但 Loop 和 Workflow 正常完成。

实现完成后必须生成独立测试文档并回写实际结果；未执行测试前任务只能进入 pending-verification。

## 12. 已确认边界与后续事项

- skipped 默认保留上游归属并转发公共 State；显式 `self` 归属必须提供
  `skip_output_mapping`。inactive 不产生字段，只能使用 default/optional。
- Timeout 使用 ADR-20260721-004 的完整运行控制方案。
- continue、break、嵌套/动态 Loop 和并行事件冲突优先级在对应延期能力启动前重新确认。
