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
    return (Format-MarkdownCellText $Value)
}

function Format-MarkdownCellText {
    param($Value)

    if ($null -eq $Value -or "$Value" -eq "") {
        return "-"
    }

    $text = "$Value"
    $text = $text.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;")
    $text = $text -replace "\r?\n", "<br>"
    $text = $text.Replace("|", "\|")
    return $text.Trim()
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

function Get-Average {
    param([object[]]$Values)

    $numbers = @()
    foreach ($value in $Values) {
        if ($null -ne $value -and "$value" -ne "") {
            $numbers += (Convert-ToDouble $value)
        }
    }

    if ($numbers.Count -eq 0) {
        return $null
    }

    return [math]::Round(($numbers | Measure-Object -Average).Average, 2)
}

function Get-ContingencyInvocationCycleAverages {
    param(
        [object[]]$CycleRows,
        [string]$RunId
    )

    if (-not $RunId) {
        return @()
    }

    $contingencyCycles = @($CycleRows | Where-Object {
        $_.run_id -eq $RunId -and
        $_.duration_ms -ne $null -and
        "$($_.duration_ms)" -ne "" -and
        [int]$_.contingency_ccrs_invocation_count -gt 0
    } | Sort-Object @{ Expression = { [int]$_.sequence } }, @{ Expression = { [int]$_.line } })

    $rows = @()
    for ($i = 0; $i -lt $contingencyCycles.Count; $i++) {
        $rows += [pscustomobject][ordered]@{
            invocation = $i + 1
            average_ms = Get-Average @($contingencyCycles[$i].duration_ms)
        }
    }

    return $rows
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

function Test-CellEndsWith {
    param(
        $Cell,
        [string]$Suffix
    )

    if ($null -eq $Cell -or "$Cell" -eq "" -or -not $Suffix) {
        return $false
    }
    return "$Cell".EndsWith("/$Suffix") -or "$Cell".EndsWith($Suffix)
}

function Get-ZoneDefinitions {
    return @(
        [pscustomobject][ordered]@{ order = 1; name = "signifier"; title = "Signifier Zone"; completion_cell = "cells/13/5"; start_cell = "" },
        [pscustomobject][ordered]@{ order = 2; name = "stigmergy"; title = "Stigmergy Zone"; completion_cell = "cells/28/14"; start_cell = "cells/13/5" },
        [pscustomobject][ordered]@{ order = 3; name = "mixed"; title = "Mixed Zone"; completion_cell = "cells/36/37"; start_cell = "cells/28/14" },
        [pscustomobject][ordered]@{ order = 4; name = "construction-site"; title = "Construction Site Zone"; completion_cell = "cells/39/43"; start_cell = "cells/36/37" },
        [pscustomobject][ordered]@{ order = 5; name = "social"; title = "Social Zone"; completion_cell = "cells/999"; start_cell = "cells/39/43" }
    )
}

function Get-ZoneCycleRows {
    param(
        [object[]]$SortedRows,
        [int]$StartIndex,
        [int]$EndIndex,
        [bool]$IncludeStartRowAsMove
    )

    if ($StartIndex -lt 0 -or $SortedRows.Count -eq 0) {
        return @()
    }

    $safeEnd = if ($EndIndex -ge $StartIndex) { $EndIndex } else { $SortedRows.Count - 1 }
    if ($safeEnd -lt $StartIndex) {
        return @()
    }

    $metricStart = if ($IncludeStartRowAsMove) { $StartIndex } else { $StartIndex + 1 }
    if ($metricStart -gt $safeEnd) {
        return @()
    }

    return @($SortedRows[$metricStart..$safeEnd])
}

function Get-ZoneDecisionRows {
    param(
        [object[]]$DecisionRows,
        [string]$RunId,
        [int]$StartLine,
        [int]$EndLine,
        [bool]$IncludeStartLine
    )

    if (-not $RunId -or $StartLine -lt 0 -or $EndLine -lt $StartLine) {
        return @()
    }

    return @($DecisionRows | Where-Object {
        $_.run_id -eq $RunId -and
        $_.line -ne $null -and "$($_.line)" -ne "" -and
        (
            ($IncludeStartLine -and [int]$_.line -ge $StartLine) -or
            ((-not $IncludeStartLine) -and [int]$_.line -gt $StartLine)
        ) -and
        [int]$_.line -le $EndLine
    })
}

function Get-DecisionMetrics {
    param([object[]]$DecisionRows)

    $multiRows = @($DecisionRows | Where-Object { $_.options_count -ne $null -and "$($_.options_count)" -ne "" -and [int]$_.options_count -ge 2 })
    $detectedRows = @($multiRows | Where-Object { "$($_.selected_has_ccrs)".ToLowerInvariant() -eq "true" })
    $overruledRows = @($multiRows | Where-Object { "$($_.selected_reordered)".ToLowerInvariant() -eq "true" })
    $zeroOneRows = @($DecisionRows | Where-Object { $_.options_count -ne $null -and "$($_.options_count)" -ne "" -and [int]$_.options_count -le 1 })

    return [pscustomobject][ordered]@{
        multi_option_decisions = $multiRows.Count
        multi_option_with_ccrs = $detectedRows.Count
        multi_option_overruled = $overruledRows.Count
        zero_or_one_option_decisions = $zeroOneRows.Count
    }
}

function Get-ZoneOverruledDecisionTypeCounts {
    param([object[]]$DecisionRows)

    $rows = @($DecisionRows | Where-Object {
        "$($_.selected_reordered)".ToLowerInvariant() -eq "true" -and
        $_.selected_type -and "$($_.selected_type)" -ne "null"
    })

    $result = @()
    foreach ($group in ($rows | Group-Object selected_type | Sort-Object Name)) {
        $result += [pscustomobject][ordered]@{
            type = $group.Name
            overruled_decisions = $group.Count
        }
    }
    return $result
}

function Get-CycleBucketAverages {
    param([object[]]$CycleRows)

    $nonContingency = @($CycleRows | Where-Object {
        $_.duration_ms -ne $null -and "$($_.duration_ms)" -ne "" -and
        ([int]$_.contingency_ccrs_invocation_count) -eq 0
    })

    return [pscustomobject][ordered]@{
        opp0 = Get-Average @($nonContingency | Where-Object { [int]$_.opp_ccrs_detected_count -eq 0 } | ForEach-Object { $_.duration_ms })
        opp1 = Get-Average @($nonContingency | Where-Object { [int]$_.opp_ccrs_detected_count -eq 1 } | ForEach-Object { $_.duration_ms })
        opp2 = Get-Average @($nonContingency | Where-Object { [int]$_.opp_ccrs_detected_count -eq 2 } | ForEach-Object { $_.duration_ms })
        opp3plus = Get-Average @($nonContingency | Where-Object { [int]$_.opp_ccrs_detected_count -ge 3 } | ForEach-Object { $_.duration_ms })
    }
}

function ConvertTo-ZoneChartRows {
    param([object[]]$Rows)

    $result = @()
    foreach ($group in ($Rows | Group-Object run_id | Sort-Object Name)) {
        $index = 0
        foreach ($row in @($group.Group | Sort-Object @{ Expression = { [int]$_.zone_step } })) {
            if ($row.duration_ms -eq $null -or "$($row.duration_ms)" -eq "") {
                continue
            }
            $index++
            $result += [pscustomobject][ordered]@{
                batch_id = $row.batch_id
                run_id = $row.run_id
                jcm = $row.jcm
                ccrs_mode = $row.ccrs_mode
                agent_label = $row.agent_label
                step = $index
                file = $row.file
                line = $row.line
                sequence = $row.sequence
                timestamp_ms = $row.timestamp_ms
                duration_ms = $row.duration_ms
                previous_cell = $row.previous_cell
                cell = $row.cell
                opp_ccrs_detected_count = $row.opp_ccrs_detected_count
                contingency_ccrs_invocation_count = $row.contingency_ccrs_invocation_count
            }
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

function ConvertTo-SvgCoordinate {
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

    return [pscustomobject][ordered]@{
        x = $px
        y = $py
    }
}

function ConvertTo-CycleDurationScale {
    param(
        [double]$DurationMs,
        [double]$LinearThresholdMs,
        [double]$LogBase,
        [double]$MaxDurationMs,
        [double]$LinearScaleFraction
    )

    $linearFraction = [math]::Min([math]::Max($LinearScaleFraction, 0.1), 0.95)
    if ($DurationMs -le $LinearThresholdMs) {
        return ($DurationMs / $LinearThresholdMs) * $linearFraction
    }

    $maxLogRatio = [math]::Max(1.0, [math]::Log([math]::Max($MaxDurationMs, $LinearThresholdMs + 1) / $LinearThresholdMs) / [math]::Log($LogBase))
    $durationLogRatio = [math]::Log($DurationMs / $LinearThresholdMs) / [math]::Log($LogBase)
    $logFraction = [math]::Min(1.0, $durationLogRatio / $maxLogRatio)
    return $linearFraction + ($logFraction * (1.0 - $linearFraction))
}

function Get-CycleDurationAxisTicks {
    param(
        [double]$MaxDurationMs,
        [double]$LinearThresholdMs
    )

    $ticks = New-Object System.Collections.Generic.List[double]
    foreach ($tick in @(0, 50, 100, 150, 200)) {
        if ($tick -le $MaxDurationMs -and -not $ticks.Contains([double]$tick)) {
            $ticks.Add([double]$tick)
        }
    }

    if ($MaxDurationMs -gt $LinearThresholdMs) {
        foreach ($tick in @(2000)) {
            if ($tick -lt $MaxDurationMs -and -not $ticks.Contains([double]$tick)) {
                $ticks.Add([double]$tick)
            }
        }
        if (-not $ticks.Contains([double]$MaxDurationMs)) {
            $ticks.Add([double]$MaxDurationMs)
        }
    }

    return @($ticks | Sort-Object -Unique)
}

function Get-StepAxisTicks {
    param(
        [double]$MaxStep,
        [int]$Interval = 25
    )

    $ticks = New-Object System.Collections.Generic.List[double]
    $tick = 0
    while ($tick -le $MaxStep) {
        $ticks.Add([double]$tick)
        $tick += $Interval
    }

    if (-not $ticks.Contains([double]$MaxStep)) {
        $ticks.Add([double]$MaxStep)
    }

    return @($ticks | Sort-Object -Unique)
}

function Format-CellLabel {
    param($Cell)

    if ($null -eq $Cell -or "$Cell" -eq "") {
        return "unknown cell"
    }

    $text = "$Cell"
    if ($text -match "/cells/(.+)$") {
        return "cells/$($matches[1])"
    }

    return $text
}

function Add-EndpointLabel {
    param(
        [System.Collections.Generic.List[string]]$Svg,
        [double]$PointX,
        [double]$PointY,
        [string]$Color,
        [string]$Label,
        [int]$SeriesIndex,
        [double]$PlotX,
        [double]$PlotY,
        [double]$PlotWidth,
        [double]$PlotHeight
    )

    $labelText = [System.Security.SecurityElement]::Escape($Label)
    $labelWidth = [math]::Max(96, [math]::Min(190, ($Label.Length * 6.2) + 12))
    $isRightEdge = $PointX -gt ($PlotX + $PlotWidth - 150)
    $anchor = if ($isRightEdge) { "end" } else { "start" }
    $labelXValue = if ($isRightEdge) {
        [math]::Max($PlotX + $labelWidth + 8, $PointX - 14)
    } else {
        [math]::Min($PlotX + $PlotWidth - $labelWidth - 8, $PointX + 14)
    }
    $offsetY = if (($SeriesIndex % 2) -eq 0) { -45 } else { 48 }
    $labelYValue = [math]::Min([math]::Max($PlotY + 12, $PointY + $offsetY), $PlotY + $PlotHeight - 12)
    $pointXText = $PointX.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $pointYText = $PointY.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $rectX = ($PointX - 3).ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $rectY = ($PointY - 3).ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $labelX = $labelXValue.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $labelY = $labelYValue.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)

    $Svg.Add("<rect x=""$rectX"" y=""$rectY"" width=""6"" height=""6"" fill=""#ffffff"" stroke=""$Color"" stroke-width=""2""/>")
    $Svg.Add("<line x1=""$pointXText"" y1=""$pointYText"" x2=""$labelX"" y2=""$labelY"" stroke=""$Color"" stroke-width=""1"" opacity=""0.65""/>")
    $Svg.Add("<text x=""$labelX"" y=""$labelY"" text-anchor=""$anchor"" dominant-baseline=""middle"" font-family=""Arial, sans-serif"" font-size=""11"" fill=""#222"">$labelText</text>")
}

function Add-OutlierLabel {
    param(
        [System.Collections.Generic.List[string]]$Svg,
        [double]$PointX,
        [double]$PointY,
        [string]$Color,
        [string]$Label,
        [int]$CalloutIndex,
        [double]$PlotX,
        [double]$PlotY,
        [double]$PlotWidth
    )

    $markerX = $PointX.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $markerY = $PointY.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $labelOffset = if ($CalloutIndex % 2 -eq 0) { -18 } else { -34 }
    $labelXValue = [math]::Min([math]::Max($PointX + 8, $PlotX + 24), $PlotX + $PlotWidth - 34)
    $labelYValue = [math]::Max($PlotY + 12, $PointY + $labelOffset)
    $labelX = $labelXValue.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $labelY = $labelYValue.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    $labelText = [System.Security.SecurityElement]::Escape($Label)

    $Svg.Add("<circle cx=""$markerX"" cy=""$markerY"" r=""4"" fill=""#ffffff"" stroke=""$Color"" stroke-width=""2""/>")
    $Svg.Add("<line x1=""$markerX"" y1=""$markerY"" x2=""$labelX"" y2=""$labelY"" stroke=""$Color"" stroke-width=""1"" opacity=""0.75""/>")
    $Svg.Add("<text x=""$labelX"" y=""$labelY"" font-family=""Arial, sans-serif"" font-size=""11"" fill=""#222"">$labelText</text>")
}

function New-CycleDurationChart {
    param(
        [object[]]$Rows,
        [string]$OutputPath,
        [double]$LogScaleThresholdMs = 200,
        [double]$LogScaleBase = 100,
        [double]$LinearScaleFraction = 0.82
    )

    $plotRows = @($Rows | Where-Object { $_.duration_ms -ne $null -and "$($_.duration_ms)" -ne "" })
    if ($plotRows.Count -eq 0) {
        return $null
    }

    $width = 1040
    $height = 520
    $plotX = 72
    $plotY = 128
    $plotWidth = 880
    $plotHeight = 300
    $colors = @("#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e")
    $steps = @($plotRows | ForEach-Object { Convert-ToDouble $_.step })
    $durations = @($plotRows | ForEach-Object { Convert-ToDouble $_.duration_ms })
    $minX = 0
    $maxX = ($steps | Measure-Object -Maximum).Maximum
    $minY = 0
    $maxObservedY = [math]::Ceiling(($durations | Measure-Object -Maximum).Maximum)
    $maxY = [math]::Max($LogScaleThresholdMs, $maxObservedY)
    $maxScaledY = 1.0
    $xTicks = Get-StepAxisTicks -MaxStep $maxX -Interval 25
    $yTicks = Get-CycleDurationAxisTicks -MaxDurationMs $maxY -LinearThresholdMs $LogScaleThresholdMs
    $linearScalePercent = [math]::Round($LinearScaleFraction * 100)

    $svg = New-Object System.Collections.Generic.List[string]
    $svg.Add("<svg xmlns=""http://www.w3.org/2000/svg"" width=""$width"" height=""$height"" viewBox=""0 0 $width $height"" role=""img"" aria-labelledby=""title desc"">")
    $svg.Add("<title id=""title"">Cycle duration comparison</title>")
    $svg.Add("<desc id=""desc"">Line chart comparing cycle duration by movement step for experiment runs. The y-axis reserves $linearScalePercent percent of plot height for 0 through $LogScaleThresholdMs ms, then compresses higher durations into a log-base-$LogScaleBase tail. High-duration outliers are labeled with their actual duration.</desc>")
    $svg.Add("<rect width=""100%"" height=""100%"" fill=""#ffffff""/>")
    $svg.Add("<text x=""$plotX"" y=""28"" font-family=""Arial, sans-serif"" font-size=""14"" font-weight=""700"">Cycle duration comparison</text>")
    $svg.Add("<text x=""$plotX"" y=""50"" font-family=""Arial, sans-serif"" font-size=""12"" fill=""#555"">Y-axis compresses values above $LogScaleThresholdMs ms. Max observed: $maxObservedY ms.</text>")
    $svg.Add("<line x1=""$plotX"" y1=""$($plotY + $plotHeight)"" x2=""$($plotX + $plotWidth)"" y2=""$($plotY + $plotHeight)"" stroke=""#333"" stroke-width=""1""/>")
    $svg.Add("<line x1=""$plotX"" y1=""$plotY"" x2=""$plotX"" y2=""$($plotY + $plotHeight)"" stroke=""#333"" stroke-width=""1""/>")
    $svg.Add("<text x=""$($plotX + ($plotWidth / 2))"" y=""$($height - 24)"" text-anchor=""middle"" font-family=""Arial, sans-serif"" font-size=""13"">Step number</text>")
    $svg.Add("<text x=""18"" y=""$($plotY + ($plotHeight / 2))"" text-anchor=""middle"" transform=""rotate(-90 18 $($plotY + ($plotHeight / 2)))"" font-family=""Arial, sans-serif"" font-size=""13"">Duration ms; log above $LogScaleThresholdMs</text>")
    foreach ($tickValue in $xTicks) {
        $point = ConvertTo-SvgCoordinate `
            -X $tickValue `
            -Y 0 `
            -MinX $minX `
            -MaxX $maxX `
            -MinY $minY `
            -MaxY $maxScaledY `
            -PlotX $plotX `
            -PlotY $plotY `
            -PlotWidth $plotWidth `
            -PlotHeight $plotHeight
        $tickX = $point.x.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
        $tickLabel = $tickValue.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
        $svg.Add("<line x1=""$tickX"" y1=""$($plotY + $plotHeight)"" x2=""$tickX"" y2=""$($plotY + $plotHeight + 5)"" stroke=""#333"" stroke-width=""1""/>")
        $svg.Add("<text x=""$tickX"" y=""$($plotY + $plotHeight + 18)"" text-anchor=""middle"" font-family=""Arial, sans-serif"" font-size=""11"">$tickLabel</text>")
    }
    foreach ($tickValue in $yTicks) {
        $scaledTick = ConvertTo-CycleDurationScale `
            -DurationMs $tickValue `
            -LinearThresholdMs $LogScaleThresholdMs `
            -LogBase $LogScaleBase `
            -MaxDurationMs $maxY `
            -LinearScaleFraction $LinearScaleFraction
        $point = ConvertTo-SvgCoordinate `
            -X 0 `
            -Y $scaledTick `
            -MinX $minX `
            -MaxX $maxX `
            -MinY $minY `
            -MaxY $maxScaledY `
            -PlotX $plotX `
            -PlotY $plotY `
            -PlotWidth $plotWidth `
            -PlotHeight $plotHeight
        $tickY = $point.y.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
        $tickLabel = $tickValue.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
        if ($tickValue -gt 0) {
            $svg.Add("<line x1=""$plotX"" y1=""$tickY"" x2=""$($plotX + $plotWidth)"" y2=""$tickY"" stroke=""#e1e1e1"" stroke-width=""1""/>")
        }
        $svg.Add("<text x=""$($plotX - 8)"" y=""$tickY"" text-anchor=""end"" dominant-baseline=""middle"" font-family=""Arial, sans-serif"" font-size=""11"">$tickLabel</text>")
    }

    $groups = @($plotRows | Group-Object run_id | Sort-Object Name)
    $colorIndex = 0
    foreach ($group in $groups) {
        $color = $colors[$colorIndex % $colors.Count]
        $colorIndex++
        $points = @()
        $callouts = @()
        $sortedGroupRows = @($group.Group | Sort-Object @{ Expression = { Convert-ToDouble $_.step } })
        foreach ($row in $sortedGroupRows) {
            $duration = Convert-ToDouble $row.duration_ms
            $plottedDuration = ConvertTo-CycleDurationScale `
                -DurationMs $duration `
                -LinearThresholdMs $LogScaleThresholdMs `
                -LogBase $LogScaleBase `
                -MaxDurationMs $maxY `
                -LinearScaleFraction $LinearScaleFraction
            $points += ConvertTo-SvgPoint `
                -X (Convert-ToDouble $row.step) `
                -Y $plottedDuration `
                -MinX $minX `
                -MaxX $maxX `
                -MinY $minY `
                -MaxY $maxScaledY `
                -PlotX $plotX `
                -PlotY $plotY `
                -PlotWidth $plotWidth `
                -PlotHeight $plotHeight
            if ($duration -gt $LogScaleThresholdMs) {
                $point = ConvertTo-SvgCoordinate `
                    -X (Convert-ToDouble $row.step) `
                    -Y $plottedDuration `
                    -MinX $minX `
                    -MaxX $maxX `
                    -MinY $minY `
                    -MaxY $maxScaledY `
                    -PlotX $plotX `
                    -PlotY $plotY `
                    -PlotWidth $plotWidth `
                    -PlotHeight $plotHeight
                $callouts += [pscustomobject][ordered]@{
                    step = Convert-ToDouble $row.step
                    duration = $duration
                    x = $point.x
                    y = $point.y
                }
            }
        }
        if ($points.Count -gt 0) {
            $label = [System.Security.SecurityElement]::Escape($group.Name)
            $svg.Add("<polyline fill=""none"" stroke=""$color"" stroke-width=""2"" points=""$($points -join ' ')""/>")
            $legendY = 34 + (($colorIndex - 1) * 20)
            $svg.Add("<line x1=""$($plotX + 610)"" y1=""$legendY"" x2=""$($plotX + 640)"" y2=""$legendY"" stroke=""$color"" stroke-width=""2""/>")
            $svg.Add("<text x=""$($plotX + 648)"" y=""$legendY"" dominant-baseline=""middle"" font-family=""Arial, sans-serif"" font-size=""12"">$label</text>")
            $calloutIndex = 0
            $lastRow = $sortedGroupRows[-1]
            if ($lastRow) {
                $lastStep = Convert-ToDouble $lastRow.step
                $lastDuration = Convert-ToDouble $lastRow.duration_ms
                $lastPlottedDuration = ConvertTo-CycleDurationScale `
                    -DurationMs $lastDuration `
                    -LinearThresholdMs $LogScaleThresholdMs `
                    -LogBase $LogScaleBase `
                    -MaxDurationMs $maxY `
                    -LinearScaleFraction $LinearScaleFraction
                $lastPoint = ConvertTo-SvgCoordinate `
                    -X $lastStep `
                    -Y $lastPlottedDuration `
                    -MinX $minX `
                    -MaxX $maxX `
                    -MinY $minY `
                    -MaxY $maxScaledY `
                    -PlotX $plotX `
                    -PlotY $plotY `
                    -PlotWidth $plotWidth `
                    -PlotHeight $plotHeight
                $lastStepLabel = $lastStep.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
                Add-EndpointLabel `
                    -Svg $svg `
                    -PointX $lastPoint.x `
                    -PointY $lastPoint.y `
                    -Color $color `
                    -Label ((Format-CellLabel $lastRow.cell) + " at step " + $lastStepLabel) `
                    -SeriesIndex ($colorIndex - 1) `
                    -PlotX $plotX `
                    -PlotY $plotY `
                    -PlotWidth $plotWidth `
                    -PlotHeight $plotHeight
            }
            foreach ($callout in $callouts) {
                $calloutIndex++
                $durationLabel = $callout.duration.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture) + " ms"
                Add-OutlierLabel `
                    -Svg $svg `
                    -PointX $callout.x `
                    -PointY $callout.y `
                    -Color $color `
                    -Label $durationLabel `
                    -CalloutIndex $calloutIndex `
                    -PlotX $plotX `
                    -PlotY $plotY `
                    -PlotWidth $plotWidth
            }
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

function Get-ScenarioReportMetadata {
    param([string]$BatchName)

    if ($BatchName -match "(?i)(^|[-_])v1($|[-_])") {
        return [pscustomobject][ordered]@{
            name = "CcrsMazeV1"
            description = "Scenario CcrsMazeV1 contains 3 locked cells. The baseline agent cannot complete the maze because it has no recovery mechanism for lock interactions. This scenario tests whether CCRS enables completion through contingency recovery, and separates normal opportunistic guidance from expensive contingency invocations."
            optimal_moves = 138
            zone_optimal_moves = @{
                signifier = 19
                stigmergy = 24
                mixed = 57
                "construction-site" = 19
                social = 19
            }
        }
    }

    if ($BatchName -match "(?i)(^|[-_])v2($|[-_])") {
        return [pscustomobject][ordered]@{
            name = "CcrsMazeV2"
            description = "Scenario CcrsMazeV2 contains no locked cells. It is the baseline traversal scenario: both agents can reach the exit without contingency recovery, so the comparison focuses on path efficiency, opportunistic CCRS influence, movement count, and normal cycle-time overhead."
            optimal_moves = 116
            zone_optimal_moves = @{
                signifier = 17
                stigmergy = 24
                mixed = 37
                "construction-site" = 19
                social = 19
            }
        }
    }

    return [pscustomobject][ordered]@{
        name = "unknown"
        description = "Scenario metadata is not configured for this batch."
        optimal_moves = $null
        zone_optimal_moves = @{}
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
$maseAgentMovedCsv = Join-Path $OutputDir "mase-agent-moved.csv"
$maseAgentMoved = @(if (Test-Path -LiteralPath $maseAgentMovedCsv) { Import-Csv -Path $maseAgentMovedCsv })
$agentsCsv = Join-Path $OutputDir "agents.csv"
$agents = @(if (Test-Path -LiteralPath $agentsCsv) { Import-Csv -Path $agentsCsv })
$pathAnalysisCsv = Join-Path $OutputDir "path-analysis-inputs.csv"
$pathAnalysisInputs = @(if (Test-Path -LiteralPath $pathAnalysisCsv) { Import-Csv -Path $pathAnalysisCsv })
$cycleDurations = @(if (Test-Path -LiteralPath $cycleDurationsCsv) { Import-Csv -Path $cycleDurationsCsv })

$batchName = if ($BatchId) { $BatchId } else { Split-Path -Leaf $RunRoot }
$scenarioMetadata = Get-ScenarioReportMetadata -BatchName $batchName
$summaryPath = Join-Path $OutputDir "summary.md"
$cycleChartPath = Join-Path $OutputDir "cycle-duration-comparison.svg"
$cycleChartLogScaleThresholdMs = 200
$cycleChartLogScaleBase = 100
$cycleChartLinearScaleFraction = 0.82
$cycleChartFile = $null
if ($cycleDurations.Count -gt 0) {
    $generatedChart = New-CycleDurationChart `
        -Rows $cycleDurations `
        -OutputPath $cycleChartPath `
        -LogScaleThresholdMs $cycleChartLogScaleThresholdMs `
        -LogScaleBase $cycleChartLogScaleBase `
        -LinearScaleFraction $cycleChartLinearScaleFraction
    if ($generatedChart) {
        $cycleChartFile = Split-Path -Leaf $generatedChart
    }
}

$zoneDefinitions = @(Get-ZoneDefinitions)
$zoneSummaryPath = Join-Path $OutputDir "zone-summary.csv"
$zoneSummaryRows = @()
$zoneCycleRowsByZone = @{}
$zoneDecisionRowsByZoneRun = @{}
$zoneChartFiles = @{}
foreach ($zone in $zoneDefinitions) {
    $zoneCycleRowsByZone[$zone.name] = @()
}

foreach ($run in ($runs | Sort-Object run_id)) {
    $runCycleRows = @($cycleDurations | Where-Object { $_.run_id -eq $run.run_id } | Sort-Object @{ Expression = { [int]$_.step } }, @{ Expression = { [int]$_.line } })
    $previousBoundaryIndex = -1
    $previousBoundaryReached = $true

    foreach ($zone in $zoneDefinitions) {
        $includeStartRowAsMove = $zone.order -eq 1
        $startIndex = if ($zone.order -eq 1) {
            if ($runCycleRows.Count -gt 0) { 0 } else { -1 }
        } elseif ($previousBoundaryReached) {
            $previousBoundaryIndex
        } else {
            -1
        }

        $completed = $false
        $endIndex = -1
        if ($startIndex -ge 0) {
            for ($i = $startIndex; $i -lt $runCycleRows.Count; $i++) {
                if (Test-CellEndsWith -Cell $runCycleRows[$i].cell -Suffix $zone.completion_cell) {
                    $completed = $true
                    $endIndex = $i
                    break
                }
            }
            if (-not $completed -and $runCycleRows.Count -gt 0) {
                $endIndex = $runCycleRows.Count - 1
            }
        }

        $zoneCycleRows = @(Get-ZoneCycleRows `
            -SortedRows $runCycleRows `
            -StartIndex $startIndex `
            -EndIndex $endIndex `
            -IncludeStartRowAsMove $includeStartRowAsMove)

        $zoneChartStep = 0
        foreach ($row in $zoneCycleRows) {
            $zoneChartStep++
            $zoneCycleRowsByZone[$zone.name] += [pscustomobject][ordered]@{
                zone = $zone.name
                zone_title = $zone.title
                zone_step = $zoneChartStep
                batch_id = $row.batch_id
                run_id = $row.run_id
                jcm = $row.jcm
                ccrs_mode = $row.ccrs_mode
                agent_label = $row.agent_label
                step = $row.step
                file = $row.file
                line = $row.line
                sequence = $row.sequence
                timestamp_ms = $row.timestamp_ms
                duration_ms = $row.duration_ms
                previous_cell = $row.previous_cell
                cell = $row.cell
                opp_ccrs_detected_count = $row.opp_ccrs_detected_count
                contingency_ccrs_invocation_count = $row.contingency_ccrs_invocation_count
            }
        }

        $startLine = if ($startIndex -ge 0) { [int]$runCycleRows[$startIndex].line } else { -1 }
        $endLine = if ($endIndex -ge $startIndex -and $endIndex -ge 0) { [int]$runCycleRows[$endIndex].line } else { -1 }
        $zoneDecisionRows = @(Get-ZoneDecisionRows `
            -DecisionRows $decisions `
            -RunId $run.run_id `
            -StartLine $startLine `
            -EndLine $endLine `
            -IncludeStartLine $includeStartRowAsMove)
        $zoneDecisionRowsByZoneRun["$($zone.name)|$($run.run_id)"] = $zoneDecisionRows
        $decisionMetrics = Get-DecisionMetrics -DecisionRows $zoneDecisionRows
        $durationValues = @($zoneCycleRows | Where-Object { $_.duration_ms -ne $null -and "$($_.duration_ms)" -ne "" } | ForEach-Object { Convert-ToDouble $_.duration_ms })
        $totalDuration = if ($durationValues.Count -gt 0) { [math]::Round(($durationValues | Measure-Object -Sum).Sum, 2) } else { $null }
        $avgDuration = Get-Average @($zoneCycleRows | ForEach-Object { $_.duration_ms })
        $finalRow = if ($endIndex -ge $startIndex -and $endIndex -ge 0) { $runCycleRows[$endIndex] } elseif ($zoneCycleRows.Count -gt 0) { $zoneCycleRows[-1] } else { $null }
        $startRow = if ($startIndex -ge 0) { $runCycleRows[$startIndex] } else { $null }

        $zoneOptimalMoves = if ($scenarioMetadata.zone_optimal_moves.ContainsKey($zone.name)) {
            $scenarioMetadata.zone_optimal_moves[$zone.name]
        } else {
            "tbd"
        }
        $zoneMoveDelta = if ($zoneOptimalMoves -ne "tbd") {
            $zoneCycleRows.Count - [int]$zoneOptimalMoves
        } else {
            "tbd"
        }

        $zoneSummaryRows += [pscustomobject][ordered]@{
            batch_id = $batchName
            zone = $zone.name
            zone_title = $zone.title
            zone_order = $zone.order
            run_id = $run.run_id
            jcm = $run.jcm
            ccrs_mode = $run.ccrs_mode
            completed = if ($completed) { "yes" } else { "no" }
            completion_cell = $zone.completion_cell
            start_cell = if ($startRow) { Format-CellLabel $startRow.cell } else { "" }
            final_cell = if ($finalRow) { Format-CellLabel $finalRow.cell } else { "" }
            start_step = if ($startRow) { $startRow.step } else { "" }
            final_step = if ($finalRow) { $finalRow.step } else { "" }
            total_duration_ms = $totalDuration
            total_moves = $zoneCycleRows.Count
            average_cycle_duration_ms = $avgDuration
            optimal_moves = $zoneOptimalMoves
            actual_moves = $zoneCycleRows.Count
            move_delta_from_optimal = $zoneMoveDelta
            multi_option_decisions = $decisionMetrics.multi_option_decisions
            multi_option_with_ccrs = $decisionMetrics.multi_option_with_ccrs
            multi_option_overruled = $decisionMetrics.multi_option_overruled
            zero_or_one_option_decisions = $decisionMetrics.zero_or_one_option_decisions
        }

        if ($completed) {
            $previousBoundaryIndex = $endIndex
            $previousBoundaryReached = $true
        } else {
            $previousBoundaryReached = $false
        }
    }
}

if ($zoneSummaryRows.Count -gt 0) {
    $zoneSummaryRows | Export-Csv -Path $zoneSummaryPath -NoTypeInformation -Encoding UTF8
}

foreach ($zone in $zoneDefinitions) {
    $chartRows = @(ConvertTo-ZoneChartRows -Rows $zoneCycleRowsByZone[$zone.name])
    if ($chartRows.Count -gt 0) {
        $chartPath = Join-Path $OutputDir "zone-cycle-duration-$($zone.name).svg"
        $generatedZoneChart = New-CycleDurationChart `
            -Rows $chartRows `
            -OutputPath $chartPath `
            -LogScaleThresholdMs $cycleChartLogScaleThresholdMs `
            -LogScaleBase $cycleChartLogScaleBase `
            -LinearScaleFraction $cycleChartLinearScaleFraction
        if ($generatedZoneChart) {
            $zoneChartFiles[$zone.name] = Split-Path -Leaf $generatedZoneChart
        }
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
$lines.Add("Scenario: $($scenarioMetadata.name)")
$lines.Add("")
$lines.Add($scenarioMetadata.description)
$lines.Add("")
$scenarioOptimalMovesText = if ($null -ne $scenarioMetadata.optimal_moves -and "$($scenarioMetadata.optimal_moves)" -ne "") {
    "$($scenarioMetadata.optimal_moves)"
} else {
    "unknown"
}
$lines.Add("Optimal path length for $($scenarioMetadata.name): $scenarioOptimalMovesText moves.")
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
    $optimalMoves = if ($row.optimal_moves) { $row.optimal_moves } else { $scenarioMetadata.optimal_moves }
    $actualMoves = if ($row.actual_moves) { $row.actual_moves } else { $row.mase_agent_moved }
    $deltaMoves = if ($row.move_delta_from_optimal -ne $null -and "$($row.move_delta_from_optimal)" -ne "") {
        $row.move_delta_from_optimal
    } elseif ($actualMoves -ne $null -and "$actualMoves" -ne "" -and $optimalMoves -ne $null -and "$optimalMoves" -ne "") {
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
    $contingencyInvocationAverages = @(Get-ContingencyInvocationCycleAverages -CycleRows $cycleDurations -RunId $ccrsRun.run_id)
    $headers = @("Baseline avg ms", "CCRS avg ms", "CCRS opp 0 avg ms", "CCRS opp 1 avg ms", "CCRS opp 2 avg ms", "CCRS opp 3+ avg ms")
    $alignments = @("---:", "---:", "---:", "---:", "---:", "---:")
    $values = @(
        (Format-Ms $baselineRun.average_agent_cycle_duration_ms),
        (Format-Ms $ccrsRun.average_agent_cycle_duration_ms),
        (Format-Ms $ccrsRun.average_cycle_opp0_ms),
        (Format-Ms $ccrsRun.average_cycle_opp1_ms),
        (Format-Ms $ccrsRun.average_cycle_opp2_ms),
        (Format-Ms $ccrsRun.average_cycle_opp3plus_ms)
    )
    foreach ($row in $contingencyInvocationAverages) {
        $headers += "CCRS cont invocation $($row.invocation) avg ms"
        $alignments += "---:"
        $values += (Format-Ms $row.average_ms)
    }
    $lines.Add("| $($headers -join ' | ') |")
    $lines.Add("| $($alignments -join ' | ') |")
    $lines.Add("| $($values -join ' | ') |")
    $lines.Add("")
    if ($contingencyInvocationAverages.Count -gt 0) {
        $lines.Add("Opportunistic CCRS cycle averages exclude cycles where contingency CCRS was active. Contingency columns are dynamically generated ordered invocation cycles, not counts per cycle.")
    } else {
        $lines.Add("Opportunistic CCRS cycle averages exclude cycles where contingency CCRS was active.")
    }
}

if ($cycleChartFile) {
    $lines.Add("")
    $lines.Add("## Cycle Duration Chart")
    $lines.Add("")
    $lines.Add("![Cycle duration comparison]($cycleChartFile)")
    $lines.Add("")
    $lines.Add("Y-axis compresses values above $cycleChartLogScaleThresholdMs ms with log-base-$cycleChartLogScaleBase; high-duration outliers are labeled with their actual duration.")
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
        foreach ($group in ($strategyRows | Group-Object run_id, invocation | Sort-Object @{ Expression = { @($_.Group)[0].run_id } }, @{ Expression = { [int]@($_.Group)[0].invocation } })) {
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

if ($zoneSummaryRows.Count -gt 0) {
    $lines.Add("")
    $lines.Add("## Zone Metrics")
    $lines.Add("")
    $lines.Add("Zone metrics mirror the overall report metrics where applicable. Zone optimal move counts are placeholders until the expected values are specified.")

    foreach ($zone in $zoneDefinitions) {
        $zoneRows = @($zoneSummaryRows | Where-Object { $_.zone -eq $zone.name } | Sort-Object run_id)
        if ($zoneRows.Count -eq 0) {
            continue
        }

        $lines.Add("")
        $lines.Add("### $($zone.title)")
        $lines.Add("")
        $lines.Add("Completed when the agent enters ``$($zone.completion_cell)``.")
        $lines.Add("")
        $lines.Add("| Run | JCM | Completed | Total duration ms | Total moves | Avg cycle duration | Final cell |")
        $lines.Add("| --- | --- | --- | ---: | ---: | ---: | --- |")
        foreach ($row in $zoneRows) {
            $lines.Add("| ``$($row.run_id)`` | ``$($row.jcm)`` | $($row.completed) | $(Format-Ms $row.total_duration_ms) | $(Format-Number $row.total_moves) | $(Format-Ms $row.average_cycle_duration_ms) | ``$(Format-MarkdownCellText $row.final_cell)`` |")
        }

        $lines.Add("")
        $lines.Add("#### Move Optimality")
        $lines.Add("")
        $lines.Add("| Run | Optimal moves | Actual moves | Delta from optimal |")
        $lines.Add("| --- | ---: | ---: | ---: |")
        foreach ($row in $zoneRows) {
            $lines.Add("| ``$($row.run_id)`` | $($row.optimal_moves) | $(Format-Number $row.actual_moves) | $($row.move_delta_from_optimal) |")
        }

        if ($zoneChartFiles.ContainsKey($zone.name)) {
            $lines.Add("")
            $lines.Add("#### Cycle Duration Chart")
            $lines.Add("")
            $lines.Add("![Cycle duration comparison for $($zone.title)]($($zoneChartFiles[$zone.name]))")
        }

        $baselineZone = @($zoneRows | Where-Object { $_.run_id -eq $baselineRun.run_id } | Select-Object -First 1)[0]
        $ccrsZone = @($zoneRows | Where-Object { $_.run_id -eq $ccrsRun.run_id } | Select-Object -First 1)[0]
        $ccrsZoneCycleRows = @($zoneCycleRowsByZone[$zone.name] | Where-Object { $_.run_id -eq $ccrsRun.run_id })
        $ccrsZoneBuckets = Get-CycleBucketAverages -CycleRows $ccrsZoneCycleRows
        $zoneContingencyInvocationAverages = @(Get-ContingencyInvocationCycleAverages -CycleRows $ccrsZoneCycleRows -RunId $ccrsRun.run_id)

        $lines.Add("")
        $lines.Add("#### Cycle Duration Summary")
        $lines.Add("")
        $headers = @("Baseline avg ms", "CCRS avg ms", "CCRS opp 0 avg ms", "CCRS opp 1 avg ms", "CCRS opp 2 avg ms", "CCRS opp 3+ avg ms")
        $alignments = @("---:", "---:", "---:", "---:", "---:", "---:")
        $values = @(
            (Format-Ms $baselineZone.average_cycle_duration_ms),
            (Format-Ms $ccrsZone.average_cycle_duration_ms),
            (Format-Ms $ccrsZoneBuckets.opp0),
            (Format-Ms $ccrsZoneBuckets.opp1),
            (Format-Ms $ccrsZoneBuckets.opp2),
            (Format-Ms $ccrsZoneBuckets.opp3plus)
        )
        foreach ($row in $zoneContingencyInvocationAverages) {
            $headers += "CCRS cont invocation $($row.invocation) avg ms"
            $alignments += "---:"
            $values += (Format-Ms $row.average_ms)
        }
        $lines.Add("| $($headers -join ' | ') |")
        $lines.Add("| $($alignments -join ' | ') |")
        $lines.Add("| $($values -join ' | ') |")

        $lines.Add("")
        $lines.Add("#### Decision Breakdown")
        $lines.Add("")
        $lines.Add("| Run | JCM | Decisions with 2+ directions | Opp-CCRS detected | Opp-CCRS detected rate | Opp-CCRS overruled default | Overruled rate | Decisions with 0-1 directions |")
        $lines.Add("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        foreach ($row in ($zoneRows | Where-Object { (Convert-ToDouble $_.multi_option_decisions) -gt 0 -or $_.ccrs_mode -ne "none" } | Sort-Object run_id)) {
            $multiCount = Convert-ToDouble $row.multi_option_decisions
            $detected = Convert-ToDouble $row.multi_option_with_ccrs
            $overruled = Convert-ToDouble $row.multi_option_overruled
            $detectedRate = if ($multiCount -gt 0) { $detected / $multiCount } else { 0 }
            $overruledRate = if ($multiCount -gt 0) { $overruled / $multiCount } else { 0 }
            $lines.Add("| ``$($row.run_id)`` | ``$($row.jcm)`` | $($row.multi_option_decisions) | $($row.multi_option_with_ccrs) | $(Format-Rate $detectedRate) | $($row.multi_option_overruled) | $(Format-Rate $overruledRate) | $($row.zero_or_one_option_decisions) |")
        }

        $zoneCcrsDecisionRows = @($zoneDecisionRowsByZoneRun["$($zone.name)|$($ccrsRun.run_id)"])
        $zoneOverruledTypeRows = @(Get-ZoneOverruledDecisionTypeCounts -DecisionRows $zoneCcrsDecisionRows)
        $lines.Add("")
        $lines.Add("#### Opportunistic CCRS Overruled Decisions")
        $lines.Add("")
        if ($zoneOverruledTypeRows.Count -gt 0) {
            $lines.Add("| CCRS type | Overruled decisions |")
            $lines.Add("| --- | ---: |")
            foreach ($row in $zoneOverruledTypeRows) {
                $lines.Add("| $($row.type) | $($row.overruled_decisions) |")
            }
        } else {
            $lines.Add("No opportunistic CCRS overruled decisions in this zone.")
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
    $lines.Add("- ``$cycleChartFile``: SVG line chart comparing cycle duration by step.")
}
if ($zoneSummaryRows.Count -gt 0) {
    $lines.Add('- `zone-summary.csv`: one row per run-zone pair with zone completion, movement, cycle, and decision metrics.')
    foreach ($zone in $zoneDefinitions) {
        if ($zoneChartFiles.ContainsKey($zone.name)) {
            $lines.Add("- ``$($zoneChartFiles[$zone.name])``: SVG line chart comparing cycle duration inside the $($zone.title).")
        }
    }
}
$lines.Add('- `path-analysis-inputs/*.cells.txt`: copy-paste cell sequences for the MASE viewer Path Analysis overlay.')
$lines.Add('- `path-analysis-inputs.csv`: index of generated Path Analysis copy-paste files.')
$lines.Add('- `summary.json`: parser metadata.')

$lines | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host "Wrote $summaryPath"
