---
id: ADR-20260721-001
title: Graph Workflow 图工作流引擎
type: architecture-decision
status: in-progress
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 8
tags:
  - graph-workflow
  - architecture
  - networkx
  - loop
---

# Graph Workflow 图工作流引擎

## 1. 背景与目标

当前 `SequentialWorkflowExecutor` 使用单一 `current_node_id` 顺序推进，已经支持线性节点和简单条件分支，但尚不能系统表达图分析、汇合、受控循环和复合节点。

本阶段目标是在保留 Level 1 确定性执行原则的基础上，引入 Graph Workflow：

```text
Workflow 定义
  ↓
Graph Compiler
  ↓
NetworkX Graph Analyzer
  ↓
拓扑排序 / 强连通分量分析
  ↓
同步顺序 Graph Executor
```

## 2. 已确认范围

- 新增使用显式 `edges` 的 Workflow v2。
- Workflow v1 继续兼容，由 `V1ToV2Compiler` 转换成内部 v2 Graph IR；新功能和新示例只使用 v2。
- 使用 NetworkX 辅助完成拓扑排序、可达性、环检测和强连通分量分析。
- 当前只做同步执行，由拓扑排序保证普通节点的执行顺序。
- 当前不实现异步调度、并发执行和并发 Join。
- Loop 不是单节点重复器，而是包含多个子任务的复合节点。
- Loop 对应受控强连通分量；通过 Tarjan 强连通分量分析完成“缩点”，在外层图中视为一个大节点。
- Loop 满足运行条件后，在复合节点内部顺序执行循环子任务。
- 当前继续使用共享 `WorkflowContext`。
- State Patch 登记为异步或并发执行前必须完成的架构改造。
- Checkpoint 与恢复暂不实现，先登记为待研究事项。
- 安全边界暂不一次性定稿，后续按照逐项澄清流程一次确认一个问题。
- Executor 拆分为 Compiler、Analyzer、Validator、Graph Executor、Loop Executor 和 Node Handler。
- Workflow v2 的条件属于 Edge，不再使用业务 `ConditionNode`；v1 Condition 节点由兼容编译器重写为条件边。
- 本轮完成边界为 Engine 稳定版：完成静态数据流、执行语义、安全预算、全局运行时限和测试，不包含 Level 3 Graph Generator、Checkpoint Runtime、异步或并发。
- `GraphWorkflowExecutor` 是唯一核心执行器；Workflow v1 永久兼容，但必须先编译为内部 v2 Execution Plan，新能力只进入 v2。
- 独立 SubflowNode 不进入本轮，稳定版完成后再详细设计。

## 3. 非目标

- 不实现异步节点执行。
- 不实现多个就绪节点并发运行。
- 不实现分布式任务队列。
- 不实现生产级故障恢复。
- 不直接使用 LangGraph 替换当前 Engine。
- 不允许未声明的普通图环绕过 Loop 规则。

## 4. 架构决策

### 4.1 NetworkX 的职责

NetworkX 只负责图结构分析：

- 构建 `DiGraph`。
- 检查节点和边引用。
- 检查从 Start 的可达性。
- 查找强连通分量。
- 识别非法普通环。
- 对 Loop 强连通分量进行缩点。
- 对缩点后的外层 DAG 生成拓扑顺序。

NetworkX 不负责：

- 执行 LLM 或 Tool。
- 修改 Context。
- 决定 Retry、Trace 和错误策略。
- 执行 Loop 内部业务语义。

这些职责仍由项目自身的 Graph Compiler、Validator 和 Executor 管理。

### 4.2 编译与执行流程

```text
Workflow v1 → V1ToV2Compiler
Workflow v2 → 显式 edges
  ↓
统一内部 v2 Graph IR
  ↓
建立 node index 与 edge index
  ↓
构建 NetworkX DiGraph
  ↓
计算强连通分量
  ↓
合法 Loop SCC → 缩为 Composite Loop Node
非法普通 SCC → 校验失败
  ↓
对缩点后的 DAG 进行 topological_sort
  ↓
Executor 按拓扑序同步执行
  ↓
遇到 Composite Loop Node 时进入其内部执行计划
```

### 4.2.1 节点状态与同步激活

每个节点具有以下最小运行状态：

```text
pending / active / inactive / skipped / running / success / failed / timed_out / cancelled
```

- 未激活节点仍保留在完整拓扑序中。
- Executor 访问未激活节点时，将其标记为 `inactive`，记录 Trace，但不执行节点逻辑，也不激活后继。
- `skipped` 表示节点已激活但明确不执行业务 Handler；它不产生副作用，继续传播活动控制和显式上游数据。
- 至少一条真正活动的入边到达节点时，该节点才进入 `active`。
- 当前只实现受限运行状态；是否升级为完整状态机等待后续项目进展。

缩点后的 DAG 使用节点 ID 作为稳定排序键，通过 NetworkX 词典序拓扑排序获得可复现的执行顺序。节点 ID 必须唯一且可稳定比较；如引入数字编号，需要采用自然排序或固定宽度，避免 `node_10` 排在 `node_2` 前。

### 4.2.2 Edge 条件与 v1 Condition 转换

Workflow v2 将路由条件放在 Edge 上，不再把 Condition 作为业务执行节点：

```yaml
edges:
  - from: validate
    to: process
    condition: "待定的安全条件结构"

  - from: validate
    to: reject
    default: true
```

v1 转换规则：

```text
P → Condition C
C.if_true → T
C.if_false → F

转换为：

P → T [condition = C.expression 为真]
P → F [default / C.expression 为假]
```

- v1 Condition 节点在内部 v2 Graph IR 中被移除。
- 如果 Condition 有多个入边，则为每个有效前驱复制对应的真/假条件边。
- 编译结果保留 `source_node_id = C.id` 等来源信息，用于错误定位、兼容测试和 Trace 展示。
- Condition 形成的环在转换后仍必须属于显式 Loop；转换不能绕过 SCC 和 Loop 边界校验。
- 条件表达式的具体安全语法尚待逐项确认。

同一来源的多条条件 Edge 允许同时命中并全部激活，以表达 Graph fan-out。该规则不代表条件天然互斥：例如 `score >= 60` 和 `score >= 90` 在 `score = 95` 时会同时成立；如果业务语义是互斥的“普通处理”和“高级处理”，Workflow 作者必须写成互斥条件，不能依赖 Edge 顺序。

Default Edge 已确认：只在同一来源没有任何非 default FLOW Edge 命中时，激活唯一 Default Edge；它不能带 Condition，不是异常处理 Edge，也不是每个条件的独立 else。存在无条件 FLOW Edge 时 Default 永远不可达，Validator 必须拒绝。

节点结果路由新增 Error Edge、Timeout Edge 和 End Edge，详细模型与 Timeout 技术路线见 ADR-20260721-003。

### 4.3 Loop 复合节点

一个合法 Loop 区域至少需要：

- 唯一 Loop 控制节点。
- 明确的数据集合 `items`。
- 每轮变量 `item_var`，可选索引变量 `index_var`。
- 明确的内部入口和内部结束边界。
- 唯一的外部入口。
- 明确的外部后继。
- `max_iterations` 上限。

外层拓扑排序只看缩点后的 Composite Loop Node。内部节点由 Loop Executor 按内部执行计划顺序运行。

第一版约束：

- Loop 内部允许多个子任务。
- Loop 内部可以继续使用 Condition 和 Retry。
- 暂不支持嵌套 Loop。
- 暂不支持并行迭代。
- 非 Loop 声明产生的强连通分量一律拒绝。
- Condition 形成的环也必须归属于显式 Loop，不能自动获得循环语义。
- 仅支持进入 Loop 时一次性求值并快照的 `foreach items`。
- 不支持 while、until、动态修改 items、continue、break、嵌套 Loop 和并行分支；这些能力登记为后续设计。

NetworkX 可以对所有非平凡 SCC 进行机械缩点，但只有包含显式 Loop 声明并通过入口、出口和边界校验的 SCC 才能成为可执行 Composite Loop。其他 SCC 即使可以缩点，也必须作为非法 Workflow 拒绝。

### 4.3.1 Loop 单层执行帧

第一版使用单层 `LoopFrame` 记录内部执行状态：

```text
LoopFrame
  loop_id
  iteration
  current_item
  internal_node_states
  internal_topological_order
  active_edges
  collected_outputs
```

Loop 内包含 Condition 时，由 `internal_node_states` 和 `active_edges` 记录本轮实际路径。第一版不实现嵌套 Loop，因此无需完整递归栈；未来支持嵌套 Loop 时再把单个执行帧升级为 `loop_stack: list[LoopFrame]`。

Loop Controller 的完整返回事件、状态机、控制 Edge 和重试/迟到事件规则见 ADR-20260721-002。该设计保留 CONTINUE、BREAK 等 arrival reason，但本轮通过 feature gate 禁止对应配置。

### 4.4 Context 与变量可用性

当前阶段由拓扑顺序保证普通节点先后关系，继续使用共享 `WorkflowContext`。

变量规则：

- 下游声明需要的变量或字段必须在执行前具有明确结构。
- 即使某个前驱分支没有产生目标变量，也必须由分支补齐、节点默认输出或编译后的补齐步骤生成对应变量/字段。
- Validator 必须检查每条可能到达下游的路径能否提供该字段。
- Workflow v2 应允许声明字段类型、nullable 和默认值。
- 未激活分支不允许由 Runtime 猜测默认值；路径既不产生字段也未声明默认值时，Validator 必须拒绝。
- `None`、空列表和空对象只有在字段 Schema 明确允许时才能作为默认值。
- 不允许因缺失变量而在运行时静默跳过节点。
- Workflow v2 新节点必须声明输出 Schema；v1 编译结果允许兼容为 `any`。
- 外部使用简化字段类型，内部标准化为 JSON Schema；Pydantic 模型是 Schema 单一事实来源，由它生成 JSON Schema。
- 静态数据流必须分析 LLM prompt、Tool input、Edge condition、Loop items、Merge input 和 Workflow outputs。
- 默认值必须通过字段 Schema；nullable、空值和缺失必须严格区分。

### 4.4.1 节点输出隔离与 Merge

不同节点产生同名字段时，不直接写入同一个公共 key，也不使用自动拼接的 `xxx_{id}` 字符串字段。内部采用结构化命名空间：

```text
global_state
node_outputs[node_id][output_name]
loop_outputs[loop_id][iteration][node_id][output_name]
```

- 普通节点先写入自己的 `node_outputs`。
- Loop 内节点写入带迭代维度的 `loop_outputs`。
- Merge 节点显式声明来源节点、来源字段、合并策略和公共输出字段。
- 只有 Merge 或明确的发布行为可以把多个节点的结果写入 `global_state`。
- `node_outputs`、`loop_outputs` 和运行控制字段属于系统保留命名空间，用户输入不得覆盖。
- Loop Body 使用每轮独立的 `current_outputs`；上一轮及失败 attempt 的中间产物不得泄漏到下一轮。
- Loop 外部只能读取 Loop Controller 发布的最终输出，不能读取内部节点产物。
- Loop 内业务分支先输出各自结果，再通过内部 Merge 生成统一 `collectItem`，Loop 只收集 `collectItem`。

该设计保留“按节点隔离写入、由 Merge 合并”的目标，同时避免动态字符串字段污染公共 Context。

### 4.5 State Patch 的延期决策

当前同步拓扑执行下，共享 Context 不存在并发写冲突，因此本阶段不改为 State Patch。

一旦引入以下任一能力，State Patch 即成为前置任务：

- 异步节点执行。
- 同层节点并发执行。
- 并发 Loop 迭代。
- 多分支同时写入同一字段。

届时节点应返回 State Patch，由 Runtime 统一校验、合并并提交，禁止节点直接并发修改共享 Context。

### 4.6 Checkpoint 与恢复

Checkpoint 当前状态为“待研究”，不进入本阶段实现范围。

后续需要理解和确认：

- Run、Step、Node Invocation 的标识方式。
- State 快照的保存边界。
- Loop 执行到中间轮次时如何恢复。
- Tool 已产生副作用后如何避免重复执行。
- 内存、SQLite 或其他存储的选择。

### 4.7 安全边界

安全设计进入 Engine 稳定版范围，宿主 Runtime 提供不可突破的硬上限，Workflow 只能声明更小预算。已确认默认值：

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

预算主题包括：

- Workflow 最大节点数。
- 最大执行步数。
- Loop 最大迭代次数。
- 最大 LLM / Tool 调用次数。
- Tool 权限与风险等级。
- Context 和 Trace 大小限制。
- AI 生成 Graph 的校验与预算限制。

Tool 必须来自 ToolRegistry；权限 allowlist 和最大风险等级由 Runtime 强制执行，Workflow 不得自行提高。CLI Demo 可使用宽松策略，AI 生成和未来生产入口默认采用严格策略。敏感字段必须在 Trace、错误和日志中脱敏。

Context 超限立即失败，禁止截断业务状态后继续。Trace 超限停止追加详细事件，保留摘要并记录 warning。所有预算必须在 Execution Plan 编译时检查，并由 Runtime 强制复检。

## 5. 数据结构方向

建议新增内部编译模型，不要求第一步立即修改外部 YAML：

```text
CompiledGraph
  node_by_id
  graph
  condensed_graph
  topological_order
  loop_regions

LoopRegion
  loop_node_id
  member_node_ids
  entry_node_id
  exit_node_id
  internal_order
  max_iterations

ExecutionState
  node_states
  active_edges
  global_state
  node_outputs
  loop_frame
```

Workflow v2 对外使用显式 `edges`。现有 Workflow v1 通过 `V1ToV2Compiler` 转换后进入同一内部 Graph IR；兼容层只维护已有能力，不承载新的 Graph 功能。

### 5.1 Executor 模块拆分

```text
SchemaNormalizer      配置解析、版本识别、Pydantic → JSON Schema 标准化
GraphCompiler         v1 / v2 → 内部 Graph IR
GraphAnalyzer         NetworkX 建图、SCC、缩点、稳定拓扑序
GraphValidator        Graph 结构和节点角色校验
LoopSCCValidator      Loop 边界和控制 Edge 校验
DataFlowValidator     全路径字段可用性和类型校验
ToolPolicyValidator   Tool Schema、权限和风险校验
ExecutionBudget       节点、Edge、调用、时长、Context 和 Trace 预算
ExecutionPlanCompiler 只为校验通过的 Workflow 生成不可变执行计划
GraphExecutor         遍历外层执行计划和管理节点状态
LoopExecutor          管理 LoopController、IterationFrame 和返回事件
EdgeRouter            Condition、default 和控制 Edge 路由
OutcomeRouter         success / error / timeout / end 结果路由
DeadlineController    Deadline、CancellationToken 和 Timeout 检测
RunControlStore       Run/Invocation/ControlEvent 持久化控制元数据
DeadlinePoller        数据库 Deadline 扫描和幂等取消请求
WorkerLeaseManager    Heartbeat、Lease 和 Worker lost 判定
IsolatedToolWorker    不可取消 Tool 的独立进程执行与强制终止
NodeHandlerRegistry   start / end / llm / tool / merge 原子 Handler
ExecutionState        节点状态、活动边、Context、Trace 和运行标识
```

固定校验流水线：

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

## 6. 异常与边界

- 普通图存在环：校验失败。
- Loop SCC 缺少唯一入口或出口：校验失败。
- Loop 内节点被外部节点直接跳入：校验失败。
- Loop 内节点直接跳到非指定外部后继：校验失败。
- Loop items 为空：不执行内部任务，直接进入外部后继。
- Loop items 为空：同时记录 warning，Loop 输出空列表。
- Loop 超过 `max_iterations`：执行失败并记录 Trace。
- 拓扑排序失败：不得进入 Executor。
- 下游所需变量在某条路径无法补齐：校验失败。
- Condition 计算缺失字段或发生类型错误：执行失败，不能当作 false 或 default 路由。
- 活动节点形成非 End 死路：执行失败。
- 至少一个活动 End 成功才算 Workflow 成功；多个 End 同时激活时，运行结果必须向用户汇总全部活动 End。
- 节点最终失败采用 fail-fast；失败 attempt 不得提交公共 State。
- 显式 Error/Timeout Edge 的恢复语义尚待确认；没有处理 Edge 时继续 fail-fast。
- Error/Timeout Edge 被处理并最终到达 End 时，Workflow 状态为 completed_with_recovery，并汇总已处理异常。
- Retry 优先于 Outcome Edge；全局 Deadline 耗尽时禁止继续 Retry。
- 有副作用 Tool 只有声明幂等能力或提供 idempotency key 时才允许自动 Retry。

## 7. 验收标准

- 现有无环 Workflow 可编译成 NetworkX DiGraph。
- 缩点后的外层图是 DAG，并能生成稳定拓扑顺序。
- 普通非法环被明确拒绝。
- 合法 Loop SCC 能被识别并缩为 Composite Loop Node。
- Executor 按拓扑顺序同步执行普通节点。
- Loop 复合节点能顺序执行多个内部子任务。
- 所有可能到达下游的路径都能提供其所需变量或字段。
- 现有 Level 1、Level 2 和 Level 3A 行为保持兼容。
- Workflow v1 永久兼容，并由唯一 Graph 核心执行器运行。
- 普通 Join 等待所有结构前驱进入终态；只要至少一个活动前驱以 success/skipped 到达就执行一次，inactive 前驱不属于有效运行时来源。
- Merge 只执行一次；其来源状态只能是 success 或 skipped。
- Loop Body 临时输出不会泄漏到外部或后续迭代。
- 至少一个活动 End 执行成功；多个活动 End 被完整汇总。

## 8. 实施任务

### TASK-20260721-001：Graph IR 与边索引

- 目标：新增显式 `edges` 的 Workflow v2，并把现有 v1 `next / if_true / if_false / body` 编译为内部 v2 Graph IR。
- 产物：Workflow v2、`V1ToV2Compiler`、Condition-to-Edge 重写、内部 Graph IR、node index、edge index。
- 不包含：异步和持久化。
- 验收：现有示例编译结果与当前执行链一致。

### TASK-20260721-002：NetworkX Graph Analyzer

- 依赖：TASK-20260721-001。
- 目标：实现可达性、SCC、非法环识别和缩点。
- 产物：`CompiledGraph` 和 `LoopRegion`。
- 验收：普通 DAG、合法 Loop、非法环均得到确定结果。

### TASK-20260721-003：同步拓扑执行器

- 依赖：TASK-20260721-002。
- 目标：按照节点 ID 稳定排序的词典序拓扑顺序同步访问节点，并仅执行状态为 `active` 的节点。
- 不包含：TaskGroup、异步、并发 Join。
- 验收：执行顺序可预测且 Trace 顺序稳定。

### TASK-20260721-011：Edge 条件路由

- 依赖：TASK-20260721-001、TASK-20260721-003。
- 目标：解析和计算 Edge 条件，只激活满足条件的后继；支持唯一默认边。
- 阻塞项：条件表达式的数据结构与允许操作符尚待确认。
- 验收：v1 Condition 转换前后的激活路径和最终结果一致。

### TASK-20260721-004：Loop Composite Executor

- 依赖：TASK-20260721-003。
- 目标：使用单层 `LoopFrame` 执行 Loop 内多节点子流程、Condition 路由并聚合每轮结果。
- 不包含：嵌套 Loop、并行迭代。
- 验收：空列表、单轮、多轮、条件分支和超限路径结果正确。

### TASK-20260721-005：变量字段补齐与路径校验

- 依赖：TASK-20260721-001。
- 目标：通过字段 Schema、显式默认值和路径分析，保证下游所需字段在所有可能路径上可用。
- 约束：Runtime 不猜测默认值；缺少生产路径和显式默认值时拒绝 Workflow。
- 验收：缺失路径在执行前被拒绝，合法补齐路径可执行。

### TASK-20260721-006：Graph Trace 扩展

- 依赖：TASK-20260721-004。
- 目标：记录 Loop 复合节点、内部节点和迭代序号。
- 验收：可以从 Trace 重建实际同步执行顺序。

### TASK-20260721-007：Graph 安全边界定稿

- 状态：待逐项确认。
- 目标：确定节点、步数、迭代、调用和资源预算。
- 阻塞原因：需要按安全主题逐项与用户确认。

### TASK-20260721-008：Checkpoint 技术预研

- 状态：待处理。
- 目标：理解状态快照、恢复和副作用去重，不进入当前实现。

### TASK-20260721-009：State Patch 并发前置改造

- 状态：延期。
- 触发条件：项目决定引入异步或并发执行。
- 目标：节点返回 Patch，Runtime 统一提交和冲突检测。

### TASK-20260721-010：结构化节点输出与 Merge

- 依赖：TASK-20260721-001、TASK-20260721-005。
- 目标：实现 `node_outputs`、`loop_outputs` 和显式 Merge 映射，避免同名字段冲突。
- 验收：多个节点可以产生同名私有输出，只有 Merge 后才生成公共业务字段。

### TASK-20260721-012：唯一核心执行器与原子模块拆分

- 目标：GraphWorkflowExecutor 成为唯一执行核心，v1 永久通过编译器兼容；按 5.1 拆分原子模块。
- 不包含：Level 3 Graph Generator 和异步并发。
- 验收：v1/v2 共享同一 Execution Plan、Handler、预算和 Runtime 复检链路。

### TASK-20260721-013：Schema 标准化与输出契约

- 目标：外部简化类型标准化为 JSON Schema，Pydantic 作为单一事实来源；v2 节点声明 output schema。
- 验收：默认值、nullable、输入和节点输出均能执行静态及运行时类型校验。

### TASK-20260721-014：完整静态字段数据流分析

- 目标：覆盖 prompt、Tool input、Edge condition、Loop items、Merge input 和 Workflow outputs 的全路径可用性。
- 验收：任一路径缺失且无显式默认值时在 Execution Plan 编译前拒绝。

### TASK-20260721-015：Join、End、死路和 Merge 状态语义

- 目标：落实普通 Join any-active、Merge 单次执行、活动死路失败、活动 End 成功和多 End 汇总。
- 验收：所有前驱状态组合均得到确定且可追踪的结果。

### TASK-20260721-016：Loop Controller 与返回事件

- 目标：实现 ADR-20260721-002 的 LoopController、IterationFrame、LoopReturnEvent 和首版 feature gate。
- 验收：正常、空 items、失败重试、重复/迟到事件和非法控制 Edge 行为确定。

### TASK-20260721-017：Loop 输出隔离与 collectItem

- 目标：每轮使用 current_outputs，业务分支经内部 Merge 生成 collectItem，对外仅发布 Loop 最终输出。
- 验收：跨轮、跨 attempt 无输出泄漏，未汇合的分支产物不能被 Loop 直接收集。

### TASK-20260721-018：运行预算、全局时限和 Timeout

- 状态：部分阻塞。
- 目标：实现宿主硬预算、Workflow 降低预算、全局 max_duration_seconds 和 Timeout 策略。
- 阻塞项：数据库轮询方案与当前同步内存 Executor 的系统边界尚待确认。

### TASK-20260721-019：运行标识与 Trace 版本

- 目标：增加 run_id、step_id、node_invocation_id、parent_loop_invocation_id 和 trace schema_version。
- 不包含：Trace 持久化和 Checkpoint Runtime。
- 验收：每次节点及 Loop 调用可唯一定位，Trace 只在内存返回并保持版本兼容。

### TASK-20260721-020：后续 Graph 能力设计

- 状态：延期。
- 范围：SubflowNode、动态 items、while/until、continue/break、嵌套 Loop、State Patch、异步和并行。
- 启动条件：Engine 稳定版完成并通过验收后逐项讨论。

### TASK-20260721-021：Outcome Edge 与结果路由

- 目标：实现 ERROR、TIMEOUT、END Edge、OutcomeEvent、timed_out/cancelled 状态和 Retry 后路由。
- 依赖：TASK-20260721-012、TASK-20260721-015。
- 验收：成功、失败、超时和正常结束均有确定的 Edge 选择、Trace 和最终状态。

### TASK-20260721-022：预算与超限强制执行

- 目标：实现已确认的节点、Edge、步数、迭代、调用、Context、Trace 和总时长硬上限。
- 验收：Workflow 只能降低宿主上限；Context 超限失败，Trace 超限保留摘要 warning。

### TASK-20260721-023：Inactive 与 Skipped 传播语义

- 目标：把未激活改为 inactive；skipped 仅表示已激活但不执行，并传播控制及显式 passthrough outputs。
- 验收：inactive 不激活后继，skipped 无副作用但能到达下游，Merge 只接受 success/skipped 有效来源。

### TASK-20260721-024：Skipped 数据映射与静态校验

- 依赖：TASK-20260721-013、TASK-20260721-014、TASK-20260721-023。
- 目标：定义 passthrough outputs、字段 Schema、缺失和 inactive 默认值规则。
- 验收：禁止复制整个 Context 或伪造业务输出，所有传播字段可静态证明。

TASK-20260721-025 至 TASK-20260721-028 的 RunControlStore、Poller/Heartbeat、隔离 Worker 和 Timeout Outcome 集成见 ADR-20260721-004。

## 9. 测试要求

实现 Agent/LLM 必须补充并执行：

- Graph IR 编译单元测试。
- NetworkX 拓扑排序和 SCC 分析测试。
- Loop 缩点和边界校验测试。
- 同步拓扑执行回归测试。
- Loop 空集合、单轮、多轮和超限测试。
- 路径变量补齐与缺失拒绝测试。
- Tool 权限、风险和调用预算安全测试。
- 现有 Level 1、Level 2、Level 3A 全量回归测试。

测试完成后必须生成独立测试文档，包含测试目标、前置条件、测试数据、执行步骤、预期结果、实际结果和通过结论。

## 10. 风险与待确认事项

- NetworkX SCC 只能识别图结构，无法自行判断某个 SCC 是否符合业务上的 Loop 语义，仍需自定义 Validator。
- 拓扑序可能不唯一，需要定义稳定排序规则，保证测试和 Trace 可复现。
- “所有字段始终存在”可能掩盖真实缺失，需要区分合法默认值与错误缺失。
- Loop 内 Condition 可能导致某些内部节点未执行，需要明确内部汇合和输出补齐规则。
- 同步超时目前不能可靠终止底层阻塞调用。
- Checkpoint、State Patch 和异步执行均已延期，不应在当前阶段宣称支持。
- Merge 来源为 skipped 时如何形成输入值仍待确认。
- 多节点发布同一公共字段的冲突规则按用户要求延后到下一轮确认；当前继续要求通过 Merge 统一处理。
- Timeout 已选择完整组合：Timer/Watchdog、CancellationToken、Provider Timeout、数据库 Poller、Heartbeat/Lease 和独立 Worker Process；任何仅设置状态或取消信号的部分仍不能单独中止不配合的进程内阻塞函数。
- 当前 ToolRegistry 保存任意内存 callable；若启用独立进程强制终止，必须扩展 ToolSpec/Provider，使 Tool 可在 Windows spawn 子进程中安全重建和调用。
- Skipped 已定义为已激活但不执行并继续传播；未激活状态改为 inactive。passthrough outputs 的默认映射范围仍待确认。

## 11. 官方资料

- NetworkX `DiGraph`：https://networkx.org/documentation/stable/reference/classes/digraph.html
- NetworkX 强连通分量：https://networkx.org/documentation/stable/reference/algorithms/generated/networkx.algorithms.components.strongly_connected_components.html
- NetworkX DAG 算法：https://networkx.org/documentation/stable/reference/algorithms/dag.html
- NetworkX 词典序拓扑排序：https://networkx.org/documentation/stable/reference/algorithms/generated/networkx.algorithms.dag.lexicographical_topological_sort.html

核验日期：2026-07-21。

## 12. 实现状态

2026-07-21 已完成 Graph 骨架；用户已选择继续完成 Engine 稳定版：

- Workflow v2 显式 Edge 模型。
- v1 `V1ToV2Compiler`，包含 Condition-to-Edge 转换。
- NetworkX SCC 分析、合法 Loop 校验、缩点和稳定词典序拓扑排序。
- 同步 Graph Executor 与节点激活状态。
- 未激活节点 inactive Trace；主动跳过节点 skipped Trace。
- 单层 LoopFrame、循环结果收集和迭代 Trace。
- 结构化 `node_outputs / loop_outputs` 与 Merge。
- Tool Schema、权限和风险的运行时复检。

延期或尚未完成：

- 完整的跨路径静态字段数据流分析，目前通过字段 Schema、显式默认值和 Merge 运行时检查覆盖核心场景。
- Graph 安全资源预算的逐项定稿。
- Checkpoint 与恢复。
- State Patch、异步和并发执行。
- 唯一核心 Executor 和原子模块拆分。
- Loop Controller 返回事件模型与跨迭代输出隔离。
- Join/End/死路完整运行语义。
- 全局运行时限、资源预算、运行标识与 Trace 版本。
- Error、Timeout、End Outcome Edge。
- Level 3 Graph Generator 明确延期到本轮完全落地后。

测试记录：`TEST-20260721-001`。
