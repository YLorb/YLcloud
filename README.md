# Mini Agent Flow

一个用于学习 Agent Workflow Engine、Tool Calling、Context 传递和 Planner-Executor 架构的 Python 项目。

当前已支持 Level 1 人工定义 Workflow、Level 2 根据 Goal 从模板库自动选择并执行 Workflow，以及同步 Graph Workflow v2。

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

## 运行 Graph Workflow v2 Demo

Workflow v2 使用显式 `edges`，支持稳定拓扑执行、Edge 条件、未激活节点跳过、Merge 和受控 Loop SCC：

```powershell
python -m mini_agent_flow run examples/level_graph_workflow.yaml
```

现有 Workflow v1 会通过 `V1ToV2Compiler` 转换为内部 v2 Graph IR；新 Graph 功能只在 v2 中声明。

每次 `run` 默认把逻辑 Run、execution epoch、节点 Invocation、脱敏 Context、节点输出和
Trace 写入 `.mini-agent-flow/runs.db`。可以通过 `--database` 使用其他 SQLite 文件：

```powershell
python -m mini_agent_flow run examples/level_graph_workflow.yaml `
  --database .mini-agent-flow/demo.db
```

## Graph Runtime 与运行控制

Graph Runtime 使用稳定拓扑序同步执行单个 Run；不同 Run 最多并发 4 个。SQLite 启用
WAL、外键、`synchronous=FULL` 和 CAS 状态转换。隔离 Tool 使用 spawn Worker、UTF-8
JSON Pipe，以及 Windows Job Object 或 Linux process group；Timeout 会终止整个进程树。

查询最近的 Run：

```powershell
python -m mini_agent_flow runs list
python -m mini_agent_flow runs show <run-id>
```

请求取消整个 Run，或在指定节点到达时取消：

```powershell
python -m mini_agent_flow runs cancel <run-id> --reason USER_CANCELLED
python -m mini_agent_flow runs cancel-node <run-id> <node-id>
```

取消请求由运行时在节点边界消费。若节点声明 Cancel Edge，其目标只能是显式
`cleanup_allowed=true`、`idempotent=true` 且超时不超过 30 秒的 Cleanup Tool；Cleanup
失败会记录到 `cleanup_errors`，最终 Run 仍为 `cancelled`。

失败、超时、取消或 abandoned 的逻辑 Run 可以显式建立新 epoch 重试：

```powershell
python -m mini_agent_flow runs retry <run-id>
```

保留期清理默认只预览。确认后才实际删除：

```powershell
python -m mini_agent_flow runs cleanup
python -m mini_agent_flow runs cleanup --apply
```

所有命令都支持 `--database <path>`。自动崩溃恢复会把 owner lease 已过期的旧 execution
原子标记为 `abandoned`；仅当已成功的副作用 Tool 全部具有稳定幂等键时，才创建新 epoch
并从 Start 重跑。发现非幂等成功副作用时，逻辑 Run 保持 `abandoned`，等待人工处理。

固定运行预算为：200 nodes、500 edges、1000 steps、100 Loop iterations、100 LLM calls、
200 Tool calls、10 MiB Context、20 MiB Trace 和 1800 秒总时长。预算通过
`RuntimeConfig` 调整，加载优先级为 CLI、环境变量、YAML、代码默认值。

## 使用 DeepSeek LLM

项目通过 OpenAI 兼容接口接入 DeepSeek。默认仍使用 Mock Provider，只有显式指定
`--provider deepseek` 才会产生真实 API 请求和费用。

在项目根目录的 `.env.local` 中配置：

```text
DEEPSEEK_API_KEY=your-key
```

`.env.local` 已加入 `.gitignore`。运行 Level 1：

```powershell
python -m mini_agent_flow run examples/level1_manual_workflow.yaml `
  --provider deepseek `
  --model deepseek-v4-flash
```

运行 Level 2：

```powershell
python -m mini_agent_flow select `
  --goal "分析这个 Python traceback 报错" `
  --provider deepseek
```

当前允许 `deepseek-v4-flash` 和 `deepseek-v4-pro`。客户端默认使用非思考模式、
60 秒超时和 1024 最大输出 token；API 请求失败后的重试继续由 Workflow 节点配置负责。

## 当前能力

```text
WorkflowLoader：自动加载 JSON / YAML workflow
WorkflowValidator：校验节点引用、可达性和工具白名单
V1ToV2Compiler：把 v1 隐式跳转和 Condition 节点转换为 v2 显式 Edge
NetworkXGraphAnalyzer：完成 SCC、Loop 缩点和稳定词典序拓扑排序
GraphWorkflowExecutor：同步执行激活节点、跳过未激活分支、执行 Merge 与 LoopFrame
RunManager / RunController：并发 Run、execution epoch、取消、Heartbeat 与崩溃恢复
SQLiteRunControlStore：WAL、Migration、CAS、审计持久化与保留期清理
IsolatedProcessRunner：JSON IPC 与可终止进程树的隔离 Tool Worker
WorkflowContext：维护运行时 key-value 上下文
VariableResolver：解析 {{ variable }} 模板
ToolRegistry：注册和调用本地工具
SequentialWorkflowExecutor：仅保留为旧代码兼容入口；生产 CLI/Level 2 统一使用 GraphWorkflowExecutor
TraceRecorder：记录节点输入、输出、状态和 context diff
Retry：支持 LLM / Tool 节点级重试
Final Output：通过 workflow 顶层 outputs 声明最终输出字段
WorkflowTemplateCatalog：加载并校验带有严格 metadata 的模板库
RuleBasedTemplateSelector：根据 Goal 关键词、优先级和模板名稳定选择模板
Level2WorkflowService：完成模板选择、输入填充、校验和执行
DeepSeekLLM：通过 OpenAI 兼容 API 执行真实 LLM 节点
```
