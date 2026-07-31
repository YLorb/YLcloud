from __future__ import annotations

import json
import math
import statistics
import time
import tracemalloc
from collections import Counter
from pathlib import Path
from typing import Any

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor
from mini_agent_flow.engine.loader import WorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.planner.catalog import WorkflowTemplateCatalog
from mini_agent_flow.planner.selector import NoMatchingTemplateError, RuleBasedTemplateSelector
from mini_agent_flow.planner.service import Level2WorkflowService
from mini_agent_flow.tools.builtin import create_default_tool_registry


PROJECT_ROOT = Path(__file__).resolve().parents[1]
TEMPLATE_DIR = PROJECT_ROOT / "templates"
PERFORMANCE_ITERATIONS = 3_000
WARMUP_ITERATIONS = 100
MEMORY_ITERATIONS = 100

LABELED_CASES: list[tuple[str, str | None]] = [
    ("分析这个 Python traceback 并给出修复方向", "python_error_analyzer"),
    ("Python 程序启动时报错，应该怎么排查", "python_error_analyzer"),
    ("这个 Python 异常为什么会发生", "python_error_analyzer"),
    ("帮我 debug 一段 Python 数据处理代码", "python_error_analyzer"),
    ("解释这段 traceback 的根因", "python_error_analyzer"),
    ("Django 请求出现异常，请提供定位步骤", "python_error_analyzer"),
    ("Python 导入模块时报错", "python_error_analyzer"),
    ("debug 这个列表越界问题", "python_error_analyzer"),
    ("处理 Python TypeError 报错", "python_error_analyzer"),
    ("分析 traceback 中最后一行", "python_error_analyzer"),
    ("Python 单元测试出现异常", "python_error_analyzer"),
    ("这个报错可能由哪些原因导致", "python_error_analyzer"),
    ("帮我 debug FastAPI 启动失败", "python_error_analyzer"),
    ("Python 脚本读取数据时报错", "python_error_analyzer"),
    ("定位这个运行时异常并给建议", "python_error_analyzer"),
    ("调研 Agent Workflow 的发展趋势", "research_summarizer"),
    ("搜索近期 AI Agent 资料并总结", "research_summarizer"),
    ("总结工作流引擎的核心设计", "research_summarizer"),
    ("分析 MCP 生态的发展趋势", "research_summarizer"),
    ("research current workflow orchestration approaches", "research_summarizer"),
    ("调研主流 Agent 框架", "research_summarizer"),
    ("搜索 Tool Calling 相关资料", "research_summarizer"),
    ("总结 LangGraph 的主要能力", "research_summarizer"),
    ("研究 AI 应用开发趋势", "research_summarizer"),
    ("调研 RAG 系统的技术路线", "research_summarizer"),
    ("搜索并整理模型评测方法", "research_summarizer"),
    ("总结多 Agent 系统的组成", "research_summarizer"),
    ("research MCP protocol adoption", "research_summarizer"),
    ("分析智能体平台市场趋势", "research_summarizer"),
    ("调研开源 Workflow Builder", "research_summarizer"),
    ("设计一个登录页面", None),
    ("计算三十年房贷月供", None),
    ("把这段中文翻译成英文", None),
    ("制定上海三日旅行计划", None),
    ("写一条 SQL 聚合查询", None),
    ("生成新品营销文案", None),
    ("安排下周项目会议", None),
    ("制作销售数据可视化", None),
    ("优化我的后端开发简历", None),
    ("生成一份团队周报", None),
]


def create_components() -> tuple[WorkflowTemplateCatalog, RuleBasedTemplateSelector, Level2WorkflowService]:
    """创建与 CLI 相同的 Level 2 组件。"""

    registry = create_default_tool_registry()
    validator = WorkflowValidator(allowed_tools=registry.names())
    loader = WorkflowLoader(validator=validator)
    catalog = WorkflowTemplateCatalog(TEMPLATE_DIR, loader)
    selector = RuleBasedTemplateSelector()
    executor = SequentialWorkflowExecutor(MockLLM(), registry)
    service = Level2WorkflowService(catalog, selector, validator, executor)
    return catalog, selector, service


def evaluate_selection(
    catalog: WorkflowTemplateCatalog,
    selector: RuleBasedTemplateSelector,
) -> dict[str, Any]:
    """在固定标注集上计算分类和域外拒绝指标。"""

    candidates = catalog.load()
    outcomes: Counter[str] = Counter()
    errors: list[dict[str, str | None]] = []
    evaluations: list[tuple[str | None, str | None]] = []

    for goal, expected in LABELED_CASES:
        try:
            actual = selector.select(goal, candidates).candidate.workflow.name
        except NoMatchingTemplateError:
            actual = None

        evaluations.append((expected, actual))

        if actual == expected:
            outcomes["correct"] += 1
        else:
            outcomes["incorrect"] += 1
            errors.append({"goal": goal, "expected": expected, "actual": actual})

    in_domain = [result for result in evaluations if result[0] is not None]
    out_of_domain = [result for result in evaluations if result[0] is None]
    in_domain_correct = sum(expected == actual for expected, actual in in_domain)
    rejected = sum(actual is None for _, actual in out_of_domain)

    return {
        "dataset_size": len(LABELED_CASES),
        "in_domain_cases": len(in_domain),
        "out_of_domain_cases": len(out_of_domain),
        "overall_accuracy_percent": round(outcomes["correct"] / len(LABELED_CASES) * 100, 2),
        "in_domain_accuracy_percent": round(in_domain_correct / len(in_domain) * 100, 2),
        "out_of_domain_rejection_percent": round(rejected / len(out_of_domain) * 100, 2),
        "errors": errors,
    }


def percentile(values: list[float], percent: float) -> float:
    """返回使用 nearest-rank 方法计算的百分位数。"""

    ordered = sorted(values)
    index = max(0, math.ceil(percent * len(ordered)) - 1)
    return ordered[index]


def evaluate_performance(service: Level2WorkflowService) -> dict[str, Any]:
    """测量完整 Level 2 Mock 执行链路的延迟、吞吐量和峰值分配。"""

    goals = (
        "分析这个 Python traceback 报错",
        "调研 Agent Workflow 的发展趋势并总结",
    )
    for index in range(WARMUP_ITERATIONS):
        service.run(goals[index % len(goals)])

    durations_ms: list[float] = []
    total_start = time.perf_counter()
    for index in range(PERFORMANCE_ITERATIONS):
        start = time.perf_counter()
        service.run(goals[index % len(goals)])
        durations_ms.append((time.perf_counter() - start) * 1_000)
    total_seconds = time.perf_counter() - total_start

    tracemalloc.start()
    for index in range(MEMORY_ITERATIONS):
        service.run(goals[index % len(goals)])
    _, peak_bytes = tracemalloc.get_traced_memory()
    tracemalloc.stop()

    return {
        "iterations": PERFORMANCE_ITERATIONS,
        "total_seconds": round(total_seconds, 4),
        "mean_latency_ms": round(statistics.fmean(durations_ms), 4),
        "p50_latency_ms": round(percentile(durations_ms, 0.50), 4),
        "p95_latency_ms": round(percentile(durations_ms, 0.95), 4),
        "throughput_runs_per_second": round(PERFORMANCE_ITERATIONS / total_seconds, 2),
        "memory_sample_iterations": MEMORY_ITERATIONS,
        "peak_traced_memory_mib": round(peak_bytes / 1024 / 1024, 3),
    }


def main() -> None:
    """运行固定数据集准确率与本机性能测试。"""

    catalog, selector, service = create_components()
    report = {
        "selection": evaluate_selection(catalog, selector),
        "performance": evaluate_performance(service),
        "notes": {
            "provider": "MockLLM + local mock tools",
            "templates": 2,
            "performance_scope": "catalog load, validation, selection, input fill, execution, trace",
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
