# Level 1 完整总结

## 当前结论

Level 1 的目标是执行人工定义的 Workflow。

当前项目已经可以完整跑通：

```text
workflow.yaml / workflow.json
  ↓
WorkflowLoader
  ↓
WorkflowValidator
  ↓
SequentialWorkflowExecutor
  ↓
MockLLM / ToolRegistry
  ↓
WorkflowContext
  ↓
TraceRecorder
  ↓
final_output
```

当前 Level 1 Demo 已完成并可运行：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.yaml
```

注意：`LoopNode` 已有模型和 Validator 基础边校验，但执行语义暂缓实现，已放入 `docs/TODO.md`。

## 已实现功能

### 1. Workflow 固定格式

核心模型位于：

```text
mini_agent_flow/engine/models.py
```

当前支持顶层字段：

```yaml
version: "1.0"
name: research_summarizer
description: ...
inputs:
  goal: ...
outputs:
  - final_answer
metadata: {}
nodes: []
```

当前支持节点类型：

```text
start
end
llm
tool
condition
loop
```

其中：

```text
start / end / llm / tool / condition 已支持执行。
loop 暂未支持执行。
```

### 2. Workflow Loader

核心文件：

```text
mini_agent_flow/engine/loader.py
```

已实现：

```text
WorkflowLoader
JsonWorkflowLoader
YamlWorkflowLoader
WorkflowLoadError
```

能力：

```text
1. 支持加载 .json。
2. 支持加载 .yaml。
3. 支持加载 .yml。
4. 根据文件后缀自动分发。
5. 支持 load_data(dict)，方便后续 AI 生成 workflow 后复用。
6. 加载后统一交给 WorkflowValidator 校验。
```

### 3. Workflow Validator

核心文件：

```text
mini_agent_flow/engine/validator.py
```

已实现校验：

```text
1. workflow 必须有且只有一个 start。
2. workflow 至少有一个 end。
3. node id 必须唯一。
4. 所有 next / if_true / if_false / loop body 引用必须存在。
5. 所有节点必须从 start 可达。
6. start 至少有一条路径可以到达 end。
7. tool 节点只能引用 allowed_tools 白名单中的工具。
8. outputs 不允许重复。
9. outputs 必须来自 workflow.inputs 或 LLM/Tool 节点 output。
```

Validator 的作用是执行前拒绝结构不清晰或不安全的 workflow。

### 4. Workflow Context

核心文件：

```text
mini_agent_flow/engine/context.py
```

Context 是运行时共享变量表。

已支持：

```text
get(key)
set(key, value)
has(key)
require(key)
update(values)
to_dict()
snapshot()
```

节点之间不直接传参，而是通过 context 传递数据：

```text
LLMNode 写入 keywords
ToolNode 读取 keywords，写入 search_results
LLMNode 读取 search_results，写入 final_answer
```

### 5. Variable Resolver

核心文件：

```text
mini_agent_flow/engine/resolver.py
```

已支持：

```text
1. 解析 {{ key }}。
2. 完整变量引用返回原始值。
3. 嵌入字符串模板时转成字符串。
4. list / dict 递归解析。
5. 缺失变量显式失败。
```

示例：

```yaml
input: "{{ keywords }}"
```

会保留 `keywords` 的原始 list 类型。

```yaml
prompt: "请总结这些资料：{{ search_results }}"
```

会渲染成字符串 prompt。

### 6. Tool Registry

核心文件：

```text
mini_agent_flow/tools/registry.py
mini_agent_flow/tools/builtin.py
```

已实现：

```text
ToolRegistry
ToolCallable
register()
register_many()
get()
names()
create_default_tool_registry()
```

当前内置工具：

```text
echo
mock_search
```

工具调用仍受 Validator 的 `allowed_tools` 限制。

### 7. Mock LLM

核心文件：

```text
mini_agent_flow/llm/base.py
mini_agent_flow/llm/mock.py
```

已实现：

```text
LLMClient Protocol
MockLLM
```

MockLLM 用于稳定测试和演示，不依赖真实模型 API。

当前示例中：

```text
生成关键词
总结搜索结果
```

都由 MockLLM 模拟完成。

### 8. Sequential Executor

核心文件：

```text
mini_agent_flow/engine/executor.py
```

已支持执行：

```text
start
llm
tool
condition
end
```

执行器职责：

```text
1. 从 start 节点开始。
2. 按 next / condition 分支移动。
3. 渲染 prompt。
4. 调用 MockLLM。
5. 调用 ToolRegistry 中的工具。
6. 写入 WorkflowContext。
7. 记录 Trace。
8. 到 end 后读取 workflow.outputs，生成 final_output。
```

### 9. Condition Node

已支持安全条件分支。

示例：

```yaml
type: condition
expression: "{{ flag }}"
if_true: success_end
if_false: failed_end
```

当前 Condition 不执行 `eval`，只解析 context 变量并做安全 truthy 判断。

### 10. Retry

已支持节点级 retry。

当前支持：

```text
llm
tool
```

示例：

```yaml
retry:
  max_attempts: 3
  backoff_seconds: 0.1
```

失败时每次 attempt 都会记录 trace。

### 11. Trace Recorder

核心文件：

```text
mini_agent_flow/engine/trace.py
```

Trace 记录：

```text
node_id
node_type
status
input
output
error
context_before_keys
context_after_keys
context_diff
started_at
ended_at
duration_ms
attempt
```

Trace 不保存完整 context 快照，避免大对象重复占用内存。

敏感字段会脱敏，例如：

```text
api_key
token
secret
password
authorization
```

### 12. Final Output

最终输出通过 workflow 顶层 `outputs` 声明。

示例：

```yaml
outputs:
  - final_answer
```

规则：

```text
不声明 outputs：final_output = None
声明一个字段：final_output 返回该字段原始值
声明多个字段：final_output 返回 dict
声明字段运行时缺失：end 节点失败并记录 failed trace
```

这样 `end` 节点只负责结束控制流，不承担数据出口职责。

## Level 1 示例调用流程

示例文件：

```text
examples/level1_manual_workflow.yaml
```

流程：

```text
start
  ↓
plan(llm)
  ↓
search(tool)
  ↓
summarize(llm)
  ↓
end
```

详细执行：

```text
1. WorkflowLoader 读取 YAML。
2. WorkflowValidator 校验结构、边、可达性和工具白名单。
3. WorkflowContext 从 inputs 初始化 goal。
4. start 节点跳转到 plan。
5. plan 渲染 prompt，并调用 MockLLM 生成 keywords。
6. keywords 写入 context。
7. search 读取 keywords，调用 mock_search。
8. search_results 写入 context。
9. summarize 读取 search_results，调用 MockLLM 生成 final_answer。
10. end 读取 workflow.outputs。
11. final_output 返回 context["final_answer"]。
12. Executor 返回 WorkflowRunResult。
```

返回结果结构：

```text
workflow_name
context
final_output
executed_nodes
trace
```

## 开发流程回顾

Level 1 是按确定性引擎逐步构建的：

```text
1. 定义 Workflow JSON Schema 和 Pydantic 模型。
2. 实现 JsonWorkflowLoader。
3. 实现 WorkflowContext。
4. 实现 VariableResolver。
5. 实现 ToolRegistry 和内置 Mock Tool。
6. 实现 MockLLM。
7. 实现 SequentialWorkflowExecutor。
8. 使用 Mock Tool 测试完整链路。
9. 增加 YamlWorkflowLoader。
10. 增加 Fake MCP Tool Provider 预留外部工具来源。
11. 增加 TraceRecorder。
12. 增加 Retry。
13. 增加 ConditionNode。
14. 统一 WorkflowLoader。
15. 增加顶层 outputs 最终输出机制。
16. 增加 CLI run 入口。
```

每一步都遵循：

```text
先给方案
用户确认
实现
测试
两次自我审查
更新 docs/flow.md
按需 git 提交
```

## 当前测试状态

当前已验证：

```text
全量测试：132 passed
CLI Level 1 YAML 示例：跑通
CLI Level 1 JSON 示例：跑通
```

## 尚未完成或暂缓

```text
1. LoopNode 执行语义暂缓。
2. 真实 LLM Client 暂未接入。
3. Level 2 Template Selector 尚未实现。
4. Level 3 Goal -> Workflow Generator 尚未实现。
5. 错误模型可以后续轻量增强。
6. Context Store / Artifact Store 暂未实现。
```

## 面试讲解重点

这个 Level 1 可以讲清楚：

```text
1. Workflow Engine 如何从配置驱动执行。
2. Loader / Validator / Executor 的职责分离。
3. Context 为什么是 key-value。
4. Tool Calling 如何通过 Registry 控制。
5. Prompt Template 如何从 Context 取值。
6. Trace 如何辅助调试 Agent Workflow。
7. Retry 如何处理节点失败。
8. Condition 如何做安全分支。
9. outputs 如何避免 end 节点职责膨胀。
10. 为什么先做确定性 Workflow，再做 AI Planner。
```
