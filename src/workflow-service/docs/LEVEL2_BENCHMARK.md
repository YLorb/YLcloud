# Level 2 定量测试报告

## 测试目标

为简历和面试提供可复现、边界明确的项目数据，验证：

```text
1. 自动化测试稳定性。
2. 规则模板选择准确率。
3. 域外请求拒绝能力。
4. Level 2 完整 Mock 执行链路性能。
```

## 测试环境

```text
日期：2026-07-04
Python：3.12.13
系统：Windows NT 10.0.26200.0
处理器：Intel64 Family 6 Model 158，12 logical processors
模板数量：2
Provider：MockLLM + 本地 Mock Tool
```

测试命令：

```powershell
python -m pytest -q --durations=5
python benchmarks/benchmark_level2.py
```

## 自动化测试

```text
通过：154
失败：0
通过率：100%
最近一次总耗时：1.12 秒
```

测试覆盖 Loader、Validator、Context、Variable Resolver、Tool Registry、
Mock MCP Provider、Executor、Condition、Retry、Trace、CLI、Level 2 模板选择链路和
DeepSeek OpenAI 兼容客户端。

## 模板选择测试

使用项目内固定的 40 条自建标注意图：

```text
Python 调试意图：15 条
研究总结意图：15 条
域外请求：10 条
```

结果：

```text
总体正确率：100%（40/40）
域内模板选择准确率：100%（30/30）
域外请求拒绝率：100%（10/10）
```

这组数据只用于验证当前两个模板的确定性规则，不代表开放领域意图识别能力。
域内样本按照模板适用范围设计，并至少包含一个模板 metadata 关键词。

## 执行性能测试

性能范围包含：

```text
Catalog 加载
Workflow 解析与校验
规则模板选择
Goal 输入填充
二次 Validator 校验
Mock LLM / Mock Tool 执行
Context 和 Trace 生成
```

完成 100 次预热后，连续执行 3,000 次：

```text
平均延迟：11.32 ms
P50 延迟：10.78 ms
P95 延迟：18.04 ms
吞吐量：88.34 runs/s
总耗时：33.96 秒
```

两次完整测试的吞吐量分别为 87.87 和 88.34 runs/s，P95 分别为 17.73 和
18.04 ms。简历中使用“约 88 runs/s、P95 约 18 ms”更稳妥。

内存跟踪与延迟测试分开执行，避免 `tracemalloc` 污染延迟结果。100 次独立内存
采样中的 Python 峰值跟踪分配为 0.08 MiB；该数字不是进程 RSS，简历中不建议使用。

## 简历可用表述

推荐写法：

> 为 Workflow Engine 建立 154 项自动化测试，覆盖加载、校验、上下文、条件分支、
> 重试、Trace 与模板选择链路，测试通过率 100%；在包含 40 条标注意图的自建测试集上，
> 两类模板选择与域外拒绝总体正确率达到 100%。

性能可以单独写：

> 在 Mock Provider 环境下完成 3,000 次 Level 2 端到端执行测试，P50/P95 延迟分别为
> 10.78/18.04 ms，单进程吞吐量约 88 runs/s。

必须保留“Mock Provider”“自建测试集”“两个模板”等限定，不能表述为真实 LLM
推理性能或通用意图识别准确率。
