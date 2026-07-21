# AGENTS.md

## 当前 YLcloud 集成任务（优先于下方历史学习路线）

本仓库已进入 YLcloud 跨仓库集成阶段。实施 `REQ-20260722-001` 时，以 YLcloud 仓库中的以下版本化文档为权威需求与架构来源：

- `docs/obsidian/10-规划与实施/REQ-20260722-001-意图识别与Workflow服务集成.md`
- `docs/obsidian/10-规划与实施/ADR-20260722-001-独立Workflow服务通信架构.md`
- 对应 `TASK-20260722-001` 至 `TASK-20260722-013` 和 `TEST-20260722-001`

下文“第一版不需要生产级分布式执行、多用户认证、向量数据库记忆”等内容是原 Mini AI Workflow Builder 学习阶段的历史边界，不适用于已确认的 YLcloud 集成任务。原有“先确定性 Workflow、显式 Tool Registry、Mock Provider 优先、Validator 后执行”等安全原则继续有效。

YLcloud 集成期间还必须遵守：

1. Workflow 只负责编排和生成结构化 Context，最终用户回答由 Java 生成并持久化。
2. Workflow 不直接访问 YLcloud 业务 Schema 或 Qdrant，只能调用有 scope、Schema、权限和幂等门禁的 Java Internal Tool Gateway。
3. 跨仓库 JSON Schema 的唯一权威目录是 `ylcloud/schemas`；本仓库只能消费生成或同步产物。
4. 按 TASK 编号逐项实现。每项功能先补完整测试用例并执行适用测试，再完成正确性审查和安全审查；通过后才允许创建包含该 TASK 编号的规范 Git commit，不混入下一项功能。
5. Mock Web/SMTP/CalDAV Tool 属于本阶段交付；真实 OAuth 和真实外部账号联调属于 TASK-20260722-014，不阻塞当前阶段。

## 项目：Mini AI Workflow Builder

本项目是一个小型 Agent 工作流平台，用来理解现代 Agent 系统是如何由工作流执行、工具调用、上下文传递、状态流转、重试机制和 LLM 规划能力组成的。

项目按照三个 Level 逐步演进。

## 核心原则

不要一开始就做完全自主的 Agent。

应该先实现一个确定性的 Workflow Engine，然后逐步加入 AI 决策能力。

学习路线是：

```text
Level 1：执行人工定义的 Workflow
Level 2：AI 从模板库选择 Workflow
Level 3：AI 根据 Goal 生成 Workflow
```

每个 Level 都应该可以独立运行、独立演示。

---

## Level 1：执行人工定义的 Workflow

### 目标

用户手动编写一个 workflow 配置文件，Engine 负责解析并执行它。

示例流程：

```text
Start
  ↓
LLM
  ↓
Tool
  ↓
Condition
  ↓
Loop
  ↓
End
```

### 输入

一个 workflow 文件，例如：

```yaml
name: research_summarizer

inputs:
  goal: "总结最近 AI Agent 的发展趋势"

outputs:
  - final_answer

nodes:
  - id: start
    type: start
    next: plan

  - id: plan
    type: llm
    prompt: "请为这个目标生成 3 个搜索关键词：{{ goal }}"
    output: keywords
    next: search

  - id: search
    type: tool
    tool: mock_search
    input: "{{ keywords }}"
    output: search_results
    next: summarize

  - id: summarize
    type: llm
    prompt: "请总结这些资料：{{ search_results }}"
    output: final_answer
    next: end

  - id: end
    type: end
```

### 输出

Engine 应该产生：

```text
1. 最终结果
2. 完整上下文
3. 执行 trace
4. 每个节点的输入/输出日志
```

### 必备能力

- 从 YAML 或 JSON 加载 workflow。
- 校验 workflow 结构。
- 支持 `start`、`end`、`llm`、`tool`、`condition` 和简单 `loop` 节点。
- 维护共享执行上下文。
- 使用 context 变量渲染 prompt template。
- 通过 tool registry 注册和调用工具。
- 记录执行 trace。
- 支持节点失败后的基础 retry 行为。

### 学到什么

- Workflow Engine 设计
- DAG 执行
- 状态机思维
- Context 传递
- Prompt Template
- Tool Calling 抽象
- Retry 和错误处理

---

## Level 2：AI 从模板库选择 Workflow

### 目标

用户只提供一个 goal。AI 不从零生成 workflow，而是从预定义模板库中选择最合适的 workflow。

这一层引入 AI 规划能力，但仍然保持执行过程可控、可调试。

### 输入

用户 goal：

```text
帮我分析这个 Python 报错，并给出修复建议。
```

模板库：

```text
templates/
  research_summarizer.yaml
  python_error_analyzer.yaml
  code_review_assistant.yaml
  report_writer.yaml
```

### 流程

```text
用户 goal
  ↓
AI classifier / planner
  ↓
选择最合适的 workflow 模板
  ↓
填充模板输入
  ↓
Workflow Engine 执行该 workflow
  ↓
返回最终结果和 trace
```

### 输出

系统应输出：

```json
{
  "selected_workflow": "python_error_analyzer.yaml",
  "selection_reason": "用户想分析 Python 报错并获得修复建议。",
  "final_answer": "...",
  "trace": []
}
```

### 必备能力

- 维护一个 workflow 模板库。
- 给每个模板添加 metadata。
- 使用 LLM 或规则匹配选择模板。
- 解释为什么选择该 workflow。
- 使用 Level 1 的 Engine 执行选中的 workflow。
- 如果没有合适模板，明确返回失败原因，而不是乱编行为。

### 分阶段实现

Level 2 不直接一次性跳到 AI Planner，建议拆成两个阶段：

```text
Level 2A：统一输入格式 + 执行安全增强 + 示例库
Level 2B：AI / 规则从模板库选择 Workflow
```

Level 2A 目标：

```text
1. 支持 JSON、YAML 和 Markdown 内嵌 Workflow。
2. 所有输入格式统一转换为 JSON IR。
3. 支持顺序执行和简单条件分支。
4. 支持节点重试、超时和全局限制。
5. 支持本地 Tool 和 MCP Tool。
6. 工具调用前进行 Schema、权限和风险检查。
7. 可以查看每一步输入、输出、状态、耗时和异常。
8. 使用 Mock Provider 完成稳定自动化测试。
9. 至少提供两个可运行的示例 Workflow。
```

Level 2B 目标：

```text
1. 建立 workflow 模板库。
2. 为模板补充 metadata，例如用途、输入、输出、适用场景和风险说明。
3. 根据用户 goal 使用规则或 Mock LLM 选择模板。
4. 输出 selected_workflow 和 selection_reason。
5. 将用户输入填充到模板 inputs。
6. 复用 Level 1 / Level 2A 的 Engine 执行选中的 workflow。
7. 没有合适模板时明确失败，不编造 workflow。
```

当前实现状态：

```text
规则选择版本已实现：TemplateCatalog -> RuleBasedTemplateSelector
-> Goal 输入填充 -> Validator -> Level 1 Executor -> Level2RunResult。

当前提供 research_summarizer 和 python_error_analyzer 两个模板；
真实 LLM Selector 作为后续增强，不阻塞 Level 2 的确定性演示。
```

### 学到什么

- Planner 和 Executor 分离
- Workflow 检索
- 意图分类
- 模板化 Agent
- 更安全的 AI 编排
- 可解释执行

---

## Level 3：AI 根据 Goal 生成 Workflow

### 目标

用户只提供一个 goal。AI 生成 workflow，系统校验 workflow，然后执行。

这是最接近 Agent 的一层。

### 输入

用户 goal：

```text
调研最新的 AI workflow 工具，并总结它们的差异。
```

### 流程

```text
用户 goal
  ↓
AI workflow generator
  ↓
生成 workflow YAML
  ↓
校验 workflow
  ↓
如果无效，尝试修复
  ↓
执行 workflow
  ↓
返回最终答案、生成的 workflow 和 trace
```

### 输出

系统应输出：

```json
{
  "generated_workflow": {
    "name": "generated_research_workflow",
    "nodes": []
  },
  "validation_status": "success",
  "final_answer": "...",
  "trace": []
}
```

### 必备能力

- 根据自然语言 goal 生成 workflow YAML 或 JSON。
- 执行前校验生成的 workflow。
- 拒绝危险或不支持的 tool。
- 确保所有引用变量都存在于 context。
- 确保所有节点都能从 `start` 到达。
- 确保 workflow 最终能到达 `end`。
- 如果校验失败，可以尝试一次修复。
- 保存生成的 workflow，方便检查。
- 只有校验通过后才允许执行。

### 学到什么

- AI Planning
- 结构化输出生成
- Workflow Validation
- Guardrails
- Self-Repair Loop
- Agent Planning 和 Agent Execution 的区别

---

## 技术栈

本项目使用 Python 作为主要开发语言，优先选择轻量、清晰、适合学习 Agent Workflow 核心机制的技术栈。

### 基础技术栈

```text
语言：Python 3.10+
配置格式：YAML / JSON
数据模型与校验：Pydantic
模板渲染：Jinja2
命令行工具：Typer
命令行展示：Rich
测试框架：pytest
LLM：先使用 Mock LLM，后续再接真实模型 API
```

### 推荐依赖

```text
pyyaml
pydantic
jinja2
typer
rich
pytest
```

如果后续接入真实 LLM，可以再增加：

```text
openai
python-dotenv
```

### 选择理由

- Python 适合快速实现 Agent 原型，也适合写 CLI、测试和工具调用逻辑。
- YAML 天然适合描述 workflow，便于人工编写和调试。
- Pydantic 适合定义 Node、Workflow、Trace 等结构，并进行输入校验。
- Jinja2 适合实现 Prompt Template，例如 `{{ goal }}`、`{{ search_results }}`。
- Typer 可以快速做出清晰的命令行入口。
- Rich 可以让执行 trace、错误信息和 workflow 输出更易读。
- pytest 适合验证 Engine、Validator、Condition、Retry 等核心逻辑。

### 分阶段使用

Level 1 阶段优先使用：

```text
Python + PyYAML + Pydantic + Jinja2 + pytest
```

目标是先跑通：

```text
workflow.yaml → validate → execute → context → trace → final_answer
```

Level 2 阶段增加：

```text
Typer + Rich
```

目标是让系统可以通过 CLI 演示：

```bash
python -m mini_agent_flow run examples/level1_manual_workflow.yaml
python -m mini_agent_flow run --goal "帮我分析 Python 报错"
```

Level 3 或后续阶段再考虑：

```text
OpenAI API / 兼容模型 API
FastAPI
Uvicorn
```

真实 LLM 不应该阻塞 Level 1。第一版应先使用 `MockLLM`，保证 Engine 的行为稳定、可测试、可复现。

---

## 编写代码要求

### 需求处理流程

每次用户提出一个新需求时，先给出一个具体、可执行的实现方案。

实现方案应尽量包含：

```text
1. 要修改或新增哪些模块
2. 每个模块负责什么
3. 核心执行流程
4. 预期输入与输出
5. 需要补充的测试
6. 可能的风险点
```

在用户修改、确认或审核方案之前，不直接开始写代码。

用户确认后，必须严格按照最终确认的方案执行。如果实现过程中发现方案存在明显问题，应先说明问题并请求用户确认调整方向。

### 代码完成后的自我审查

每次完成代码后，必须进行两次自我审查。

第一次审查重点是代码的正确性与完整性：

```text
1. 是否完整实现了已确认的需求
2. 是否遗漏边界情况
3. workflow 执行流程是否符合预期
4. context、trace、node 输入输出是否正确传递
5. 测试是否覆盖核心路径
6. 是否存在明显的异常处理缺失
```

第二次审查重点是安全性：

```text
1. 是否存在危险的文件读写行为
2. 是否可能执行未经允许的命令
3. 是否可能调用未注册或未授权的 tool
4. AI 生成的 workflow 是否经过 validator 校验
5. prompt、context、tool input 是否可能导致越权行为
6. 是否避免在日志中输出 API Key、token 等敏感信息
```

只有两次审查都完成后，才向用户汇报代码完成情况、测试结果和遗留风险。

### 代码注释要求

核心模型、核心流程和非显然校验逻辑必须添加中文注释。

注释应遵循：

```text
1. 优先解释模块职责、设计意图、边界条件和安全限制。
2. 避免只把代码翻译成中文，例如“给变量赋值”这类注释不需要写。
3. Pydantic 模型、Validator、Executor、Tool Registry、Planner 等核心模块应有类级或方法级注释。
4. 涉及 workflow 跳转、状态流转、tool 白名单、文件访问、AI 生成内容校验的逻辑必须说明为什么这样限制。
5. 测试代码应说明测试意图，尤其是失败场景对应的业务规则。
6. 注释不能替代清晰命名；如果需要大量注释解释简单代码，应优先考虑重构命名或拆分函数。
```

---

## 推荐架构

```text
mini-agent-flow/
  engine/
    context.py
    executor.py
    loader.py
    nodes.py
    validator.py
    trace.py

  llm/
    base.py
    mock.py
    openai_client.py

  tools/
    registry.py
    mock_search.py
    file_reader.py

  planner/
    template_selector.py
    workflow_generator.py

  templates/
    research_summarizer.yaml
    python_error_analyzer.yaml
    code_review_assistant.yaml

  examples/
    level1_manual_workflow.yaml
    level2_template_selection.yaml
    level3_generated_workflow.json

  tests/
    test_executor.py
    test_validator.py
    test_template_selector.py

  README.md
  AGENTS.md
```

---

## 开发顺序

建议按这个顺序做：

```text
1. Context 对象
2. Node 模型
3. Workflow Loader
4. Workflow Validator
5. Tool Registry
6. Mock LLM
7. Executor
8. Trace Recorder
9. Level 1 示例 workflow
10. Level 2A：统一输入格式、执行安全增强和示例库
11. Level 2B：Template Selector
12. Level 3 Workflow Generator
```

不要在 Level 1 稳定之前直接做 Level 3。

---

## 不做什么

第一版不需要：

- 复杂 Web UI
- 真实浏览器自动化
- 生产级分布式执行
- 多用户认证
- 向量数据库记忆
- 完整兼容 LangGraph
- 通用 Coding Agent

这些都可以后续再加。

---

## 每个 Level 的最小可运行 Demo

### Level 1 Demo：执行人工定义的 Workflow

用户手写一个 workflow 文件，然后运行：

```bash
python run.py examples/level1_manual_workflow.yaml
```

输入：

```text
总结 AI Agent 工作流系统的核心组成。
```

系统输出：

```text
1. workflow 执行成功
2. 每个节点的执行 trace
3. 最终总结结果
```

这个 Demo 证明：

```text
Workflow Engine 可以解析配置、执行节点、传递 context、调用 mock LLM / mock tool。
```

### Level 2 Demo：AI 从模板库选择 Workflow

用户输入 goal：

```bash
python run.py --goal "帮我分析这个 Python 报错，并给出修复建议"
```

系统从模板库中选择：

```text
templates/python_error_analyzer.yaml
```

系统输出：

```text
1. 被选择的 workflow
2. 选择原因
3. workflow 执行 trace
4. 最终分析结果
```

这个 Demo 证明：

```text
系统具备 Planner-Executor 分离能力。
AI 负责选择流程，Engine 负责执行流程。
```

### Level 3 Demo：AI 根据 Goal 生成 Workflow

用户输入 goal：

```bash
python run.py --goal "调研 AI Workflow Builder、LangGraph、Dify 的区别" --auto-generate
```

系统自动生成 workflow：

```text
生成搜索关键词
  ↓
调用搜索工具
  ↓
整理资料
  ↓
总结差异
  ↓
输出结果
```

系统输出：

```text
1. AI 生成的 workflow
2. workflow 校验结果
3. 执行 trace
4. 最终答案
```

这个 Demo 证明：

```text
系统可以从自然语言 goal 生成结构化执行计划，并在校验后执行。
```

### Demo 优先级

短期内优先保证：

```text
Level 1 Demo 必须稳定。
Level 2 Demo 可以使用 mock LLM 或规则选择。
Level 3 Demo 可以先生成简单 workflow，不追求复杂通用性。
```

不要为了 Level 3 的智能程度牺牲 Level 1 的稳定性。

---

## 完成标准

短期学习目标下，项目完成标准是能演示：

```text
Level 1：
  运行一个人工定义的 workflow。

Level 2：
  根据用户 goal 从模板库选择 workflow。

Level 3：
  根据用户 goal 生成一个简单 workflow，校验并执行它。
```

每个 demo 都应该展示：

```text
1. 输入 goal
2. 选择或生成的 workflow
3. 执行 trace
4. 最终答案
```

---

## 面试讲解点

这个项目可以体现你理解：

- Agent 工作流编排
- DAG 和状态机执行
- Context 管理
- Tool Calling
- Prompt Template
- Retry 和失败处理
- Planner-Executor 架构
- AI 生成计划时的 Guardrails
- LangGraph、Dify、n8n 等工作流型 Agent 平台背后的基本思想
