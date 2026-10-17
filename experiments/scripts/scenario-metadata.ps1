function Get-ScenarioReportMetadata {
    param([string]$ScenarioName, [string]$BatchName)

    foreach ($candidate in @($ScenarioName, $BatchName)) {
        if ($candidate -match '(?i)^CcrsMazeV1$|(^|[-_])v1($|[-_])') {
            return [pscustomobject][ordered]@{
                name = 'CcrsMazeV1'
                description = 'Scenario CcrsMazeV1 contains no locked cells. Both agents can reach the exit without contingency recovery; the comparison measures path efficiency and opportunistic CCRS overhead.'
                optimal_moves = 116
                zone_optimal_moves = @{ signifier = 17; stigmergy = 24; mixed = 37; 'construction-site' = 19; social = 19 }
            }
        }
        if ($candidate -match '(?i)^CcrsMazeV2$|(^|[-_])v2($|[-_])') {
            return [pscustomobject][ordered]@{
                name = 'CcrsMazeV2'
                description = 'Scenario CcrsMazeV2 contains 3 locked cells. The comparison tests contingency recovery and separates its cost from normal opportunistic guidance.'
                optimal_moves = 138
                zone_optimal_moves = @{ signifier = 19; stigmergy = 24; mixed = 57; 'construction-site' = 19; social = 19 }
            }
        }
    }
    return [pscustomobject][ordered]@{
        name = 'unknown'
        description = 'Scenario metadata is not configured for this batch.'
        optimal_moves = $null
        zone_optimal_moves = @{}
    }
}
