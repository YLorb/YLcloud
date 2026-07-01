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
