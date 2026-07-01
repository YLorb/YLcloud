# TODO

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
