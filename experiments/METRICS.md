# Experiment Metrics Foundation

This document defines the current analysis vocabulary for manually executed MASE
experiments. It is the place to refine metric definitions one by one before the
report layout is treated as final.

The manual workflow is documented in [README.md](README.md).
The current scripts are [import-manual-run.ps1](scripts/import-manual-run.ps1),
[parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1), and
[write-report.ps1](scripts/write-report.ps1).

## Analysis Package Model

A run package is one archived directory under:

```text
experiments/runs/<batch-id>/<run-id>/
```

The package may contain:

- `run.json`: run identity and import metadata.
- `mas-*.log`: JaCaMo/Jason log files copied or moved from the agent run.
- `mase-events.jsonl`: normalized MASE viewer or WebSocket event records.
- `source-exports/`: original files consumed from `experiments/runs/latest`.
- generated CSVs such as `mase-events.csv`, `mase-agent-moved.csv`,
  `mase-transactions.csv`, `agents.csv`, and `path-analysis-inputs.csv`.

The report package is generated under:

```text
experiments/reports/<batch-id>/
```

The experiment workflow lives under `experiments/`: scripts in
`experiments/scripts/`, reports in `experiments/reports/`, run archives in
`experiments/runs/`, and the experiment README and metrics documentation beside
them.

## Source Boundaries

The analysis pipeline keeps these sources separate:

| Source | Current file shape | Primary use |
| --- | --- | --- |
| Agent log | `mas-*.log` | Agent-side decisions, free-text action markers, structured CCRS events. |
| CCRS event | `[CCRS-EVENT] event=...` lines inside `mas-*.log` | Stable CCRS diagnostics such as prioritization and contingency evaluation. |
| MASE event | NDJSON records exported from the viewer IndexedDB or captured from WebSocket | Environment-observed movement and transaction evidence. |
| Run metadata | `run.json` | Manual identity: batch, run id, `.jcm`, expected experiment agent, scenario, notes. |

The MASE server and RDF store remain the environment source of truth. Viewer
exports are evidence captured from the server event stream, not a separate
simulation state.

When a run declares experiment agents in `run.json`, MASE event parsing keeps
only records for those agents. Raw viewer exports are preserved in
`source-exports/`, but generated CSVs and reports exclude scenario-service
agents by default.

## Agent Separation

Every metric must state its agent scope.

| Agent role | Definition |
| --- | --- |
| `experiment_agent` | The agent currently being evaluated, declared by `-AgentName` or inferred from the selected `.jcm` file during import. |
| `scenario_service` | MASE support agents such as `ccrs-agent` and `keyholder-agent`. They may be necessary for the scenario but are filtered out of generated metrics. |
| `other_agent` | Any observed agent that is neither the experiment agent nor a known scenario service. These records are filtered out of generated metrics when `-AgentName` is set. |
| `unknown` | Records with no usable agent identifier. These should be inspected before drawing conclusions. |

[parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1) writes
`agents.csv` so movement and transaction totals can be inspected per observed
agent before run-level totals are interpreted.

## Current Tables

| Table | Granularity | Purpose |
| --- | --- | --- |
| `runs.csv` | one row per run | Overall run outcome and aggregate metrics. |
| `agents.csv` | one row per observed agent per run | Per-agent MASE movement and transaction totals. |
| `actions.csv` | one parsed action marker per row | Agent-side action attempts and request markers from JaCaMo logs. |
| `decisions.csv` | one prioritization decision per row | Opportunistic CCRS influence analysis. |
| `contingency.csv` | one contingency event per row | Contingency CCRS invocation, strategy, result, and timing evidence. |
| `mase-events.csv` | one MASE event per row | Flattened event index across movement and transactions. |
| `mase-agent-moved.csv` | one movement event per row | Route/path analysis input. |
| `mase-transactions.csv` | one transaction event per row | Server-side POST, status, timing, and trace-mode evidence. |
| `path-analysis-inputs.csv` | one path file per run-agent pair | Index of paste-ready route files for the MASE viewer. |
| `cycle-durations.csv` | one row per movement cycle | Agent-cycle timestamp pairs and derived cycle durations. |
| `zone-summary.csv` | one row per run-zone pair | Zone-level completion, duration, movement, cycle, decision, and opportunistic-overrule metrics derived from existing parsed tables. |
| `zone-cycle-duration-<zone>.svg` | one file per report zone | Static line chart comparing baseline and CCRS cycle duration inside one scenario zone. |
| `cycle-duration-comparison.svg` | one file per report package | Static line chart comparing run cycle duration by step. |

## Agent Cycle Timestamp Marker

Use a single structured `[METRIC]` line for each location update. This keeps the
parser independent from human-readable text such as `I'm now at: ...` and reuses
the same `key=value` extraction style as `[CCRS-EVENT]` records.

Recommended AgentSpeak pattern:

```asl
.date(Y,M,D) ;
.time(H,Min,Sec,MilSec) ;
NowMs = ((H * 3600 + Min * 60 + Sec) * 1000) + MilSec ;
if (not cycle_step(_)) {
    +cycle_step(0)
} ;
?cycle_step(PreviousStep) ;
Step = PreviousStep + 1 ;
-+cycle_step(Step) ;
.print("[METRIC] event=agent.cycle.location step=", Step,
       " y=", Y, " m=", M, " d=", D,
       " h=", H, " min=", Min, " sec=", Sec, " ms=", MilSec,
       " t_ms=", NowMs,
       " previous=", Previous,
       " cell=", Target) ;
```

The parser should treat `step` as the sequence number and `t_ms` as the
within-day timestamp. The date fields are included so a future parser can handle
midnight rollover without guessing. The cell URIs are safe as unquoted values
because they contain no whitespace.

The marker should be added to both [dfs_baseline.asl](../src/agt/dfs_baseline.asl)
and [dfs_ccrs.asl](../src/agt/dfs_ccrs.asl) directly after `at(Target)` is updated.
The existing `I'm now at: ...` line can remain for humans, but metric extraction
should use the structured `[METRIC]` line.

## Metric Definition Template

Use this template when adding or refining a metric:

```text
Name:
Scope: run | agent | decision | contingency | transaction | path
Source table:
Source event or log pattern:
Definition:
Unit:
Inclusions:
Exclusions:
Failure behavior:
Known caveats:
Report placement:
Status: draft | accepted | deprecated
```

## Active Metric Set

Only the metrics in this section are active in the current report. Other parsed
columns remain available in CSV files but should not be treated as selected
comparison metrics yet.

| Metric | Scope | Current definition | Status |
| --- | --- | --- | --- |
| `reached_exit` | run | `true` when the final filtered MASE movement cell ends with `/cells/999`. This is the primary success criterion. | accepted |
| `agent_elapsed_total_ms` | run | Agent-reported elapsed time parsed from `It took ... seconds and ... milliseconds` in JaCaMo logs, converted to milliseconds. | accepted |
| `mase_agent_moved` | run | Count of filtered MASE `AGENT_MOVED` records for the experiment agent. This is the current total movement count. | accepted |
| `optimal_moves` | run | Scenario-specific optimal path length: `138` moves for `CcrsMazeV1`, `116` moves for `CcrsMazeV2`. | accepted |
| `move_delta_from_optimal` | run | `actual_moves - optimal_moves`, where `actual_moves` is the filtered MASE movement count. | accepted |
| `average_agent_cycle_duration_ms` | run | Mean of `duration_ms` from `cycle-durations.csv`, derived from adjacent `agent.cycle.location` markers. Existing runs without markers report an empty value. | accepted |
| `multi_option_decisions` | run | Number of parsed `ccrs.opportunistic.prioritize` decisions where `options_count >= 2`. | accepted |
| `multi_option_with_ccrs_rate` | run | `multi_option_with_ccrs / multi_option_decisions`, where `multi_option_with_ccrs` means the selected option had opportunistic CCRS guidance. | accepted |
| `multi_option_overruled_rate` | run | `multi_option_overruled / multi_option_decisions`, where `multi_option_overruled` means opportunistic CCRS selected an option whose original index was greater than `0`. | accepted |
| `zero_or_one_option_decisions` | run | Number of parsed prioritization decisions where `options_count <= 1`. These are reported separately because there is no meaningful next-step choice to overrule. | accepted |
| `contingency.strategy.evaluated` details | contingency invocation | One table per invocation, showing every evaluated strategy ordered by confidence with result type, action, target, timing, opportunistic-guidance flag, no-help reason, and rationale. | accepted |
| `average_cycle_duration_by_opp_count` | run, agent, cycle group | Mean cycle duration grouped by the number of `ccrs.opportunistic.detected` events observed inside the cycle window. Report columns are `0`, `1`, `2`, and `3+`. Cycles with active contingency CCRS are excluded from these opportunistic-only averages. | accepted |
| `average_cycle_duration_by_contingency_invocation_order` | run, agent, invocation cycle | Mean cycle duration for each contingency CCRS invocation cycle, ordered by the `ccrs.contingency.evaluate.request` events observed in cycle windows. The report dynamically adds one column per observed invocation. | accepted |
| `cycle_duration_line_chart` | batch | SVG comparison line chart with `x = step` and `y = duration_ms`, plotting available runs on the same axes. The x-axis shows step markers every 25 steps and keeps the observed maximum step marker. The y-axis reserves 82% of plot height for the linear 0-200 ms range, then compresses higher durations into a log-base-100 tail so outliers do not flatten median values. High-duration outliers keep actual duration labels. The final datapoint for each run is labeled with the stopped cell coordinate and final step, such as `cells/999 at step 147`. | accepted |
| `opportunistic_type_overruled_decisions` | batch, CCRS run, type | Count of opportunistic CCRS decisions where the selected option had `selected_reordered=true`, grouped by `selected_type` such as `signifier` or `stigmergy`. | accepted |

## Zone Metrics

The report includes a zone-level section near the end of `summary.md`, before
`## Generated Artifacts`. Zone metrics intentionally mirror the current
whole-run report metrics so readers can compare whether baseline and CCRS
behavior changed in specific MASE dataset regions rather than only over the
complete run.

The current zone boundaries for the CCRS maze scenario family are:

| Zone | Starts after | Completed when agent enters | Boundary meaning |
| --- | --- | --- | --- |
| signifier zone | run start | `cells/13/5` | `cells/13/5` is the first cell of the stigmergy zone. |
| stigmergy zone | `cells/13/5` | `cells/28/14` | `cells/28/14` is the first cell of the mixed zone. |
| mixed zone | `cells/28/14` | `cells/36/37` | `cells/36/37` is the first cell of the construction site zone. |
| construction site zone | `cells/36/37` | `cells/39/43` | `cells/39/43` is the first cell of the social zone. |
| social zone | `cells/39/43` | `cells/999` | `cells/999` is the scenario exit. |

Zone completion is `yes` when the experiment agent has a filtered movement or
cycle row whose `cell` ends with the zone's completion cell. It is `no`
otherwise. A boundary cell both completes the previous zone and serves as the
starting cell for the next zone. For example, `cells/13/5` completes the
signifier zone and is the first cell of the stigmergy zone. Movement, duration,
and average-cycle counts for a later zone begin after its starting boundary and
include the move into the zone's completion cell. For the signifier zone, the
previous boundary is the run start. If a run never reaches a zone's start
boundary, that zone has no included rows and reports `completed = no` with empty
duration and average-cycle fields.

Zone-level report metrics reuse the existing whole-run definitions unless
explicitly stated otherwise:

| Metric | Scope | Zone definition | Status |
| --- | --- | --- | --- |
| `zone_completed` | run-zone | `yes` when the agent enters the zone completion cell, otherwise `no`. | accepted |
| `zone_total_duration_ms` | run-zone | Sum of non-empty `duration_ms` values in included zone cycle rows. | accepted |
| `zone_total_moves` | run-zone | Count of included cycle rows for the experiment agent. The completion-cell move is included in the ending zone. | accepted |
| `zone_average_cycle_duration_ms` | run-zone | Mean of non-empty `duration_ms` from included zone cycle rows. | accepted |
| `zone_cycle_duration_chart` | zone | SVG chart with one line per run showing cycle duration by local step within the zone. | accepted |
| `zone_move_optimality` | run-zone | Table columns for optimal moves, actual moves, and delta. `CcrsMazeV1` optimal moves are signifier `19`, stigmergy `24`, mixed `57`, construction site `19`, social `19`. `CcrsMazeV2` optimal moves are signifier `17`, stigmergy `24`, mixed `37`, construction site `19`, social `19`. | accepted |
| `zone_cycle_duration_summary` | zone | Same shape as the whole-run cycle-duration summary: baseline average, CCRS average, CCRS opportunistic-count buckets `0`, `1`, `2`, `3+`, and dynamically generated CCRS contingency invocation columns if present in the zone. | accepted |
| `zone_decision_breakdown` | zone | Same definitions as whole-run decision breakdown, filtered to decisions whose log line falls inside the zone's cycle window. | accepted |
| `zone_opportunistic_type_overruled_decisions` | zone | Same definition as whole-run `opportunistic_type_overruled_decisions`, filtered to overruled decisions inside the zone and grouped by `selected_type`. | accepted |

Zone filtering should use source-order or sequence fields already present in the
CSV outputs, not wall-clock string comparisons. If both `mase-agent-moved.csv`
and `cycle-durations.csv` are available, movement completion should be validated
against MASE movement evidence and cycle timing should be computed from
`cycle-durations.csv`.

## Opportunistic Contribution Measurement

The report currently includes only direct counts of overruled decisions by
`selected_type` values such as `signifier` and `stigmergy`. This avoids
presenting a heuristic as a move-saving measurement. The count answers "how
often did this CCRS type overrule the default option?" but not "how many moves
did this type save?"

For a causal measurement, run ablation batches on the same maze and reset
procedure:

- baseline DFS with no opportunistic CCRS;
- CCRS with signifier guidance only;
- CCRS with stigmergy guidance only;
- CCRS with both signifier and stigmergy guidance.

Then calculate contribution from deltas:

```text
signifier_contribution = baseline_delta - signifier_only_delta
stigmergy_contribution = baseline_delta - stigmergy_only_delta
combined_contribution = baseline_delta - both_enabled_delta
interaction_effect = combined_contribution - signifier_contribution - stigmergy_contribution
```

The ablation approach is the metric to use for claims such as "signifier reduced
the delta by X moves." The current single-run attribution should be described as
an estimate.

## Cycle Window Semantics

A cycle starts at one `agent.cycle.location` marker and ends at the next marker
for the same run-agent pair. The duration for step `N` is:

```text
cycle[N].duration_ms = cycle[N].timestamp_ms - cycle[N-1].timestamp_ms
```

The first marker has no previous marker and should be written with an empty
duration. Summary averages exclude empty durations.

For the CCRS agent, opportunistic and contingency counts are assigned to the
cycle window in which their structured log events occur:

- `opp_ccrs_detected_count`: count of `ccrs.opportunistic.detected` events
  between the previous and current `agent.cycle.location` marker.
- `contingency_ccrs_invocation_count`: count of
  `ccrs.contingency.evaluate.request` events between the previous and current
  `agent.cycle.location` marker.

This windowing answers how expensive the decision and recovery work was before
the next observed movement. If a later analysis needs to attribute cost to the
cell just arrived at instead, add a second derived view rather than changing
this definition silently.

The report's opportunistic CCRS cycle averages exclude cycle windows where
`contingency_ccrs_invocation_count > 0`, because contingency calls dominate the
duration and would hide normal opportunistic overhead. The report's contingency
columns are not per-cycle count buckets. They are dynamically generated ordered
invocation cycles: first contingency invocation, second contingency invocation,
third contingency invocation, and so on for every observed invocation.

## Known Caveats

- Manual viewer exports may contain multiple agents. Import each run with
  `-AgentName` so generated MASE event CSVs and run-level movement or
  transaction totals include only the experiment agent.
- MASE `summary` transaction trace mode is enough for transaction counts and
  status, but not for request bodies or per-triple RDF diffs.
- Free-text AgentSpeak markers are useful for bootstrapping but are fragile.
  Prefer `[CCRS-EVENT]` lines or explicit machine-readable agent markers for new
  metrics.
- Total duration currently uses the agent log line `It took ...`. Alternatives
  for later comparison are MASE event-span duration, process wall-clock
  duration, or explicit per-cycle timestamps.
- Average agent-cycle duration depends on structured `[METRIC]
  event=agent.cycle.location ...` markers. Existing runs without those markers
  keep the field empty.
- A run can have useful MASE evidence even when the command status is not
  `completed`. Preserve those packages for diagnosis instead of deleting them.
