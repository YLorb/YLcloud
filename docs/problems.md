
  - embed/rerank 当前返回 offline-fallback:*，所以连通性通过，但按文档定义不算真实 embedding/rerank 质量验证。
  - 无关问题返回了正确 no-answer：无法从当前知识库回答。，但响应里仍带了 5 个 citations。
    这不完全符合 checklist 的“no unrelated recent chunks are used”。建议后续增加相似度/相关性阈值过滤：当最终判断 no-answer 时，不返回 citations，或在检索阶段过滤低相关 chunk。


