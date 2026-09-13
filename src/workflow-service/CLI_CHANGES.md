# CLI 变动总结

## 本次目标

为 Mini Agent Flow 增加命令行入口，让用户可以直接运行 Level 1 workflow 示例。

目标命令：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.yaml
```

也支持 JSON：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.json
```

安装项目后还可以运行：

```powershell
mini-agent-flow run examples/level1_manual_workflow.yaml
```

## 新增文件

### 1. mini_agent_flow/cli.py

这是 CLI 的核心入口。

主要内容：

```text
1. 创建 Typer app。
2. 注册 run 命令。
3. 加载 workflow 文件。
4. 创建默认工具注册表。
5. 创建 WorkflowValidator。
6. 创建 WorkflowLoader。
7. 创建 SequentialWorkflowExecutor。
8. 使用 MockLLM 执行 workflow。
9. 使用 Rich 输出结果。
10. 捕获加载、校验和执行错误。
```

核心命令注册：

```python
@app.command("run")
def run_workflow(workflow_path: Path) -> None:
    ...
```

这表示：

```text
命令行中的 run 命令会调用 Python 函数 run_workflow。
```

### 2. mini_agent_flow/__main__.py

用于支持：

```powershell
python -m mini_agent_flow ...
```

Python 执行 `-m mini_agent_flow` 时，会自动运行：

```text
mini_agent_flow/__main__.py
```

该文件只负责调用：

```python
app()
```

实际 CLI 逻辑仍在 `cli.py`。

### 3. tests/test_cli.py

新增 CLI 测试。

覆盖场景：

```text
1. run YAML 示例成功。
2. run JSON 示例成功。
3. 不存在文件返回 exit_code = 1。
4. 输出中包含 workflow 名称、Final Output、Trace 和执行节点。
```

## 修改文件

### 1. pyproject.toml

新增：

```toml
[project.scripts]
mini-agent-flow = "mini_agent_flow.cli:app"
```

作用：

```text
项目被 pip install 后，系统会生成 mini-agent-flow 命令。
```

所以可以运行：

```powershell
mini-agent-flow run examples/level1_manual_workflow.yaml
```

### 2. README.md

新增内容：

```text
1. 如何创建虚拟环境。
2. 如何安装依赖。
3. 如何运行 YAML 示例。
4. 如何运行 JSON 示例。
5. CLI 会输出哪些信息。
6. 当前项目能力列表。
```

### 3. docs/flow.md

新增 CLI 实现记录，包括：

```text
1. 本次目标。
2. 新增入口。
3. CLI 执行流程。
4. 错误处理。
5. 文档更新。
6. 测试覆盖。
7. 正确性与完整性自查。
8. 安全性自查。
```

## CLI 调用链路

用户运行：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.yaml
```

内部流程：

```text
python -m mini_agent_flow
  ↓
mini_agent_flow/__main__.py
  ↓
mini_agent_flow.cli:app
  ↓
Typer 解析 run 命令
  ↓
调用 run_workflow(workflow_path)
  ↓
create_default_tool_registry()
  ↓
WorkflowValidator(allowed_tools=registry.names())
  ↓
WorkflowLoader(validator).load(path)
  ↓
SequentialWorkflowExecutor(MockLLM, registry)
  ↓
executor.run(workflow)
  ↓
Rich 输出执行结果
```

## run 命令做了什么

`run` 命令的职责是执行一个人工定义的 workflow 文件。

它不会：

```text
1. 生成 workflow。
2. 选择模板。
3. 调用真实 LLM。
4. 自动注册外部工具。
5. 执行未通过 Validator 的 workflow。
```

它会：

```text
1. 加载 JSON / YAML workflow。
2. 校验 workflow。
3. 使用 MockLLM。
4. 使用默认 ToolRegistry。
5. 执行 workflow。
6. 输出 final_output、context 和 trace 摘要。
```

## CLI 输出内容

当前输出分为 5 个区域：

```text
Workflow
Final Output
Executed Nodes
Context
Trace
```

示例：

```text
Workflow:
  research_summarizer

Final Output:
  Mock response: ...

Executed Nodes:
  start -> plan -> search -> summarize -> end

Context:
  goal
  keywords
  search_results
  final_answer

Trace:
  start      success
  plan       success
  search     success
  summarize  success
  end        success
```

## 错误处理

CLI 捕获三类错误：

```text
WorkflowLoadError
WorkflowValidationError
WorkflowExecutionError
```

错误时：

```text
1. 打印 Error 信息。
2. 如果错误携带 trace，则打印已产生的 trace 摘要。
3. 返回非 0 exit code。
```

这让 CLI 可以用于脚本、测试和自动化判断：

```text
exit_code = 0：workflow 执行成功
exit_code = 1：加载、校验或执行失败
```

## 为什么使用 Typer

项目技术栈中已经规划使用：

```text
Typer + Rich
```

Typer 的作用：

```text
1. 快速定义命令。
2. 自动解析参数。
3. 自动生成 help。
4. 测试方便，支持 CliRunner。
```

Rich 的作用：

```text
1. 美化终端输出。
2. 用 Panel 展示结果。
3. 用 Table 展示 trace。
4. 让 Demo 更适合展示。
```

## 为什么需要 @app.callback()

当前 CLI 使用：

```python
app = typer.Typer(no_args_is_help=True)

@app.callback()
def main() -> None:
    ...

@app.command("run")
def run_workflow(...):
    ...
```

`@app.callback()` 的作用是让 Typer 固定使用“子命令模式”。

这样命令形式是：

```powershell
python -m mini_agent_flow run workflow.yaml
```

如果只有一个命令而没有 callback，Typer 可能把 app 简化成单命令模式，导致 `run`
被误认为多余参数。

## 测试结果

实现 CLI 后已验证：

```text
tests/test_cli.py：3 passed
全量测试：132 passed
compileall：通过
安全扫描：未发现 subprocess / os.system / eval / exec / yaml.load 等危险模式
CLI YAML 示例：跑通
```

## 当前限制

当前 CLI 仍然是 Level 1 最小入口。

暂未支持：

```text
1. --output-json
2. --show-trace-json
3. --show-context / --no-context
4. --llm real
5. --goal
6. Level 2 模板选择
7. Level 3 自动生成 workflow
```

这些可以作为后续增强。

## 后续建议

CLI 后续可以按这个顺序扩展：

```text
1. 增加 --output-json，方便机器读取。
2. 增加 --trace-json，输出完整 trace。
3. 增加 --quiet，只输出 final_output。
4. 增加 --goal，进入 Level 2 模板选择。
5. 增加 --auto-generate，进入 Level 3 workflow 生成。
6. 增加真实 LLM 配置参数。
```

当前版本已经足够支撑：

```text
Level 1 Demo 演示
README 示例运行
面试项目讲解
自动化测试
```
