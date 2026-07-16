# TODO

## Level 2A：统一输入格式 + 执行安全增强 + 示例库

状态：进行中

目标：

```text
在进入 AI / 规则模板选择之前，先把 Engine 的输入格式、执行安全和示例库补强。
Level 2A 不负责根据 goal 选择 workflow，而是让各种 workflow 来源都能稳定进入统一执行链路。
```

需要支持：

- [x] 支持 JSON、YAML 和 Markdown 内嵌 Workflow。
- [x] 所有输入格式统一转换为 JSON IR。
- [x] 支持顺序执行和简单条件分支。
- [ ] 支持节点重试、超时和全局限制。（部分完成：Retry 和 `max_steps` 已支持；节点超时尚未正确实现，缺少全局时长限制。）
- [x] 支持本地 Tool 和 Fake MCP Tool Provider。（当前 Level 2A 使用 Fake Provider 验证 MCP 工具导入边界，真实 MCP Client 留待后续。）
- [x] 工具调用前进行 Schema、权限和风险检查。
- [x] 可以查看每一步输入、输出、状态、耗时和异常。
- [x] 使用 Mock Provider 完成稳定自动化测试。（现有测试基础已完成；当前 Modified 代码仍需修复后重新执行全量测试。）
- [x] 至少提供两个可运行的示例 Workflow。

建议拆分任务：

- [x] 明确 JSON IR 概念：JSON / YAML / Markdown -> dict -> Workflow。
- [x] 实现 MarkdownWorkflowLoader，先支持 frontmatter + `##` 节点标题的 Markdown Workflow。
- [x] 让 WorkflowLoader 支持 `.md`。
- [x] 增加第二个可运行示例 Workflow，覆盖 Condition 分支。
- [ ] 增加 CLI 对 `.md` Workflow 的运行测试。
- [ ] 设计节点 `timeout_seconds` 和全局 `max_duration_seconds`。（节点字段已加入模型，但执行逻辑尚未正确实现。）
- [x] 为 ToolRegistry 引入 ToolSpec，描述 `input_schema`、`permission` 和 `risk_level`。
- [x] 在 Tool 调用前做 Schema、权限和风险检查。
- [x] 用 FakeMCPToolProvider 覆盖 MCP Tool 自动化测试。

完成标准：

- [ ] `python -m mini_agent_flow run examples/level1_manual_workflow.yaml` 可以运行。（原有能力已实现，但当前 Executor 的 P0 语法错误会阻断运行。）
- [x] `python -m mini_agent_flow run examples/conditional_tool_workflow.yaml` 可以运行。
- [x] `python -m mini_agent_flow run examples/markdown_embedded_workflow.md` 可以运行。
- [ ] 全量测试通过。（当前 pytest tmp_path 在 Windows 环境下存在权限问题；另有 generator 单测失败。）
- [x] Trace 能展示每一步输入、输出、状态、耗时和异常。
- [x] 未授权、Schema 不匹配或高风险 Tool 调用会在执行前被拒绝。

## 已知问题 / 待修复

- `tests/test_workflow_generator.py::test_generator_repairs_invalid_workflow`
  - 现状：期望 `repair_attempts == 1`，实际得到 `0`。
  - 原因：MockLLM 首次生成的 workflow 已经通过了校验，没有进入修复循环。
  - 处理方向：调整测试断言或修复 generator/validator 的修复触发逻辑，确保出现非法 workflow 时 self-repair 至少尝试一次。

## Level 2B：AI / 规则从模板库选择 Workflow

状态：规则选择版本已实现；LLM Selector 作为后续增强

目标：

```text
用户只输入 goal，系统从模板库中选择最合适的 workflow，
解释选择原因，填充 inputs，然后交给 Engine 执行。
```

需要支持：

```text
1. 建立 templates/ workflow 模板库。
2. 每个模板包含 metadata。
3. 使用规则或 Mock LLM 根据 goal 选择模板。
4. 输出 selected_workflow 和 selection_reason。
5. 没有合适模板时明确失败。
6. 复用 Level 2A 的 Loader、Validator、Executor、Tool 安全检查和 Trace。
```

建议拆分任务：

```text
1. 定义 TemplateMetadata。
2. 增加 templates/research_summarizer.yaml。
3. 增加 templates/python_error_analyzer.yaml。
4. 实现 TemplateSelector。
5. 先用规则匹配实现稳定选择，再预留 LLM selector。
6. CLI 增加 goal 入口，例如 mini-agent-flow select --goal "..."。
7. 输出 selected_workflow、selection_reason、final_output 和 trace。
```

已完成：

```text
1. 定义 TemplateMetadata、TemplateCandidate、TemplateSelection 和 Level2RunResult。
2. 实现受模板目录约束的 WorkflowTemplateCatalog。
3. 实现 RuleBasedTemplateSelector，支持关键词得分、priority 和稳定同分处理。
4. 提供 research_summarizer 与 python_error_analyzer 两个模板。
5. 实现 Level2WorkflowService，完成 Goal 填充、二次校验和 Engine 执行。
6. CLI 增加 select --goal、--templates 和 --trace/--no-trace。
7. 无匹配模板时明确失败，不生成或随意选择 Workflow。
8. 使用 MockLLM 和内置 Mock Tool 完成离线自动化测试。
```

后续增强：

```text
1. 增加 LLMTemplateSelector，并继续复用 TemplateSelector 协议。
2. 在 Level 2A 完成后复用 Tool Schema、权限和风险检查。
3. 增加更多模板和更丰富的 metadata 检索策略。
```

完成标准：

```text
1. 输入 research 类 goal，可以选择 research_summarizer 模板。
2. 输入 Python 报错类 goal，可以选择 python_error_analyzer 模板。
3. 选择原因可解释。
4. 选中模板可以被 Engine 执行。
5. 无匹配模板时明确返回失败原因。
```

## 预留：Markdown Workflow Importer

状态：待设计

目标：

```text
后期支持用户用约定好的 Markdown 格式描述 workflow，
再由 Markdown Importer 读取并转换为标准 Workflow 数据结构。
```

当前决策：

```text
1. 暂不把普通 Markdown 当作可直接执行的 workflow 配置。
2. 先保留 Markdown Importer 任务，不在当前阶段实现解析逻辑。
3. 等 Markdown 格式规范确定后，再实现具体 Importer。
4. 最终仍应输出标准 dict / Workflow 对象，并复用 WorkflowValidator。
```

后续设计时需要明确：

```text
1. Markdown 的固定格式，例如标题结构、表格结构或代码块结构。
2. 如何声明 workflow version、name、inputs。
3. 如何声明 node id、type、prompt、tool、input、output、next。
4. 如何处理 condition、loop、retry 等复杂节点。
5. Markdown Importer 是确定性解析，还是交给 LLM 转换为 JSON/YAML。
6. 转换后的 workflow 必须经过 Validator 校验后才能执行。
```

建议实现位置：

```text
mini_agent_flow/engine/loader.py
```

候选类名：

```text
MarkdownWorkflowLoader
```

## 后续：真实 MCP Client Provider

状态：待设计

目标：

```text
在 FakeMCPToolProvider 基础上，接入真实 MCP Client，
从 MCP Server 发现工具并包装成 ToolRegistry 可注册的 callable。
```

后续设计时需要明确：

```text
1. 使用哪个 MCP Python SDK。
2. 支持 stdio、HTTP、SSE 中的哪种连接方式。
3. 工具发现 list_tools() 的返回结构如何映射为 tool metadata。
4. call_tool() 的输入输出如何转换为当前 ToolCallable。
5. 是否需要支持异步 Executor 或同步 wrapper。
6. MCP 工具权限、超时、错误处理和敏感信息日志策略。
```

## 后续：Context Store / Artifact Store

状态：待设计

目标：

```text
当 context 中出现大对象时，不默认把完整 context 写入每个 TraceEvent。
后续可以将 context value 按 key 或 ref 保存到硬盘，
Trace 只记录 context key、diff 和 value reference，需要查看时再按 ref 读取。
```

设计动机：

```text
1. 避免 TraceEvent 重复复制大 context，导致内存占用过高。
2. 支持大文本、网页内容、代码文件、搜索结果、LLM 长输出等大对象。
3. 让 Trace 保持轻量，只记录节点输入输出摘要、context keys、context diff 和引用。
4. 为后续 CLI / UI 查看历史运行结果预留持久化能力。
```

后续设计时需要明确：

```text
1. 是否引入 run_id，用于隔离每次 workflow 运行。
2. context value 保存格式使用 JSON、文本文件，还是按类型选择不同格式。
3. 不可 JSON 序列化对象如何处理。
4. context key 重名、覆盖和版本历史如何表示。
5. TraceEvent 中如何记录 context_refs。
6. 是否提供 MemoryContextStore 和 FileContextStore 两种实现。
7. 大对象保存前是否需要脱敏。
8. 运行失败或测试结束后，临时 artifact 是否清理。
```

候选接口：

```text
ContextStore.save(run_id, key, value) -> ref
ContextStore.load(ref) -> value
```

建议实现位置：

```text
mini_agent_flow/engine/context_store.py
```

## 后续：Loop Node 执行机制

状态：暂缓实现

目标：

```text
支持 workflow 对一组数据重复执行同一段节点逻辑，
例如逐个处理搜索结果、逐个总结网页、逐个分析文件或逐个运行检查项。
```

当前决策：

```text
1. 当前阶段先跳过 Loop Node，优先完成 Level 1 可运行 Demo。
2. LoopNode 模型和 Validator 的基础引用校验可以先保留。
3. Executor 暂不实现 loop 执行语义，避免过早引入复杂控制流。
4. 后续再设计循环体边界、结果聚合、失败策略和 trace 展示。
```

后续设计时需要明确：

```text
1. items 是否只支持 list，还是支持 dict、tuple、字符串等可迭代对象。
2. 每轮 item 写入 context 的变量名如何约定。
3. loop body 是按 body 列表顺序执行，还是继续依赖节点自己的 next。
4. 循环体是否允许 condition、retry、嵌套 loop。
5. 每轮输出是覆盖同一个 key，还是自动 append 到结果列表。
6. 单轮失败后是立即终止、跳过当前 item，还是继续后续 item。
7. trace 如何展示 loop 总览和每一轮 body 节点执行细节。
8. 如何配合 max_steps 防止异常循环。
```

候选执行语义：

```text
LoopNode
  ↓
解析 items
  ↓
for item in items:
  context[item_var] = item
  执行 body 节点链
  ↓
全部完成后进入 next
```

## Level 3A：根据 Goal 生成 Workflow 并执行

状态：已完成

目标：

```text
用户只输入自然语言 goal，系统调用 LLM 生成 workflow，
经过 WorkflowValidator 校验后，把 workflow 流程、保存路径和 YAML 展示给用户。
用户确认后，系统保存 YAML 并使用 SequentialWorkflowExecutor 执行。
```

方案：

```text
1. 新增 CLI 命令：mini-agent-flow make-and-run --goal "..."
2. 复用 WorkflowGenerator.generate() 完成生成与校验（含 self-repair）。
3. 在对话中展示：
   - workflow 名称
   - 节点流程（例如 start -> plan -> search -> summarize -> end）
   - 保存路径
   - YAML 内容
   - repair_attempts
4. 提示用户确认 (y/N)。
5. 确认后保存 YAML 到 --output 或默认路径，并执行。
6. 拒绝后不保存、不执行。
```

验收标准：

```text
- [x] make-and-run --goal "..." --yes 能生成、保存并执行 workflow。
- [x] 用户未确认时不保存、不执行。
- [x] 新增 CLI 测试通过。
```

注意：

```text
- MockLLM 默认返回非 JSON，命令在 mock provider 下无法直接工作；真实 LLM 或测试用固定 LLM 可验证。
- 测试需要 monkeypatch create_llm。
```
