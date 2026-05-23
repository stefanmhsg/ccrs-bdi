param(
    [string]$SourceDir = "experiments\runs\latest",
    [string]$BatchId = ("manual-" + (Get-Date -Format "yyyyMMdd-HHmmss")),
    [string]$RunId,
    [string]$Jcm,
    [string[]]$AgentName = @(),
    [string]$Scenario = "ccrs",
    [string]$OutputRoot = "experiments\runs",
    [switch]$KeepSource,
    [string]$Notes
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param(
        [string]$RepoRoot,
        [string]$Path
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }

    return (Join-Path $RepoRoot $Path)
}

function ConvertTo-SafeName {
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return "manual"
    }

    $safe = $Text -replace "[^A-Za-z0-9._-]+", "_"
    $safe = $safe.Trim("_")
    if (-not $safe) {
        return "manual"
    }
    if ($safe.Length -gt 90) {
        return $safe.Substring(0, 90)
    }
    return $safe
}

function Expand-CommaSeparatedValues {
    param([string[]]$Values)

    $expanded = @()
    foreach ($value in $Values) {
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        foreach ($part in ($value -split ",")) {
            $trimmed = $part.Trim()
            if ($trimmed) {
                $expanded += $trimmed
            }
        }
    }
    return $expanded
}

function Get-CcrsMode {
    param([string]$JcmFile)

    if ($JcmFile -match "dfs_ccrs") { return "both" }
    if ($JcmFile -match "opportunistic") { return "opportunistic" }
    if ($JcmFile -match "contingency") { return "contingency" }
    if ($JcmFile) { return "none" }
    return "unknown"
}

function Get-JcmAgentNames {
    param([string]$JcmPath)

    if (-not $JcmPath -or -not (Test-Path -LiteralPath $JcmPath -PathType Leaf)) {
        return @()
    }

    $names = @()
    foreach ($line in Get-Content -Path $JcmPath) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^//" -or $trimmed -match "^/\*" -or $trimmed -match "^\*") {
            continue
        }

        if ($trimmed -match "^agent\s+([A-Za-z_][A-Za-z0-9_-]*)\s*:") {
            $names += $Matches[1]
        }
    }

    return $names
}

function New-UniqueDirectory {
    param([string]$BasePath)

    if (-not (Test-Path -LiteralPath $BasePath)) {
        return (New-Item -ItemType Directory -Path $BasePath)
    }

    for ($i = 1; $i -lt 1000; $i++) {
        $candidate = "$BasePath-$i"
        if (-not (Test-Path -LiteralPath $candidate)) {
            return (New-Item -ItemType Directory -Path $candidate)
        }
    }

    throw "Could not allocate a unique run directory for $BasePath"
}

function Get-JsonValue {
    param(
        $Record,
        [string]$Name
    )

    if ($null -eq $Record) {
        return $null
    }

    $property = $Record.PSObject.Properties[$Name]
    if ($property) {
        return $property.Value
    }

    return $null
}

function Get-EventAgentValues {
    param($Record)

    $values = @()
    $agent = Get-JsonValue $Record "agent"
    if ($agent) {
        $values += [string]$agent
    }

    $event = Get-JsonValue $Record "event"
    if ($event) {
        $nestedAgent = Get-JsonValue $event "agent"
        if ($nestedAgent) {
            $values += [string]$nestedAgent
        }
    }

    return @($values | Where-Object { $_ } | Select-Object -Unique)
}

function Get-EventType {
    param($Record)

    $type = Get-JsonValue $Record "type"
    if ($type) {
        return [string]$type
    }

    $event = Get-JsonValue $Record "event"
    if ($event) {
        $nestedType = Get-JsonValue $event "type"
        if ($nestedType) {
            return [string]$nestedType
        }
    }

    return $null
}

function Normalize-MaseExports {
    param(
        [System.IO.FileInfo[]]$Files,
        [string]$TargetPath
    )

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $writer = [System.IO.StreamWriter]::new($TargetPath, $false, $utf8NoBom)
    $eventCount = 0
    $parseErrors = 0
    $eventTypes = @{}
    $agents = @{}

    try {
        foreach ($file in $Files) {
            foreach ($line in Get-Content -LiteralPath $file.FullName) {
                if ([string]::IsNullOrWhiteSpace($line)) {
                    continue
                }

                try {
                    $record = $line | ConvertFrom-Json
                    $type = Get-EventType -Record $record
                    if ($type) {
                        $eventTypes[$type] = 1 + $(if ($eventTypes.ContainsKey($type)) { $eventTypes[$type] } else { 0 })
                    }
                    foreach ($agent in Get-EventAgentValues -Record $record) {
                        $agents[$agent] = $true
                    }
                    $writer.WriteLine($line)
                    $eventCount++
                } catch {
                    $parseErrors++
                }
            }
        }
    } finally {
        $writer.Dispose()
    }

    return [pscustomobject][ordered]@{
        eventCount = $eventCount
        parseErrors = $parseErrors
        eventTypes = $eventTypes
        agents = @($agents.Keys | Sort-Object)
    }
}

function Copy-OrMoveFile {
    param(
        [System.IO.FileInfo]$File,
        [string]$DestinationDirectory,
        [switch]$CopyOnly
    )

    $target = Join-Path $DestinationDirectory $File.Name
    if (Test-Path -LiteralPath $target) {
        $stem = [System.IO.Path]::GetFileNameWithoutExtension($File.Name)
        $extension = [System.IO.Path]::GetExtension($File.Name)
        for ($i = 1; $i -lt 1000; $i++) {
            $candidate = Join-Path $DestinationDirectory ("{0}-{1}{2}" -f $stem, $i, $extension)
            if (-not (Test-Path -LiteralPath $candidate)) {
                $target = $candidate
                break
            }
        }
    }

    if ($CopyOnly) {
        Copy-Item -LiteralPath $File.FullName -Destination $target
    } else {
        Move-Item -LiteralPath $File.FullName -Destination $target
    }

    return (Split-Path -Leaf $target)
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sourcePath = Resolve-RepoPath -RepoRoot $repoRoot -Path $SourceDir
$outputRootPath = Resolve-RepoPath -RepoRoot $repoRoot -Path $OutputRoot

if (-not (Test-Path -LiteralPath $sourcePath)) {
    New-Item -ItemType Directory -Path $sourcePath -Force | Out-Null
    throw "Created empty manual staging directory: $sourcePath. Export or move logs there, then rerun this script."
}

$sourceFullPath = (Resolve-Path -LiteralPath $sourcePath).Path
New-Item -ItemType Directory -Path $outputRootPath -Force | Out-Null

$agentNames = @(Expand-CommaSeparatedValues -Values $AgentName)
$jcmPath = $null
if ($Jcm) {
    $jcmPath = Resolve-RepoPath -RepoRoot $repoRoot -Path $Jcm
    if (-not (Test-Path -LiteralPath $jcmPath -PathType Leaf)) {
        throw "JCM file not found: $jcmPath"
    }

    if ($agentNames.Count -eq 0) {
        $agentNames = @(Get-JcmAgentNames -JcmPath $jcmPath)
    }
}

$batchDir = Join-Path $outputRootPath $BatchId
New-Item -ItemType Directory -Path $batchDir -Force | Out-Null

if (-not $RunId) {
    $existingCount = @(Get-ChildItem -Path $batchDir -Directory -ErrorAction SilentlyContinue).Count
    $suffix = if ($Jcm) { ConvertTo-SafeName -Text $Jcm } elseif ($agentNames.Count -gt 0) { ConvertTo-SafeName -Text $agentNames[0] } else { "manual" }
    $RunId = "{0:D3}-{1}" -f ($existingCount + 1), $suffix
}

$runDir = (New-UniqueDirectory -BasePath (Join-Path $batchDir (ConvertTo-SafeName -Text $RunId))).FullName
$sourceArchiveDir = Join-Path $runDir "source-exports"
New-Item -ItemType Directory -Path $sourceArchiveDir -Force | Out-Null

$sourceFiles = @(Get-ChildItem -LiteralPath $sourceFullPath -File)
if ($sourceFiles.Count -eq 0) {
    throw "Manual staging directory is empty: $sourceFullPath"
}

$maseExportFiles = @($sourceFiles | Where-Object {
    $_.Extension -in @(".ndjson", ".jsonl") -or
    ($_.Extension -eq ".json" -and $_.Name -match "mase-viewer|mase-events|event-archive|viewer-log")
})
$otherFiles = @($sourceFiles | Where-Object { $maseExportFiles.FullName -notcontains $_.FullName })
$logFiles = @($otherFiles | Where-Object { $_.Name -match "\.log(\..*)?$" -or $_.Name -like "mas-*.log*" })
$metadataFiles = @($otherFiles | Where-Object { $logFiles.FullName -notcontains $_.FullName })

$movedSourceFiles = @()
$copiedLogFiles = @()
$copiedMetadataFiles = @()
$maseSummary = [pscustomobject][ordered]@{
    eventCount = 0
    parseErrors = 0
    eventTypes = @{}
    agents = @()
}

if ($maseExportFiles.Count -gt 0) {
    $maseEventsPath = Join-Path $runDir "mase-events.jsonl"
    $maseSummary = Normalize-MaseExports -Files $maseExportFiles -TargetPath $maseEventsPath
    foreach ($file in $maseExportFiles) {
        $movedSourceFiles += Copy-OrMoveFile -File $file -DestinationDirectory $sourceArchiveDir -CopyOnly:$KeepSource
    }
}

foreach ($file in $logFiles) {
    $copiedLogFiles += Copy-OrMoveFile -File $file -DestinationDirectory $runDir -CopyOnly:$KeepSource
}

foreach ($file in $metadataFiles) {
    $copiedMetadataFiles += Copy-OrMoveFile -File $file -DestinationDirectory $sourceArchiveDir -CopyOnly:$KeepSource
}

if ($agentNames.Count -eq 0 -and $maseSummary.agents.Count -gt 0) {
    $agentNames = @($maseSummary.agents | ForEach-Object { ($_ -replace "^.*/", "").TrimEnd("/") } | Where-Object { $_ } | Select-Object -Unique)
}

$importedAt = Get-Date
$ccrsMode = Get-CcrsMode -JcmFile $Jcm
$maseCaptureStatus = if ($maseSummary.parseErrors -gt 0) {
    "partial"
} elseif ($maseSummary.eventCount -gt 0) {
    "completed"
} elseif ($maseExportFiles.Count -gt 0) {
    "empty"
} else {
    "disabled"
}

$run = [ordered]@{
    batchId = $BatchId
    runId = Split-Path -Leaf $runDir
    repetition = $null
    jcm = if ($Jcm) { $Jcm } else { "manual" }
    agentType = "manual"
    ccrsMode = $ccrsMode
    scenario = $Scenario
    command = "manual"
    importedAt = $importedAt.ToString("o")
    sourceDir = $sourceFullPath
    durationMs = $null
    timeout = $false
    interrupted = $false
    exitCode = $null
    status = "manual_import"
    runStopReason = "manual_import"
    runTerminalPattern = $null
    runTerminalLine = $null
    runTerminalLineNumber = $null
    logFiles = $copiedLogFiles
    sourceExportFiles = $movedSourceFiles
    metadataFiles = $copiedMetadataFiles
    agentNames = $agentNames
    maseMode = "manual"
    maseEventCapture = if ($maseExportFiles.Count -gt 0) { "viewer_export" } else { "none" }
    maseCaptureStatus = $maseCaptureStatus
    maseCaptureFile = if ($maseSummary.eventCount -gt 0) { "mase-events.jsonl" } else { $null }
    maseCaptureEventCount = $maseSummary.eventCount
    maseCaptureParseErrors = $maseSummary.parseErrors
    maseCaptureAgents = $maseSummary.agents
    maseEventTypeCounts = $maseSummary.eventTypes
    notes = $Notes
}

$runPath = Join-Path $runDir "run.json"
$run | ConvertTo-Json -Depth 12 | Set-Content -Path $runPath -Encoding UTF8

$runs = @()
foreach ($dir in Get-ChildItem -Path $batchDir -Directory | Sort-Object Name) {
    $candidateRunJson = Join-Path $dir.FullName "run.json"
    if (Test-Path -LiteralPath $candidateRunJson) {
        $runs += (Get-Content -Path $candidateRunJson -Raw | ConvertFrom-Json)
    }
}

[ordered]@{
    batchId = $BatchId
    createdOrUpdatedAt = (Get-Date).ToString("o")
    repoRoot = $repoRoot
    sourceDir = $sourceFullPath
    importedBy = "experiments/scripts/import-manual-run.ps1"
    runs = $runs
} | ConvertTo-Json -Depth 12 | Set-Content -Path (Join-Path $batchDir "manifest.json") -Encoding UTF8

Write-Host "Imported manual run: $($run.runId)"
Write-Host "Run directory: $runDir"
Write-Host "MASE events: $($maseSummary.eventCount); parse errors: $($maseSummary.parseErrors)"
if (-not $KeepSource) {
    Write-Host "Source files were moved from: $sourceFullPath"
} else {
    Write-Host "Source files were copied; staging directory was left unchanged."
}
Write-Host "Generate or refresh the report with:"
Write-Host "  powershell -ExecutionPolicy Bypass -File experiments/scripts/write-report.ps1 -BatchId $BatchId"
