param(
    [string]$BatchId,
    [string]$RunRoot,
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"

function Convert-ToDouble {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") { return 0.0 }
    return [double]::Parse(
        "$Value",
        [System.Globalization.CultureInfo]::InvariantCulture
    )
}

function Format-Rate {
    param([double]$Value)

    return ("{0:P1}" -f $Value)
}

function Format-Bool {
    param($Value)

    if ("$Value".ToLowerInvariant() -eq "true") {
        return "yes"
    }
    return "no"
}

function Format-Value {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") {
        return "-"
    }
    return "$Value"
}

function Format-Ms {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") {
        return "-"
    }
    return (Convert-ToDouble $Value).ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Format-Number {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") {
        return "-"
    }
    return "$Value"
}

function Format-CodeValue {
    param($Value)

    $formatted = Format-Value $Value
    if ($formatted -eq "-") {
        return "-"
    }
    return "``$formatted``"
}

function Average-Field {
    param(
        [object[]]$Rows,
        [string]$Field
    )

    if (-not $Rows -or $Rows.Count -eq 0) {
        return 0.0
    }

    $values = @()
    foreach ($row in $Rows) {
        $value = $row.$Field
        if ($null -ne $value -and "$value" -ne "") {
            $values += (Convert-ToDouble $value)
        }
    }

    if ($values.Count -eq 0) {
        return 0.0
    }

    return ($values | Measure-Object -Average).Average
}

function Get-FirstRunMatching {
    param(
        [object[]]$Rows,
        [string]$Pattern
    )

    return @($Rows | Where-Object { $_.jcm -match $Pattern -or $_.run_id -match $Pattern } | Sort-Object run_id | Select-Object -First 1)[0]
}

function Get-OverruledDecisionTypeCounts {
    param(
        [object[]]$DecisionRows,
        $CcrsRun
    )

    if (-not $CcrsRun) {
        return @()
    }

    $ccrsDecisionRows = @($DecisionRows | Where-Object {
        $_.run_id -eq $CcrsRun.run_id -and
        "$($_.selected_reordered)".ToLowerInvariant() -eq "true" -and
        $_.selected_type -and "$($_.selected_type)" -ne "null"
    })

    if ($ccrsDecisionRows.Count -eq 0) {
        return @()
    }

    $result = @()
    foreach ($group in ($ccrsDecisionRows | Group-Object selected_type | Sort-Object Name)) {
        $result += [pscustomobject][ordered]@{
            type = $group.Name
            overruled_decisions = $group.Count
        }
    }
    return $result
}

function ConvertTo-SvgPoint {
    param(
        [double]$X,
        [double]$Y,
        [double]$MinX,
        [double]$MaxX,
        [double]$MinY,
        [double]$MaxY,
        [double]$PlotX,
        [double]$PlotY,
        [double]$PlotWidth,
        [double]$PlotHeight
    )

    $xRange = [math]::Max(1.0, $MaxX - $MinX)
    $yRange = [math]::Max(1.0, $MaxY - $MinY)
    $px = $PlotX + (($X - $MinX) / $xRange) * $PlotWidth
    $py = $PlotY + $PlotHeight - (($Y - $MinY) / $yRange) * $PlotHeight
    return ("{0:F2},{1:F2}" -f $px, $py)
}

function New-CycleDurationChart {
    param(
        [object[]]$Rows,
        [string]$OutputPath
    )

    $plotRows = @($Rows | Where-Object { $_.duration_ms -ne $null -and "$($_.duration_ms)" -ne "" })
    if ($plotRows.Count -eq 0) {
        return $null
    }

    $width = 960
    $height = 420
    $plotX = 72
    $plotY = 40
    $plotWidth = 820
    $plotHeight = 300
    $colors = @("#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e")
    $steps = @($plotRows | ForEach-Object { Convert-ToDouble $_.step })
    $durations = @($plotRows | ForEach-Object { Convert-ToDouble $_.duration_ms })
    $minX = ($steps | Measure-Object -Minimum).Minimum
    $maxX = ($steps | Measure-Object -Maximum).Maximum
    $minY = 0
    $maxY = [math]::Ceiling((($durations | Measure-Object -Maximum).Maximum) * 1.1)
    if ($maxY -le 0) { $maxY = 1 }

    $svg = New-Object System.Collections.Generic.List[string]
    $svg.Add("<svg xmlns=""http://www.w3.org/2000/svg"" width=""$width"" height=""$height"" viewBox=""0 0 $width $height"" role=""img"" aria-labelledby=""title desc"">")
    $svg.Add("<title id=""title"">Cycle duration comparison</title>")
    $svg.Add("<desc id=""desc"">Line chart comparing cycle duration by movement step for experiment runs.</desc>")
    $svg.Add("<rect width=""100%"" height=""100%"" fill=""#ffffff""/>")
    $svg.Add("<line x1=""$plotX"" y1=""$($plotY + $plotHeight)"" x2=""$($plotX + $plotWidth)"" y2=""$($plotY + $plotHeight)"" stroke=""#333"" stroke-width=""1""/>")
    $svg.Add("<line x1=""$plotX"" y1=""$plotY"" x2=""$plotX"" y2=""$($plotY + $plotHeight)"" stroke=""#333"" stroke-width=""1""/>")
    $svg.Add("<text x=""$($plotX + ($plotWidth / 2))"" y=""$($height - 24)"" text-anchor=""middle"" font-family=""Arial, sans-serif"" font-size=""13"">Step number</text>")
    $svg.Add("<text x=""18"" y=""$($plotY + ($plotHeight / 2))"" text-anchor=""middle"" transform=""rotate(-90 18 $($plotY + ($plotHeight / 2)))"" font-family=""Arial, sans-serif"" font-size=""13"">Duration ms</text>")
    $svg.Add("<text x=""$plotX"" y=""$($plotY + $plotHeight + 18)"" text-anchor=""middle"" font-family=""Arial, sans-serif"" font-size=""11"">$minX</text>")
    $svg.Add("<text x=""$($plotX + $plotWidth)"" y=""$($plotY + $plotHeight + 18)"" text-anchor=""middle"" font-family=""Arial, sans-serif"" font-size=""11"">$maxX</text>")
    $svg.Add("<text x=""$($plotX - 8)"" y=""$($plotY + $plotHeight)"" text-anchor=""end"" dominant-baseline=""middle"" font-family=""Arial, sans-serif"" font-size=""11"">0</text>")
    $svg.Add("<text x=""$($plotX - 8)"" y=""$plotY"" text-anchor=""end"" dominant-baseline=""middle"" font-family=""Arial, sans-serif"" font-size=""11"">$maxY</text>")

    $groups = @($plotRows | Group-Object run_id | Sort-Object Name)
    $colorIndex = 0
    foreach ($group in $groups) {
        $color = $colors[$colorIndex % $colors.Count]
        $colorIndex++
        $points = @()
        foreach ($row in ($group.Group | Sort-Object @{ Expression = { Convert-ToDouble $_.step } })) {
            $points += ConvertTo-SvgPoint `
                -X (Convert-ToDouble $row.step) `
                -Y (Convert-ToDouble $row.duration_ms) `
                -MinX $minX `
                -MaxX $maxX `
                -MinY $minY `
                -MaxY $maxY `
                -PlotX $plotX `
                -PlotY $plotY `
                -PlotWidth $plotWidth `
                -PlotHeight $plotHeight
        }
        if ($points.Count -gt 0) {
            $label = [System.Security.SecurityElement]::Escape($group.Name)
            $svg.Add("<polyline fill=""none"" stroke=""$color"" stroke-width=""2"" points=""$($points -join ' ')""/>")
            $legendY = 34 + (($colorIndex - 1) * 20)
            $svg.Add("<line x1=""$($plotX + 610)"" y1=""$legendY"" x2=""$($plotX + 640)"" y2=""$legendY"" stroke=""$color"" stroke-width=""2""/>")
            $svg.Add("<text x=""$($plotX + 648)"" y=""$legendY"" dominant-baseline=""middle"" font-family=""Arial, sans-serif"" font-size=""12"">$label</text>")
        }
    }

    $svg.Add("</svg>")
    $svg | Set-Content -Path $OutputPath -Encoding UTF8
    return $OutputPath
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

$runsCsv = Join-Path $OutputDir "runs.csv"
$cycleDurationsCsv = Join-Path $OutputDir "cycle-durations.csv"
if (-not (Test-Path -LiteralPath $RunRoot)) {
    throw (Get-RunRootHelp -RepoRoot $repoRoot -RunRoot $RunRoot)
}
& (Join-Path $PSScriptRoot "parse-experiment-logs.ps1") -BatchId $BatchId -RunRoot $RunRoot -OutputDir $OutputDir

$runs = @(Import-Csv -Path $runsCsv)
$contingencyCsv = Join-Path $OutputDir "contingency.csv"
$contingency = @(if (Test-Path -LiteralPath $contingencyCsv) { Import-Csv -Path $contingencyCsv })
$decisionsCsv = Join-Path $OutputDir "decisions.csv"
$decisions = @(if (Test-Path -LiteralPath $decisionsCsv) { Import-Csv -Path $decisionsCsv })
$maseEventsCsv = Join-Path $OutputDir "mase-events.csv"
$maseEvents = @(if (Test-Path -LiteralPath $maseEventsCsv) { Import-Csv -Path $maseEventsCsv })
$agentsCsv = Join-Path $OutputDir "agents.csv"
$agents = @(if (Test-Path -LiteralPath $agentsCsv) { Import-Csv -Path $agentsCsv })
$pathAnalysisCsv = Join-Path $OutputDir "path-analysis-inputs.csv"
$pathAnalysisInputs = @(if (Test-Path -LiteralPath $pathAnalysisCsv) { Import-Csv -Path $pathAnalysisCsv })
$cycleDurations = @(if (Test-Path -LiteralPath $cycleDurationsCsv) { Import-Csv -Path $cycleDurationsCsv })

$batchName = if ($BatchId) { $BatchId } else { Split-Path -Leaf $RunRoot }
$summaryPath = Join-Path $OutputDir "summary.md"
$cycleChartPath = Join-Path $OutputDir "cycle-duration-comparison.svg"
$cycleChartFile = $null
if ($cycleDurations.Count -gt 0) {
    $generatedChart = New-CycleDurationChart -Rows $cycleDurations -OutputPath $cycleChartPath
    if ($generatedChart) {
        $cycleChartFile = Split-Path -Leaf $generatedChart
    }
}
$generatedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss K"

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Experiment Summary: $batchName")
$lines.Add("")
$lines.Add("Generated: $generatedAt")
$lines.Add("")
$lines.Add("Run root: ``$RunRoot``")
$lines.Add("")

if ($runs.Count -eq 0) {
    $lines.Add("No runs were parsed.")
    $lines | Set-Content -Path $summaryPath -Encoding UTF8
    Write-Host "Wrote $summaryPath"
    return
}

$lines.Add("## Core Metrics")
$lines.Add("")
$lines.Add("| Run | JCM | Reached exit | Total duration ms (source = ASL) | Total moves | Avg agent cycle duration | Final cell |")
$lines.Add("| --- | --- | --- | ---: | ---: | --- | --- |")
foreach ($row in $runs | Sort-Object run_id) {
    $duration = Format-Value $row.agent_elapsed_total_ms
    $avgCycle = Format-Ms $row.average_agent_cycle_duration_ms
$lines.Add("| ``$($row.run_id)`` | ``$($row.jcm)`` | $(Format-Bool $row.reached_exit) | $duration | $($row.mase_agent_moved) | $avgCycle | ``$($row.mase_final_cell)`` |")
}

$lines.Add("")
$lines.Add("## Move Optimality")
$lines.Add("")
$lines.Add("| Run | JCM | Optimal moves | Actual moves | Delta from optimal |")
$lines.Add("| --- | --- | ---: | ---: | ---: |")
foreach ($row in $runs | Sort-Object run_id) {
    $optimalMoves = if ($row.optimal_moves) { $row.optimal_moves } else { "118" }
    $actualMoves = if ($row.actual_moves) { $row.actual_moves } else { $row.mase_agent_moved }
    $deltaMoves = if ($row.move_delta_from_optimal -ne $null -and "$($row.move_delta_from_optimal)" -ne "") {
        $row.move_delta_from_optimal
    } elseif ($actualMoves -ne $null -and "$actualMoves" -ne "") {
        (Convert-ToDouble $actualMoves) - (Convert-ToDouble $optimalMoves)
    } else {
        $null
    }
    $lines.Add("| ``$($row.run_id)`` | ``$($row.jcm)`` | $(Format-Number $optimalMoves) | $(Format-Number $actualMoves) | $(Format-Number $deltaMoves) |")
}

$baselineRun = Get-FirstRunMatching -Rows $runs -Pattern "baseline"
$ccrsRun = Get-FirstRunMatching -Rows $runs -Pattern "ccrs"
if ($baselineRun -or $ccrsRun) {
    $lines.Add("")
    $lines.Add("## Cycle Duration Summary")
    $lines.Add("")
    $lines.Add("| Baseline avg ms | CCRS avg ms | CCRS opp 0 avg ms | CCRS opp 1 avg ms | CCRS opp 2 avg ms | CCRS opp 3+ avg ms | CCRS cont 1 avg ms | CCRS cont 2 avg ms | CCRS cont 3+ avg ms |")
    $lines.Add("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    $lines.Add("| $(Format-Ms $baselineRun.average_agent_cycle_duration_ms) | $(Format-Ms $ccrsRun.average_agent_cycle_duration_ms) | $(Format-Ms $ccrsRun.average_cycle_opp0_ms) | $(Format-Ms $ccrsRun.average_cycle_opp1_ms) | $(Format-Ms $ccrsRun.average_cycle_opp2_ms) | $(Format-Ms $ccrsRun.average_cycle_opp3plus_ms) | $(Format-Ms $ccrsRun.average_cycle_cont1_ms) | $(Format-Ms $ccrsRun.average_cycle_cont2_ms) | $(Format-Ms $ccrsRun.average_cycle_cont3plus_ms) |")
}

if ($cycleChartFile) {
    $lines.Add("")
    $lines.Add("## Cycle Duration Chart")
    $lines.Add("")
    $lines.Add("![Cycle duration comparison]($cycleChartFile)")
}

$lines.Add("")
$lines.Add("## Decision Breakdown")
$lines.Add("")
$lines.Add("| Run | JCM | Decisions with 2+ directions | Opp-CCRS detected | Opp-CCRS detected rate | Opp-CCRS overruled default | Overruled rate | Decisions with 0-1 directions |")
$lines.Add("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
foreach ($row in ($runs | Where-Object { (Convert-ToDouble $_.multi_option_decisions) -gt 0 -or $_.ccrs_mode -ne "none" } | Sort-Object run_id)) {
    $multiCount = Convert-ToDouble $row.multi_option_decisions
    $detected = Convert-ToDouble $row.multi_option_with_ccrs
    $overruled = Convert-ToDouble $row.multi_option_overruled
    $detectedRate = if ($multiCount -gt 0) { $detected / $multiCount } else { 0 }
    $overruledRate = if ($multiCount -gt 0) { $overruled / $multiCount } else { 0 }
    $lines.Add("| ``$($row.run_id)`` | ``$($row.jcm)`` | $($row.multi_option_decisions) | $($row.multi_option_with_ccrs) | $(Format-Rate $detectedRate) | $($row.multi_option_overruled) | $(Format-Rate $overruledRate) | $($row.zero_or_one_option_decisions) |")
}

$overruledTypeRows = @(Get-OverruledDecisionTypeCounts -DecisionRows $decisions -CcrsRun $ccrsRun)
if ($overruledTypeRows.Count -gt 0) {
    $lines.Add("")
    $lines.Add("## Opportunistic CCRS Overruled Decisions")
    $lines.Add("")
    $lines.Add("| CCRS type | Overruled decisions |")
    $lines.Add("| --- | ---: |")
    foreach ($row in $overruledTypeRows) {
        $lines.Add("| $($row.type) | $($row.overruled_decisions) |")
    }
}

if ($contingency.Count -gt 0) {
    $strategyRows = @($contingency | Where-Object { $_.event -eq "ccrs.contingency.strategy.evaluated" })
    if ($strategyRows.Count -gt 0) {
        $lines.Add("")
        $lines.Add("## Contingency CCRS Details")
        $lines.Add("")
        foreach ($group in ($strategyRows | Group-Object run_id, invocation | Sort-Object Name)) {
            $first = @($group.Group)[0]
            $lines.Add("### Invocation $($first.invocation): ``$($first.run_id)``")
            $lines.Add("")
            $lines.Add("| Strategy | Result | Action | Target | Confidence | Eval ms | Opportunistic guidance | No-help reason | Rationale |")
            $lines.Add("| --- | --- | --- | --- | ---: | ---: | --- | --- | --- |")
            foreach ($row in @($group.Group | Sort-Object @{ Expression = { -(Convert-ToDouble $_.confidence) } }, strategy_id)) {
                $lines.Add("| $(Format-CodeValue $row.strategy_id) | $(Format-Value $row.result_type) | $(Format-Value $row.action_type) | $(Format-CodeValue $row.action_target) | $(Format-Value $row.confidence) | $(Format-Value $row.evaluation_time_ms) | $(Format-Value $row.has_opportunistic_guidance) | $(Format-Value $row.no_help_reason) | $(Format-Value $row.rationale) |")
            }
            $lines.Add("")
        }
    } else {
        $selected = @($contingency | Where-Object { $_.event -eq "ccrs.contingency.evaluate.completed" })
        if ($selected.Count -eq 0) {
            $selected = @($contingency | Where-Object { $_.event -eq "ccrs.contingency.evaluate.returned" })
        }
        if ($selected.Count -gt 0) {
            $lines.Add("")
            $lines.Add("## Contingency CCRS Details")
            $lines.Add("")
            $lines.Add("| Run | Invocation | Strategy | Result | Action | Target | Confidence |")
            $lines.Add("| --- | ---: | --- | --- | --- | --- | ---: |")
            foreach ($row in ($selected | Sort-Object run_id, invocation, @{ Expression = { -(Convert-ToDouble $_.confidence) } })) {
                $lines.Add("| ``$($row.run_id)`` | $($row.invocation) | $(Format-CodeValue $row.strategy_id) | $(Format-Value $row.result_type) | $(Format-Value $row.action_type) | $(Format-CodeValue $row.action_target) | $(Format-Value $row.confidence) |")
            }
        }
    }
}

$lines.Add("")
$lines.Add("## Generated Artifacts")
$lines.Add("")
$lines.Add('- `runs.csv`: one row per run with outcome and aggregate metrics.')
$lines.Add('- `decisions.csv`: one row per parsed prioritization decision.')
$lines.Add('- `contingency.csv`: structured and fallback contingency events.')
$lines.Add('- `actions.csv`: parsed movement and action events.')
$lines.Add('- `mase-events.csv`: filtered MASE `AGENT_MOVED` and `TRANSACTION` event records.')
$lines.Add('- `mase-agent-moved.csv`: MASE movement events normalized for route analysis.')
$lines.Add('- `mase-transactions.csv`: MASE transaction events normalized for action/server-side analysis.')
$lines.Add('- `agents.csv`: one row per observed agent per run with movement and transaction totals.')
$lines.Add('- `cycle-durations.csv`: one row per structured agent cycle marker with derived cycle duration and CCRS event counts.')
if ($cycleChartFile) {
    $lines.Add("- `$cycleChartFile`: SVG line chart comparing cycle duration by step.")
}
$lines.Add('- `path-analysis-inputs/*.cells.txt`: copy-paste cell sequences for the MASE viewer Path Analysis overlay.')
$lines.Add('- `path-analysis-inputs.csv`: index of generated Path Analysis copy-paste files.')
$lines.Add('- `summary.json`: parser metadata.')

$lines | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host "Wrote $summaryPath"
