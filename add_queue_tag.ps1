$ErrorActionPreference = 'Stop'

$root = 'E:\Program\Obsidian\repo\YLcloud'
$updated = @()
$alreadyTagged = @()

$files = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notlike '*\archive\*' }

foreach($file in $files) {
    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    if($text -notmatch '\[\[待完成任务(?:[|#\]])') {
        continue
    }

    $frontmatterMatch = [regex]::Match($text, '\A---\r?\n(?<yaml>.*?)\r?\n---', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if(-not $frontmatterMatch.Success) {
        $text = "---`r`ntags:`r`n  - queue`r`n---`r`n`r`n" + $text
        [System.IO.File]::WriteAllText($file.FullName, $text, [System.Text.UTF8Encoding]::new($false))
        $updated += $file.FullName.Substring($root.Length + 1)
        continue
    }

    $yaml = $frontmatterMatch.Groups['yaml'].Value
    if($yaml -match '(?im)^\s*-\s*queue\s*$' -or $yaml -match '(?im)^tags:\s*\[[^\]]*(?:^|,\s*)queue(?:\s*,|\s*\])') {
        $alreadyTagged += $file.FullName.Substring($root.Length + 1)
        continue
    }

    if($yaml -match '(?m)^tags:\s*\[(?<items>[^\]]*)\]\s*$') {
        $newYaml = [regex]::Replace($yaml, '(?m)^tags:\s*\[(?<items>[^\]]*)\]\s*$', {
            param($m)
            $items = $m.Groups['items'].Value.Trim()
            if([string]::IsNullOrWhiteSpace($items)) { 'tags: [queue]' } else { "tags: [$items, queue]" }
        }, 1)
    } elseif($yaml -match '(?m)^tags:\s*$') {
        $lines = $yaml -split '\r?\n'
        $result = New-Object System.Collections.Generic.List[string]
        $inserted = $false
        for($i = 0; $i -lt $lines.Count; $i++) {
            $result.Add($lines[$i])
            if(-not $inserted -and $lines[$i] -match '^tags:\s*$') {
                $j = $i + 1
                while($j -lt $lines.Count -and $lines[$j] -match '^\s+-\s+') {
                    $result.Add($lines[$j])
                    $i = $j
                    $j++
                }
                $result.Add('  - queue')
                $inserted = $true
            }
        }
        $newYaml = $result -join "`r`n"
    } else {
        $newYaml = $yaml.TrimEnd() + "`r`ntags:`r`n  - queue"
    }

    $newFrontmatter = "---`r`n" + $newYaml + "`r`n---"
    $text = $text.Substring(0, $frontmatterMatch.Index) + $newFrontmatter + $text.Substring($frontmatterMatch.Index + $frontmatterMatch.Length)
    [System.IO.File]::WriteAllText($file.FullName, $text, [System.Text.UTF8Encoding]::new($false))
    $updated += $file.FullName.Substring($root.Length + 1)
}

[pscustomobject]@{
    UpdatedCount = $updated.Count
    AlreadyTaggedCount = $alreadyTagged.Count
}
'---UPDATED---'
$updated
'---ALREADY TAGGED---'
$alreadyTagged
