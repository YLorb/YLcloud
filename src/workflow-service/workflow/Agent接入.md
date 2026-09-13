
Agent 名称：知识库的Agent
# 一、Agent边界

Agent 接收的输入：
- 知识库中被向量化的文档
- 用户的要求

Agent 输出：
- 回答引用的信息来源（当没有检索文档时可以为空）
- 任务完成的结果

允许调用：
- 已经注册的工具

禁止
- 越权调用其他非当前用户所属的知识库
- 未经允许执行高风险任务


# 二、Agent 核心执行循环

1、Graph-Workflow架构（这是新功能）

流程：

接收输入
  ↓
创建 Run
  ↓
初始化公共 State
  ↓
选择合法的Workflow，若没有，由LLM自主创建并等待用户确认
  ↓
找到 START 节点
  ↓
执行当前节点
  ↓
产生 Node Outcome
  ↓
根据 Outcome 匹配 Edge
  ↓
更新 State
  ↓
执行下一个节点
  ↓
直到到达 END、失败或暂停

2、当Graph-Workflow架构失效时或任务过于简单，采用Re-act架构和Plan-Excute结构，具体流程：

接收任务 -> 构建context -> 模型自主Plan与生成/应用已有的Workflow -> 调用 Tool -> 获得 Observation -> 更新 State -> 回看计划 -> 继续决策或输出最终结果

# 四、Agent 数据结构设计

### 1.AgentSpec

- Agent 是谁：YLcloud中的知识资产管理与应用Agent。
- 使用什么模型：Deepseek-v4（当前使用）
- 能调用什么工具：已注册的工具
- 最大执行次数：3
- 最终输出格式：
- 权限和风险限制：
### 2. Run

每次 Agent 执行都创建一个 Run：

```
{
  "runId": "run_123",
  "agentId": "code-review-agent",
  "agentVersion": "1.0.0",
  "status": "RUNNING",
  "input": {
    "repository": "project-a",
    "commitId": "abc123"
  },
  "currentStep": 4,
  "createdAt": "2026-07-22T10:00:00Z"
}
```

建议状态固定为：

```
PENDING
RUNNING
WAITING_TOOL
WAITING_HUMAN
SUCCEEDED
FAILED
CANCELLED
TIMED_OUT
```

### 3. Agent State

State 是 Agent 当前“知道什么”和“做到哪里了”。

```
{
  "task": {
    "goal": "检查当前代码修改"
  },
  "variables": {
    "commitId": "abc123"
  },
  "observations": [],
  "toolResults": {},
  "workingMemory": {
    "currentPlan": [],
    "completedSteps": []
  },
  "finalOutput": null
}
```

建议区分三类信息：

```
Input State
用户输入和任务参数

Working State
Agent 当前计划、中间结果、工具输出

Output State
最终结构化输出
```

不要把所有内容都塞进一个公共 Map，否则后期很难判断字段来源、权限和生命周期。

### 5.Tool设计

这部分具体参考new_project关于Tool的定义与设计

# 六、Agent 如何接入现有系统

选用异步 Run 接入

更适合正式 Agent 系统：

```
POST /agents/{agentId}/runs
```

返回：

```
{
  "runId": "run_123",
  "status": "PENDING"
}
```

然后查询：

```
GET /runs/run_123
GET /runs/run_123/events
```

推荐语义：

> 只有 Run 成功写入数据库后才返回 `202 Accepted`。

这样即使 Agent 进程崩溃，任务仍然可以从数据库恢复，而不会因为只存在于进程内线程池而丢失。

## 七、Context 和 Memory 怎么设计

Agent 每一步不能简单地把所有历史消息都发送给模型，否则成本会越来越高。

建议 Context Builder 按以下顺序构建：

```
System Prompt
AgentSpec
当前任务目标
当前 State 摘要
最近几步 Observation
相关长期记忆
允许使用的 Tool 描述
输出 Schema
```

Context Snapshot 保存“本次实际注入模型的文本”，用于：

- 问题复现。
- 审计。
- 调试模型为什么做出某个决定。
- 对比 Agent 不同版本的表现。

长期记忆则不应全部注入，而应先检索：

```
当前任务
  ↓
判断是否需要长期记忆
  ↓
Memory Retrieval
  ↓
排序、过滤
  ↓
选出少量相关记忆
  ↓
注入 Context
```

context上下文大小由Admin控制，初始为256K。长期记忆可以借助网盘已有的RAG系统进行记忆向量化存取。

## 八、安全边界必须单独设计

Agent 最危险的地方不是模型回答错误，而是模型错误地执行了操作。

推荐将工具划分为：

```
READ_ONLY
LOW_RISK_WRITE
HIGH_RISK_WRITE
EXTERNAL_SIDE_EFFECT
```

例如：

|Tool|风险|
|---|---|
|搜索文档|只读|
|读取文件|只读|
|创建草稿|低风险写|
|修改数据库|高风险写|
|删除数据|高风险写|
|发送邮件|外部副作用|
|发起支付|外部副作用|

高风险操作建议采用：

```
Agent 提议操作
  ↓
Policy Engine 校验
  ↓
人工确认
  ↓
真正执行
```

不要让模型仅凭一句自然语言就直接获得完整 Shell 或数据库权限。

# 九、Trace 应该记录什么

此处在Graph Workflow应该已经实现，沿用即可。

# 十、AgentNode

如果拥有固定的模板，则沿用；如果没有，则在生成Workflow十，不确定性决策交由 AgentNode 执行。