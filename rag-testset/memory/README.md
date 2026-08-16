# 用户记忆评测集

- `quick.jsonl`：24 条提交前快速集，覆盖召回、冲突、过期、反馈和跨用户隔离。
- `formal.jsonl`：520 条正式集，用于周期性评测。
- `generate_memory_eval.py`：确定性生成器，可安全重复运行。

核心指标为 Recall@5、Recall@10、MRR、错误记忆率、矛盾率、跨用户泄漏率、上下文 Token 与 P95 延迟。跨用户泄漏率必须为 0；知识库 citation 与个人记忆来源必须分别统计。

运行：

```powershell
python .\rag-testset\memory\generate_memory_eval.py
```
