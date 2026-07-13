$ErrorActionPreference = 'Stop'

$root = 'E:\Program\Obsidian\repo\YLcloud'

$replacements = [ordered]@{
    '[[项目总览]]' = '[[project-overview|项目总览]]'
    '[[YLcloud]]' = '[[project-overview|YLcloud]]'
    '[[YLcloud 项目总览]]' = '[[project-overview|YLcloud 项目总览]]'
    '[[RAG]]' = '[[RAG/RAG导航|RAG]]'
    '[[RAG 检索与问答]]' = '[[RAG/RAG导航|RAG 检索与问答]]'
    '[[Space RAG]]' = '[[RAG/RAG导航|Space RAG]]'
    '[[RAG 知识库]]' = '[[RAG/RAG导航|RAG 知识库]]'
    '[[LLM 问答]]' = '[[RAG/RAG导航|LLM 问答]]'
    '[[Embedding]]' = '[[RAG/RAG导航|Embedding]]'
    '[[BGE Embedding]]' = '[[RAG/RAG导航|BGE Embedding]]'
    '[[BGE-M3]]' = '[[RAG/RAG导航|BGE-M3]]'
    '[[Qdrant]]' = '[[RAG/RAG功能问答-Qdrant向量检索|Qdrant]]'
    '[[BGE Reranker]]' = '[[RAG/RAG功能问答-Rerank重排|BGE Reranker]]'
    '[[Rerank]]' = '[[RAG/RAG功能问答-Rerank重排|Rerank]]'
    '[[Query Rewrite]]' = '[[RAG/RAG功能问答-Query润色与查询规划|Query Rewrite]]'
    '[[QueryPlan]]' = '[[RAG/RAG功能问答-Query润色与查询规划|QueryPlan]]'
    '[[多路召回]]' = '[[RAG/RAG功能问答-多路召回与候选融合|多路召回]]'
    '[[结构感知切片]]' = '[[RAG/RAG功能问答-结构感知切片|结构感知切片]]'
    '[[文档切片]]' = '[[RAG/RAG功能问答-结构感知切片|文档切片]]'
    '[[文档解析]]' = '[[RAG/RAG功能问答-结构化文档解析|文档解析]]'
    '[[知识库流水线]]' = '[[知识库流水线]]'
    '[[Knowledge Pipeline]]' = '[[知识库流水线|Knowledge Pipeline]]'
    '[[KnowledgeOps]]' = '[[知识库流水线|KnowledgeOps]]'
    '[[Pipeline 设计约束]]' = '[[知识库流水线|Pipeline 设计约束]]'
    '[[增量更新策略]]' = '[[知识库流水线|增量更新策略]]'
    '[[画像版本管理]]' = '[[知识库流水线|画像版本管理]]'
    '[[知识图谱]]' = '[[知识库流水线|知识图谱]]'
    '[[图数据库]]' = '[[知识库流水线|图数据库]]'
    '[[cloud-server]]' = '[[系统模块索引|cloud-server]]'
    '[[cloud-frontend]]' = '[[系统模块索引|cloud-frontend]]'
    '[[model-service]]' = '[[系统模块索引|model-service]]'
    '[[document-parser-service]]' = '[[系统模块索引|document-parser-service]]'
    '[[Flyway]]' = '[[系统模块索引|Flyway]]'
    '[[MinIO]]' = '[[系统模块索引|MinIO]]'
    '[[MySQL]]' = '[[系统模块索引|MySQL]]'
    '[[Docker Compose]]' = '[[系统模块索引|Docker Compose]]'
    '[[消息队列]]' = '[[系统模块索引|消息队列]]'
    '[[模型服务]]' = '[[系统模块索引|模型服务]]'
    '[[生产配置]]' = '[[系统模块索引|生产配置]]'
    '[[LangChain4j]]' = '[[系统模块索引|LangChain4j]]'
    '[[用户认证]]' = '[[产品模块索引|用户认证]]'
    '[[文件上传]]' = '[[产品模块索引|文件上传]]'
    '[[分片上传]]' = '[[产品模块索引|分片上传]]'
    '[[大文件分片上传]]' = '[[产品模块索引|大文件分片上传]]'
    '[[文件管理]]' = '[[产品模块索引|文件管理]]'
    '[[文件分享]]' = '[[产品模块索引|文件分享]]'
    '[[Space]]' = '[[产品模块索引|Space]]'
    '[[Space 空间]]' = '[[产品模块索引|Space 空间]]'
    '[[Knowledge Base]]' = '[[产品模块索引|Knowledge Base]]'
    '[[知识库]]' = '[[产品模块索引|知识库]]'
    '[[Knowledge Base 前端]]' = '[[产品模块索引|Knowledge Base 前端]]'
    '[[Knowledge Chat 后端]]' = '[[产品模块索引|Knowledge Chat 后端]]'
    '[[多知识库问答后端]]' = '[[产品模块索引|多知识库问答后端]]'
    '[[RAG Analytics 后端]]' = '[[产品模块索引|RAG Analytics 后端]]'
    '[[存储配额后端]]' = '[[产品模块索引|存储配额后端]]'
    '[[前端知识库页面]]' = '[[产品模块索引|前端知识库页面]]'
    '[[前端]]' = '[[前端重构|前端]]'
    '[[历史版本]]' = '[[产品模块索引|历史版本]]'
    '[[历史版本与 RAG 联动]]' = '[[产品模块索引|历史版本与 RAG 联动]]'
    '[[产品设计实习生]]' = '[[产品模块索引|产品设计实习生]]'
    '[[端到端验证]]' = '[[验收与测试|端到端验证]]'
    '[[权限验证]]' = '[[验收与测试|权限验证]]'
    '[[测试指南]]' = '[[验收与测试|测试指南]]'
    '[[知识库流水线 E2E]]' = '[[验收与测试|知识库流水线 E2E]]'
    '[[archive]]' = '[[文档导航#历史核实记录|历史核实记录]]'
    '[[2026-07-10-对话总结-RAG项目进度]]' = '[[RAG/RAG项目进度（整理合并）|2026-07-10 RAG 项目进度]]'
    '[[RAG/RAG项目进度]]' = '[[RAG/RAG项目进度（整理合并）|RAG 项目进度]]'
    '[[2026-07-10-RAG与知识库验收对话总结]]' = '[[RAG/RAG与知识库验收对话总结（整理合并）|2026-07-10 RAG 验收总结]]'
    '[[RAG/RAG与知识库验收对话总结]]' = '[[RAG/RAG与知识库验收对话总结（整理合并）|RAG 验收总结]]'
    '[[2026-07-10-知识库流水线进度核实]]' = '[[知识库流水线进度核实|2026-07-10 知识库流水线进度核实]]'
    '[[2026-07-10 对话总结]]' = '[[YLcloud 对话总结（当前分支完整合并）|2026-07-10 对话总结]]'
    '[[2026-07-10-对话总结]]' = '[[YLcloud 对话总结（当前分支完整合并）|2026-07-10 对话总结]]'
    '[[2026-07-10 对话总结（整理合并）]]' = '[[YLcloud 对话总结（当前分支完整合并）|2026-07-10 对话总结（整理合并）]]'
    '[[对话？总结]]' = '[[YLcloud 对话总结（当前分支完整合并）|对话总结旧稿]]'
    '[[对话总结？]]' = '[[YLcloud 对话总结（当前分支完整合并）|对话总结旧稿]]'
}

function Get-RelationBlock([string]$relativePath) {
    $parent = '[[文档导航]]'
    $related = '[[project-overview|项目总览]] · [[当前进度]] · [[待完成任务]]'
    $note = '当前任务状态统一同步到根目录权威文档。'

    if($relativePath -like 'RAG\RAG功能问答-*') {
        $parent = '[[RAG/RAG导航|RAG 导航]]'
        $related = '[[验收与测试]] · [[当前进度]] · [[待完成任务]]'
        $note = '本页维护能力说明和代码证据；未完成项汇总到根目录任务清单。'
    } elseif($relativePath -like 'RAG\*') {
        $parent = '[[RAG/RAG导航|RAG 导航]]'
        $related = '[[验收与测试]] · [[项目分析]] · [[当前进度]] · [[待完成任务]]'
        $note = '本页属于 RAG 历史核实或设计记录；当前状态以根目录权威文档为准。'
    } elseif($relativePath -eq '知识库流水线进度核实.md') {
        $parent = '[[知识库流水线]]'
        $related = '[[RAG/RAG导航|RAG 导航]] · [[验收与测试]] · [[当前进度]] · [[待完成任务]]'
        $note = '本页维护 Pipeline 专项证据；任务同步到根目录任务清单。'
    } elseif($relativePath -eq '前端重构.md') {
        $parent = '[[产品模块索引]]'
        $related = '[[系统模块索引]] · [[验收与测试]] · [[当前进度]] · [[待完成任务]]'
        $note = '本页维护前端专题设计与实现状态；执行任务同步到根目录。'
    } elseif($relativePath -eq '项目分析.md') {
        $parent = '[[project-overview|项目总览]]'
        $related = '[[系统模块索引]] · [[验收与测试]] · [[当前进度]] · [[待完成任务]]'
        $note = '本页维护证据链，不作为唯一任务状态来源。'
    } elseif($relativePath -match '对话总结|云端知识库') {
        $parent = '[[文档导航#历史核实记录|历史核实记录]]'
        $related = '[[project-overview|项目总览]] · [[项目分析]] · [[当前进度]] · [[待完成任务]]'
        $note = '本页保留历史上下文；其中任务已去重同步到根目录。'
    }

    return @"
<!-- relations:start -->
> [!abstract] 文档关系
> 上级：$parent
> 相关：$related
> $note
<!-- relations:end -->
"@
}

$skip = @(
    'project-overview.md', '当前进度.md', '待完成任务.md', '文档导航.md',
    '系统模块索引.md', '产品模块索引.md', '验收与测试.md', '知识库流水线.md',
    'RAG\RAG导航.md'
)

$files = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notlike '*\archive\*' }

foreach($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1)
    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8

    foreach($entry in $replacements.GetEnumerator()) {
        $text = $text.Replace($entry.Key, $entry.Value)
    }
    $text = $text.Replace('[[ylcloud]]', '[[project-overview|YLcloud]]')

    if($skip -notcontains $relative) {
        $text = [regex]::Replace($text, '(?ms)\r?\n?<!-- relations:start -->.*?<!-- relations:end -->\r?\n?', "`r`n")
        $block = Get-RelationBlock $relative
        $match = [regex]::Match($text, '(?m)^# .+$')
        if($match.Success) {
            $insertAt = $match.Index + $match.Length
            $text = $text.Insert($insertAt, "`r`n`r`n" + $block.TrimEnd())
        }
    }

    [System.IO.File]::WriteAllText($file.FullName, $text, [System.Text.UTF8Encoding]::new($false))
}

Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notlike '*\archive\*' } |
    Select-Object FullName, Length, LastWriteTime
