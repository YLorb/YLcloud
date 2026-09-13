---
id: TEST-20260721-001
title: Graph Workflow 核心实现测试记录
type: test-record
status: completed
priority: P1
created: 2026-07-21
updated: 2026-07-21
owner: codex
version: 2
tags:
  - graph-workflow
  - testing
---

# Graph Workflow 核心实现测试记录

## 测试目标

验证 Workflow v2、v1 兼容编译、图分析、同步拓扑执行、Edge 条件、Loop、Merge、Trace 和工具安全检查，并确认现有 Level 1、Level 2、Level 3A 没有回归。

## 前置条件

- Python 3.12.13（Windows CPython）虚拟环境。
- NetworkX 3.6.1。
- OpenAI SDK 2.46.0、jiter 0.16.0、tqdm 4.69.0。
- JSON Schema 4.26.0。
- 使用 MockLLM 和本地 ToolRegistry，不产生真实 LLM 请求。

## 测试内容

- v1 Condition 转换为 v2 条件 Edge。
- v1 通过 Graph Executor 兼容运行。
- 按节点 ID 的稳定自然词典序拓扑执行。
- 未激活分支保留在拓扑序并记录 skipped。
- 非显式 Loop 普通环拒绝。
- Loop SCC 缩点、单层 LoopFrame、多轮执行和结果收集。
- Loop 最大迭代数限制与失败 Trace。
- 同名节点输出隔离和 Merge。
- 未激活 Merge 输入使用显式默认值。
- v2 未知边目标拒绝。
- Tool 风险策略在运行时复检。
- 全量历史功能回归。

## 执行命令

```powershell
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe -m pytest -q --basetemp=.pytest-env-check
.\.venv\Scripts\python.exe -m compileall -q mini_agent_flow
.\.venv\Scripts\python.exe -m mini_agent_flow run examples\level_graph_workflow.yaml
.\.venv\Scripts\python.exe -m mini_agent_flow run examples\level1_manual_workflow.yaml
```

## 实际结果

```text
pip check：No broken requirements found
JSON Schema 元校验：通过
pytest：198 passed in 1.46s
compileall：通过
Graph Workflow CLI Demo：通过
Workflow v1 CLI 兼容 Demo：通过
Level 2 模板选择 Demo：通过
```

## 依赖检查

原 `.venv` 使用 MSYS/MinGW Python，无法匹配 `jiter`、`rpds-py` 的 Windows wheel，并在本地编译时发生 Python 动态库链接失败。现已保留为 `.venv-msys-backup`，使用标准 Windows CPython 3.12.13 重建 `.venv`，并通过 `pip install -e ".[dev]"` 安装完整运行依赖与开发依赖。

`pip check` 已无依赖冲突；`jiter`、`tqdm`、`jsonschema` 和 `rpds-py` 均可正常导入，Workflow v2 JSON Schema 已通过 Draft 2020-12 元校验。

## 结论

Graph Workflow 核心范围的实现和回归测试通过。功能状态为待用户验收；静态字段数据流、安全预算、Checkpoint、State Patch、异步和并发继续保留在路线图中。

## 第一次自审：正确性与完整性

检查范围：v1/v2 编译、拓扑激活、分支跳过、Merge、Loop SCC、Context、Trace、CLI 和回归测试。

审查中补充或修复：

- v1 Condition 真/假目标相同时去重为普通 Edge，避免产生平行边。
- 显式 Loop 必须实际处于循环 SCC；普通环仍拒绝。
- Loop 内部节点必须从 `body_entry` 可达，并保证 `body_exit` 可达。
- Loop collect 来源必须属于 Loop SCC。
- Merge 缺失分支只能使用显式 default，不允许系统猜测。
- Merge 和 Loop 失败均记录 failed Trace。
- Loop 内部 Trace 增加 `loop_id` 和 `iteration`。
- v1 CLI、v2 CLI、Level 2 模板选择和全量测试均通过。

结论：核心已确认需求完整实现；完整静态路径字段分析仍按路线图标记为部分完成。

## 第二次自审：安全性

检查结果：

- Edge 条件使用结构化操作符，不调用 `eval`、`exec` 或动态 import。
- Graph Workflow 不能从配置加载任意 Python callable，只能调用 ToolRegistry 已注册工具。
- Tool 的 input schema、permission 和 risk level 在实际调用前再次检查，Loop 每轮不会绕过。
- `max_steps` 限制总执行步数，`max_iterations` 限制单个 Loop 迭代数。
- 未声明普通环、非法 Loop 边界、未知节点、未知输出引用在执行前拒绝。
- Trace 继续复用敏感字段递归脱敏。
- 未新增文件删除、命令执行、Pickle 反序列化或外部网络调用能力。

遗留风险：Workflow 最大节点/边数量、Context/Trace 大小和 LLM/Tool 调用预算尚未逐项确认；同步超时仍是调用完成后的软超时，不能保证终止底层阻塞操作。
