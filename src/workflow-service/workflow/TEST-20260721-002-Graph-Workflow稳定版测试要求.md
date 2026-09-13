---
id: TEST-20260721-002
title: Graph Workflow 稳定版测试要求
type: test
status: pending-verification
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: unassigned
version: 2
tags:
  - graph-workflow
  - test-requirement
  - sqlite
  - multiprocessing
---

# Graph Workflow 稳定版测试要求

## 1. 测试目标

验证 TASK-20260721-005、007、012–019、021–031 的实现满足已确认的 Graph Workflow 稳定版架构，并保证 v1 永久兼容、安全边界和 Windows/Linux 多进程行为。

## 2. 状态门禁

- 当前状态为 pending，仅表示测试范围已规划，未表示任何测试已执行。
- 单个任务实现完成但测试未完成时，任务只能为 pending-verification。
- 全部必选测试通过、实际结果写入本文且两轮审查完成后，本文才能改为 completed。
- 任一 P0/P1 正确性或安全问题未解决时，Graph Workflow 稳定版不得标记完成。

## 3. 必须记录的环境

- 操作系统与版本：Windows、Linux 分别记录。
- Python 版本和进程启动方式。
- Pydantic、NetworkX、pytest 版本。
- SQLite runtime 版本、journal_mode、synchronous、busy_timeout。
- CPU 数、并发 Run 上限、Worker 上限。
- 测试数据库、临时目录和清理结果。

## 4. 必选测试范围

### 4.1 编译、Schema 和兼容

- v1 → v2 条件 Edge、Default Edge、End Edge 和内部 terminal handler。
- v2 edge_id、kind、priority、required 的解析和非法组合。
- 简化类型 → JSON Schema；嵌套 object、array、nullable、default、动态 Map。
- v1 TraceAdapter golden 对比；旧 CLI 和旧测试结果保持兼容。

通过标准：合法 Workflow 得到稳定 ExecutionPlan；非法配置执行前失败；v1 外部结构不变。

### 4.2 Graph、数据流和 Loop

- 可达性、Tarjan SCC、显式 Loop、缩点、stable topo。
- 字段 must/may、所有路径补齐、同名 publish 冲突和 Merge。
- Loop items 一次求值、空 items warning、单层 LoopFrame、collectItem、迟到返回事件。

通过标准：结构和字段错误具有确定错误位置；Loop 内部产物不泄漏到外部 State。

### 4.3 激活、Skipped、Join 和 Merge

- inactive Trace 且无控制/数据传播。
- skip_if、Handler SkipResult、Runtime/Policy skip reason。
- skipped 转发上游输入引用、显式 publish mapping。
- all_activated Join；Merge 只读取 success/skipped，省略 inactive，执行一次。

通过标准：条件分支所有组合均无死等、无错误字段来源、无重复 Merge。

### 4.4 Outcome、Retry、End 和 Cancel

- 多 Error Edge priority 升序、重复 priority 拒绝。
- 每节点最多一条 Timeout Edge。
- Default 不处理 Error/Timeout。
- Error/Timeout 恢复后的 completed_with_recovery、多 End 汇总和活动死路。
- pending/running 节点取消、Cleanup 白名单、30s 硬时限、cleanup_errors。

通过标准：路由矩阵全部符合状态表；Handler 失败不发生递归 Outcome。

### 4.5 SQLite 和 Migration

- 空数据库 migration、重复启动、checksum 不匹配、高版本数据库拒绝。
- WAL/FULL/foreign_keys/busy_timeout 实际值。
- 多进程独立 connection、短事务、CAS 单胜者、busy 三次有界重试。
- 外键 cascade、Trace/Context/node outputs 落库与脱敏。
- 使用真实 SQLite 故障/锁竞争，不以 Fake Store 代替关键集成测试。

通过标准：重复和迟到更新不能覆盖终态；数据库故障时停止新派发并安全失败。

### 4.6 Poller、Heartbeat 和 Deadline

- 单活动 Poller、owner lease、故障接管。
- Poll 500ms、Heartbeat 2s、Lease 6s、默认节点 60s 和全局 1800s。
- deadline 与 lease 独立；Timer/Poller/Worker 同时到期只接受一个结果。
- 全局 Deadline 耗尽不 Retry。

通过标准：重复扫描和竞态不产生重复 Retry、Edge 或终态。

### 4.7 隔离 Worker 和 IPC

- ImportableEntrypoint、ProcessToolProviderDescriptor 和 Registry 校验。
- Pipe `send_bytes/recv_bytes`、JSON Schema、payload 大小限制和协议版本。
- debug stdout/stderr 默认关闭；开启时脱敏和截断。
- Secret 最小传递；子进程不获得完整 Context/Registry/环境变量。
- 不可序列化 Tool 在注册时拒绝。

通过标准：业务对象不经 pickle；异常、取消、强杀后句柄和 Pipe 全部关闭。

### 4.8 Windows/Linux 进程树

Windows：Job Object 关联、start gate、KILL_ON_JOB_CLOSE、Tool 后代进程终止、关联失败安全拒绝。

Linux：`setsid`、start gate、SIGTERM/SIGKILL `killpg`、Tool 后代进程终止。

通过标准：测试结束后无 Worker 或后代残留；不能仅检查直接 Worker PID。

### 4.9 Timeout、幂等和恢复

- 首次 Timeout 固定额外 Retry 一次；第二次 Timeout Edge。
- Worker lost：幂等 Tool Retry，非幂等 Tool 不 Retry。
- 稳定 idempotency_key 在 Timeout Retry/crash restart 中复用。
- 主进程被强制终止后，安全 Run 新 epoch 从 Start 重跑。
- 已成功非幂等副作用 Run 进入 abandoned。
- 旧 epoch 迟到结果不能污染新 epoch。

通过标准：副作用门禁和 execution_id 隔离可由数据库证据验证。

### 4.10 Cleanup、CLI 和 README

- 7/30 天保留边界、批量删除、running 不删除、dry-run 无写入。
- runs list/show/cancel/cancel-node/retry/cleanup。
- CLI 错误退出码、脱敏、配置覆盖优先级。
- README 命令可按文档运行。

通过标准：CLI 不直接散落 SQL；输出不泄漏 Secret；清理统计准确。

### 4.11 预算和容量边界

- nodes/edges/steps/Loop/LLM/Tool/Context/Trace/duration 全部边界。
- 默认最多 4 个并发 Run，额外 Run 正确等待/拒绝，不无限创建 Worker。
- Context 超限失败；Trace 超限保留摘要和 warning。

通过标准：所有限制在编译和 Runtime 两层强制，不能由 Workflow 提高。

## 5. 必须执行的回归范围

- Graph Workflow 现有测试。
- Sequential、Condition、Retry、Context、Resolver、Trace。
- Tool Registry、Tool Provider、MCP Fake Provider。
- CLI、Level 2 selector/service、Workflow generator/corrector。

## 6. 安全测试范围

- Workflow 注入任意 Python entrypoint。
- 未注册/未授权/高风险 Tool。
- output_schema 绕过、动态顶层字段、公共 State 冲突。
- Secret 在 Trace、Context、DB、CLI、stdout/stderr 中的泄漏。
- 超大 IPC、畸形 JSON、伪造 invocation_id/protocol_version。
- Cleanup 调用非白名单或非幂等 Tool。
- 进程 breakaway、孤儿进程、残留句柄。
- 非终态/运行中 Run 被 CleanupJob 删除。

通过标准：所有攻击/误用场景安全拒绝并留下脱敏审计信息。

## 7. 实际执行记录模板

实现 Agent 执行后在本文追加：

```text
测试日期：
代码版本/提交：
环境：
执行命令：
测试总数：
通过：
失败：
跳过：
未执行：

按 TASK 的测试结果：
  TASK ID：
  测试内容：
  预期结果：
  实际结果：
  证据摘要：
  结论：通过 / 不通过 / 待验证

正确性审查结果：
安全审查结果：
遗留风险：
最终结论：
```

## 8. 关联任务

TASK-20260721-005、007、012–019、021–031。

## 9. 2026-07-21 实际执行记录

```text
测试日期：2026-07-21
代码版本/提交：当前工作树，尚未提交
环境：Windows 11 10.0.26200，Python 3.12.13，spawn Worker
依赖：Pydantic 2.13.4，NetworkX 3.6.1，pytest 9.1.1
执行命令：.\.venv\Scripts\python.exe -m pytest -q
测试总数：242
通过：242
失败：0
跳过：0
Windows：已执行
Linux：未执行；本机无 WSL，Docker daemon 不可用
```

已验证范围：

- v1→v2、稳定拓扑序、并行条件边、Default、Inactive、Skipped 和显式透传归属。
- Merge、多个 End、活动死路、Error/Timeout/Cancel Outcome、契约错误 fail-fast。
- Loop SCC、每轮输出隔离、collect、空 items warning、Return Event 去重/迟到保护。
- 固定预算、默认节点时限、Timeout 额外一次 Retry、全局 Deadline 不重试。
- SQLite WAL/FULL、Migration checksum、非托管数据库拒绝、CAS、Poller lease、Heartbeat。
- Run/Execution/Invocation、审计 Trace/Context/output、7/30 天 Cleanup dry-run。
- 新 execution epoch 恢复、非幂等恢复门禁、运行控制 CLI。
- Windows spawn、Job Object、JSON IPC、ProcessToolProvider、运行中取消与进程树终止。
- Tool 权限/风险复检、Cleanup 白名单、Secret 最小传递与错误脱敏。

第一次正确性审查结果：通过。修复了 CAS 前契约/预算验证、Timeout 最终状态、
Loop 输出泄漏、invocation Heartbeat、恢复 Deadline、显式 Loop Return Event 等问题。

第二次安全审查结果：Windows 范围通过。未发现任意 workflow import、pickle IPC、
未授权 Cleanup、Secret 明文持久化或孤儿 Worker；Loader 可重建性、stdout/stderr 隔离、
debug 上限和幂等键协议均已强制。

遗留验证：Linux process group/SIGTERM→SIGKILL 路径尚未在 Linux 主机执行。因此本文按
状态门禁保持 `pending-verification`，不能标记 `completed`。Checkpoint、State Patch、
Subflow、嵌套/动态 Loop、continue/break 和异步节点仍按已确认范围延期，不属于本轮缺陷。
