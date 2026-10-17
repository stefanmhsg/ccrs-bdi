param()

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$scriptsDir = Join-Path $repoRoot "experiments\scripts"
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ccrs-bdi-report-tests-" + [guid]::NewGuid().ToString("N"))
$script:passed = 0
$script:failures = [System.Collections.Generic.List[string]]::new()

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)

    if ("$Expected" -ne "$Actual") {
        throw "$Message. Expected '$Expected', got '$Actual'."
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-FileExists {
    param([string]$Path, [string]$Message)

    Assert-True -Condition (Test-Path -LiteralPath $Path -PathType Leaf) -Message $Message
}

function Invoke-Test {
    param([string]$Name, [scriptblock]$Body)

    try {
        & $Body | Out-Null
        $script:passed++
        Write-Host "PASS $Name"
    } catch {
        $script:failures.Add("$Name`: $($_.Exception.Message)")
        Write-Host "FAIL $Name"
        Write-Host "  $($_.Exception.Message)"
    }
}

function New-MaseEvent {
    param(
        [string]$Type,
        [string]$Agent,
        [string]$Cell,
        [int]$Sequence
    )

    $agentUri = "http://127.0.1.1:8080/agents/$Agent"
    $cellUri = "http://127.0.1.1:8080/$Cell"
    $timestamp = [int64]9000000000000 + ($Sequence * 1000)
    if ($Type -eq "TRANSACTION") {
        return [ordered]@{
            runId = "fixture-mase-run"
            type = $Type
            timestamp = $timestamp
            agent = $Agent
            graph = $cellUri
            transactionId = $Sequence
            event = [ordered]@{
                type = $Type
                agent = $Agent
                graph = $cellUri
                transactionId = $Sequence
                trigger = "POST"
                status = "COMMITTED"
                traceMode = "summary"
                ruleCount = 1
                startedAt = $timestamp - 10
                finishedAt = $timestamp
                timestamp = $timestamp
            }
            archiveId = $Sequence
        }
    }

    return [ordered]@{
        runId = "fixture-mase-run"
        type = $Type
        timestamp = $timestamp
        agent = $agentUri
        cell = $cellUri
        transactionId = -1
        event = [ordered]@{
            type = $Type
            agent = $agentUri
            cell = $cellUri
            timestamp = $timestamp
        }
        archiveId = $Sequence
    }
}

function Write-Ndjson {
    param([object[]]$Records, [string]$Path)

    $lines = @($Records | ForEach-Object { $_ | ConvertTo-Json -Depth 16 -Compress })
    Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

function New-FixtureRun {
    param(
        [string]$RunRoot,
        [string]$RunId,
        [string]$Agent,
        [bool]$Ccrs,
        [string]$Scenario = 'ccrs'
    )

    $runDir = Join-Path $RunRoot $RunId
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    $logName = "mas-0.log"
    [ordered]@{
        batchId = "fixture-v1"
        runId = $RunId
        jcm = if ($Ccrs) { "dfs_ccrs.jcm" } else { "dfs_baseline.jcm" }
        agentType = "manual"
        ccrsMode = if ($Ccrs) { "both" } else { "none" }
        scenario = $Scenario
        status = "manual_import"
        timeout = $false
        exitCode = $null
        logFiles = @($logName)
        agentNames = @($Agent)
        maseCaptureStatus = "completed"
        maseCaptureEventCount = 4
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDir "run.json") -Encoding UTF8

    $logLines = [System.Collections.Generic.List[string]]::new()
    $logLines.Add("[$Agent] [METRIC] event=agent.cycle.location step=1 y=2026 m=1 d=1 h=12 min=0 sec=0 ms=0 t_ms=43200000 previous=http://127.0.1.1:8080/maze cell=http://127.0.1.1:8080/cells/0")
    $logLines.Add("[$Agent] Attempting move to: http://127.0.1.1:8080/cells/0")
    $logLines.Add("[$Agent] Access approved: http://127.0.1.1:8080/cells/0")
    if ($Ccrs) {
        $logLines.Add("[CcrsAgentArch] [CCRS-EVENT] event=ccrs.opportunistic.detected agent_id=$Agent source=http://127.0.1.1:8080/cells/0 target=http://127.0.1.1:8080/cells/999 type=signifier pattern_id=fixture utility=0.9")
        $logLines.Add("[prioritize] [CCRS-EVENT] event=ccrs.opportunistic.prioritize agent_id=$Agent options_count=2 matched_count=1 selected_uri=http://127.0.1.1:8080/cells/999 selected_original_index=1 selected_has_ccrs=true selected_reordered=true selected_origin=opportunistic-ccrs selected_type=signifier selected_utility=0.9 selected_strategy=null")
        $logLines.Add("[prioritize] [CCRS-EVENT] event=ccrs.opportunistic.prioritize agent_id=$Agent options_count=0 matched_count=0 selected_uri=null selected_original_index=null selected_has_ccrs=false selected_reordered=false")
        $logLines.Add("[prioritize] [CCRS-EVENT] event=ccrs.opportunistic.prioritize agent_id=$Agent options_count=1 matched_count=0 selected_uri=http://127.0.1.1:8080/cells/999 selected_original_index=0 selected_has_ccrs=false selected_reordered=false")
        $logLines.Add("[evaluate] [CCRS-EVENT] event=ccrs.contingency.evaluate.request agent_id=$Agent trigger=blocked_navigation current_resource=http://127.0.1.1:8080/cells/0 failed_action=get http_status=503 error_type=SERVER_FAILURE error_message=service_unavailable")
        $logLines.Add("[evaluate] [CCRS-EVENT] event=ccrs.contingency.strategy.evaluated agent_id=$Agent strategy_id=backtrack result_type=suggestion action_type=navigate action_target=http://127.0.1.1:8080/cells/999 confidence=0.8 rationale=fixture-recovery")
    }
    $cycleDuration = if ($Ccrs) { 400 } else { 200 }
    $cycleTimestamp = [int64]43200000 + $cycleDuration
    $logLines.Add("[$Agent] [METRIC] event=agent.cycle.location step=2 y=2026 m=1 d=1 h=12 min=0 sec=0 ms=$cycleDuration t_ms=$cycleTimestamp previous=http://127.0.1.1:8080/cells/0 cell=http://127.0.1.1:8080/cells/999")
    $logLines.Add("[$Agent] Attempting move to: http://127.0.1.1:8080/cells/999")
    $logLines.Add("[$Agent] Access approved: http://127.0.1.1:8080/cells/999")
    Set-Content -LiteralPath (Join-Path $runDir $logName) -Value $logLines -Encoding UTF8

    $events = @(
        (New-MaseEvent -Type "AGENT_MOVED" -Agent $Agent -Cell "cells/0" -Sequence 1),
        (New-MaseEvent -Type "TRANSACTION" -Agent $Agent -Cell "cells/0" -Sequence 2),
        (New-MaseEvent -Type "AGENT_MOVED" -Agent $Agent -Cell "cells/999" -Sequence 3),
        (New-MaseEvent -Type "AGENT_MOVED" -Agent "unrelated-agent" -Cell "cells/7" -Sequence 4)
    )
    Write-Ndjson -Records $events -Path (Join-Path $runDir "mase-events.jsonl")
}

New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
try {
    Invoke-Test -Name "import-manual-run normalizes and preserves sources" -Body {
        $caseRoot = Join-Path $testRoot "import"
        $sourceDir = Join-Path $caseRoot "source"
        $outputRoot = Join-Path $caseRoot "runs"
        New-Item -ItemType Directory -Path $sourceDir -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $sourceDir "mas-0.log") -Value "fixture log" -Encoding UTF8
        Set-Content -LiteralPath (Join-Path $sourceDir "notes.txt") -Value "fixture notes" -Encoding UTF8
        $exportPath = Join-Path $sourceDir "mase-viewer-export.json"
        $exportRecords = @(
            (New-MaseEvent -Type "AGENT_MOVED" -Agent "fixture-agent" -Cell "cells/0" -Sequence 1),
            (New-MaseEvent -Type "AGENT_MOVED" -Agent "fixture-agent" -Cell "cells/999" -Sequence 2)
        )
        $exportJson = ConvertTo-Json -InputObject $exportRecords -Depth 16
        Assert-Equal -Expected 2 -Actual $exportRecords.Count -Message "Fixture export record count is incorrect"
        Assert-True -Condition $exportJson.TrimStart().StartsWith("[") -Message "Fixture export was not serialized as a JSON array"
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($exportPath, $exportJson, $utf8NoBom)
        $ndjsonPath = Join-Path $sourceDir "mase-viewer-extra.ndjson"
        Write-Ndjson -Records @(
            (New-MaseEvent -Type "TRANSACTION" -Agent "fixture-agent" -Cell "cells/999" -Sequence 3)
        ) -Path $ndjsonPath

        & (Join-Path $scriptsDir "import-manual-run.ps1") `
            -SourceDir $sourceDir `
            -OutputRoot $outputRoot `
            -BatchId "fixture-batch" `
            -RunId "001-ccrs" `
            -Jcm "dfs_ccrs.jcm" `
            -AgentName "fixture-agent" `
            -KeepSource

        $runDir = Join-Path $outputRoot "fixture-batch\001-ccrs"
        $run = Get-Content -LiteralPath (Join-Path $runDir "run.json") -Raw | ConvertFrom-Json
        $manifest = Get-Content -LiteralPath (Join-Path $outputRoot "fixture-batch\manifest.json") -Raw | ConvertFrom-Json
        $normalizedEvents = @(Get-Content -LiteralPath (Join-Path $runDir "mase-events.jsonl"))

        Assert-Equal -Expected "both" -Actual $run.ccrsMode -Message "CCRS mode was not inferred from the JCM"
        Assert-Equal -Expected 3 -Actual $run.maseCaptureEventCount -Message "Unexpected normalized MASE event count"
        Assert-Equal -Expected "completed" -Actual $run.maseCaptureStatus -Message "Unexpected MASE capture status"
        Assert-Equal -Expected 1 -Actual @($manifest.runs).Count -Message "Manifest run count is incorrect"
        Assert-Equal -Expected 3 -Actual $normalizedEvents.Count -Message "Normalized NDJSON line count is incorrect"
        Assert-FileExists -Path (Join-Path $runDir "mas-0.log") -Message "Imported log is missing"
        Assert-True -Condition (Test-Path -LiteralPath $exportPath) -Message "-KeepSource removed the MASE export"
        Assert-True -Condition (Test-Path -LiteralPath $ndjsonPath) -Message "-KeepSource removed the NDJSON export"
        Assert-True -Condition (Test-Path -LiteralPath (Join-Path $sourceDir "notes.txt")) -Message "-KeepSource removed metadata"
    }

    Invoke-Test -Name "write-report parses fixtures and is idempotent" -Body {
        $caseRoot = Join-Path $testRoot "report"
        $runRoot = Join-Path $caseRoot "fixture-v1"
        $outputDir = Join-Path $caseRoot "output"
        New-FixtureRun -RunRoot $runRoot -RunId "001-baseline" -Agent "fixture-baseline" -Ccrs $false
        New-FixtureRun -RunRoot $runRoot -RunId "002-ccrs" -Agent "fixture-ccrs" -Ccrs $true

        $reportArgs = @{
            BatchId = "fixture-v1"
            RunRoot = $runRoot
            OutputDir = $outputDir
        }
        & (Join-Path $scriptsDir "write-report.ps1") @reportArgs
        $firstSummary = Get-Content -LiteralPath (Join-Path $outputDir "summary.json") -Raw | ConvertFrom-Json
        & (Join-Path $scriptsDir "write-report.ps1") @reportArgs
        $summary = Get-Content -LiteralPath (Join-Path $outputDir "summary.json") -Raw | ConvertFrom-Json

        Assert-Equal -Expected 2 -Actual $summary.runCount -Message "Unexpected run count"
        Assert-Equal -Expected 3 -Actual $summary.decisionCount -Message "Unexpected decision count"
        Assert-Equal -Expected 2 -Actual $summary.contingencyRecordCount -Message "Unexpected contingency count"
        Assert-Equal -Expected 8 -Actual $summary.actionRecordCount -Message "Unexpected action count"
        Assert-Equal -Expected 6 -Actual $summary.maseEventCount -Message "Agent filtering produced the wrong MASE count"
        Assert-Equal -Expected 2 -Actual $summary.maseAgentMovedCount -Message "Initial placements must not count as moves"
        Assert-Equal -Expected 2 -Actual $summary.maseTransactionCount -Message "Unexpected transaction count"
        Assert-Equal -Expected 2 -Actual $summary.cycleDurationCount -Message "Initial placement markers must not count as cycles"
        Assert-Equal -Expected 2 -Actual $summary.pathAnalysisInputCount -Message "Unexpected path input count"
        Assert-Equal -Expected $firstSummary.decisionCount -Actual $summary.decisionCount -Message "Repeated parsing changed the decision count"
        Assert-Equal -Expected $firstSummary.maseEventCount -Actual $summary.maseEventCount -Message "Repeated parsing changed the MASE count"

        $runs = @(Import-Csv -LiteralPath (Join-Path $outputDir "runs.csv"))
        $baseline = @($runs | Where-Object { $_.run_id -eq "001-baseline" })[0]
        $ccrs = @($runs | Where-Object { $_.run_id -eq "002-ccrs" })[0]
        Assert-Equal -Expected "success" -Actual $baseline.outcome -Message "Baseline outcome is incorrect"
        Assert-Equal -Expected "success" -Actual $ccrs.outcome -Message "CCRS outcome is incorrect"
        Assert-Equal -Expected 200 -Actual $baseline.average_agent_cycle_duration_ms -Message "Baseline cycle average is incorrect"
        Assert-Equal -Expected 400 -Actual $ccrs.average_agent_cycle_duration_ms -Message "CCRS cycle average is incorrect"
        Assert-Equal -Expected 1 -Actual $ccrs.ccrs_detected -Message "CCRS detection count is incorrect"
        Assert-Equal -Expected 1 -Actual $ccrs.contingency_invocations -Message "Contingency invocation count is incorrect"
        Assert-Equal -Expected 2 -Actual $ccrs.zero_or_one_option_decisions -Message "Zero-option decisions must be included"
        Assert-Equal -Expected 1 -Actual $ccrs.multi_option_decisions -Message "Zero-option decisions changed the multi-option denominator"
        Assert-Equal -Expected 116 -Actual $baseline.optimal_moves -Message "V1 optimum must match MASE"
        Assert-Equal -Expected 1 -Actual $baseline.actual_moves -Message "Placement was included in actual moves"
        Assert-Equal -Expected (-115) -Actual $baseline.move_delta_from_optimal -Message "Move delta uses inconsistent counting"
        Assert-Equal -Expected '' -Actual $baseline.average_agent_cycle_duration_status -Message "One timed move is a valid cycle sample"
        $cycles = @(Import-Csv -LiteralPath (Join-Path $outputDir 'cycle-durations.csv'))
        Assert-Equal -Expected 1 -Actual $cycles[0].step -Message "The first evaluated movement must be step 1"
        $agentRows = @(Import-Csv -LiteralPath (Join-Path $outputDir 'agents.csv'))
        Assert-Equal -Expected 1 -Actual $agentRows[0].mase_moves -Message "Per-agent count includes placement"
        $zones = @(Import-Csv -LiteralPath (Join-Path $outputDir 'zone-summary.csv'))
        $signifier = @($zones | Where-Object { $_.run_id -eq '002-ccrs' -and $_.zone -eq 'signifier' })[0]
        Assert-Equal -Expected 1 -Actual $signifier.actual_moves -Message "Zone count includes placement"
        Assert-Equal -Expected 17 -Actual $signifier.optimal_moves -Message "V1 zone optimum must match MASE"
        Assert-Equal -Expected 1 -Actual $signifier.multi_option_decisions -Message "First-move decision was lost from the zone"
        $paths = @(Import-Csv -LiteralPath (Join-Path $outputDir 'path-analysis-inputs.csv'))
        Assert-Equal -Expected 1 -Actual $paths[0].steps -Message "Path steps must exclude the start anchor"
        Assert-Equal -Expected '/cells/0' -Actual (Get-Content -LiteralPath (Join-Path $outputDir $paths[0].file) -TotalCount 1) -Message "Path lost its start anchor"

        $decisions = @(Import-Csv -LiteralPath (Join-Path $outputDir "decisions.csv"))
        Assert-Equal -Expected "True" -Actual $decisions[0].selected_reordered -Message "Decision reordering was not parsed"
        Assert-Equal -Expected "signifier" -Actual $decisions[0].selected_type -Message "Decision type was not parsed"
        Assert-Equal -Expected 10 -Actual @(Import-Csv -LiteralPath (Join-Path $outputDir "zone-summary.csv")).Count -Message "Zone summary row count is incorrect"

        Assert-FileExists -Path (Join-Path $outputDir "summary.md") -Message "Markdown report is missing"
        Assert-FileExists -Path (Join-Path $outputDir "cycle-duration-comparison.svg") -Message "Cycle chart is missing"
        Assert-Equal -Expected 2 -Actual @(Get-ChildItem -LiteralPath (Join-Path $outputDir "path-analysis-inputs") -Filter "*.cells.txt" -File).Count -Message "Path analysis files are missing"
        $markdown = Get-Content -LiteralPath (Join-Path $outputDir "summary.md") -Raw
        Assert-True -Condition $markdown.Contains("# Experiment Summary: fixture-v1") -Message "Report title is missing"
        Assert-True -Condition $markdown.Contains("## Zone Metrics") -Message "Zone report section is missing"
    }

    Invoke-Test -Name 'explicit V2 scenario overrides the batch suffix' -Body {
        $runRoot = Join-Path $testRoot 'scenario-runs'
        $outputDir = Join-Path $testRoot 'scenario-output'
        New-FixtureRun -RunRoot $runRoot -RunId '001-baseline' -Agent 'baseline' -Ccrs $false -Scenario 'CcrsMazeV2'
        & (Join-Path $scriptsDir 'write-report.ps1') -BatchId 'neutral-batch' -RunRoot $runRoot -OutputDir $outputDir
        $run = @(Import-Csv -LiteralPath (Join-Path $outputDir 'runs.csv'))[0]
        Assert-Equal -Expected 'CcrsMazeV2' -Actual $run.scenario -Message 'Explicit scenario was ignored'
        Assert-Equal -Expected 138 -Actual $run.optimal_moves -Message 'V2 optimum must match MASE'
        $zones = @(Import-Csv -LiteralPath (Join-Path $outputDir 'zone-summary.csv'))
        Assert-Equal -Expected 19 -Actual $zones[0].optimal_moves -Message 'V2 signifier optimum is incorrect'
        Assert-Equal -Expected 57 -Actual $zones[2].optimal_moves -Message 'V2 mixed optimum is incorrect'
        $markdown = Get-Content -LiteralPath (Join-Path $outputDir 'summary.md') -Raw
        Assert-True -Condition $markdown.Contains('Scenario: CcrsMazeV2') -Message 'Report scenario differs from run metrics'
        Assert-True -Condition $markdown.Contains('contains 3 locked cells') -Message 'V2 scenario description is incorrect'
    }

    Invoke-Test -Name 'give-up timestamps and return moves appear without success' -Body {
        $runRoot = Join-Path $testRoot 'give-up-runs'
        $outputDir = Join-Path $testRoot 'give-up-output'
        foreach ($case in @(@('001-baseline', $false), @('002-ccrs', $true))) {
            $agent = $case[0]
            New-FixtureRun -RunRoot $runRoot -RunId $agent -Agent $agent -Ccrs $case[1]
            $runDir = Join-Path $runRoot $agent
            @(
                "[$agent] [METRIC] event=agent.cycle.location step=1 y=2026 m=1 d=1 h=12 min=0 sec=0 ms=0 t_ms=43200000 previous=http://localhost/maze cell=http://localhost/cells/0"
                "[$agent] [METRIC] event=agent.cycle.location step=2 y=2026 m=1 d=1 h=12 min=0 sec=0 ms=200 t_ms=43200200 previous=http://localhost/cells/0 cell=http://localhost/cells/1"
                "[$agent] [METRIC] event=agent.cycle.location step=3 y=2026 m=1 d=1 h=12 min=0 sec=0 ms=400 t_ms=43200400 previous=http://localhost/cells/1 cell=http://localhost/cells/0"
                "[$agent] All the way back to http://localhost/maze. I give up."
                "[$agent] [METRIC] event=agent.run.finished y=2026 m=1 d=1 h=12 min=0 sec=1 ms=234"
                "[$agent] It took 1 seconds and 234 milliseconds"
                "[$agent] found 42 triples in the KG."
            ) | Set-Content -LiteralPath (Join-Path $runDir 'mas-0.log') -Encoding UTF8
            Write-Ndjson -Path (Join-Path $runDir 'mase-events.jsonl') -Records @(
                (New-MaseEvent -Type 'AGENT_MOVED' -Agent $agent -Cell 'cells/0' -Sequence 1),
                (New-MaseEvent -Type 'AGENT_MOVED' -Agent $agent -Cell 'cells/1' -Sequence 2),
                (New-MaseEvent -Type 'AGENT_MOVED' -Agent $agent -Cell 'cells/0' -Sequence 3)
            )
        }
        & (Join-Path $scriptsDir 'write-report.ps1') -BatchId 'give-up-v1' -RunRoot $runRoot -OutputDir $outputDir
        foreach ($run in @(Import-Csv -LiteralPath (Join-Path $outputDir 'runs.csv'))) {
            Assert-Equal -Expected 'failure' -Actual $run.outcome -Message 'Elapsed time must not imply success'
            Assert-Equal -Expected 'False' -Actual $run.reached_exit -Message 'Give-up was reported as reaching exit'
            Assert-Equal -Expected 1234 -Actual $run.agent_elapsed_total_ms -Message 'Give-up duration is missing'
            Assert-Equal -Expected '2026-01-01 12:00:01.234' -Actual $run.agent_finished_at -Message 'End timestamp is missing'
            Assert-Equal -Expected 2 -Actual $run.actual_moves -Message 'A later return to cells/0 must count'
            Assert-Equal -Expected 200 -Actual $run.average_agent_cycle_duration_ms -Message 'First evaluated move lost its time reference'
        }
        $markdown = Get-Content -LiteralPath (Join-Path $outputDir 'summary.md') -Raw
        Assert-Equal -Expected 2 -Actual ([regex]::Matches($markdown, 'no \| 2026-01-01 12:00:01.234 \| 1234').Count) -Message 'Both give-up results must show end timestamp and duration in the report'
    }

    if ($script:failures.Count -gt 0) {
        throw ("{0} test(s) failed:`n{1}" -f $script:failures.Count, ($script:failures -join "`n"))
    }

    Write-Host "All $script:passed BDI experiment script tests passed."
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
