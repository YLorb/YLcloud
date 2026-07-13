$ErrorActionPreference = 'Stop'

$dir = 'E:\Program\Obsidian\repo\YLcloud'
$overviewPath = Join-Path $dir 'YLcloud 项目总览.md'
$summaryPath = Join-Path $dir '云端知识库对话总结.md'
$outputPath = Join-Path $dir 'YLcloud 项目总览与云端知识库对话总结.md'

function Remove-Frontmatter([string]$text) {
    return [regex]::Replace($text, '\A---\r?\n.*?\r?\n---\r?\n', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
}

function Demote-Headings([string]$text) {
    return [regex]::Replace($text, '(?m)^(#{1,5}) ', { param($m) ('#' + $m.Groups[1].Value + ' ') })
}

$overview = Get-Content -LiteralPath $overviewPath -Raw -Encoding UTF8
$summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8

$overview = Remove-Frontmatter $overview
$overview = $overview -replace '(?m)^# YLcloud 项目总览\s*', ''
$overview = $overview -replace '(?m)^<!-- codex:start -->\s*', ''
$overview = $overview -replace '(?m)^<!-- codex:end -->\s*', ''
$overview = Demote-Headings $overview.Trim()

$summary = $summary -replace '(?m)^# 2026-07-10 云端知识库对话总结\s*', ''
$summary = $summary -replace '(?m)^tags:.*\r?\n', ''
$summary = $summary -replace '(?m)^关联：.*\r?\n', ''
$summary = $summary -replace '(?m)^<!-- BEGIN CODEX -->\s*', ''
$summary = $summary -replace '(?m)^<!-- END CODEX -->\s*', ''
$summary = $summary -replace '\[\[项目总览\]\]', '[[YLcloud 项目总览]]'
$summary = Demote-Headings $summary.Trim()

$date = Get-Date -Format 'yyyy-MM-dd'
$header = @"
---
type: project-overview
project: YLcloud
created: 2026-07-10
updated: $date
aliases:
  - YLcloud 总览与对话总结
tags:
  - ylcloud
  - project-overview
  - codex-summary
  - rag
  - knowledge-base
sources:
  - "[[YLcloud 项目总览]]"
  - "[[云端知识库对话总结]]"
---

# YLcloud 项目总览与云端知识库对话总结

> [!info] 文档说明
> 本文合并自 [[YLcloud 项目总览]] 与 [[云端知识库对话总结]]。前半部分提供项目全景，后半部分保留 2026-07-10 对代码与验证状态的详细核实记录。

## 导航

- [[#第一部分：项目总览|第一部分：项目总览]]
- [[#第二部分：2026-07-10 云端知识库对话与核实记录|第二部分：2026-07-10 云端知识库对话与核实记录]]

## 第一部分：项目总览

"@

$middle = @"

---

## 第二部分：2026-07-10 云端知识库对话与核实记录

关联：[[YLcloud 项目总览]]、[[当前进度]]、[[Space RAG]]、[[Qdrant]]、[[BGE Embedding]]、[[BGE Reranker]]、[[LangChain4j]]、[[MinIO]]、[[Flyway]]

"@

$merged = $header + $overview + $middle + $summary + "`r`n"
[System.IO.File]::WriteAllText($outputPath, $merged, [System.Text.UTF8Encoding]::new($false))
Get-Item -LiteralPath $outputPath | Select-Object FullName, Length, LastWriteTime
