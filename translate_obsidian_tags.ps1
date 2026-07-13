$ErrorActionPreference = 'Stop'

$root = 'E:\Program\Obsidian\repo\YLcloud'
$map = @{
    'architecture' = '架构'
    'backlog' = '待办'
    'candidate-fusion' = '候选融合'
    'chunking' = '文本切片'
    'codex-summary' = 'Codex总结'
    'disabled' = '已禁用'
    'document-parsing' = '文档解析'
    'embedding' = '向量嵌入'
    'governance' = '治理'
    'hybrid-search' = '混合检索'
    'keyword-search' = '关键词检索'
    'knowledge-base' = '知识库'
    'knowledge-pipeline' = '知识库流水线'
    'modules' = '模块'
    'multi-query' = '多查询'
    'multi-route-retrieval' = '多路召回'
    'navigation' = '导航'
    'overview' = '总览'
    'parser-service' = '解析服务'
    'product' = '产品'
    'progress' = '进度'
    'project-overview' = '项目总览'
    'project-summary' = '项目总结'
    'qa' = '验收'
    'query-expansion' = '查询扩展'
    'query-planning' = '查询规划'
    'query-rewrite' = '查询改写'
    'queue' = '待办'
    'rag-enhancement' = 'RAG增强'
    'recursive-chunking' = '递归切片'
    'retrieval' = '检索'
    'retrieval-enhancement' = '检索增强'
    'structure-aware' = '结构感知'
    'structured-blocks' = '结构化块'
    'table-preservation' = '表格保护'
    'tasks' = '任务'
    'testing' = '测试'
    'vector-search' = '向量检索'
}

function Translate-Tag([string]$tag) {
    $clean = $tag.Trim().Trim('"').Trim("'")
    $key = $clean.ToLowerInvariant()
    if($map.ContainsKey($key)) { return $map[$key] }
    return $clean
}

function Unique-Tags([string[]]$tags) {
    $seen = @{}
    $result = New-Object System.Collections.Generic.List[string]
    foreach($tag in $tags) {
        $translated = Translate-Tag $tag
        if([string]::IsNullOrWhiteSpace($translated)) { continue }
        $key = $translated.ToLowerInvariant()
        if(-not $seen.ContainsKey($key)) {
            $seen[$key] = $true
            $result.Add($translated)
        }
    }
    return $result.ToArray()
}

$updated = @()
$files = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notlike '*\archive\*' }

foreach($file in $files) {
    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    $original = $text
    $fm = [regex]::Match($text, '\A---\r?\n(?<yaml>.*?)\r?\n---', [System.Text.RegularExpressions.RegexOptions]::Singleline)

    if($fm.Success) {
        $yaml = $fm.Groups['yaml'].Value

        if($yaml -match '(?m)^tags:\s*\[(?<items>[^\]]*)\]\s*$') {
            $newYaml = [regex]::Replace($yaml, '(?m)^tags:\s*\[(?<items>[^\]]*)\]\s*$', {
                param($m)
                $tags = Unique-Tags ($m.Groups['items'].Value -split ',')
                'tags: [' + ($tags -join ', ') + ']'
            }, 1)
        } elseif($yaml -match '(?m)^tags:\s*$') {
            $lines = $yaml -split '\r?\n'
            $result = New-Object System.Collections.Generic.List[string]
            for($i = 0; $i -lt $lines.Count; $i++) {
                if($lines[$i] -notmatch '^tags:\s*$') {
                    $result.Add($lines[$i])
                    continue
                }

                $result.Add('tags:')
                $rawTags = New-Object System.Collections.Generic.List[string]
                $j = $i + 1
                while($j -lt $lines.Count -and $lines[$j] -match '^\s+-\s+(.+)$') {
                    $rawTags.Add($matches[1])
                    $j++
                }
                foreach($tag in (Unique-Tags $rawTags.ToArray())) {
                    $result.Add('  - ' + $tag)
                }
                $i = $j - 1
            }
            $newYaml = $result -join "`r`n"
        } else {
            $newYaml = $yaml
        }

        $newFrontmatter = "---`r`n" + $newYaml + "`r`n---"
        $text = $text.Substring(0, $fm.Index) + $newFrontmatter + $text.Substring($fm.Index + $fm.Length)
    }

    $text = [regex]::Replace($text, '(?m)^tags:\s+(?<hashes>(?:#[^\s]+\s*)+)$', {
        param($m)
        $raw = [regex]::Matches($m.Groups['hashes'].Value, '#([^\s]+)') | ForEach-Object { $_.Groups[1].Value }
        $tags = Unique-Tags $raw
        'tags: ' + (($tags | ForEach-Object { '#' + $_ }) -join ' ')
    })

    if($text -cne $original) {
        [System.IO.File]::WriteAllText($file.FullName, $text, [System.Text.UTF8Encoding]::new($false))
        $updated += $file.FullName.Substring($root.Length + 1)
    }
}

[pscustomobject]@{ UpdatedCount = $updated.Count }
$updated
