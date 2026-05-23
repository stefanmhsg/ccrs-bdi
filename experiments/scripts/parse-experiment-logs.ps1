param(
    [string]$BatchId,
    [string]$RunRoot,
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"

$optimalPathLengthMoves = 118

function ConvertTo-TypedValue {
    param([string]$Text)

    if ($null -eq $Text -or $Text -eq "null") { return $null }
    if ($Text -eq "true") { return $true }
    if ($Text -eq "false") { return $false }

    $number = 0.0
    if ([double]::TryParse(
        $Text,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$number
    )) {
        if ($Text -notmatch "[\.\-+eE]") {
            return [int64]$number
        }
        return $number
    }

    return $Text
}

function Unescape-QuotedValue {
    param([string]$Text)

    if ($null -eq $Text) { return $null }
    $value = $Text
    $value = $value.Replace('\t', "`t")
    $value = $value.Replace('\r', "`r")
    $value = $value.Replace('\n', "`n")
    $value = $value.Replace('\"', '"')
    $value = $value.Replace('\\', '\')
    return $value
}

function ConvertFrom-KeyValueLine {
    param([string]$Line)

    $prefixes = @("[CCRS-EVENT]", "[METRIC]")
    $start = -1
    $prefix = $null
    foreach ($candidate in $prefixes) {
        $idx = $Line.IndexOf($candidate)
        if ($idx -ge 0) {
            $start = $idx + $candidate.Length
            $prefix = $candidate
            break
        }
    }

    if ($start -lt 0) {
        return $null
    }

    $payload = $Line.Substring($start).Trim()
    $result = @{}
    $result["_prefix"] = $prefix

    $regex = [regex]'([A-Za-z_][A-Za-z0-9_.-]*)=("(?:\\.|[^"\\])*"|[^ \t\r\n]+)'
    foreach ($match in $regex.Matches($payload)) {
        $key = $match.Groups[1].Value
        $raw = $match.Groups[2].Value
        if ($raw.StartsWith('"') -and $raw.EndsWith('"')) {
            $raw = Unescape-QuotedValue -Text $raw.Substring(1, $raw.Length - 2)
        }
        $result[$key] = ConvertTo-TypedValue -Text $raw
    }

    return $result
}

function Get-MapValue {
    param(
        $Map,
        [string[]]$Key,
        $Default = $null
    )

    if (-not $Map) {
        return $Default
    }

    foreach ($candidate in $Key) {
        if ($Map -is [System.Collections.IDictionary] -and $Map.Contains($candidate)) {
            return $Map[$candidate]
        }

        $property = $Map.PSObject.Properties[$candidate]
        if ($property) {
            return $property.Value
        }
    }

    return $Default
}

function New-RunMeta {
    param(
        [System.IO.DirectoryInfo]$RunDir,
        $Raw
    )

    [pscustomobject][ordered]@{
        batchId = Get-MapValue $Raw @("batchId", "batch_id") (Split-Path -Leaf (Split-Path -Parent $RunDir.FullName))
        runId = Get-MapValue $Raw @("runId", "run_id") $RunDir.Name
        repetition = Get-MapValue $Raw "repetition"
        jcm = Get-MapValue $Raw "jcm" "unknown"
        agentType = Get-MapValue $Raw @("agentType", "agent_type") "unknown"
        ccrsMode = Get-MapValue $Raw @("ccrsMode", "ccrs_mode") "unknown"
        scenario = Get-MapValue $Raw "scenario" "unknown"
        agentNames = @(Get-MapValue $Raw @("agentNames", "maseCaptureAgentNames", "mase_capture_agent_names") @())
        durationMs = Get-MapValue $Raw @("durationMs", "duration_ms")
        timeout = Get-MapValue $Raw @("timeout", "timed_out") $false
        exitCode = Get-MapValue $Raw @("exitCode", "exit_code")
        status = Get-MapValue $Raw "status" "unknown"
        runStopReason = Get-MapValue $Raw @("runStopReason", "run_stop_reason")
        runTerminalPattern = Get-MapValue $Raw @("runTerminalPattern", "run_terminal_pattern")
        runTerminalLine = Get-MapValue $Raw @("runTerminalLine", "run_terminal_line")
    }
}

function ConvertTo-Bool {
    param($Value)

    if ($Value -is [bool]) { return $Value }
    return "$Value".ToLowerInvariant() -eq "true"
}

function Get-RegexValue {
    param(
        [string]$Text,
        [string]$Pattern
    )

    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) {
        return $null
    }

    for ($i = 1; $i -lt $match.Groups.Count; $i++) {
        if ($match.Groups[$i].Success -and $match.Groups[$i].Value -ne "") {
            return $match.Groups[$i].Value
        }
    }
    return $null
}

function ConvertTo-SafeFileName {
    param([string]$Text)

    if (-not $Text) {
        return "unknown"
    }

    $safe = $Text -replace "[^A-Za-z0-9._-]+", "_"
    $safe = $safe.Trim("_")
    if (-not $safe) {
        return "unknown"
    }
    if ($safe.Length -gt 80) {
        return $safe.Substring(0, 80)
    }
    return $safe
}

function Get-ResourceLabel {
    param(
        [string]$Value,
        [string]$PathPrefix
    )

    if (-not $Value) {
        return "unknown"
    }

    if ($Value -match ("(?i)/" + [regex]::Escape($PathPrefix.Trim("/")) + "/([^/?#\s]+)")) {
        return $matches[1]
    }

    $trimmed = $Value.TrimEnd("/", "#")
    $last = [regex]::Match($trimmed, "[^/#?]+$")
    if ($last.Success) {
        return $last.Value
    }

    return $Value
}

function ConvertTo-PathAnalysisCell {
    param([string]$Cell)

    if (-not $Cell) {
        return $null
    }

    $match = [regex]::Match($Cell, "(?i)/cells/[^/?#\s]+/[^/?#\s]+")
    if ($match.Success) {
        return $match.Value
    }

    return $Cell
}

function ConvertTo-SortableLong {
    param(
        $Value,
        [int64]$Default = [int64]::MaxValue
    )

    if ($null -eq $Value -or "$Value" -eq "") {
        return $Default
    }

    $parsed = [int64]0
    if ([int64]::TryParse("$Value", [ref]$parsed)) {
        return $parsed
    }

    return $Default
}

function Get-RunRootHelp {
    param(
        [string]$RepoRoot,
        [string]$RunRoot
    )

    $runsRoot = Join-Path $RepoRoot "experiments\runs"
    $available = if (Test-Path -LiteralPath $runsRoot) {
        @(Get-ChildItem -Path $runsRoot -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 5 -ExpandProperty Name)
    } else {
        @()
    }

    $message = "Run root does not exist: $RunRoot`n`nImport a manual run first from the repository root:`n  powershell -ExecutionPolicy Bypass -File experiments/scripts/import-manual-run.ps1 -BatchId smoke -Jcm dfs_baseline.jcm -AgentName dfs_baseline_nesw_1`n`nThen generate the report:`n  powershell -ExecutionPolicy Bypass -File experiments/scripts/write-report.ps1 -BatchId smoke"
    if ($available.Count -gt 0) {
        $message += "`n`nAvailable batches:`n  " + ($available -join "`n  ")
    }
    return $message
}

function Add-Decision {
    param(
        [System.Collections.ArrayList]$Rows,
        [hashtable]$Metrics,
        [object]$RunMeta,
        [string]$RunId,
        [string]$Source,
        [string]$FileName,
        [int]$LineNumber,
        [string]$SelectedUri,
        $SelectedOriginalIndex,
        $SelectedHasCcrs,
        $SelectedReordered,
        $SelectedOrigin,
        $SelectedType,
        $SelectedUtility,
        $OptionsCount,
        $MatchedCount
    )

    $hasCcrs = ConvertTo-Bool $SelectedHasCcrs
    $reordered = ConvertTo-Bool $SelectedReordered
    $optionCountNumber = $null
    if ($null -ne $OptionsCount -and "$OptionsCount" -ne "") {
        $optionCountNumber = [int]$OptionsCount
    }

    $Metrics.decisionCount++
    if ($hasCcrs) { $Metrics.softInfluenced++ }
    if ($reordered) { $Metrics.strictInfluenced++ }
    if ($null -ne $optionCountNumber -and $optionCountNumber -ge 2) {
        $Metrics.multiOptionDecisions++
        if ($hasCcrs) { $Metrics.multiOptionWithCcrs++ }
        if ($reordered) { $Metrics.multiOptionOverruled++ }
    } elseif ($null -ne $optionCountNumber) {
        $Metrics.zeroOrOneOptionDecisions++
    }

    [void]$Rows.Add([pscustomobject][ordered]@{
        batch_id = $RunMeta.batchId
        run_id = $RunId
        jcm = $RunMeta.jcm
        ccrs_mode = $RunMeta.ccrsMode
        source = $Source
        file = $FileName
        line = $LineNumber
        selected_uri = $SelectedUri
        selected_original_index = $SelectedOriginalIndex
        selected_has_ccrs = $hasCcrs
        selected_reordered = $reordered
        selected_origin = $SelectedOrigin
        selected_type = $SelectedType
        selected_utility = $SelectedUtility
        options_count = $OptionsCount
        matched_count = $MatchedCount
    })
}

function Add-Action {
    param(
        [System.Collections.ArrayList]$Rows,
        [object]$RunMeta,
        [string]$RunId,
        [string]$FileName,
        [int]$LineNumber,
        [string]$ActionType,
        [string]$Target,
        [string]$Outcome
    )

    [void]$Rows.Add([pscustomobject][ordered]@{
        batch_id = $RunMeta.batchId
        run_id = $RunId
        jcm = $RunMeta.jcm
        ccrs_mode = $RunMeta.ccrsMode
        file = $FileName
        line = $LineNumber
        action_type = $ActionType
        target = $Target
        outcome = $Outcome
    })
}

function Add-Contingency {
    param(
        [System.Collections.ArrayList]$Rows,
        [object]$RunMeta,
        [string]$RunId,
        [string]$Source,
        [string]$FileName,
        [int]$LineNumber,
        [int]$Invocation,
        [hashtable]$Fields
    )

    [void]$Rows.Add([pscustomobject][ordered]@{
        batch_id = $RunMeta.batchId
        run_id = $RunId
        jcm = $RunMeta.jcm
        ccrs_mode = $RunMeta.ccrsMode
        source = $Source
        file = $FileName
        line = $LineNumber
        invocation = $Invocation
        event = Get-MapValue $Fields "event"
        strategy_id = Get-MapValue $Fields "strategy_id" (Get-MapValue $Fields "top_strategy")
        result_type = Get-MapValue $Fields "result_type"
        action_type = Get-MapValue $Fields "action_type" (Get-MapValue $Fields "top_action")
        action_target = Get-MapValue $Fields "action_target" (Get-MapValue $Fields "top_target")
        confidence = Get-MapValue $Fields "confidence" (Get-MapValue $Fields "top_confidence")
        no_help_reason = Get-MapValue $Fields "no_help_reason"
        rationale = Get-MapValue $Fields "rationale" (Get-MapValue $Fields "explanation")
        evaluation_time_ms = Get-MapValue $Fields "evaluation_time_ms" (Get-MapValue $Fields "total_time_ms")
        selected_count = Get-MapValue $Fields "selected_count"
        suggestion_count = Get-MapValue $Fields "suggestion_count"
        has_opportunistic_guidance = Get-MapValue $Fields "has_opportunistic_guidance" (Get-MapValue $Fields "top_has_opportunistic_guidance")
    })
}

function Add-CycleMarker {
    param(
        [System.Collections.ArrayList]$Rows,
        [object]$RunMeta,
        [string]$RunId,
        [string]$FileName,
        [int]$LineNumber,
        [int]$Sequence,
        [hashtable]$Fields
    )

    [void]$Rows.Add([pscustomobject][ordered]@{
        batch_id = $RunMeta.batchId
        run_id = $RunId
        jcm = $RunMeta.jcm
        ccrs_mode = $RunMeta.ccrsMode
        agent_label = if ($RunMeta.agentNames.Count -gt 0) { $RunMeta.agentNames[0] } else { $RunId }
        file = $FileName
        line = $LineNumber
        sequence = $Sequence
        step = Get-MapValue $Fields "step"
        y = Get-MapValue $Fields "y"
        m = Get-MapValue $Fields "m"
        d = Get-MapValue $Fields "d"
        h = Get-MapValue $Fields "h"
        min = Get-MapValue $Fields "min"
        sec = Get-MapValue $Fields "sec"
        ms = Get-MapValue $Fields "ms"
        timestamp_ms = Get-MapValue $Fields "t_ms"
        previous_cell = Get-MapValue $Fields "previous"
        cell = Get-MapValue $Fields "cell"
    })
}

function Add-CycleEvent {
    param(
        [System.Collections.ArrayList]$Rows,
        [string]$EventType,
        [int]$Sequence
    )

    [void]$Rows.Add([pscustomobject][ordered]@{
        event_type = $EventType
        sequence = $Sequence
    })
}

function ConvertTo-NullableDouble {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") {
        return $null
    }
    return [double]::Parse("$Value", [System.Globalization.CultureInfo]::InvariantCulture)
}

function ConvertTo-NullableInt64 {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") {
        return $null
    }
    return [int64]$Value
}

function Get-CycleAbsoluteTimestampMs {
    param($Marker)

    $year = ConvertTo-NullableInt64 $Marker.y
    $month = ConvertTo-NullableInt64 $Marker.m
    $day = ConvertTo-NullableInt64 $Marker.d
    $hour = ConvertTo-NullableInt64 $Marker.h
    $minute = ConvertTo-NullableInt64 $Marker.min
    $second = ConvertTo-NullableInt64 $Marker.sec
    $millisecond = ConvertTo-NullableInt64 $Marker.ms

    if ($null -ne $year -and $null -ne $month -and $null -ne $day -and
        $null -ne $hour -and $null -ne $minute -and $null -ne $second -and $null -ne $millisecond) {
        try {
            $dt = [datetime]::new([int]$year, [int]$month, [int]$day, [int]$hour, [int]$minute, [int]$second, [int]$millisecond)
            return [int64]($dt.Ticks / [timespan]::TicksPerMillisecond)
        } catch {
            # Fall back to within-day timestamp below.
        }
    }

    return ConvertTo-NullableInt64 $Marker.timestamp_ms
}

function Get-Average {
    param([object[]]$Values)

    $numbers = @()
    foreach ($value in $Values) {
        $number = ConvertTo-NullableDouble $value
        if ($null -ne $number) {
            $numbers += $number
        }
    }

    if ($numbers.Count -eq 0) {
        return $null
    }
    return [math]::Round(($numbers | Measure-Object -Average).Average, 2)
}

function Get-CycleAverage {
    param(
        [object[]]$Rows,
        [scriptblock]$Filter = { $true }
    )

    $values = @()
    foreach ($row in $Rows) {
        if (& $Filter $row) {
            $values += $row.duration_ms
        }
    }
    return Get-Average $values
}

function New-CycleDurationRows {
    param(
        [System.Collections.ArrayList]$Markers,
        [System.Collections.ArrayList]$Events
    )

    $rows = New-Object System.Collections.ArrayList
    $orderedMarkers = @($Markers | Sort-Object @{ Expression = { [int]$_.sequence } }, @{ Expression = { [int]$_.line } })
    $orderedEvents = @($Events | Sort-Object @{ Expression = { [int]$_.sequence } })
    $previousMarker = $null
    $previousTimestamp = $null

    foreach ($marker in $orderedMarkers) {
        $timestamp = Get-CycleAbsoluteTimestampMs $marker
        $duration = $null
        $oppCount = 0
        $contingencyCount = 0

        if ($null -ne $previousMarker) {
            if ($null -ne $timestamp -and $null -ne $previousTimestamp) {
                $duration = $timestamp - $previousTimestamp
                if ($duration -lt 0) {
                    $duration += 86400000
                }
            }

            foreach ($event in $orderedEvents) {
                $eventSequence = [int]$event.sequence
                if ($eventSequence -gt [int]$previousMarker.sequence -and $eventSequence -le [int]$marker.sequence) {
                    if ($event.event_type -eq "ccrs.opportunistic.detected") { $oppCount++ }
                    if ($event.event_type -eq "ccrs.contingency.evaluate.request") { $contingencyCount++ }
                }
            }
        }

        [void]$rows.Add([pscustomobject][ordered]@{
            batch_id = $marker.batch_id
            run_id = $marker.run_id
            jcm = $marker.jcm
            ccrs_mode = $marker.ccrs_mode
            agent_label = $marker.agent_label
            step = $marker.step
            file = $marker.file
            line = $marker.line
            sequence = $marker.sequence
            timestamp_ms = $marker.timestamp_ms
            duration_ms = $duration
            previous_cell = $marker.previous_cell
            cell = $marker.cell
            opp_ccrs_detected_count = $oppCount
            contingency_ccrs_invocation_count = $contingencyCount
        })

        $previousMarker = $marker
        $previousTimestamp = $timestamp
    }

    return $rows
}

function Get-MaseValue {
    param(
        $Record,
        $Event,
        [string[]]$Keys,
        $Default = $null
    )

    foreach ($key in $Keys) {
        $value = Get-MapValue $Record $key $null
        if ($null -ne $value -and "$value" -ne "") {
            return $value
        }
        $value = Get-MapValue $Event $key $null
        if ($null -ne $value -and "$value" -ne "") {
            return $value
        }
    }

    return $Default
}

function Add-MaseEvent {
    param(
        [System.Collections.ArrayList]$EventRows,
        [System.Collections.ArrayList]$AgentMovedRows,
        [System.Collections.ArrayList]$TransactionRows,
        [System.Collections.ArrayList]$RunEventRows,
        [System.Collections.ArrayList]$RunAgentMovedRows,
        [System.Collections.ArrayList]$RunTransactionRows,
        [hashtable]$Metrics,
        [object]$RunMeta,
        [string]$RunId,
        [string]$FileName,
        [int]$LineNumber,
        $Record
    )

    $event = Get-MapValue $Record "event" $Record
    $type = Get-MaseValue $Record $event "type"
    if (-not $type) {
        return
    }

    $timestamp = Get-MaseValue $Record $event "timestamp"
    $agent = Get-MaseValue $Record $event "agent"
    $cell = Get-MaseValue $Record $event "cell"
    $graph = Get-MaseValue $Record $event "graph"
    $transactionId = Get-MaseValue $Record $event @("transactionId", "transaction_id")
    $trigger = Get-MaseValue $Record $event "trigger"
    $transactionStatus = Get-MaseValue $Record $event "status"
    $startedAt = Get-MaseValue $Record $event "startedAt"
    $finishedAt = Get-MaseValue $Record $event "finishedAt"
    $traceMode = Get-MaseValue $Record $event "traceMode"
    $ruleCount = Get-MaseValue $Record $event "ruleCount"
    $errorText = Get-MaseValue $Record $event "error"
    $maseRunId = Get-MapValue $Record @("runId", "run_id")
    $archiveId = Get-MapValue $Record @("archiveId", "archive_id")
    $archivedAt = Get-MapValue $Record "archivedAt"
    $capturedAt = Get-MapValue $Record "capturedAt"
    $captureSource = Get-MapValue $Record "captureSource" "unknown"

    if ($RunMeta.agentNames -and @($RunMeta.agentNames).Count -gt 0 -and -not (Test-ExperimentAgent -RunMeta $RunMeta -Agent $agent)) {
        return
    }

    $row = [pscustomobject][ordered]@{
        batch_id = $RunMeta.batchId
        run_id = $RunId
        jcm = $RunMeta.jcm
        ccrs_mode = $RunMeta.ccrsMode
        file = $FileName
        line = $LineNumber
        mase_run_id = $maseRunId
        archive_id = $archiveId
        capture_source = $captureSource
        type = $type
        timestamp = $timestamp
        archived_at = $archivedAt
        captured_at = $capturedAt
        agent = $agent
        cell = $cell
        graph = $graph
        transaction_id = $transactionId
        trigger = $trigger
        status = $transactionStatus
    }

    [void]$EventRows.Add($row)
    [void]$RunEventRows.Add($row)
    $Metrics.maseEventCount++
    if ($null -ne $timestamp -and "$timestamp" -ne "") {
        if ($null -eq $Metrics.maseFirstTimestamp) {
            $Metrics.maseFirstTimestamp = $timestamp
        }
        $Metrics.maseLastTimestamp = $timestamp
    }

    if ($type -eq "AGENT_MOVED") {
        $Metrics.maseAgentMoved++
        if ($cell) {
            $Metrics.maseUniqueCells["$cell"] = $true
            $Metrics.maseFinalCell = $cell
        }
        if ($agent) {
            $Metrics.maseFinalAgent = $agent
        }
        $movedRow = [pscustomobject][ordered]@{
            batch_id = $RunMeta.batchId
            run_id = $RunId
            jcm = $RunMeta.jcm
            ccrs_mode = $RunMeta.ccrsMode
            file = $FileName
            line = $LineNumber
            mase_run_id = $maseRunId
            archive_id = $archiveId
            timestamp = $timestamp
            agent = $agent
            cell = $cell
            capture_source = $captureSource
        }
        [void]$AgentMovedRows.Add($movedRow)
        [void]$RunAgentMovedRows.Add($movedRow)
        return
    }

    if ($type -eq "TRANSACTION") {
        $Metrics.maseTransactions++
        if ($trigger -eq "POST") { $Metrics.masePostTransactions++ }
        if ($trigger -eq "STARTUP") { $Metrics.maseStartupTransactions++ }
        if ($transactionStatus -eq "COMMITTED") {
            $Metrics.maseCommittedTransactions++
        } elseif ($transactionStatus -eq "FAILED") {
            $Metrics.maseFailedTransactions++
        } elseif ($transactionStatus -eq "ROLLED_BACK") {
            $Metrics.maseRolledBackTransactions++
        }

        $transactionDurationMs = $null
        if ($null -ne $startedAt -and $null -ne $finishedAt -and "$startedAt" -ne "" -and "$finishedAt" -ne "") {
            $transactionDurationMs = [int64]$finishedAt - [int64]$startedAt
        }

        $transactionRow = [pscustomobject][ordered]@{
            batch_id = $RunMeta.batchId
            run_id = $RunId
            jcm = $RunMeta.jcm
            ccrs_mode = $RunMeta.ccrsMode
            file = $FileName
            line = $LineNumber
            mase_run_id = $maseRunId
            archive_id = $archiveId
            timestamp = $timestamp
            transaction_id = $transactionId
            trigger = $trigger
            status = $transactionStatus
            agent = $agent
            graph = $graph
            trace_mode = $traceMode
            rule_count = $ruleCount
            error = $errorText
            started_at = $startedAt
            finished_at = $finishedAt
            duration_ms = $transactionDurationMs
            capture_source = $captureSource
        }
        [void]$TransactionRows.Add($transactionRow)
        [void]$RunTransactionRows.Add($transactionRow)
    }
}

function Write-PathAnalysisInputs {
    param(
        [object[]]$Rows,
        [string]$TargetDirectory,
        [string]$FilePrefix,
        [string]$RelativeDirectory,
        [object]$RunMeta,
        [string]$RunId,
        [System.Collections.ArrayList]$IndexRows
    )

    if (-not $Rows -or $Rows.Count -eq 0) {
        return
    }

    New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null

    foreach ($group in ($Rows | Group-Object agent | Sort-Object Name)) {
        $orderedRows = @($group.Group | Sort-Object `
            @{ Expression = { ConvertTo-SortableLong $_.archive_id } }, `
            @{ Expression = { ConvertTo-SortableLong $_.timestamp } }, `
            @{ Expression = { ConvertTo-SortableLong $_.line } })

        $cells = @($orderedRows |
            ForEach-Object { ConvertTo-PathAnalysisCell $_.cell } |
            Where-Object { $_ })

        if ($cells.Count -eq 0) {
            continue
        }

        $agent = if ($group.Name) { $group.Name } else { "unknown" }
        $agentLabel = Get-ResourceLabel -Value $agent -PathPrefix "agents"
        $safeAgent = ConvertTo-SafeFileName -Text $agentLabel
        $safePrefix = ConvertTo-SafeFileName -Text $FilePrefix
        $fileName = "$safePrefix-$safeAgent.cells.txt"
        $targetPath = Join-Path $TargetDirectory $fileName
        $cells | Set-Content -Path $targetPath -Encoding UTF8

        $relativePath = if ($RelativeDirectory) {
            ($RelativeDirectory.TrimEnd("/", "\") + "/" + $fileName)
        } else {
            $fileName
        }

        [void]$IndexRows.Add([pscustomobject][ordered]@{
            batch_id = $RunMeta.batchId
            run_id = $RunId
            jcm = $RunMeta.jcm
            ccrs_mode = $RunMeta.ccrsMode
            agent = $agent
            agent_label = $agentLabel
            steps = $cells.Count
            first_cell = $cells[0]
            last_cell = $cells[$cells.Count - 1]
            sequence_format = "newline-separated cell paths"
            file = $relativePath
        })
    }
}

function Test-ExperimentAgent {
    param(
        [object]$RunMeta,
        [string]$Agent
    )

    if (-not $Agent -or -not $RunMeta.agentNames) {
        return $false
    }

    $agentLabel = Get-ResourceLabel -Value $Agent -PathPrefix "agents"
    foreach ($candidate in @($RunMeta.agentNames)) {
        if (-not $candidate) {
            continue
        }
        $candidateLabel = Get-ResourceLabel -Value ([string]$candidate) -PathPrefix "agents"
        if ($candidate -eq $Agent -or $candidateLabel -eq $agentLabel) {
            return $true
        }
    }

    return $false
}

function Get-AgentRole {
    param(
        [object]$RunMeta,
        [string]$Agent
    )

    if (-not $Agent) {
        return "unknown"
    }

    if (Test-ExperimentAgent -RunMeta $RunMeta -Agent $Agent) {
        return "experiment_agent"
    }

    $label = Get-ResourceLabel -Value $Agent -PathPrefix "agents"
    if ($label -in @("ccrs-agent", "keyholder-agent")) {
        return "scenario_service"
    }

    return "other_agent"
}

function Add-AgentSummaryRows {
    param(
        [System.Collections.ArrayList]$Rows,
        [object]$RunMeta,
        [string]$RunId,
        [object[]]$MovementRows,
        [object[]]$TransactionRows
    )

    $agentMap = @{}
    foreach ($row in @($MovementRows)) {
        if ($row.agent) {
            $label = Get-ResourceLabel -Value $row.agent -PathPrefix "agents"
            if (-not $agentMap.ContainsKey($label) -or "$($row.agent)" -match "/agents/") {
                $agentMap[$label] = "$($row.agent)"
            }
        }
    }
    foreach ($row in @($TransactionRows)) {
        if ($row.agent) {
            $label = Get-ResourceLabel -Value $row.agent -PathPrefix "agents"
            if (-not $agentMap.ContainsKey($label)) {
                $agentMap[$label] = "$($row.agent)"
            }
        }
    }

    foreach ($agentLabel in ($agentMap.Keys | Sort-Object)) {
        $agent = $agentMap[$agentLabel]
        $moves = @($MovementRows | Where-Object { (Get-ResourceLabel -Value $_.agent -PathPrefix "agents") -eq $agentLabel } | Sort-Object `
            @{ Expression = { ConvertTo-SortableLong $_.archive_id } }, `
            @{ Expression = { ConvertTo-SortableLong $_.timestamp } }, `
            @{ Expression = { ConvertTo-SortableLong $_.line } })
        $transactions = @($TransactionRows | Where-Object { (Get-ResourceLabel -Value $_.agent -PathPrefix "agents") -eq $agentLabel })
        $postTransactions = @($transactions | Where-Object { $_.trigger -eq "POST" })
        $committedTransactions = @($transactions | Where-Object { $_.status -eq "COMMITTED" })
        $failedTransactions = @($transactions | Where-Object { $_.status -eq "FAILED" -or $_.status -eq "ROLLED_BACK" })
        $cells = @($moves | ForEach-Object { $_.cell } | Where-Object { $_ } | Select-Object -Unique)
        $firstMove = if ($moves.Count -gt 0) { $moves[0] } else { $null }
        $lastMove = if ($moves.Count -gt 0) { $moves[$moves.Count - 1] } else { $null }

        [void]$Rows.Add([pscustomobject][ordered]@{
            batch_id = $RunMeta.batchId
            run_id = $RunId
            jcm = $RunMeta.jcm
            ccrs_mode = $RunMeta.ccrsMode
            scenario = $RunMeta.scenario
            agent = $agent
            agent_label = $agentLabel
            agent_role = Get-AgentRole -RunMeta $RunMeta -Agent $agent
            mase_moves = $moves.Count
            mase_unique_cells = $cells.Count
            first_cell = if ($firstMove) { $firstMove.cell } else { $null }
            last_cell = if ($lastMove) { $lastMove.cell } else { $null }
            first_timestamp = if ($firstMove) { $firstMove.timestamp } else { $null }
            last_timestamp = if ($lastMove) { $lastMove.timestamp } else { $null }
            transactions = $transactions.Count
            post_transactions = $postTransactions.Count
            committed_transactions = $committedTransactions.Count
            failed_or_rolled_back_transactions = $failedTransactions.Count
        })
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $RunRoot) {
    if (-not $BatchId) {
        throw "Provide -BatchId or -RunRoot."
    }
    $RunRoot = Join-Path $repoRoot "experiments\runs\$BatchId"
}
if (-not $OutputDir) {
    $batchName = if ($BatchId) { $BatchId } else { Split-Path -Leaf $RunRoot }
    $OutputDir = Join-Path $repoRoot "experiments\reports\$batchName"
}

if (-not (Test-Path -LiteralPath $RunRoot)) {
    throw (Get-RunRootHelp -RepoRoot $repoRoot -RunRoot $RunRoot)
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$runRows = New-Object System.Collections.ArrayList
$decisionRows = New-Object System.Collections.ArrayList
$contingencyRows = New-Object System.Collections.ArrayList
$actionRows = New-Object System.Collections.ArrayList
$maseEventRows = New-Object System.Collections.ArrayList
$maseAgentMovedRows = New-Object System.Collections.ArrayList
$maseTransactionRows = New-Object System.Collections.ArrayList
$agentRows = New-Object System.Collections.ArrayList
$pathAnalysisRows = New-Object System.Collections.ArrayList
$cycleRows = New-Object System.Collections.ArrayList

$outputFullName = (Resolve-Path -LiteralPath $OutputDir).Path
$runDirs = Get-ChildItem -Path $RunRoot -Directory |
    Where-Object {
        $_.FullName -ne $outputFullName -and (
            (Test-Path -LiteralPath (Join-Path $_.FullName "run.json")) -or
            @(Get-ChildItem -Path $_.FullName -Filter "*.log*" -File -ErrorAction SilentlyContinue).Count -gt 0
        )
    } |
    Sort-Object Name
foreach ($runDir in $runDirs) {
    $runJson = Join-Path $runDir.FullName "run.json"
    if (Test-Path -LiteralPath $runJson) {
        $runMeta = New-RunMeta -RunDir $runDir -Raw (Get-Content -Path $runJson -Raw | ConvertFrom-Json)
    } else {
        $runMeta = New-RunMeta -RunDir $runDir -Raw @{}
    }

    $runId = if ($runMeta.runId) { $runMeta.runId } else { $runDir.Name }
    $metrics = @{
        moveAttempts = 0
        accessApproved = 0
        locationUpdates = 0
        discovered = 0
        retrieves = 0
        movePosts = 0
        actionPosts = 0
        backtrackDeadEnds = 0
        backtracks = 0
        actionRequired = 0
        actionCompleted = 0
        decisionCount = 0
        softInfluenced = 0
        strictInfluenced = 0
        multiOptionDecisions = 0
        multiOptionWithCcrs = 0
        multiOptionOverruled = 0
        zeroOrOneOptionDecisions = 0
        ccrsDetected = 0
        ccrsDetectedSignifier = 0
        ccrsDetectedStigmergy = 0
        contingencyInvocations = 0
        contingencySuggestions = 0
        contingencyNoHelp = 0
        directSuggestionExecutions = 0
        guidanceExecutions = 0
        unhandledFailures = 0
        successDetected = $false
        elapsedSeconds = $null
        elapsedMsRemainder = $null
        triplesFound = $null
        metricQuality = "fallback"
        maseEventCount = 0
        maseAgentMoved = 0
        maseTransactions = 0
        masePostTransactions = 0
        maseStartupTransactions = 0
        maseCommittedTransactions = 0
        maseFailedTransactions = 0
        maseRolledBackTransactions = 0
        maseUniqueCells = @{}
        maseFinalAgent = $null
        maseFinalCell = $null
        maseFirstTimestamp = $null
        maseLastTimestamp = $null
    }

    $structuredPrioritize = $false
    $structuredContingency = $false
    $pendingCcrsOutput = $false
    $contingencyInvocationIndex = 0
    $lineNumber = 0
    $logSequence = 0
    $runMaseEventRows = New-Object System.Collections.ArrayList
    $runMaseAgentMovedRows = New-Object System.Collections.ArrayList
    $runMaseTransactionRows = New-Object System.Collections.ArrayList
    $runCycleMarkers = New-Object System.Collections.ArrayList
    $runCycleEvents = New-Object System.Collections.ArrayList

    $logFiles = Get-ChildItem -Path $runDir.FullName -Filter "*.log*" | Sort-Object Name
    foreach ($logFile in $logFiles) {
        $lineNumber = 0
        foreach ($line in Get-Content -Path $logFile.FullName) {
            $lineNumber++
            $logSequence++

            $kv = ConvertFrom-KeyValueLine -Line $line
            if ($kv -and $kv.ContainsKey("event")) {
                $metrics.metricQuality = "structured"
                $event = [string]$kv["event"]

                if ($event -eq "agent.cycle.location") {
                    Add-CycleMarker `
                        -Rows $runCycleMarkers `
                        -RunMeta $runMeta `
                        -RunId $runId `
                        -FileName $logFile.Name `
                        -LineNumber $lineNumber `
                        -Sequence $logSequence `
                        -Fields $kv
                    continue
                }

                if ($event -eq "ccrs.opportunistic.prioritize") {
                    $structuredPrioritize = $true
                    $optionsCount = Get-MapValue $kv "options_count" 0
                    if ($optionsCount -gt 0) {
                        Add-Decision `
                            -Rows $decisionRows `
                            -Metrics $metrics `
                            -RunMeta $runMeta `
                            -RunId $runId `
                            -Source "structured" `
                            -FileName $logFile.Name `
                            -LineNumber $lineNumber `
                            -SelectedUri (Get-MapValue $kv "selected_uri") `
                            -SelectedOriginalIndex (Get-MapValue $kv "selected_original_index") `
                            -SelectedHasCcrs (Get-MapValue $kv "selected_has_ccrs" $false) `
                            -SelectedReordered (Get-MapValue $kv "selected_reordered" $false) `
                            -SelectedOrigin (Get-MapValue $kv "selected_origin") `
                            -SelectedType (Get-MapValue $kv "selected_type") `
                            -SelectedUtility (Get-MapValue $kv "selected_utility") `
                            -OptionsCount $optionsCount `
                            -MatchedCount (Get-MapValue $kv "matched_count" 0)
                    }
                    continue
                }

                if ($event -eq "ccrs.opportunistic.detected") {
                    Add-CycleEvent -Rows $runCycleEvents -EventType $event -Sequence $logSequence
                    $metrics.ccrsDetected++
                    $detectedType = Get-MapValue $kv "type"
                    if ($detectedType -eq "signifier") { $metrics.ccrsDetectedSignifier++ }
                    if ($detectedType -eq "stigmergy") { $metrics.ccrsDetectedStigmergy++ }
                    continue
                }

                if ($event -like "ccrs.contingency.*") {
                    $structuredContingency = $true
                    if ($event -eq "ccrs.contingency.evaluate.request") {
                        $contingencyInvocationIndex++
                        Add-CycleEvent -Rows $runCycleEvents -EventType $event -Sequence $logSequence
                    }
                    Add-Contingency `
                        -Rows $contingencyRows `
                        -RunMeta $runMeta `
                        -RunId $runId `
                        -Source "structured" `
                        -FileName $logFile.Name `
                        -LineNumber $lineNumber `
                        -Invocation $contingencyInvocationIndex `
                        -Fields $kv

                    if ($event -eq "ccrs.contingency.evaluate.request") {
                        $metrics.contingencyInvocations++
                    } elseif ($event -eq "ccrs.contingency.strategy.evaluated") {
                        $resultType = Get-MapValue $kv "result_type"
                        if ($resultType -eq "suggestion") { $metrics.contingencySuggestions++ }
                        if ($resultType -eq "no_help") { $metrics.contingencyNoHelp++ }
                    }
                    continue
                }
            }

            if ($line -match "Already at exit:|It took|found .* triples in the KG") {
                $metrics.successDetected = $true
            }
            if ($line -match "It took (\d+) seconds and (\d+) milliseconds") {
                $metrics.elapsedSeconds = [int]$matches[1]
                $metrics.elapsedMsRemainder = [int]$matches[2]
            }
            if ($line -match "found (\d+) triples in the KG") {
                $metrics.triplesFound = [int]$matches[1]
            }

            if ($line -match "Attempting move to: (.+)$") {
                $metrics.moveAttempts++
                Add-Action $actionRows $runMeta $runId $logFile.Name $lineNumber "move_attempt" $matches[1] "attempted"
            }
            if ($line -match "Access approved: (.+)$") {
                $metrics.accessApproved++
                Add-Action $actionRows $runMeta $runId $logFile.Name $lineNumber "move" $matches[1] "success"
            }
            if ($line -match "I'm now at: (.+)$") { $metrics.locationUpdates++ }
            if ($line -match "Discovered: (.+?) from:") { $metrics.discovered++ }
            if ($line -match "Retrieving (.+)$") { $metrics.retrieves++ }
            if ($line -match "POST to target URI - requesting MOVE to: (.+)$") {
                $metrics.movePosts++
                Add-Action $actionRows $runMeta $runId $logFile.Name $lineNumber "move_post" $matches[1] "requested"
            }
            if ($line -match "POST to: (.+?) with body:") {
                $metrics.actionPosts++
                Add-Action $actionRows $runMeta $runId $logFile.Name $lineNumber "post" $matches[1] "requested"
            }
            if ($line -match "Location fully explored") { $metrics.backtrackDeadEnds++ }
            if ($line -match "Going back to parent: (.+)$") {
                $metrics.backtracks++
                Add-Action $actionRows $runMeta $runId $logFile.Name $lineNumber "backtrack" $matches[1] "attempted"
            }
            if ($line -match "Action required:") { $metrics.actionRequired++ }
            if ($line -match "Action completed:") { $metrics.actionCompleted++ }
            if ($line -match "No failure event was generated|error\(action_failed\)|CLIENT_ERROR|I give up\.") {
                $metrics.unhandledFailures++
            }

            if (-not $structuredContingency -and $metrics.metricQuality -ne "structured" -and $line -match "CATCH ERROR") {
                $contingencyInvocationIndex++
                $metrics.contingencyInvocations++
                Add-CycleEvent -Rows $runCycleEvents -EventType "ccrs.contingency.evaluate.request" -Sequence $logSequence
                Add-Contingency $contingencyRows $runMeta $runId "fallback" $logFile.Name $lineNumber $contingencyInvocationIndex @{ event = "ccrs.contingency.evaluate.request" }
            }
            if ($line -match "CCRS OUTPUT RECEIVED") {
                $pendingCcrsOutput = $true
            } elseif ($pendingCcrsOutput -and $line -match "Id: (.+)$") {
                if (-not $structuredContingency -and $metrics.metricQuality -ne "structured") {
                    $strategy = $matches[1].Trim()
                    $metrics.contingencySuggestions++
                    Add-Contingency $contingencyRows $runMeta $runId "fallback" $logFile.Name $lineNumber $contingencyInvocationIndex @{
                        event = "ccrs.contingency.evaluate.returned"
                        top_strategy = $strategy
                        result_type = "suggestion"
                    }
                }
                $pendingCcrsOutput = $false
            }
            if ($line -match "CCRS executing POST suggestion|CCRS executing get suggestion") {
                $metrics.directSuggestionExecutions++
            }
            if ($line -match "CCRS opportunistic backtrack guidance present") {
                $metrics.guidanceExecutions++
            }
            if (-not $structuredContingency -and $metrics.metricQuality -ne "structured" -and $line -match "Returning noHelp") {
                $metrics.contingencyNoHelp++
            }

            if (-not $structuredPrioritize -and $line -match "\[CCRS-Prioritize\] Detailed list \(output\): \[uri\(") {
                $selectedUri = Get-RegexValue $line 'Detailed list \(output\): \[uri\("([^"]+)"\)'
                $selectedIndex = Get-RegexValue $line 'original_index\(([0-9]+)\)'
                $selectedOrigin = Get-RegexValue $line 'origin\("([^"]+)"\)|origin\(([^)]+)\)'
                $selectedType = Get-RegexValue $line 'type\("([^"]+)"\)|type\(([^)]+)\)'
                $selectedUtility = Get-RegexValue $line 'utility\(([-0-9.]+)\)'
                $hasCcrs = $selectedOrigin -and $selectedOrigin -ne "null"
                $reordered = $hasCcrs -and $selectedIndex -and ([int]$selectedIndex -gt 0)

                Add-Decision `
                    -Rows $decisionRows `
                    -Metrics $metrics `
                    -RunMeta $runMeta `
                    -RunId $runId `
                    -Source "fallback" `
                    -FileName $logFile.Name `
                    -LineNumber $lineNumber `
                    -SelectedUri $selectedUri `
                    -SelectedOriginalIndex $selectedIndex `
                    -SelectedHasCcrs $hasCcrs `
                    -SelectedReordered $reordered `
                    -SelectedOrigin $selectedOrigin `
                    -SelectedType $selectedType `
                    -SelectedUtility $selectedUtility `
                    -OptionsCount $null `
                    -MatchedCount $null
            }
        }
    }

    $runCycleDurationRows = New-CycleDurationRows -Markers $runCycleMarkers -Events $runCycleEvents
    if ($runCycleDurationRows.Count -gt 0) {
        foreach ($row in $runCycleDurationRows) {
            [void]$cycleRows.Add($row)
        }
        $runCycleDurationRows | Export-Csv -Path (Join-Path $runDir.FullName "cycle-durations.csv") -NoTypeInformation -Encoding UTF8
    }

    $maseFiles = @(
        Get-ChildItem -Path $runDir.FullName -Filter "mase-events*.jsonl" -File -ErrorAction SilentlyContinue
        Get-ChildItem -Path $runDir.FullName -Filter "mase-events*.ndjson" -File -ErrorAction SilentlyContinue
    ) | Sort-Object Name -Unique
    foreach ($maseFile in $maseFiles) {
        $maseLineNumber = 0
        foreach ($line in Get-Content -Path $maseFile.FullName) {
            $maseLineNumber++
            if (-not $line -or $line.Trim() -eq "") {
                continue
            }
            try {
                $record = $line | ConvertFrom-Json
                Add-MaseEvent `
                    -EventRows $maseEventRows `
                    -AgentMovedRows $maseAgentMovedRows `
                    -TransactionRows $maseTransactionRows `
                    -RunEventRows $runMaseEventRows `
                    -RunAgentMovedRows $runMaseAgentMovedRows `
                    -RunTransactionRows $runMaseTransactionRows `
                    -Metrics $metrics `
                    -RunMeta $runMeta `
                    -RunId $runId `
                    -FileName $maseFile.Name `
                    -LineNumber $maseLineNumber `
                    -Record $record
            } catch {
                Write-Warning "Could not parse MASE event in $($maseFile.FullName):$maseLineNumber - $($_.Exception.Message)"
            }
        }
    }

    if ($runMaseEventRows.Count -gt 0) {
        $runMaseEventRows | Export-Csv -Path (Join-Path $runDir.FullName "mase-events.csv") -NoTypeInformation -Encoding UTF8
    }
    if ($runMaseAgentMovedRows.Count -gt 0) {
        $runMaseAgentMovedRows | Export-Csv -Path (Join-Path $runDir.FullName "mase-agent-moved.csv") -NoTypeInformation -Encoding UTF8
    }
    if ($runMaseTransactionRows.Count -gt 0) {
        $runMaseTransactionRows | Export-Csv -Path (Join-Path $runDir.FullName "mase-transactions.csv") -NoTypeInformation -Encoding UTF8
    }
    if ($runMaseAgentMovedRows.Count -gt 0) {
        $runPathRows = New-Object System.Collections.ArrayList
        Write-PathAnalysisInputs `
            -Rows @($runMaseAgentMovedRows) `
            -TargetDirectory (Join-Path $runDir.FullName "path-analysis-inputs") `
            -FilePrefix $runId `
            -RelativeDirectory "path-analysis-inputs" `
            -RunMeta $runMeta `
            -RunId $runId `
            -IndexRows $runPathRows
        if ($runPathRows.Count -gt 0) {
            $runPathRows | Export-Csv -Path (Join-Path $runDir.FullName "path-analysis-inputs.csv") -NoTypeInformation -Encoding UTF8
        }
    }
    if ($runMaseAgentMovedRows.Count -gt 0 -or $runMaseTransactionRows.Count -gt 0) {
        $runAgentRows = New-Object System.Collections.ArrayList
        Add-AgentSummaryRows `
            -Rows $runAgentRows `
            -RunMeta $runMeta `
            -RunId $runId `
            -MovementRows @($runMaseAgentMovedRows) `
            -TransactionRows @($runMaseTransactionRows)

        if ($runAgentRows.Count -gt 0) {
            foreach ($row in $runAgentRows) {
                [void]$agentRows.Add($row)
            }
            $runAgentRows | Export-Csv -Path (Join-Path $runDir.FullName "agents.csv") -NoTypeInformation -Encoding UTF8
        }
    }

    $decisionCount = [double]$metrics.decisionCount
    $softRate = if ($decisionCount -gt 0) { [math]::Round($metrics.softInfluenced / $decisionCount, 4) } else { 0 }
    $strictRate = if ($decisionCount -gt 0) { [math]::Round($metrics.strictInfluenced / $decisionCount, 4) } else { 0 }
    $multiOptionDecisionCount = [double]$metrics.multiOptionDecisions
    $multiOptionCcrsRate = if ($multiOptionDecisionCount -gt 0) { [math]::Round($metrics.multiOptionWithCcrs / $multiOptionDecisionCount, 4) } else { 0 }
    $multiOptionOverruledRate = if ($multiOptionDecisionCount -gt 0) { [math]::Round($metrics.multiOptionOverruled / $multiOptionDecisionCount, 4) } else { 0 }
    $maseMoveDelta = $metrics.moveAttempts - $metrics.maseAgentMoved
    $optimalMoveDelta = if ($null -ne $metrics.maseAgentMoved -and "$($metrics.maseAgentMoved)" -ne "") {
        [int]$metrics.maseAgentMoved - $optimalPathLengthMoves
    } else {
        $null
    }
    $maseFailedOrRolledBack = $metrics.maseFailedTransactions + $metrics.maseRolledBackTransactions
    $reachedExit = $metrics.maseFinalCell -match "/cells/999(?:[#/?].*)?$"
    $agentElapsedTotalMs = if ($null -ne $metrics.elapsedSeconds -and $null -ne $metrics.elapsedMsRemainder) {
        ([int64]$metrics.elapsedSeconds * 1000) + [int64]$metrics.elapsedMsRemainder
    } else {
        $null
    }
    $runCycleRowsArray = @($runCycleDurationRows)
    $averageCycleDurationMs = Get-CycleAverage -Rows $runCycleRowsArray
    $averageCycleDurationStatus = if ($runCycleRowsArray.Count -gt 1 -and $null -ne $averageCycleDurationMs) { "" } else { "missing_agent_cycle_location_markers" }
    $averageCycleOpp0Ms = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.opp_ccrs_detected_count -eq 0 }
    $averageCycleOpp1Ms = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.opp_ccrs_detected_count -eq 1 }
    $averageCycleOpp2Ms = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.opp_ccrs_detected_count -eq 2 }
    $averageCycleOpp3PlusMs = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.opp_ccrs_detected_count -ge 3 }
    $averageCycleCont1Ms = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.contingency_ccrs_invocation_count -eq 1 }
    $averageCycleCont2Ms = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.contingency_ccrs_invocation_count -eq 2 }
    $averageCycleCont3PlusMs = Get-CycleAverage -Rows $runCycleRowsArray -Filter { param($row) [int]$row.contingency_ccrs_invocation_count -ge 3 }

    $status = if ($reachedExit -or $metrics.successDetected) {
        "success"
    } elseif ($runMeta.timeout -eq $true) {
        "timeout"
    } elseif ($metrics.unhandledFailures -gt 0) {
        "failure"
    } elseif ($runMeta.status -eq "crash" -or ($null -ne $runMeta.exitCode -and $runMeta.exitCode -ne 0)) {
        "crash"
    } else {
        "unknown"
    }

    $failureCategory = if ($status -eq "success") {
        ""
    } elseif ($runMeta.timeout -eq $true) {
        "timeout"
    } elseif ($metrics.unhandledFailures -gt 0) {
        "unhandled_action_failure"
    } elseif ($status -eq "crash") {
        "process_exit"
    } else {
        "unknown"
    }

    [void]$runRows.Add([pscustomobject][ordered]@{
        batch_id = $runMeta.batchId
        run_id = $runId
        repetition = $runMeta.repetition
        jcm = $runMeta.jcm
        agent_type = $runMeta.agentType
        ccrs_mode = $runMeta.ccrsMode
        command_status = $runMeta.status
        exit_code = $runMeta.exitCode
        timeout = $runMeta.timeout
        run_stop_reason = $runMeta.runStopReason
        run_terminal_pattern = $runMeta.runTerminalPattern
        run_terminal_line = $runMeta.runTerminalLine
        outcome = $status
        reached_exit = $reachedExit
        failure_category = $failureCategory
        duration_ms = $runMeta.durationMs
        agent_elapsed_total_ms = $agentElapsedTotalMs
        agent_elapsed_seconds = $metrics.elapsedSeconds
        agent_elapsed_ms_remainder = $metrics.elapsedMsRemainder
        move_attempts = $metrics.moveAttempts
        optimal_moves = $optimalPathLengthMoves
        actual_moves = $metrics.maseAgentMoved
        move_delta_from_optimal = $optimalMoveDelta
        access_approved = $metrics.accessApproved
        location_updates = $metrics.locationUpdates
        discovered = $metrics.discovered
        retrieves = $metrics.retrieves
        move_posts = $metrics.movePosts
        action_posts = $metrics.actionPosts
        action_required = $metrics.actionRequired
        action_completed = $metrics.actionCompleted
        backtrack_dead_ends = $metrics.backtrackDeadEnds
        backtracks = $metrics.backtracks
        decisions = $metrics.decisionCount
        soft_influenced_decisions = $metrics.softInfluenced
        strict_influenced_decisions = $metrics.strictInfluenced
        soft_influence_rate = $softRate
        strict_influence_rate = $strictRate
        multi_option_decisions = $metrics.multiOptionDecisions
        multi_option_with_ccrs = $metrics.multiOptionWithCcrs
        multi_option_with_ccrs_rate = $multiOptionCcrsRate
        multi_option_overruled = $metrics.multiOptionOverruled
        multi_option_overruled_rate = $multiOptionOverruledRate
        zero_or_one_option_decisions = $metrics.zeroOrOneOptionDecisions
        average_agent_cycle_duration_ms = $averageCycleDurationMs
        average_agent_cycle_duration_status = $averageCycleDurationStatus
        average_cycle_opp0_ms = $averageCycleOpp0Ms
        average_cycle_opp1_ms = $averageCycleOpp1Ms
        average_cycle_opp2_ms = $averageCycleOpp2Ms
        average_cycle_opp3plus_ms = $averageCycleOpp3PlusMs
        average_cycle_cont1_ms = $averageCycleCont1Ms
        average_cycle_cont2_ms = $averageCycleCont2Ms
        average_cycle_cont3plus_ms = $averageCycleCont3PlusMs
        ccrs_detected = $metrics.ccrsDetected
        ccrs_detected_signifier = $metrics.ccrsDetectedSignifier
        ccrs_detected_stigmergy = $metrics.ccrsDetectedStigmergy
        contingency_invocations = $metrics.contingencyInvocations
        contingency_suggestions = $metrics.contingencySuggestions
        contingency_no_help = $metrics.contingencyNoHelp
        direct_suggestion_executions = $metrics.directSuggestionExecutions
        guidance_executions = $metrics.guidanceExecutions
        unhandled_failures = $metrics.unhandledFailures
        triples_found = $metrics.triplesFound
        mase_events = $metrics.maseEventCount
        mase_agent_moved = $metrics.maseAgentMoved
        mase_transactions = $metrics.maseTransactions
        mase_post_transactions = $metrics.masePostTransactions
        mase_startup_transactions = $metrics.maseStartupTransactions
        mase_committed_transactions = $metrics.maseCommittedTransactions
        mase_failed_transactions = $metrics.maseFailedTransactions
        mase_rolled_back_transactions = $metrics.maseRolledBackTransactions
        mase_failed_or_rolled_back_transactions = $maseFailedOrRolledBack
        mase_unique_cells = $metrics.maseUniqueCells.Count
        mase_final_agent = $metrics.maseFinalAgent
        mase_final_cell = $metrics.maseFinalCell
        mase_first_timestamp = $metrics.maseFirstTimestamp
        mase_last_timestamp = $metrics.maseLastTimestamp
        agent_mase_move_delta = $maseMoveDelta
        metric_quality = $metrics.metricQuality
    })
}

$runsCsv = Join-Path $OutputDir "runs.csv"
$decisionsCsv = Join-Path $OutputDir "decisions.csv"
$contingencyCsv = Join-Path $OutputDir "contingency.csv"
$actionsCsv = Join-Path $OutputDir "actions.csv"
$maseEventsCsv = Join-Path $OutputDir "mase-events.csv"
$maseAgentMovedCsv = Join-Path $OutputDir "mase-agent-moved.csv"
$maseTransactionsCsv = Join-Path $OutputDir "mase-transactions.csv"
$agentsCsv = Join-Path $OutputDir "agents.csv"
$cycleDurationsCsv = Join-Path $OutputDir "cycle-durations.csv"
$pathAnalysisDir = Join-Path $OutputDir "path-analysis-inputs"
$pathAnalysisCsv = Join-Path $OutputDir "path-analysis-inputs.csv"
$summaryJson = Join-Path $OutputDir "summary.json"

$runRows | Export-Csv -Path $runsCsv -NoTypeInformation -Encoding UTF8
$decisionRows | Export-Csv -Path $decisionsCsv -NoTypeInformation -Encoding UTF8
$contingencyRows | Export-Csv -Path $contingencyCsv -NoTypeInformation -Encoding UTF8
$actionRows | Export-Csv -Path $actionsCsv -NoTypeInformation -Encoding UTF8
$maseEventRows | Export-Csv -Path $maseEventsCsv -NoTypeInformation -Encoding UTF8
$maseAgentMovedRows | Export-Csv -Path $maseAgentMovedCsv -NoTypeInformation -Encoding UTF8
$maseTransactionRows | Export-Csv -Path $maseTransactionsCsv -NoTypeInformation -Encoding UTF8
$agentRows | Export-Csv -Path $agentsCsv -NoTypeInformation -Encoding UTF8
$cycleRows | Export-Csv -Path $cycleDurationsCsv -NoTypeInformation -Encoding UTF8
foreach ($group in ($maseAgentMovedRows | Group-Object run_id | Sort-Object Name)) {
    if (-not $group.Name) {
        continue
    }

    $first = @($group.Group)[0]
    $meta = [pscustomobject][ordered]@{
        batchId = $first.batch_id
        jcm = $first.jcm
        ccrsMode = $first.ccrs_mode
    }
    Write-PathAnalysisInputs `
        -Rows @($group.Group) `
        -TargetDirectory $pathAnalysisDir `
        -FilePrefix $group.Name `
        -RelativeDirectory "path-analysis-inputs" `
        -RunMeta $meta `
        -RunId $group.Name `
        -IndexRows $pathAnalysisRows
}

if ($pathAnalysisRows.Count -gt 0) {
    $pathAnalysisRows | Export-Csv -Path $pathAnalysisCsv -NoTypeInformation -Encoding UTF8
}

[ordered]@{
    batchId = if ($BatchId) { $BatchId } else { Split-Path -Leaf $RunRoot }
    runRoot = $RunRoot
    outputDir = $OutputDir
    runCount = $runRows.Count
    decisionCount = $decisionRows.Count
    contingencyRecordCount = $contingencyRows.Count
    actionRecordCount = $actionRows.Count
    maseEventCount = $maseEventRows.Count
    maseAgentMovedCount = $maseAgentMovedRows.Count
    maseTransactionCount = $maseTransactionRows.Count
    agentSummaryCount = $agentRows.Count
    cycleDurationCount = $cycleRows.Count
    pathAnalysisInputCount = $pathAnalysisRows.Count
    generatedAt = (Get-Date).ToString("o")
} | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJson -Encoding UTF8

Write-Host "Parsed $($runRows.Count) run(s)."
Write-Host "Wrote $OutputDir"
