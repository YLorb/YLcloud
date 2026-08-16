import json
from pathlib import Path

ROOT = Path(__file__).parent
TEMPLATES = [
    ("PREFERENCE", "用户明确偏好使用中文回答", "以后请始终用中文回答", "你应该用什么语言回答我？"),
    ("CONSTRAINT", "项目部署环境限定为 Windows 10", "这个项目只能部署到 Windows 10", "项目的部署环境限制是什么？"),
    ("DECISION", "用户已决定采用 Qdrant 作为向量数据库", "我们确定使用 Qdrant", "已经确定使用哪个向量数据库？"),
    ("FACT", "用户负责的项目名称是 YLCloud", "我负责的项目叫 YLCloud", "我负责的项目叫什么？"),
    ("PREFERENCE", "用户偏好简洁回答且先给结论", "回答请简洁并先给结论", "回答结构应遵循什么偏好？"),
    ("CONSTRAINT", "正式环境禁止访问公网", "生产环境不能访问公网", "生产环境的网络约束是什么？"),
    ("DECISION", "用户决定每周五执行正式评测", "正式评测定在每周五", "正式评测安排在什么时候？"),
    ("FACT", "用户团队使用 Java 17", "我们的服务端统一使用 Java 17", "服务端统一使用哪个 Java 版本？"),
]

def case(index: int, cross_user: bool = False):
    memory_type, memory, source, query = TEMPLATES[index % len(TEMPLATES)]
    user_id = index % 37 + 1
    return {
        "id": f"memory-{index + 1:04d}", "userId": user_id, "otherUserId": user_id + 1000,
        "source": source, "memoryType": memory_type, "expectedMemory": memory, "query": query,
        "expectedTopK": [memory], "mustNotRecall": [memory] if cross_user else [],
        "scenario": "cross_user_isolation" if cross_user else ["recall", "conflict", "expiry", "feedback"][index % 4],
        "metrics": ["recall@5", "recall@10", "mrr", "false_memory_rate", "conflict_rate", "cross_user_leakage", "context_tokens", "p95_latency_ms"]
    }

quick = [case(i, i % 5 == 0) for i in range(24)]
formal = [case(i, i % 7 == 0) for i in range(520)]
for name, rows in (("quick.jsonl", quick), ("formal.jsonl", formal)):
    (ROOT / name).write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
print(f"generated quick={len(quick)}, formal={len(formal)}")
