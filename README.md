# Mini Agent Flow

一个用于学习 Agent Workflow Engine、Tool Calling、Context 传递和 Planner-Executor 架构的 Python 项目。

当前阶段聚焦于 Level 1：执行人工定义的 Workflow。

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
```
