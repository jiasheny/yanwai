# Sync the pinned qingsheng-skill revision into app/src/main/assets/qingsheng.
#
# Why this exists: the app bundles a second advisor next to goutoujunshi. Upstream text stays
# verbatim - only whole sections that have no host inside an Xposed module are dropped, and the
# example library is cut along its own stage headings. Nothing is paraphrased, so what the app
# ships can always be diffed against upstream.
#
# Usage:
#   powershell -File tools/fetch-qingsheng.ps1                  # fetch pinned revision, regenerate
#   powershell -File tools/fetch-qingsheng.ps1 -SourceDir DIR   # use an existing checkout
#   powershell -File tools/fetch-qingsheng.ps1 -Check           # verify committed assets match
#
# NOTE: this file carries a UTF-8 BOM on purpose. Windows PowerShell 5.1 otherwise reads .ps1
# files as the system ANSI codepage and mangles the Chinese section titles used for matching.

param(
    [string]$SourceDir = "",
    [string]$Revision = "fd762df2e0ce4b8ec68b0087ba7178761045bd1a",
    [string]$RepoUrl = "https://github.com/tomwong001/qingsheng-skill.git",
    [switch]$Check
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root "app/src/main/assets/qingsheng"

# Whole sections removed by title prefix. Everything else is copied as-is.
$dropTitles = @(
    "Preamble",
    "开场白",
    "第负一步：上下文加载",
    "第负一步 · Gate A",
    "上下文归档"
)

# Reference slices cut from the example library along its own stage headings.
$sliceBuckets = [ordered]@{
    "examples-stage1-2.md" = @("阶段1", "阶段2")
    "examples-stage3-4.md" = @("阶段3", "阶段4")
    "examples-stage5-7.md" = @("阶段5", "阶段6", "阶段7", "挽回")
}

function Read-NormalizedLines([string]$path) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $text = $text -replace "`r`n", "`n" -replace "`r", "`n"
    return , ($text -split "`n")
}

function Write-Text([string]$path, [string]$text) {
    $parent = Split-Path -Parent $path
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $normalized = ($text -replace "`r`n", "`n" -replace "`r", "`n")
    [System.IO.File]::WriteAllText($path, $normalized, (New-Object System.Text.UTF8Encoding($false)))
}

function Remove-DroppedSections([string[]]$lines) {
    $kept = New-Object 'System.Collections.Generic.List[string]'
    $dropped = New-Object 'System.Collections.Generic.List[string]'
    $skipLevel = 0
    foreach ($line in $lines) {
        if ($line -match '^(#{2,3})\s+(.+?)\s*$') {
            $level = $Matches[1].Length
            $title = $Matches[2]
            if ($skipLevel -gt 0 -and $level -le $skipLevel) { $skipLevel = 0 }
            if ($skipLevel -eq 0) {
                foreach ($drop in $dropTitles) {
                    if ($title.StartsWith($drop)) { $skipLevel = $level; $dropped.Add($title); break }
                }
            }
        }
        if ($skipLevel -gt 0) { continue }
        $kept.Add($line)
    }
    return @{ Lines = $kept; Dropped = $dropped }
}

function Get-Level2Sections([string[]]$lines) {
    $pre = New-Object 'System.Collections.Generic.List[string]'
    $sections = New-Object 'System.Collections.Generic.List[object]'
    $current = $null
    foreach ($line in $lines) {
        if ($line -match '^##\s+(.+?)\s*$') {
            $current = [pscustomobject]@{
                Title = $Matches[1]
                Lines = (New-Object 'System.Collections.Generic.List[string]')
            }
            $current.Lines.Add($line)
            $sections.Add($current)
            continue
        }
        if ($null -eq $current) { $pre.Add($line) } else { $current.Lines.Add($line) }
    }
    return @{ Pre = $pre; Sections = $sections }
}

function Join-Lines($lines) {
    $text = ($lines -join "`n")
    return ($text.TrimEnd("`n") + "`n")
}

function Resolve-Source([string]$requested) {
    if ($requested) {
        if (-not (Test-Path (Join-Path $requested "skill/SKILL.md"))) {
            throw "SourceDir does not look like a qingsheng-skill checkout: $requested"
        }
        return (Resolve-Path $requested).Path
    }
    $temp = Join-Path $env:TEMP ("qingsheng-" + $Revision.Substring(0, 12))
    if (-not (Test-Path (Join-Path $temp "skill/SKILL.md"))) {
        if (Test-Path $temp) { Remove-Item -Recurse -Force $temp }
        New-Item -ItemType Directory -Force -Path $temp | Out-Null
        git -C $temp init -q
        git -C $temp remote add origin $RepoUrl
        git -C $temp fetch -q --depth 1 origin $Revision
        git -C $temp checkout -q FETCH_HEAD
    }
    return $temp
}

function New-Assets([string]$out, [string]$source) {
    $written = New-Object 'System.Collections.Generic.List[string]'
    $skillLines = Read-NormalizedLines (Join-Path $source "skill/SKILL.md")
    $result = Remove-DroppedSections $skillLines
    if ($result.Dropped.Count -ne $dropTitles.Count) {
        throw ("Expected " + $dropTitles.Count + " sections to drop, found " + $result.Dropped.Count +
            ": " + ($result.Dropped -join ", "))
    }

    Write-Text (Join-Path $out "upstream/SKILL.md") (Join-Lines $skillLines)
    $written.Add("upstream/SKILL.md")
    Write-Text (Join-Path $out "skill.md") (Join-Lines $result.Lines)
    $written.Add("skill.md")

    $refsSource = Join-Path $source "skill/references"
    foreach ($ref in (Get-ChildItem -File $refsSource | Sort-Object Name)) {
        $relative = "refs/" + $ref.Name
        Write-Text (Join-Path $out $relative) (Join-Lines (Read-NormalizedLines $ref.FullName))
        $written.Add($relative)
    }

    $library = Join-Path $out "refs/examples-library.md"
    if (-not (Test-Path $library)) { throw "Missing upstream example library at $library" }
    $parsed = Get-Level2Sections (Read-NormalizedLines $library)
    foreach ($name in $sliceBuckets.Keys) {
        $prefixes = $sliceBuckets[$name]
        $picked = New-Object 'System.Collections.Generic.List[string]'
        foreach ($line in $parsed.Pre) { $picked.Add($line) }
        $count = 0
        foreach ($section in $parsed.Sections) {
            foreach ($prefix in $prefixes) {
                if ($section.Title.StartsWith($prefix)) {
                    $picked.Add("")
                    foreach ($line in $section.Lines) { $picked.Add($line) }
                    $count++
                    break
                }
            }
        }
        if ($count -eq 0) { throw "No sections matched for slice $name" }
        $relative = "slices/" + $name
        Write-Text (Join-Path $out $relative) (Join-Lines $picked)
        $written.Add($relative)
    }

    Copy-Item (Join-Path $source "LICENSE") (Join-Path $out "LICENSE") -Force
    $written.Add("LICENSE")

    $version = "unknown"
    $versionFile = Join-Path $source "VERSION"
    if (Test-Path $versionFile) { $version = ([System.IO.File]::ReadAllText($versionFile)).Trim() }

    $hashes = New-Object 'System.Collections.Generic.List[string]'
    foreach ($relative in ($written | Sort-Object)) {
        $file = Join-Path $out $relative
        $sha = (Get-FileHash -Algorithm SHA256 $file).Hash.ToLower()
        $size = (Get-Item $file).Length
        $hashes.Add(("{0}  {1}  {2}" -f $sha, $size.ToString().PadLeft(8), $relative))
    }
    $sections = $result.Dropped | ForEach-Object { "  - " + $_ }
    $body = @(
        "qingsheng (情圣)",
        $RepoUrl,
        "revision: $Revision",
        "upstream VERSION: $version",
        "license: MIT, Copyright (c) 2026 tomwong001 and qingsheng-skill contributors (see LICENSE)",
        "",
        "Generated by tools/fetch-qingsheng.ps1. Do not hand-edit files in this directory;",
        "re-run the script instead, then diff.",
        "",
        "upstream/SKILL.md and refs/* are verbatim copies (line endings normalized to LF).",
        "skill.md removes these whole sections and changes nothing else:",
        "",
        "slices/* are cut from refs/examples-library.md along its own '## 阶段N' headings.",
        "",
        "Reason for the removal: an Xposed module has no shell, no writable home directory and no",
        "host-side window automation, so the version-check preamble, the standalone greeting, the",
        "profile bootstrap and the end-of-chat archiving have no host here. The app provides version",
        "checks, per-contact background and consent itself. Every other part of the upstream text,",
        "including the computer-use and web-search paragraphs, is kept verbatim; the bridge prompt in",
        "ReplyProtocol declares those parts inapplicable instead of deleting them.",
        "",
        "sha256  bytes  path"
    ) + $sections + @("", "") + $hashes
    Write-Text (Join-Path $out "SOURCE.txt") ($body -join "`n")

    return @{ Written = $written; Dropped = $result.Dropped; Version = $version }
}

$source = Resolve-Source $SourceDir
if ($Check) {
    $temp = Join-Path $env:TEMP ("qingsheng-check-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    New-Assets $temp $source | Out-Null
    $differences = New-Object 'System.Collections.Generic.List[string]'
    foreach ($file in (Get-ChildItem -Recurse -File $temp)) {
        $relative = $file.FullName.Substring($temp.Length + 1)
        $committed = Join-Path $assets $relative
        if (-not (Test-Path $committed)) { $differences.Add("missing: $relative"); continue }
        $left = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash
        $right = (Get-FileHash -Algorithm SHA256 $committed).Hash
        if ($left -ne $right) { $differences.Add("differs: $relative") }
    }
    Remove-Item -Recurse -Force $temp
    if ($differences.Count -gt 0) {
        $differences | ForEach-Object { Write-Host $_ }
        throw "Committed qingsheng assets differ from the pinned revision."
    }
    Write-Host "qingsheng assets match revision $Revision."
} else {
    $result = New-Assets $assets $source
    Write-Host ("qingsheng " + $result.Version + " <- " + $Revision.Substring(0, 12))
    Write-Host ("dropped sections: " + ($result.Dropped -join " | "))
    foreach ($relative in ($result.Written | Sort-Object)) {
        $file = Join-Path $assets $relative
        Write-Host ("  {0,8} bytes  {1}" -f (Get-Item $file).Length, $relative)
    }
}
