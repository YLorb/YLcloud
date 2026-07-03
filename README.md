# Mini Agent Flow

一个用于学习 Agent Workflow Engine、Tool Calling、Context 传递和 Planner-Executor 架构的 Python 项目。

当前已支持 Level 1 人工定义 Workflow，以及 Level 2 根据 Goal 从模板库自动选择并执行 Workflow。

## 安装依赖

建议先创建项目虚拟环境：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -e ".[dev]"
```

## 运行 Level 1 Demo

使用模块入口：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.yaml
```

也可以运行 JSON 示例：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.json
```

如果已经通过 `pip install -e ".[dev]"` 安装项目，也可以使用脚本入口：

```powershell
mini-agent-flow run examples/level1_manual_workflow.yaml
```

CLI 会输出：

```text
1. workflow 名称
2. final_output
3. executed nodes
4. context
5. trace 摘要
```

## 运行 Level 2 Demo

用户只提供 Goal，规则选择器会根据模板 metadata 自动选择并执行 Workflow：

```powershell
python -m mini_agent_flow select --goal "帮我分析这个 Python traceback 报错"
```

研究类 Goal 会选择研究总结模板：

```powershell
python -m mini_agent_flow select --goal "调研 Agent Workflow 的发展趋势并总结"
```

使用 `--no-trace` 可以隐藏 Trace 表格，使用 `--templates` 可以指定受控模板目录。

## 当前能力

```text
WorkflowLoader：自动加载 JSON / YAML workflow
WorkflowValidator：校验节点引用、可达性和工具白名单
WorkflowContext：维护运行时 key-value 上下文
VariableResolver：解析 {{ variable }} 模板
ToolRegistry：注册和调用本地工具
SequentialWorkflowExecutor：执行 start / llm / tool / condition / end
TraceRecorder：记录节点输入、输出、状态和 context diff
Retry：支持 LLM / Tool 节点级重试
Final Output：通过 workflow 顶层 outputs 声明最终输出字段
WorkflowTemplateCatalog：加载并校验带有严格 metadata 的模板库
RuleBasedTemplateSelector：根据 Goal 关键词、优先级和模板名稳定选择模板
Level2WorkflowService：完成模板选择、输入填充、校验和执行
```
