# 开发更新记录

## 当前工作目录

```text
D:\document\new_project
```

## 已完成更新

### 1. 项目规则迁移

已将项目协作与开发规则迁移到当前项目目录：

```text
docs/AGENTS.md
```

该文档定义了项目目标、三个 Level 的演进路线、技术栈、开发顺序、最小可运行 Demo、完成标准，以及后续写代码前必须先提交实现方案、代码完成后必须进行两次自我审查的规则。

### 2. Workflow JSON Schema

已新增固定 Workflow JSON 格式：

```text
schemas/workflow.schema.json
```

Schema 使用 JSON Schema Draft 2020-12，当前支持以下节点类型：

```text
start
end
llm
tool
condition
loop
```

当前约束包括：

```text
1. 顶层必须包含 version、name、nodes
2. version 固定为 1.0
3. nodes 必须是数组，至少包含 2 个节点
4. 每个节点必须包含 id 和 type
5. node type 使用固定枚举
6. 不同节点类型有各自必填字段
7. 节点不允许任意额外字段
8. metadata 和 retry 作为受控扩展字段保留
```

### 3. Pydantic 数据模型

已新增 Workflow 数据模型：

```text
mini_agent_flow/engine/models.py
```

当前模型包括：

```text
RetryPolicy
BaseNode
StartNode
EndNode
LLMNode
ToolNode
ConditionNode
LoopNode
Workflow
```

模型负责基础结构校验，例如：

```text
1. start 节点必须存在且只能有一个
2. end 节点至少存在一个
3. node id 必须唯一
4. 各类型节点必须包含对应字段
```

### 4. Workflow Validator

已新增 Workflow 校验器：

```text
mini_agent_flow/engine/validator.py
```

当前校验能力包括：

```text
1. 读取并解析 workflow JSON 文件
2. 使用 Pydantic 模型做结构校验
3. 检查 next、if_true、if_false、loop body 是否指向存在的节点
4. 检查从 start 出发是否能到达所有节点
5. 检查是否至少存在一条从 start 到 end 的路径
6. 支持 allowed_tools 白名单校验
```

### 5. 示例 Workflow

已新增 Level 1 示例：

```text
examples/level1_manual_workflow.json
```

该示例展示了一个基础流程：

```text
start
  ↓
llm: plan
  ↓
tool: search
  ↓
llm: summarize
  ↓
end
```

### 6. 测试

已新增测试：

```text
tests/test_workflow_schema.py
```

当前测试覆盖：

```text
1. 合法 workflow 可以通过校验
2. 缺少顶层必填字段会失败
3. 重复 node id 会失败
4. 缺少 start 会失败
5. 多个 start 会失败
6. next 指向不存在节点会失败
7. llm 缺少 prompt 会失败
8. tool 缺少 tool 会失败
9. tool 缺少 input 会失败
10. 引用不支持的 tool 会失败
```

### 7. 项目配置

已新增：

```text
pyproject.toml
README.md
```

`pyproject.toml` 定义了项目依赖、开发依赖、包发现规则和 pytest 配置。

## 验证结果

已使用 bundled Python 运行：

```bash
python -m pytest -q
```

结果：

```text
10 passed
```

同时已验证：

```text
schemas/workflow.schema.json 是合法 JSON Schema
examples/level1_manual_workflow.json 符合该 schema
```

## 自我审查结果

### 第一次审查：正确性与完整性

发现并修复了一个实现偏差：

```text
tool 节点的 input 字段原本被实现为可选字段。
```

根据已确认方案，`tool` 节点必须包含：

```text
tool
input
output
next
```

现已修正模型、JSON Schema 和测试。

### 第二次审查：安全性

当前实现只进行本地 workflow JSON 读取与校验。

安全检查结论：

```text
1. 没有执行 shell 命令的业务代码
2. 没有使用 eval 或 exec
3. 没有写入用户文件的业务代码
4. 没有 API Key、token、secret 日志输出
5. tool 节点支持 allowed_tools 白名单校验
6. workflow 执行能力尚未实现，因此不存在实际 tool 调用风险
```

## 下一步建议

下一步可以实现：

```text
1. Workflow Loader
2. Context 对象
3. Trace Recorder
4. Mock LLM
5. Tool Registry
6. Level 1 Executor
```

## 追加更新：中文注释与注释规范

### 已完成内容

已为当前已有核心代码补充中文注释：

```text
mini_agent_flow/engine/models.py
mini_agent_flow/engine/validator.py
tests/test_workflow_schema.py
```

注释重点说明：

```text
1. 每个 Pydantic 模型的职责
2. 不同节点类型在 workflow 中的含义
3. Validator 与后续 Loader / Executor 的职责边界
4. 边引用校验、可达性校验、start 到 end 路径校验的目的
5. tool 白名单作为安全边界的意义
6. 每个测试用例对应的业务规则
```

### AGENTS.md 更新

已在项目规则中追加：

```text
docs/AGENTS.md
```

新增小节：

```text
代码注释要求
```

该小节要求后续核心模型、核心流程、非显然校验逻辑、安全限制和测试意图使用中文注释说明。

### 本次未做

```text
1. 未实现 JSON Workflow Loader
2. 未修改 workflow schema 格式
3. 未改变 validator 行为
4. 未新增功能逻辑
```

## 追加更新：JSON Workflow Loader

### 已完成内容

已新增 JSON Workflow Loader：

```text
mini_agent_flow/engine/loader.py
```

Loader 的职责是：

```text
1. 检查 workflow 文件路径是否存在
2. 检查路径是否是文件
3. 检查文件后缀是否为 .json
4. 读取并解析 JSON
5. 确保 JSON 顶层是 object
6. 调用 WorkflowValidator.validate_data()
7. 返回已校验的 Workflow 对象
```

### 职责边界

当前边界为：

```text
JsonWorkflowLoader：
  负责文件读取、JSON 解析、文件级错误处理。

WorkflowValidator：
  负责 workflow 结构校验、节点引用校验、可达性校验、tool 白名单校验。

Workflow：
  作为加载和校验后的标准对象，交给后续 Executor / CLI / Planner 使用。
```

### 兼容调整

已保留：

```text
WorkflowValidator.validate_file()
```

但该方法现在只作为兼容入口，内部转交给：

```text
JsonWorkflowLoader(validator=self).load(path)
```

后续新代码应优先直接使用 `JsonWorkflowLoader`。

### 新增测试

已新增：

```text
tests/test_workflow_loader.py
```

覆盖场景：

```text
1. 合法 JSON workflow 文件可以被 load
2. 不存在的文件会抛 WorkflowLoadError
3. 目录路径会抛 WorkflowLoadError
4. 非 .json 后缀会抛 WorkflowLoadError
5. JSON 语法错误会抛 WorkflowLoadError
6. JSON 顶层不是 object 会抛 WorkflowLoadError
7. load_data 直接接收数据时，顶层也必须是 object
8. 结构合法但语义非法时，透出 WorkflowValidationError
9. allowed_tools 可以通过传入的 validator 生效
```

### 验证结果

已运行：

```bash
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
19 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. Loader 已返回 Workflow 对象，符合本轮目标。
2. 文件级错误和 workflow 内容校验错误已分离。
3. WorkflowValidator.validate_file() 保持兼容，不破坏已有测试。
4. 新增测试覆盖了 Loader 的核心成功与失败路径。
```

第二次审查：安全性

```text
1. Loader 只读取用户显式传入的本地路径。
2. Loader 限制文件后缀为 .json。
3. Loader 不执行任何 workflow、tool 或 shell 命令。
4. JSON 顶层不是 object 时直接拒绝，避免后续校验器接收不确定结构。
5. tool 白名单仍由 WorkflowValidator 控制。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：Workflow Context

### 已完成内容

已新增 Workflow Context：

```text
mini_agent_flow/engine/context.py
```

Context 的职责是作为 workflow 执行时的共享变量表：

```text
1. 从 workflow.inputs 初始化运行时变量
2. 节点执行时按 key 读取变量
3. 节点执行完成后按 output key 写入结果
4. 为后续 Trace Recorder 提供状态快照
```

### 已实现接口

```text
WorkflowContext()
WorkflowContext.from_workflow(workflow)
get(key, default=None)
require(key)
set(key, value)
update(values)
has(key)
to_dict()
snapshot()
```

### 变量名规则

Context 变量名与 workflow output 字段保持一致：

```text
^[A-Za-z_][A-Za-z0-9_]*$
```

允许示例：

```text
goal
keywords
search_results
final_answer
```

拒绝示例：

```text
""
"1abc"
"user-name"
"foo.bar"
```

### 设计说明

Context 是 key-value 型共享状态，不是节点之间直接传值。

执行时的数据流是：

```text
节点 A 执行结果
  ↓
写入 context["some_key"]
  ↓
节点 B 根据 workflow 配置读取 context["some_key"]
```

这保证 Level 1 的数据流是显式、可检查、可追踪的。

### 新增测试

已新增：

```text
tests/test_workflow_context.py
```

覆盖场景：

```text
1. 空 Context 可以创建
2. initial_values 会被复制保存
3. from_workflow 会读取 workflow.inputs
4. get 可以读取存在变量
5. get 读取不存在变量时返回 default
6. require 读取不存在变量时抛 WorkflowContextError
7. set 可以写入合法变量名
8. set 非法变量名会失败
9. update 可以批量写入
10. update 非 dict 会失败
11. update 中有非法 key 时不会产生半更新状态
12. has 可以判断变量是否存在
13. to_dict 返回副本
14. snapshot 返回副本，适合后续 trace 使用
```

### 验证结果

已运行：

```bash
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
37 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. Context 已能从 Workflow.inputs 初始化。
2. get / require / set / update / has / to_dict / snapshot 均已实现。
3. initial_values、set、update、to_dict、snapshot 都使用深拷贝，避免外部修改污染运行时状态。
4. update 先校验所有 key 再写入，避免半更新状态。
5. 测试覆盖了核心成功路径、失败路径和副本隔离。
```

第二次审查：安全性

```text
1. Context 不执行 workflow、tool、shell 命令或表达式。
2. Context 只在内存中保存显式传入的数据。
3. 变量名被限制为安全的标识符格式，避免后续模板变量引用混乱。
4. require 缺失变量时显式失败，避免节点静默使用 None。
5. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：Variable Resolver

### 已完成内容

已新增变量解析器：

```text
mini_agent_flow/engine/resolver.py
```

Resolver 的职责是把 workflow 配置中的变量引用映射到 `WorkflowContext` 中已有的值：

```text
{{ goal }}
{{ search_results }}
{{ final_answer }}
```

### 已实现接口

```text
VariableResolver.resolve_template(template, context)
VariableResolver.resolve_value(value, context)
VariableResolver.extract_variables(template)
```

### 解析规则

当前只支持简单变量引用：

```text
{{ key }}
```

不支持：

```text
1. 函数调用
2. 过滤器
3. 表达式计算
4. 条件语句
5. 循环语句
6. 属性访问
```

变量名规则沿用 Context：

```text
^[A-Za-z_][A-Za-z0-9_]*$
```

### 完整变量引用与嵌入模板

如果整个字段就是变量引用：

```text
"{{ search_results }}"
```

Resolver 返回原始对象，保留类型：

```text
list / dict / int / bool / str / None
```

如果变量嵌入字符串：

```text
"请总结：{{ search_results }}"
```

Resolver 会把变量值格式化为字符串后替换进去。

其中：

```text
1. list / dict 使用 json.dumps(..., ensure_ascii=False)
2. None 转为空字符串
3. 其他类型使用 str(value)
```

### 递归解析

`resolve_value()` 支持递归解析 list 和 dict 的值：

```json
{
  "query": "{{ keywords }}",
  "limit": "{{ limit }}",
  "message": "搜索 {{ limit }} 条"
}
```

可以解析为：

```python
{
    "query": ["agent", "workflow"],
    "limit": 5,
    "message": "搜索 5 条"
}
```

### 缺失变量策略

Resolver 只解析 Context 中已经拥有的字段。

如果模板引用不存在的变量：

```text
{{ missing_key }}
```

会抛出：

```text
VariableResolveError
```

这样可以避免缺变量时静默生成错误 prompt 或错误 tool input。

### 新增测试

已新增：

```text
tests/test_variable_resolver.py
```

覆盖场景：

```text
1. 普通字符串没有变量时原样返回
2. 字符串模板可以替换变量
3. 完整变量引用返回原始对象
4. 完整数字变量保留 int 类型
5. 嵌入字符串的数字会变成字符串片段
6. list 中的变量可以递归解析
7. dict 中的变量可以递归解析
8. 嵌套 dict/list 可以解析
9. 缺失变量会抛 VariableResolveError
10. 非法变量语法会抛 VariableResolveError
11. 多个变量可以在同一字符串中替换
12. 非字符串标量会原样返回
13. list/dict 嵌入 prompt 时使用 JSON 字符串
14. None 嵌入字符串模板时转换为空字符串
15. extract_variables 可以提取模板变量名
```

### 验证结果

已运行：

```bash
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
55 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. Resolver 已能区分完整变量引用和嵌入字符串模板。
2. 完整变量引用保留原始类型，适合后续 tool input。
3. 嵌入字符串模板会格式化变量值，适合后续 LLM prompt。
4. list/dict 支持递归解析。
5. 缺失变量和非法模板语法会显式失败。
6. 测试覆盖了核心成功路径、失败路径和多类型数据。
```

第二次审查：安全性

```text
1. Resolver 不使用 eval 或 exec。
2. Resolver 不开放 Jinja2 高级语法。
3. Resolver 不执行函数、表达式、属性访问、循环或条件。
4. Resolver 只读取 WorkflowContext 中已有变量。
5. 变量名受固定正则限制，避免不受控模板语法进入执行流程。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：本地 Tool Registry

### 已完成内容

已新增本地工具注册包：

```text
mini_agent_flow/tools/__init__.py
mini_agent_flow/tools/registry.py
mini_agent_flow/tools/builtin.py
```

Tool Registry 的职责是把 workflow 中的 tool 名称映射到受控的本地 Python callable。

### 已实现接口

```text
ToolRegistry.register(name, tool)
ToolRegistry.get(name)
ToolRegistry.has(name)
ToolRegistry.names()
ToolRegistry.unregister(name)
```

错误类型：

```text
ToolRegistryError
```

工具函数类型：

```text
ToolCallable = Callable[[Any], Any]
```

### 工具名规则

工具名沿用 workflow ToolNode.tool 字段规则：

```text
^[A-Za-z_][A-Za-z0-9_-]*$
```

允许示例：

```text
mock_search
file_reader
http-fetch
```

拒绝示例：

```text
""
"1tool"
"bad name"
"tool.name"
```

### 内置工具

已新增两个安全内置工具：

```text
echo
mock_search
```

`echo`：

```text
返回输入原值，用于测试工具调用链路。
```

`mock_search`：

```text
不联网、不读取文件，只根据输入构造本地假搜索结果。
```

默认注册表：

```text
create_default_tool_registry()
```

会注册：

```text
mock_search
echo
```

### 与 Validator 的关系

当前没有修改 `WorkflowValidator` 接口。

推荐调用方式：

```python
registry = create_default_tool_registry()
validator = WorkflowValidator(allowed_tools=registry.names())
loader = JsonWorkflowLoader(validator=validator)
workflow = loader.load("examples/level1_manual_workflow.json")
```

也就是说：

```text
ToolRegistry：
  保存工具名到 callable 的映射。

WorkflowValidator：
  只接收 allowed_tools 集合，检查 workflow 中的 tool 名是否在白名单里。

Executor：
  后续真正执行 tool 节点时，再通过 registry.get(name) 取出 callable 并调用。
```

这样可以降低 Validator 与 Tool Registry 的耦合。

### 新增测试

已新增：

```text
tests/test_tool_registry.py
```

覆盖场景：

```text
1. 可以注册并获取工具
2. get 未注册工具会失败
3. 非法工具名会失败
4. 注册非 callable 会失败
5. 重复注册同名工具会失败
6. has 可以判断工具是否存在
7. names 返回已注册工具名副本
8. unregister 可以移除工具
9. unregister 未注册工具会失败
10. create_default_tool_registry 包含 mock_search 和 echo
11. echo 返回输入原值
12. mock_search 对 str 输入返回 mock 结果
13. mock_search 对 list 输入返回 mock 结果
```

### 验证结果

已运行：

```bash
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
72 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. ToolRegistry 已实现注册、读取、存在性判断、名称导出和注销。
2. 工具名规则与 workflow ToolNode.tool 保持一致。
3. 默认注册表包含 mock_search 和 echo。
4. mock_search 支持 str/list 输入并返回稳定本地假数据。
5. 测试覆盖了核心成功路径、失败路径和内置工具行为。
```

第二次审查：安全性

```text
1. ToolRegistry 不支持从 workflow 动态 import 任意 Python 函数。
2. 只有代码显式注册过的工具可以被获取。
3. 重复注册默认失败，避免误覆盖已有工具实现。
4. mock_search 不联网、不读写文件、不执行命令。
5. 当前仍未实现 Executor，因此本轮不会实际执行 workflow tool 节点。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：顺序执行器

### 已完成内容

已新增 Level 1 顺序执行器：

```text
mini_agent_flow/engine/executor.py
```

已新增 LLM 抽象与本地 Mock LLM：

```text
mini_agent_flow/llm/__init__.py
mini_agent_flow/llm/base.py
mini_agent_flow/llm/mock.py
```

顺序执行器负责把现有组件串起来：

```text
Workflow
  ↓
WorkflowContext
  ↓
VariableResolver
  ↓
LLMClient / ToolRegistry
  ↓
WorkflowRunResult
```

### 已实现接口

```text
SequentialWorkflowExecutor.run(workflow)
```

执行结果：

```text
WorkflowRunResult
```

字段：

```text
workflow_name
context
final_output
executed_nodes
```

### 支持节点

当前支持：

```text
start
llm
tool
end
```

执行规则：

```text
start:
  跳转到 next

llm:
  使用 VariableResolver 渲染 prompt
  调用 llm.generate(prompt)
  将结果写入 context[node.output]
  跳转到 next

tool:
  使用 VariableResolver 解析 node.input
  通过 ToolRegistry.get(node.tool) 获取工具
  调用工具
  将结果写入 context[node.output]
  跳转到 next

end:
  停止执行并返回 WorkflowRunResult
```

### 暂不支持节点

当前顺序执行器暂不支持：

```text
condition
loop
retry
并发 DAG
```

遇到未支持节点会抛出：

```text
WorkflowExecutionError
```

### Mock LLM

已新增：

```text
MockLLM
```

行为：

```text
1. prompt 包含“搜索关键词”或“关键词”时，返回 ["AI Agent", "Workflow Engine", "Tool Calling"]
2. 其他 prompt 返回 "Mock response: {prompt}"
```

MockLLM 不联网、不需要 API key，只用于测试和 Level 1 演示。

### 调用方式

```python
from mini_agent_flow.engine.executor import SequentialWorkflowExecutor
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry

registry = create_default_tool_registry()

workflow = JsonWorkflowLoader(
    validator=WorkflowValidator(allowed_tools=registry.names())
).load("examples/level1_manual_workflow.json")

executor = SequentialWorkflowExecutor(
    llm=MockLLM(),
    tool_registry=registry,
)

result = executor.run(workflow)
```

### 新增测试

已新增：

```text
tests/test_sequential_executor.py
```

覆盖场景：

```text
1. 可以执行 examples/level1_manual_workflow.json
2. 执行结果 context 包含 goal / keywords / search_results / final_answer
3. executed_nodes 顺序正确
4. final_output 等于 context["final_answer"]
5. tool_registry 缺少工具时执行失败
6. 缺变量时执行失败
7. 遇到 condition 节点时执行失败
8. max_steps 可以防止循环
9. max_steps 小于等于 0 时会失败
```

### 验证结果

已运行：

```bash
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
78 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. 顺序执行器已能跑通 Level 1 示例 workflow。
2. LLM 节点会渲染 prompt、调用 llm.generate、写回 context。
3. Tool 节点会解析 input、从 registry 获取工具、调用工具、写回 context。
4. executed_nodes 能反映真实执行顺序。
5. max_steps 能防止错误 workflow 无限循环。
6. 测试覆盖成功路径和主要失败路径。
```

第二次审查：安全性

```text
1. Executor 不动态导入工具。
2. Tool 调用只能通过 ToolRegistry.get 获取已注册工具。
3. MockLLM 不联网、不访问外部 API。
4. condition / loop / retry 暂不执行，避免未设计清楚前引入不受控行为。
5. max_steps 限制避免无限循环。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：Mock Tool 端到端测试

### 本次补充目标

补充 Mock Tool 测试，用来验证当前 Level 1 的完整链路是否真正跑通：

```text
Workflow JSON / dict
  ↓
JsonWorkflowLoader
  ↓
WorkflowValidator
  ↓
SequentialWorkflowExecutor
  ↓
WorkflowContext
  ↓
VariableResolver
  ↓
ToolRegistry
  ↓
Mock Tool
  ↓
Context / WorkflowRunResult
```

### 新增测试文件

```text
tests/test_mock_tool_workflow.py
```

### 覆盖场景

```text
1. echo 最小工具链路：
   start -> echo -> end
   验证完整变量引用 "{{ message }}" 会保留 dict 原始类型。

2. mock_search 列表输入链路：
   start -> mock_search -> end
   验证 "{{ keywords }}" 会保留 list 原始类型，并写入 search_results。

3. 嵌入字符串模板链路：
   input: "query={{ keyword }}"
   验证嵌入模板会转换成字符串后传给工具。

4. 未注册工具失败链路：
   Validator allowed_tools 允许 missing_tool。
   Executor 的 ToolRegistry 未注册 missing_tool。
   验证执行阶段仍会失败。

5. 示例文件完整链路：
   加载 examples/level1_manual_workflow.json。
   执行 start -> plan -> search -> summarize -> end。
   验证 context 包含 goal / keywords / search_results / final_answer。
```

### 职责边界验证

本次测试进一步确认：

```text
1. Validator 只负责判断 workflow 是否引用允许的工具名。
2. ToolRegistry 才负责保存实际可调用工具。
3. Executor 运行 tool 节点时必须从 ToolRegistry 获取 callable。
4. VariableResolver 会根据模板形态决定保留原始类型或转换为字符串。
5. Context 是节点之间传递数据的唯一共享状态。
```

### 本次未改动

```text
1. 未修改 Executor 业务逻辑。
2. 未修改 Resolver 业务逻辑。
3. 未修改 ToolRegistry 业务逻辑。
4. 未修改 Validator 业务逻辑。
5. 未引入真实网络、真实 LLM 或真实文件工具。
```

### 验证结果

已运行：

```bash
python -m pytest tests/test_mock_tool_workflow.py -q
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
tests/test_mock_tool_workflow.py：5 passed
全量测试：83 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. 新增测试覆盖了 echo、mock_search、嵌入模板、未注册工具失败和示例文件完整链路。
2. 测试通过 Loader + Validator 加载 workflow，没有绕过当前主要入口。
3. 测试验证了完整变量引用会保留 dict/list 原始类型。
4. 测试验证了嵌入字符串模板会转换为字符串。
5. 测试验证了 allowed_tools 白名单与 ToolRegistry 实际注册表是两个独立边界。
6. 未修改业务代码，因此本次变更只增强回归保护。
```

第二次审查：安全性

```text
1. 新增测试只调用 echo 和 mock_search 两个本地 mock 工具。
2. mock_search 不联网、不读取文件、不执行命令。
3. 未引入真实 LLM、真实 API、API Key、token 或 secret。
4. 未新增动态 import、eval、exec 或 shell 调用业务逻辑。
5. 未注册工具失败测试覆盖了执行阶段的工具调用边界。
6. 文档更新不包含敏感信息。
```

## 追加更新：YAML Workflow Importer

### 已完成内容

已新增 YAML Workflow Loader：

```text
mini_agent_flow/engine/loader.py
```

新增类：

```text
YamlWorkflowLoader
```

YAML Loader 的职责与 JSON Loader 保持一致：

```text
1. 检查 workflow 文件路径是否存在
2. 检查路径是否是文件
3. 检查文件后缀是否为 .yaml 或 .yml
4. 使用 yaml.safe_load() 解析 YAML
5. 确保 YAML 顶层是 object/mapping
6. 调用 WorkflowValidator.validate_data()
7. 返回已校验的 Workflow 对象
```

### 新增示例文件

已新增 Level 1 YAML 示例：

```text
examples/level1_manual_workflow.yaml
```

该示例与 JSON 示例等价：

```text
start
  ↓
llm: plan
  ↓
tool: search
  ↓
llm: summarize
  ↓
end
```

### 与 JSON Loader 的关系

当前保留两个明确入口：

```text
JsonWorkflowLoader：
  只加载 .json。

YamlWorkflowLoader：
  只加载 .yaml / .yml。
```

本轮没有实现根据后缀自动分发的统一 Loader，避免扩大范围。后续可以新增：

```text
WorkflowLoader.load(path)
```

由它根据文件后缀分发给 JSON 或 YAML Loader。

### 新增测试

已新增：

```text
tests/test_yaml_workflow_loader.py
```

覆盖场景：

```text
1. 合法 .yaml workflow 文件可以被加载
2. .yml 后缀可以被加载
3. 非 .yaml / .yml 后缀会失败
4. YAML 语法错误会失败
5. 空 YAML 文件会失败
6. YAML 顶层不是 object/mapping 会失败
7. allowed_tools 配置会在 YAML Loader 中生效
8. YAML 示例可以跑通 Loader、Validator、Executor、MockLLM 和 Mock Tool 全链路
```

### 验证结果

已运行：

```bash
python -m pytest tests/test_yaml_workflow_loader.py -q
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
tests/test_yaml_workflow_loader.py：8 passed
全量测试：91 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. YamlWorkflowLoader 已支持 .yaml 和 .yml。
2. YAML Loader 与 JSON Loader 一样返回 Workflow 对象。
3. YAML Loader 会复用 WorkflowValidator，因此节点引用、可达性和 allowed_tools 规则一致。
4. 空 YAML、数组顶层、语法错误、错误后缀均已覆盖。
5. YAML 示例文件可以通过顺序执行器跑完整 Level 1 链路。
6. 本轮没有改变 JSON Loader 行为。
```

第二次审查：安全性

```text
1. YAML 解析使用 yaml.safe_load()，没有使用 yaml.load()。
2. YAML Loader 只读取用户显式传入的本地 .yaml / .yml 文件。
3. YAML Loader 不执行 workflow、tool、shell 命令或表达式。
4. 真实工具调用仍由 Executor 通过 ToolRegistry 获取已注册 callable。
5. allowed_tools 白名单仍由 WorkflowValidator 控制。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：MCP Tool Provider 基础结构

### 已完成内容

已新增 Tool Provider 抽象：

```text
mini_agent_flow/tools/provider.py
```

新增协议：

```text
ToolProvider
```

它规定任何工具来源只要能返回：

```text
dict[str, ToolCallable]
```

就可以被导入 `ToolRegistry`。

### ToolRegistry 更新

已更新：

```text
mini_agent_flow/tools/registry.py
```

新增：

```text
ToolRegistry.register_many()
```

用于批量注册 Provider 返回的工具。

注册规则：

```text
1. 入参必须是 mapping
2. 所有工具名必须合法
3. 所有工具对象必须是 callable
4. 不允许覆盖已有工具
5. 先完整校验，全部通过后再写入，避免半注册状态
```

### Fake MCP Provider

已新增：

```text
mini_agent_flow/tools/mcp_provider.py
```

新增：

```text
FakeMCPToolProvider
```

它不连接真实 MCP Server，只模拟 MCP 工具来源：

```text
外部工具来源
  ↓
load_tools()
  ↓
dict[str, ToolCallable]
  ↓
ToolRegistry.register_many()
```

本轮没有接入真实 MCP SDK，也没有启动 MCP Server。

### 当前架构

当前工具导入链路为：

```text
FakeMCPToolProvider
  ↓
load_tools()
  ↓
ToolRegistry.register_many()
  ↓
WorkflowValidator.allowed_tools
  ↓
SequentialWorkflowExecutor
  ↓
registry.get(tool_name)
  ↓
tool(input)
  ↓
context[output]
```

这验证了后续接真实 MCP 时，Executor 不需要关心工具来源。

### 新增测试

已新增：

```text
tests/test_tool_provider.py
```

覆盖场景：

```text
1. FakeMCPToolProvider.load_tools() 返回工具映射副本
2. ToolRegistry.register_many() 可以批量注册 Provider 工具
3. register_many() 拒绝非 mapping 入参
4. register_many() 拒绝非法工具名
5. register_many() 拒绝非 callable 工具
6. register_many() 拒绝覆盖已有工具
7. register_many() 失败时不会产生半注册状态
8. Provider 导入的工具可以被 workflow tool 节点调用
```

### 验证结果

已运行：

```bash
python -m pytest tests/test_tool_provider.py -q
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
tests/test_tool_provider.py：8 passed
全量测试：99 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. ToolProvider 协议已定义工具来源的统一接口。
2. ToolRegistry.register_many() 已支持 Provider 工具批量导入。
3. 批量注册失败时不会留下部分注册状态。
4. FakeMCPToolProvider 能模拟外部 MCP 工具来源。
5. 新测试验证了 Provider 工具能通过 Executor 完整执行。
6. Executor 不需要修改，仍只依赖 ToolRegistry.get()。
```

第二次审查：安全性

```text
1. 本轮没有接入真实 MCP Server，不产生网络连接。
2. ToolRegistry 仍不支持从 workflow 动态 import 任意函数。
3. Provider 返回的工具必须经过工具名和 callable 校验。
4. 批量注册不允许覆盖已有工具，避免外部 Provider 替换安全工具。
5. Provider 导入后的工具仍受 WorkflowValidator.allowed_tools 控制。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：Trace Recorder

### 已完成内容

已新增内存 Trace Recorder：

```text
mini_agent_flow/engine/trace.py
```

新增结构：

```text
TraceStatus
TraceError
ContextDiff
TraceEvent
TraceSpan
TraceRecorder
```

Trace Recorder 用于记录 workflow 每个节点的结构化执行过程，不是普通 print 日志。

### TraceEvent 记录内容

当前每个节点 trace 记录：

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

状态枚举预留：

```text
success
failed
skipped
```

当前顺序执行器只主动产生：

```text
success
failed
```

`skipped` 保留给后续 condition、loop、DAG 或 disabled node 语义。

### Context 记录策略

本轮没有在每个 TraceEvent 中保存完整：

```text
context_before
context_after
```

原因是完整 context 可能包含大文本、搜索结果、网页内容、代码内容或 LLM 长输出。
如果每个节点都复制完整 context，会造成不必要的内存占用。

当前只记录：

```text
context_before_keys
context_after_keys
context_diff
```

示例：

```json
{
  "context_before_keys": ["goal", "keywords"],
  "context_after_keys": ["goal", "keywords", "search_results"],
  "context_diff": {
    "added": ["search_results"],
    "updated": [],
    "removed": []
  }
}
```

后续如果需要大对象持久化，将通过 TODO 中的 Context Store / Artifact Store 设计实现。

### Executor 更新

已更新：

```text
mini_agent_flow/engine/executor.py
```

`WorkflowRunResult` 新增：

```text
trace
```

执行成功时返回完整 trace：

```text
result.trace
```

执行失败时，`WorkflowExecutionError` 会携带已记录的 trace：

```text
exc.trace
```

这样失败节点也能被调试。

### 当前支持节点

Trace 当前覆盖顺序执行器已支持的节点：

```text
start
llm
tool
end
```

LLM 节点记录：

```text
渲染后的 prompt
写入的 output key/value
```

Tool 节点记录：

```text
tool name
解析后的 tool input
写入的 output key/value
```

### 敏感信息脱敏

TraceRecorder 会对 input / output 中的敏感字段做基础脱敏。

当前识别字段名包含：

```text
api_key
apikey
token
secret
password
authorization
```

命中后记录为：

```text
[REDACTED]
```

注意：脱敏只影响 trace，不改变真实 workflow context。

### 新增测试

已新增：

```text
tests/test_trace_recorder.py
```

覆盖场景：

```text
1. 成功执行 workflow 会生成每个节点的 trace
2. trace 节点顺序正确
3. LLM trace 记录渲染后的 prompt 和 output
4. Tool trace 记录 tool name、tool input 和 output
5. trace 记录 context_before_keys / context_after_keys / context_diff
6. trace 不记录完整 context_before / context_after
7. 执行失败时 WorkflowExecutionError 携带 failed trace
8. input / output 中的敏感字段会被脱敏
```

### 验证结果

已运行：

```bash
python -m pytest tests/test_trace_recorder.py -q
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
tests/test_trace_recorder.py：6 passed
全量测试：105 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. TraceRecorder 已能记录 start / llm / tool / end 节点。
2. WorkflowRunResult 已返回 trace。
3. WorkflowExecutionError 已携带失败时的 trace。
4. TraceEvent 已记录节点输入、输出、状态、错误、时间和 attempt。
5. Context 只记录 keys 和 diff，没有保存完整快照。
6. 测试覆盖了成功链路、失败链路、context diff 和敏感字段脱敏。
```

第二次审查：安全性

```text
1. TraceRecorder 不执行 workflow、tool、shell 命令或表达式。
2. TraceRecorder 不读取环境变量或外部文件。
3. input / output 中的常见敏感字段会被脱敏。
4. Trace 不保存完整 context 快照，降低大对象和敏感数据扩散风险。
5. 脱敏只影响 trace，不会修改真实 context。
6. 未新增 API Key、token、secret 等敏感信息处理逻辑。
```

## 追加更新：节点级 Retry 执行机制

### 已完成内容

已在顺序执行器中实现节点级 retry：

```text
mini_agent_flow/engine/executor.py
```

复用现有模型：

```text
RetryPolicy
```

支持字段：

```text
max_attempts
backoff_seconds
```

示例：

```json
{
  "id": "search",
  "type": "tool",
  "tool": "mock_search",
  "input": "{{ keywords }}",
  "output": "search_results",
  "next": "summarize",
  "retry": {
    "max_attempts": 3,
    "backoff_seconds": 0
  }
}
```

### 支持范围

当前支持 retry 的节点：

```text
llm
tool
```

当前不对以下节点执行 retry：

```text
start
end
condition
loop
unsupported node
```

原因：

```text
1. start / end 没有外部调用，不需要 retry。
2. condition / loop 当前还没有执行语义。
3. unsupported node retry 没有意义，应直接失败。
```

### 执行语义

每个支持 retry 的节点按如下流程执行：

```text
attempt = 1
  ↓
执行节点
  ↓
成功：
    记录 success trace
    跳转 next
  ↓
失败：
    记录 failed trace
    如果 attempt < max_attempts：
        等待 backoff_seconds
        继续下一次 attempt
    否则：
        抛 WorkflowExecutionError
```

没有配置 retry 时：

```text
max_attempts = 1
```

也就是失败后不重试。

### Trace 记录

每次 attempt 都会生成独立 TraceEvent。

示例：

```text
call_tool attempt=1 failed
call_tool attempt=2 success
```

如果全部失败：

```text
call_tool attempt=1 failed
call_tool attempt=2 failed
call_tool attempt=3 failed
```

失败异常仍会携带完整 trace：

```text
WorkflowExecutionError.trace
```

### Context 写入边界

当前 llm / tool 节点只有在成功拿到结果后才写入：

```text
context[node.output]
```

如果某次 attempt 失败，不会提前写入 output key。

本轮没有实现完整 context rollback，因为当前节点执行模型不存在半写入状态：

```text
执行成功后才写 context。
执行失败不会写 output。
```

### 新增测试

已新增：

```text
tests/test_retry_executor.py
```

覆盖场景：

```text
1. tool 节点第一次失败、第二次成功，workflow 继续执行
2. trace 中记录 failed attempt=1 和 success attempt=2
3. tool 节点超过 max_attempts 后失败
4. 失败异常携带全部 retry trace
5. 未配置 retry 时默认只执行一次
6. llm 节点第一次失败、第二次成功
7. retry 全部失败时不会提前写入 output key
```

### 验证结果

已运行：

```bash
python -m pytest tests/test_retry_executor.py -q
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
tests/test_retry_executor.py：5 passed
全量测试：110 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. Executor 已读取 node.retry 并应用 max_attempts / backoff_seconds。
2. llm / tool 节点均支持 retry。
3. 每次 attempt 都会单独记录 trace。
4. 未配置 retry 时默认 max_attempts=1，不改变旧行为。
5. retry 全部失败时会抛 WorkflowExecutionError，并携带完整 trace。
6. 失败 attempt 不会提前写入节点 output key。
```

第二次审查：安全性

```text
1. Retry 不引入动态 import、shell 命令或任意代码执行。
2. Retry 只重复调用已由 Executor 允许执行的 llm / tool 节点。
3. Tool 调用仍必须通过 ToolRegistry.get() 获取已注册 callable。
4. Workflow 中的 tool 仍受 WorkflowValidator.allowed_tools 控制。
5. Trace 脱敏逻辑继续生效，每次 attempt 的 input / output 都会经过脱敏。
6. backoff 使用标准库 sleep，测试中使用 0 秒避免拖慢测试。
```

## 追加更新：Condition Node 执行机制

### 已完成内容

已在顺序执行器中实现 Condition 节点：

```text
mini_agent_flow/engine/executor.py
```

Condition 模型已存在：

```text
ConditionNode
```

字段：

```text
expression
if_true
if_false
```

### 执行语义

Condition 节点执行流程：

```text
读取 expression
  ↓
使用 VariableResolver.resolve_value() 解析变量
  ↓
对解析后的值做安全 truthy 判断
  ↓
True  -> 跳转 if_true
False -> 跳转 if_false
  ↓
记录 trace
```

示例：

```json
{
  "id": "check_results",
  "type": "condition",
  "expression": "{{ search_results }}",
  "if_true": "summarize",
  "if_false": "fallback"
}
```

### Truthy 判断规则

当前不使用：

```text
eval
exec
表达式语言
Jinja2 条件语法
```

只做基础值判断：

```text
bool:
  True / False 原样使用

None:
  False

int / float:
  0 为 False
  非 0 为 True

str:
  "" / "false" / "no" / "0" 为 False
  "true" / "yes" / "1" 为 True
  其他非空字符串为 True

list / dict / tuple / set:
  空为 False
  非空为 True

其他对象:
  使用 bool(value)
```

### Trace 记录

Condition 成功时记录：

```json
{
  "input": {
    "expression": "{{ search_results }}",
    "resolved_value": [],
    "if_true": "summarize",
    "if_false": "fallback"
  },
  "output": {
    "condition_result": false,
    "selected_branch": "if_false",
    "next": "fallback"
  }
}
```

Condition 失败时记录：

```json
{
  "status": "failed",
  "input": {
    "expression": "{{ missing_key }}",
    "if_true": "summarize",
    "if_false": "fallback"
  }
}
```

本轮仍不记录未走分支的 skipped trace。

原因：

```text
未走分支是否记录 skipped，属于后续分支/DAG trace 语义。
当前先记录 selected_branch 和 next，避免过早引入 skipped 语义复杂度。
```

### Retry 关系

Condition 不支持 retry。

原因：

```text
1. Condition 是本地确定性判断，不调用外部 LLM 或工具。
2. Condition 失败通常表示变量缺失或 workflow 配置错误。
3. 对配置错误重试没有意义。
```

### 新增测试

已新增：

```text
tests/test_condition_executor.py
```

覆盖场景：

```text
1. bool True 走 if_true
2. bool False 走 if_false
3. 空 list 走 if_false
4. 非空 list 走 if_true
5. 字符串 "false" 走 if_false
6. condition trace 记录 expression、resolved_value、selected_branch、next
7. 缺失变量时执行失败，并携带 failed trace
8. condition 当前不产生 skipped trace
```

已更新：

```text
tests/test_sequential_executor.py
```

将原来的：

```text
condition unsupported
```

改为：

```text
condition 可以正常执行
```

### 验证结果

已运行：

```bash
python -m pytest tests/test_condition_executor.py -q
python -m pytest tests/test_sequential_executor.py -q
python -m pytest -q
python -m compileall mini_agent_flow tests
```

结果：

```text
tests/test_condition_executor.py：8 passed
tests/test_sequential_executor.py：6 passed
全量测试：118 passed
compileall 通过
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. Executor 已支持 ConditionNode。
2. expression 会通过 VariableResolver 解析，不绕过现有变量规则。
3. truthy 判断覆盖 bool、None、数字、字符串和集合类型。
4. Condition 成功 trace 会记录解析值、判断结果、分支和 next。
5. Condition 缺失变量会失败，并携带 failed trace。
6. 旧的 unsupported condition 测试已更新为支持执行。
```

第二次审查：安全性

```text
1. Condition 不使用 eval、exec 或任意表达式执行。
2. Condition 不调用 shell、外部文件、网络或动态 import。
3. Condition 只读取 WorkflowContext 中已有变量。
4. 缺失变量会显式失败，不会静默走默认分支。
5. Trace 脱敏逻辑继续生效。
6. Condition 不支持 retry，避免对配置错误做无意义重试。
```

## 统一 WorkflowLoader 实现记录

### 本次目标

新增统一 Loader 入口：

```text
WorkflowLoader
```

调用方只需要传入 workflow 文件路径，Loader 根据文件后缀自动分发：

```text
.json  -> JsonWorkflowLoader
.yaml  -> YamlWorkflowLoader
.yml   -> YamlWorkflowLoader
其他   -> WorkflowLoadError
```

这样后续 CLI、Planner 或测试代码不需要自己判断文件格式。

### 新增模块行为

已在：

```text
mini_agent_flow/engine/loader.py
```

新增：

```text
WorkflowLoader.load(path)
WorkflowLoader.load_data(data)
```

其中：

```text
1. load(path) 负责根据后缀选择具体 Loader。
2. load_data(data) 负责把已解析 dict 交给 WorkflowValidator。
3. JsonWorkflowLoader 和 YamlWorkflowLoader 保留，避免破坏已有调用方。
4. WorkflowLoader 会复用传入的 Validator，所以 allowed_tools 等策略仍然生效。
```

### Validator 兼容入口更新

已更新：

```text
mini_agent_flow/engine/validator.py
```

将：

```text
WorkflowValidator.validate_file(path)
```

从只调用 `JsonWorkflowLoader` 改为调用统一的 `WorkflowLoader`。

现在它可以加载：

```text
workflow.json
workflow.yaml
workflow.yml
```

### 测试覆盖

已更新：

```text
tests/test_workflow_loader.py
```

新增覆盖：

```text
1. WorkflowLoader 可以根据 .json 后缀加载 JSON workflow。
2. WorkflowLoader 可以根据 .yaml 后缀加载 YAML workflow。
3. WorkflowLoader 可以根据 .yml 后缀加载 YAML workflow。
4. WorkflowLoader 遇到未知后缀会抛 WorkflowLoadError。
5. WorkflowLoader.load_data(dict) 可以返回 Workflow 对象。
6. WorkflowLoader.load_data 非 dict 顶层会失败。
7. WorkflowValidator.validate_file() 可以通过统一 Loader 加载 YAML。
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. 已新增统一 WorkflowLoader。
2. JSON / YAML / YML 三种入口都走现有具体 Loader，不重复解析逻辑。
3. load_data 仍然复用 WorkflowValidator。
4. 原有 JsonWorkflowLoader / YamlWorkflowLoader 保持兼容。
5. WorkflowValidator.validate_file 已升级为格式无关入口。
6. 测试覆盖了成功路径和未知后缀失败路径。
```

第二次审查：安全性

```text
1. WorkflowLoader 只根据文件后缀分发，不执行外部命令。
2. YAML 解析仍使用 yaml.safe_load。
3. 所有加载结果仍必须经过 WorkflowValidator。
4. allowed_tools 白名单策略仍由同一个 Validator 控制。
5. 未知后缀会拒绝加载，不会猜测解析。
6. 没有引入动态 import、eval、exec 或网络访问。
```

## 最终输出机制实现记录

### 本次目标

将最终输出从 Executor 中硬编码的：

```text
context["final_answer"]
```

改为 workflow 顶层显式声明：

```yaml
outputs:
  - final_answer
```

核心职责划分：

```text
End 节点：只表示控制流结束。
Workflow.outputs：声明执行结束后要返回哪些 context 字段。
```

这样可以避免 End 节点承担数据出口职责，也避免不同 workflow 被迫统一使用
`final_answer` 这个固定 key。

### 模型与 Schema 更新

已更新：

```text
mini_agent_flow/engine/models.py
schemas/workflow.schema.json
```

新增顶层字段：

```text
outputs: list[str]
```

规则：

```text
1. outputs 可选，默认空列表。
2. outputs 中的字段名必须符合 context 变量名规则。
3. 不声明 outputs 时，Executor 返回 final_output = None。
4. 声明一个 output 时，final_output 返回该字段原始值。
5. 声明多个 output 时，final_output 返回 dict。
```

### Validator 更新

已更新：

```text
mini_agent_flow/engine/validator.py
```

新增校验：

```text
1. workflow.outputs 不允许重复字段。
2. workflow.outputs 中的字段必须来自 workflow.inputs 或 LLM/Tool 节点 output。
```

这一步可以提前发现明显拼写错误，例如：

```yaml
outputs:
  - fina_answer
```

### Executor 更新

已更新：

```text
mini_agent_flow/engine/executor.py
```

执行到 EndNode 时：

```text
1. 读取 workflow.outputs。
2. 从 WorkflowContext 中 require 对应字段。
3. 组装 final_output。
4. 将最终输出写入 end 节点 trace。
5. 如果声明字段运行时不存在，end 节点记录 failed trace 并抛 WorkflowExecutionError。
```

### 示例更新

已更新：

```text
examples/level1_manual_workflow.json
examples/level1_manual_workflow.yaml
docs/AGENTS.md
```

Level 1 示例现在显式声明：

```yaml
outputs:
  - final_answer
```

### 测试覆盖

已更新：

```text
tests/test_sequential_executor.py
tests/test_workflow_schema.py
```

新增覆盖：

```text
1. 单个 outputs 字段返回原始值。
2. 多个 outputs 字段返回 dict。
3. 不声明 outputs 时 final_output 为 None。
4. 声明字段运行时不存在时，Executor 在 end 节点失败。
5. outputs 声明不存在字段时，Validator 失败。
6. outputs 重复声明时，Validator 失败。
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. 已去掉 Executor 对 final_answer 的硬编码依赖。
2. Workflow 顶层 outputs 成为唯一最终输出声明来源。
3. EndNode 职责保持为控制流终点，没有新增节点级 outputs。
4. 示例 JSON/YAML 已同步新格式。
5. Validator 和 Executor 都覆盖了输出字段缺失场景。
6. 测试覆盖单输出、多输出、空输出、声明错误和运行时缺失。
```

第二次审查：安全性

```text
1. outputs 只读取 WorkflowContext 中已有字段，不触发工具、命令或文件访问。
2. 输出字段名受变量名正则约束，避免任意 key 形态。
3. 输出组装使用 context.require，缺失字段显式失败。
4. final_output 写入 trace 时继续走 TraceRecorder 脱敏逻辑。
5. 没有引入 eval、exec、动态 import、shell 或网络访问。
6. EndNode 不接收额外动态表达式，避免输出阶段出现新的执行面。
```

## CLI 入口实现记录

### 本次目标

为 Level 1 Demo 增加命令行入口，让用户可以直接运行：

```bash
python -m mini_agent_flow run examples/level1_manual_workflow.yaml
```

也支持 JSON：

```bash
python -m mini_agent_flow run examples/level1_manual_workflow.json
```

### 新增入口

已新增：

```text
mini_agent_flow/cli.py
mini_agent_flow/__main__.py
```

其中：

```text
1. cli.py 使用 Typer 定义 run 命令。
2. __main__.py 支持 python -m mini_agent_flow。
3. pyproject.toml 增加 mini-agent-flow 脚本入口。
```

安装项目后也可以运行：

```bash
mini-agent-flow run examples/level1_manual_workflow.yaml
```

### CLI 执行流程

```text
CLI run(path)
  ↓
create_default_tool_registry()
  ↓
WorkflowValidator(allowed_tools=registry.names())
  ↓
WorkflowLoader.load(path)
  ↓
SequentialWorkflowExecutor(MockLLM, registry)
  ↓
executor.run(workflow)
  ↓
Rich 输出 workflow / final_output / executed_nodes / context / trace
```

### 错误处理

CLI 捕获：

```text
WorkflowLoadError
WorkflowValidationError
WorkflowExecutionError
```

并返回非 0 exit code。

如果执行错误携带 trace，CLI 会展示已产生的 trace 摘要，方便定位失败节点。

### 文档更新

已更新：

```text
README.md
```

补充：

```text
1. 安装依赖。
2. 运行 YAML 示例。
3. 运行 JSON 示例。
4. CLI 输出内容。
5. 当前核心能力列表。
```

### 测试覆盖

已新增：

```text
tests/test_cli.py
```

覆盖：

```text
1. CLI run YAML 示例成功。
2. CLI run JSON 示例成功。
3. CLI 遇到不存在文件时返回非 0。
4. 输出中包含 workflow 名称、final_output、trace 和执行节点。
```

### 自我审查结果

第一次审查：正确性与完整性

```text
1. CLI 使用统一 WorkflowLoader，不绕过 JSON/YAML 自动分发。
2. CLI 使用 WorkflowValidator，并把 allowed_tools 限制为默认注册工具。
3. CLI 使用 SequentialWorkflowExecutor、MockLLM 和默认工具注册表跑通 Level 1。
4. python -m mini_agent_flow 和 mini-agent-flow 两种入口都已具备。
5. README 已提供可复制的运行命令。
6. CLI 测试覆盖成功路径和加载失败路径。
```

第二次审查：安全性

```text
1. CLI 不接受任意命令字符串，也不调用 shell。
2. CLI 只执行已通过 Validator 的 workflow。
3. Tool 调用仍受 ToolRegistry 和 allowed_tools 白名单限制。
4. YAML 仍通过 WorkflowLoader 内部的 yaml.safe_load 解析。
5. 执行 trace 输出继续使用 TraceRecorder 的脱敏结果。
6. 错误处理不会吞掉失败状态，失败时返回非 0 exit code。
```
