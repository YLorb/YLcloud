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
