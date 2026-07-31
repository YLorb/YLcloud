---
version: "1.0"
name: markdown_embedded_workflow
description: Markdown 内嵌 Workflow 示例，演示从 Markdown  frontmatter 与 ## 节点标题中加载 workflow。
inputs:
  goal: 总结 AI Agent 工作流发展趋势
outputs:
  - final_answer
---

## start
type: start
next: plan

## plan
type: llm
prompt: "请为这个目标生成 3 个搜索关键词：{{ goal }}"
output: keywords
next: search

## search
type: tool
tool: mock_search
input: "{{ keywords }}"
output: search_results
next: summarize

## summarize
type: llm
prompt: "请总结这些资料：{{ search_results }}"
output: final_answer
next: end

## end
type: end
