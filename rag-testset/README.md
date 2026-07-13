# YLcloud RAG 测试集方案（v0.1）

## 1. 目标

基于 `E:\Program\Obsidian\repo\YLcloud` 的非归档文档，建立一套可重复执行、能同时评估“检索”和“回答”的中文 RAG 测试集。首版重点验证：

- 专有名词、类名、配置项的精确召回；
- Query Rewrite、Multi-Query、HyDE、Step-Back、BM25、Qdrant、RRF、Rerank 等链路知识；
- 已实现、未实现、仅预留、未验证四种状态的准确区分；
- 跨文档综合与对比推理；
- 面对知识库没有依据的问题时正确拒答。

## 2. 数据范围与权威性

语料范围：Obsidian 仓库根目录的权威入口及 `RAG/` 专题页；`archive/` 不进入首版语料。

冲突处理优先级：

1. `当前进度.md`、`待完成任务.md`；
2. `验收与测试.md`、专题能力页；
3. 历史对话总结和整理合并稿。

每条样本记录证据文档和标题。文档更新后，可据此定位并回归维护。

## 3. 样本结构

数据文件采用 JSONL，一行一个对象。关键字段：

- `id`：稳定编号；
- `question`：测试问题；
- `answer`：参考答案；
- `type`：`factoid`、`status`、`comparison`、`multi_hop` 或 `unanswerable`；
- `difficulty`：`easy`、`medium`、`hard`；
- `retrieval_mode`：主要考察的召回方式；
- `evidence`：相关文档与标题；
- `required_points`：答案判分要点；
- `must_not_claim`：禁止误答或过度推断；
- `is_answerable`：语料是否足以回答。

## 4. 首版构成

首版共 38 条，覆盖事实、状态/边界、比较、跨文档综合和不可回答问题。题目刻意包含中英文混合、类名、配置名、口语化问法和否定表达，并为 12 个 RAG 功能专题分别设置了直接证据样本。

建议在后续扩展到 100—200 条，并按 70%/15%/15% 划分 train/dev/test。隐藏 test 的参考答案，避免调参污染。

## 5. 评测方法

检索评测：

- `Recall@5`、`Recall@10`：证据文档是否进入 Top-K；
- `MRR@10`：首个相关证据的排名；
- `NDCG@10`：多证据题的整体排序质量；
- 按 `retrieval_mode`、`type`、`difficulty` 分桶统计。

生成评测：

- 要点覆盖率：`required_points` 命中比例；
- 忠实度：答案是否完全由 evidence 支撑；
- 状态准确率：是否正确区分“代码存在”和“真实 E2E 已证明”；
- 拒答准确率：不可回答题是否拒绝编造；
- 引用准确率：引用是否指向支持当前结论的文档。

建议首轮门槛：Recall@5 ≥ 0.85、MRR@10 ≥ 0.70、要点覆盖率 ≥ 0.85、忠实度 ≥ 0.95、不可回答题拒答率 = 1.00。门槛是测试建议，不代表当前系统已经达到。

## 6. 执行流程

1. 导入非归档 Markdown，并保存 `source_path`、标题层级、更新时间等 metadata。
2. 对每个问题执行检索，记录 Top-10 chunk、分数和 route。
3. 用相同上下文生成答案，要求返回引用。
4. 将检索结果与 `evidence` 对齐，将回答与 `required_points`、`must_not_claim` 对齐。
5. 分桶比较纯向量、BM25、混合召回、RRF、Rerank 的增益。
6. 对失败样本归因：语料缺失、切片问题、召回遗漏、排序错误或生成幻觉。

## 7. 文件

- `ylcloud-rag-testset-v0.1.jsonl`：首版测试集。
