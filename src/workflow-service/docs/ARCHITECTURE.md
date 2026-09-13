# Mini Agent Flow 架构文档

## 项目定位

Mini Agent Flow 是一个轻量级 Agent 工作流平台，用 Python 实现，目标是理解现代 Agent 系统的核心机制：工作流执行、工具调用、上下文传递、状态流转、重试机制和 LLM 规划能力。

项目按三个 Level 演进，每个 Level 独立可运行：

```
Level 1  执行人工定义的 Workflow     ← 已完成
Level 2  AI 从模板库选择 Workflow    ← 已完成（规则匹配）
Level 3  AI 根据 Goal 生成 Workflow  ← 未开始
```

核心原则是**先做确定性引擎，再加 AI 决策**。Planner 和 Executor 严格分层，AI 只负责"选什么流程"，Engine 负责"怎么执行流程"。

---

## 整体架构

```
┌─────────────────────────────────────────────────┐
│                      CLI (Typer + Rich)          │
│            run / select / --goal / --provider    │
├──────────────────┬──────────────────────────────┤
│   Planner 层     │      Engine 层                │
│                  │                               │
│  ┌─────────────┐ │  ┌──────────┐  ┌──────────┐  │
│  │  Catalog    │ │  │  Loader   │→│ Validator │  │
│  │  Selector   │ │  └──────────┘  └──────────┘  │
│  │  Service    │ │        ↓               ↓      │
│  └─────────────┘ │  ┌──────────┐  ┌──────────┐  │
│        ↓         │  │ Executor  │←│ Context  │  │
│  选中 Workflow    │  │ (顺序执行) │  │ (共享状态)│  │
│  填入 Goal       │  └────┬──┬──┘  └──────────┘  │
│        ↓         │       │  │                    │
│  交给 Engine 执行  │       │  ↓                    │
│                  │       │ ┌──────────┐          │
│                  │       │ │   LLM    │          │
│                  │       │ │ (Mock/   │          │
│                  │       │ │ DeepSeek)│          │
│                  │       │ └──────────┘          │
│                  │       │ ┌──────────┐          │
│                  │       │ │  Tools   │          │
│                  │       │ │ Registry │          │
│                  │       │ └──────────┘          │
│                  │       │ ┌──────────┐          │
│                  │       └→│  Trace   │          │
│                  │         │ Recorder │          │
│                  │         └──────────┘          │
└──────────────────┴──────────────────────────────┘
```

上层是 Planner（Level 2+），下层是 Engine（Level 1 就有）。Planner 的产出是一个标准 Workflow 对象，和人工编写的 Workflow 走完全相同的执行路径。

---

## JSON IR：统一中间表示

不管输入格式是 JSON、YAML 还是未来的 Markdown，都先转成 Python dict，再通过 `WorkflowValidator.validate_data()` 生成 Workflow 对象。这个 dict 就是 JSON IR（Intermediate Representation）。

```
.json 文件 → json.loads()          ┐
.yaml 文件 → yaml.safe_load()      ├→ dict → WorkflowValidator → Workflow
.md   文件 → parse_markdown()      ┘
AI 生成的 dict → 直接传入            ↗
```

所有 Loader 的职责到"产出 dict"为止，后续的校验和执行与输入格式完全无关。这也是 Level 3 中 AI 生成 Workflow 后能复用同一套校验管线的基础。

---

## 模块详解

### engine/models.py — 数据模型

用 Pydantic 定义所有节点和 Workflow 的结构。`extra="forbid"` 防止 AI 生成时塞入未知字段。

```
Workflow
  ├── version: "1.0"
  ├── name, description
  ├── inputs: dict          ← 初始 context 值
  ├── outputs: list[str]    ← 最终返回的 context 字段
  └── nodes: list[WorkflowNode]

WorkflowNode (discriminated union on type)
  ├── StartNode      → next
  ├── EndNode        → (无 next)
  ├── LLMNode        → prompt, output, next
  ├── ToolNode       → tool, input, output, next
  ├── ConditionNode  → expression, if_true, if_false
  └── LoopNode       → items, item_var, body, next  (模型已有，执行未实现)

BaseNode (共享基类)
  └── id, type, description, metadata, retry: RetryPolicy
```

模型层的校验包括：start/end 节点数量、节点 id 唯一性。跨节点语义校验交给 Validator。

### engine/loader.py — 加载器

三种格式的 Loader 共享同一结构：

```
WorkflowLoader (统一入口，按后缀分发)
  ├── JsonWorkflowLoader      → json.loads() → dict
  ├── YamlWorkflowLoader      → yaml.safe_load() → dict
  └── MarkdownWorkflowLoader  → parse_markdown() → dict  (待实现)

每个 Loader 都有 load(path) 和 load_data(dict) 两个方法。
load() 负责读文件 + 格式解析；load_data() 负责把已有 dict 交给 Validator。
```

### engine/validator.py — 校验器

Pydantic 做字段级校验，Validator 做跨节点语义校验。检查项包括：

```
1. 边引用合法性       所有 next/if_true/if_false 指向的节点必须存在
2. 工具白名单         allowed_tools 非 None 时，tool 节点只能引用集合内工具
3. outputs 声明       outputs 中的字段必须有 inputs 或 llm/tool 节点产出
4. 可达性 (DFS)       所有节点都能从 start 到达
5. 路径到 end         从 start 出发至少能到达一个 end 节点
```

### engine/context.py — 执行上下文

WorkflowContext 是一个共享的 key-value 存储，所有节点通过它传递数据。

```python
context.get(key)        # 读取（返回深拷贝）
context.set(key, value) # 写入（存深拷贝）
context.has(key)        # 判断存在
context.require(key)    # 不存在则抛异常
context.update(dict)    # 批量写入（原子操作，先校验所有 key 再写入）
context.to_dict()       # 导出快照
```

深拷贝语义防止外部引用被意外修改。变量名限制为 `^[A-Za-z_][A-Za-z0-9_]*$`。

### engine/resolver.py — 变量解析

VariableResolver 负责把 `{{ variable }}` 模板渲染成实际值。

```
"{{ keywords }}"           → 完整变量引用，保留原始类型（list/dict/str）
"搜索 {{ keywords }} 结果"  → 字符串嵌入，转为 str
[{{ a }}, {{ b }}]         → 递归解析 list/dict 内部
```

不使用 Jinja2 的完整能力（表达式、过滤器、属性访问），只支持纯变量替换，防止注入风险。

### engine/executor.py — 执行器

SequentialWorkflowExecutor 按节点链顺序执行，从 start 开始，沿着 next 指针前进。

```
run(workflow, context)
  → 从 start 出发
  → 按 next 指针遍历
  → 每个节点走 _execute_with_retry → _execute_node
  → max_steps 防止无限循环
  → 返回 WorkflowRunResult

支持的节点类型：
  start     → 直接跳到 next
  llm       → 解析 prompt → 调用 LLMClient → 写 output 到 context
  tool      → 解析 input → 从 ToolRegistry 取工具 → 调用 → 写 output
  condition → 解析 expression → 安全 truthy 判断 → 走 if_true 或 if_false
  end       → 终止执行
```

Retry 机制：llm/tool 节点支持 `RetryPolicy(max_attempts, backoff_seconds)`，每次重试独立记录 trace。Condition 节点不支持 retry（无 I/O 操作）。

### engine/trace.py — 执行追踪

TraceRecorder 为每个节点执行记录 TraceEvent：

```
TraceEvent
  ├── node_id, node_type
  ├── status: "success" | "error" | "skipped"
  ├── input, output          ← 节点输入输出
  ├── error                  ← 失败时的异常信息
  ├── context_before_keys    ← 执行前 context 有哪些 key
  ├── context_after_keys     ← 执行后 context 有哪些 key
  ├── context_diff           ← 新增/更新/删除的 key（不存完整值）
  ├── started_at, ended_at, duration_ms
  └── attempt                ← 第几次尝试
```

敏感字段自动脱敏：包含 `api_key`、`token`、`secret`、`password`、`authorization` 的字段值会被替换为 `"***"`。

### llm/ — LLM 抽象层

```
LLMClient (Protocol)
  └── generate(prompt: str) → str

MockLLM             → 根据 prompt 关键词返回固定结果，无需网络
DeepSeekLLM         → 通过 OpenAI SDK 调用 DeepSeek API
LLMProvider (enum)  → mock / deepseek
create_llm()        → 工厂函数，按 provider + model 创建实例
```

MockLLM 保证测试稳定可复现。DeepSeekLLM 支持 `deepseek-v4-flash` 和 `deepseek-v4-pro`，错误信息做安全脱敏（不暴露 API key 和内部 URL）。

### tools/ — 工具层

```
ToolRegistry
  ├── register(name, callable)     注册单个工具
  ├── register_many(dict|Provider) 批量注册
  ├── get(name) → callable         获取工具
  └── names → set[str]             已注册工具名

ToolProvider (Protocol)
  └── load_tools() → dict[str, callable]

FakeMCPToolProvider  → 模拟外部 MCP 工具源
builtin              → echo + mock_search 两个内置工具
```

工具注册是显式的，只有注册过的工具才能被 Workflow 引用。Validator 的 `allowed_tools` 白名单提供第二层防护。

### planner/ — 规划层（Level 2）

```
WorkflowTemplateCatalog
  └── 从 templates/ 目录加载模板，校验 metadata 完整性

RuleBasedTemplateSelector
  └── 关键词匹配 + 优先级 + 稳定排序 → 选出最匹配的模板

Level2WorkflowService
  └── 编排：goal → 选择模板 → 填充 inputs → 重新校验 → 执行 → Level2RunResult

数据流：
  Goal → Catalog.list_metadata()
       → Selector.select(goal, candidates)
       → Catalog.load_template(name)
       → fill_inputs(template, goal)
       → Validator.validate_data(filled_data)
       → Executor.run(workflow, context)
       → Level2RunResult
```

无匹配模板时抛 `NoMatchingTemplateError`，不猜测执行。

---

## 执行流程示例（Level 1）

以 `research_summarizer` 为例：

```
start ──→ plan (LLM) ──→ search (Tool) ──→ summarize (LLM) ──→ end
           ↓                ↓                  ↓
     生成关键词写入       mock_search 搜索     总结搜索结果写入
     context.keywords    结果写入              context.final_answer
                          context.search_results
```

具体步骤：

```
1.  Loader 读取 YAML → dict → Validator 校验 → Workflow 对象
2.  Executor 创建 Context，写入 inputs.goal
3.  start 节点：记录 trace，跳到 next = plan
4.  plan 节点：Resolver 渲染 "{{ goal }}" → MockLLM 生成关键词 → 写入 context.keywords
5.  search 节点：Resolver 渲染 "{{ keywords }}" → mock_search 调用 → 写入 context.search_results
6.  summarize 节点：Resolver 渲染 "{{ search_results }}" → MockLLM 总结 → 写入 context.final_answer
7.  end 节点：执行终止
8.  从 context 取 outputs 声明的字段 → 作为 final_output 返回
9.  返回 WorkflowRunResult（workflow_name, context, final_output, trace）
```

---

## 关键设计决策

**为什么不用 Jinja2 做模板渲染？** Jinja2 支持表达式、过滤器、属性访问，存在注入风险。项目只用 `{{ variable }}` 纯替换，自己实现的 VariableResolver 更安全，约 60 行代码。

**为什么 Context 用深拷贝？** 防止节点间通过共享对象引用产生隐式耦合。代价是性能开销，但 Agent 工作流的数据量通常不大，安全性优先。

**为什么 Condition 不用 eval？** `eval()` 可以执行任意 Python 代码，AI 生成的 workflow 可能注入危险表达式。当前实现只做安全的 truthy 判断：`None`/`False`/`0`/`""`/`[]` → false，其余 → true。

**为什么 Planner 和 Executor 分离？** 这是 LangGraph、Dify 等工作流平台的核心思想。AI 决策（选哪个流程）和确定性执行（怎么跑流程）分开，让执行过程可追踪、可复现、可调试。

**为什么先做 MockLLM？** 真实 LLM 的响应不可预测，会干扰 Engine 行为的调试。MockLLM 保证 Engine 的行为稳定、可测试、可复现。Level 1 的 154 个测试全部基于 Mock，零网络依赖。

---

## 目录结构

```
mini_agent_flow/
  engine/
    models.py       节点与 Workflow 的 Pydantic 模型
    loader.py       JSON/YAML 加载器 + 统一入口
    validator.py    跨节点语义校验
    context.py      共享执行上下文
    resolver.py     {{ variable }} 模板渲染
    executor.py     顺序执行器 + retry
    trace.py        执行追踪与脱敏

  llm/
    base.py         LLMClient Protocol
    mock.py         MockLLM（确定性响应）
    deepseek.py     DeepSeekLLM（OpenAI 兼容 API）
    factory.py      工厂函数 + Provider 枚举

  tools/
    registry.py     工具注册表
    builtin.py      echo + mock_search
    provider.py     ToolProvider Protocol
    mcp_provider.py FakeMCPToolProvider（测试用）

  planner/
    catalog.py      模板目录管理
    selector.py     规则匹配选择器
    service.py      Level 2 编排服务
    models.py       元数据与结果模型

  cli.py            Typer CLI（run / select）
  __main__.py       python -m 入口

templates/          YAML 模板库
examples/           示例 Workflow
schemas/            JSON Schema
tests/              154 个 pytest 测试
benchmarks/         Level 2 定量测试
docs/               开发文档与流程记录
```

---

## 当前状态

| 模块 | 状态 | 测试 |
|---|---|---|
| 数据模型 (models) | 完成 | test_workflow_schema |
| 加载器 (loader) | 完成 (JSON/YAML) | test_workflow_loader, test_yaml_workflow_loader |
| 校验器 (validator) | 完成 | 内嵌于各 loader 测试 |
| 上下文 (context) | 完成 | test_workflow_context |
| 变量解析 (resolver) | 完成 | test_variable_resolver |
| 执行器 (executor) | 完成 (start/llm/tool/condition/end) | test_sequential_executor, test_condition_executor, test_retry_executor |
| 追踪 (trace) | 完成 | test_trace_recorder |
| LLM (mock + deepseek) | 完成 | test_deepseek_llm |
| 工具 (registry + provider) | 完成 | test_tool_registry, test_tool_provider |
| 规划器 (catalog + selector) | 完成 (规则匹配) | test_template_catalog, test_template_selector, test_level2_service |
| CLI | 完成 (run + select) | test_cli, test_level2_cli |
| Loop 节点 | 模型已有，执行未实现 | — |
| Markdown 加载器 | 未实现 | — |
| ToolSpec / 权限检查 | 未实现 | — |
| Level 3 生成器 | 未实现 | — |

共 18 个测试文件，154 个测试用例，100% 通过。Level 2 benchmark：40 标注意图，100% 准确率，P95 18ms。
