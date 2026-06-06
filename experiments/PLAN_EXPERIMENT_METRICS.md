# PLAN_EXPERIMENT_METRICS: Add cycle-duration metrics and consolidate experiment tooling

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository does not currently have a checked-in `PLANS.md` guide. This plan follows the local `PLAN_<SCOPE>.md` convention described in the repository guidance.

## Purpose / Big Picture

Manual MASE experiments should produce a compact analysis package that compares the baseline DFS agent and the CCRS DFS agent without hand-counting log lines. After this work, a user can run the existing manual experiment workflow, generate a report, and see cycle-duration metrics, grouped CCRS overhead metrics, and a line chart comparing baseline and CCRS cycle time by movement step.

The work also consolidates experiment-related scripts, reports, run archives, and documentation under `experiments/` so the experiment workflow has one obvious home.

## Progress

- [x] (2026-05-23) Defined the structured AgentSpeak cycle timestamp marker in [METRICS.md](METRICS.md).
- [x] (2026-05-23) Created this plan under `experiments/` and recorded the cycle-duration and directory-consolidation work.
- [x] (2026-05-23) Updated `.gitignore` exceptions so experiment Markdown files and future `experiments/scripts/` files are not hidden by the generated-artifact ignore rule.
- [x] (2026-05-23) Added the `[METRIC] event=agent.cycle.location ...` marker to both [dfs_baseline.asl](../src/agt/dfs_baseline.asl) and [dfs_ccrs.asl](../src/agt/dfs_ccrs.asl).
- [x] (2026-05-23) Moved experiment scripts and documentation under `experiments/`.
- [x] (2026-05-23) Removed the legacy compatibility wrappers from `scripts/experiments/`.
- [x] (2026-05-23) Extended [parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1) to create `cycle-durations.csv`.
- [x] (2026-05-23) Extended [write-report.ps1](scripts/write-report.ps1) to add the requested cycle-duration summary table.
- [x] (2026-05-23) Generated `cycle-duration-comparison.svg` when parsed cycle durations are available.
- [x] (2026-05-23) Moved experiment tooling and documentation into `experiments/` and updated links and commands.
- [x] (2026-05-23) Added move optimality metrics; later revised to scenario-specific optimal path lengths.
- [x] (2026-05-23) Added overruled opportunistic CCRS decision counts grouped by `selected_type`.
- [x] (2026-05-23) Added contingency strategy rationale to structured logs, `contingency.csv`, and invocation tables.
- [ ] Improve opportunistic CCRS delta analysis so deviations from the optimal path can be attributed to green signifiers, orange signifiers, and stigmergy markers.
- [ ] Add a metric for opportunistic CCRS overruling decisions that led onto a suboptimal path.
- [x] (2026-06-06) Replaced the previous cycle-duration clipping approach with a 200 ms linear/log-base-100 y-axis, 25-step x-axis markers, max-step labeling, and outlier callouts.
- [x] (2026-06-06) Added final datapoint callouts to the cycle-duration chart showing each run's stopped cell coordinate and final step.
- [x] (2026-06-06) Reserved 82% of cycle-chart plot height for the 0-200 ms range and repositioned final datapoint labels away from the series lines.
- [x] (2026-06-06) Added a zone-level report section before `Generated Artifacts`, split by signifier, stigmergy, mixed, construction site, and social zones.
- [x] (2026-06-06) Differentiated total and zone optimal move counts by scenario version: `CcrsMazeV1` and `CcrsMazeV2`.

## Surprises & Discoveries

- Observation: The parser already supports structured `[METRIC]` lines through the same `key=value` parser used for `[CCRS-EVENT]`.
  Evidence: [parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1) includes `[METRIC]` in `ConvertFrom-KeyValueLine`.
- Observation: There was no existing `PLAN_*.md` in `ccrs-bdi`, so this plan is the first executable plan for the experiment metrics work.
  Evidence: `rg --files -g "PLAN_*.md" -g "PLANS.md"` returned only the experiment README and metrics docs before this file was added.

## Decision Log

- Decision: Use `[METRIC] event=agent.cycle.location ...` as the AgentSpeak log marker for cycle timing.
  Rationale: The parser already understands `[METRIC]` key-value lines, the prefix separates machine-readable metrics from human messages, and a single-line marker is more robust than parsing `I'm now at: ...` plus a separate timestamp line.
  Date/Author: 2026-05-23 / Codex.
- Decision: Define a cycle as the interval between two adjacent `agent.cycle.location` markers for the same run-agent pair.
  Rationale: The marker is emitted immediately after `at(Target)` is updated, so adjacent markers represent observed movement-to-movement cycle time. This matches the requested step-number line chart.
  Date/Author: 2026-05-23 / Codex.
- Decision: Attribute opportunistic and contingency CCRS event counts to the cycle window in which their structured log events occur.
  Rationale: This measures the work done before the next observed movement. It also keeps the baseline agent comparable because it will have the same cycle windows with zero CCRS events.
  Date/Author: 2026-05-23 / Codex.
- Decision: Consolidate experiment assets under `experiments/` with scripts in `experiments/scripts/`, reports in `experiments/reports/`, runs in `experiments/runs/`, and docs in `experiments/`.
  Rationale: The current split between root Markdown files, `scripts/experiments/`, and `experiments/` makes the workflow harder to discover and update.
  Date/Author: 2026-05-23 / Codex.

## Outcomes & Retrospective

Cycle-duration parsing, run-level cycle aggregates, the summary table, and SVG chart generation are implemented. Existing archived runs without `[METRIC] event=agent.cycle.location` markers report empty cycle values; new runs with the marker produce `cycle-durations.csv`, populated run-level averages, and `cycle-duration-comparison.svg`.

Move optimality reporting is implemented with scenario-specific `optimal_moves`, `actual_moves`, and `move_delta_from_optimal`. `CcrsMazeV1` uses total optimal moves `138`; `CcrsMazeV2` uses total optimal moves `116`. The summary also includes direct overruled decision counts grouped by `selected_type`; move-saving contribution remains reserved for future ablation runs.

Future work should move beyond raw overruled counts toward path-quality attribution. The goal is to explain how each opportunistic CCRS type affected deviation from the optimal path: green signifiers, orange signifiers, and stigmergy markers should each be measurable as contributing moves that either reduced or increased the final delta from the scenario-specific optimal path.

## Context and Orientation

The repository is `ccrs-bdi`. It contains the JaCaMo agents in [src/agt/](../src/agt/), experiment import and reporting scripts in [experiments/scripts/](scripts/), run archives in [runs/](runs/), and generated reports in [reports/](reports/).

The baseline agent is [dfs_baseline.asl](../src/agt/dfs_baseline.asl). The CCRS-enabled agent is [dfs_ccrs.asl](../src/agt/dfs_ccrs.asl). Both agents update their current location in a plan that reacts to RDF cell perceptions and currently print `I'm now at: ...`.

The parser is [parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1). It reads archived run packages from `experiments/runs/<batch-id>/<run-id>/`, parses JaCaMo logs and MASE viewer exports, and writes CSV files under `experiments/reports/<batch-id>/`.

The report writer is [write-report.ps1](scripts/write-report.ps1). It refreshes the parsed CSV files and creates `summary.md`.

The current metric definitions are in [METRICS.md](METRICS.md). The manual experiment workflow is in [README.md](README.md). The maintained scripts live in `experiments/scripts/`.

## Milestones

Milestone 1 adds structured cycle timestamp markers to both agents. At the end of this milestone, a new run log contains `[METRIC] event=agent.cycle.location` lines with `step`, date fields, `t_ms`, `previous`, and `cell`.

Milestone 2 parses cycle durations. `cycle-durations.csv` now exists in each report package and contains one row per movement cycle, with empty duration for the first marker and `duration_ms` for subsequent markers.

Milestone 3 adds report tables. `summary.md` now includes a cycle-duration comparison table with the requested columns: baseline average, CCRS average, CCRS averages grouped by zero, one, two, and three-or-more opportunistic detections, and CCRS averages grouped by one, two, and three-or-more contingency invocations.

Milestone 4 adds the line chart. `experiments/reports/<batch-id>/cycle-duration-comparison.svg` is generated when cycle-duration rows are available, and `summary.md` references it. The chart plots step number on the x-axis and cycle duration in milliseconds on the y-axis.

Milestone 5 consolidates experiment files. Experiment-specific files now live under `experiments/`: scripts in `experiments/scripts/`, this plan in `experiments/`, metrics documentation in `experiments/METRICS.md`, workflow documentation in `experiments/README.md`, run packages in `experiments/runs/`, and reports in `experiments/reports/`. The root-level compatibility wrappers under `scripts/experiments/` have been removed.

Milestone 6 improves opportunistic CCRS delta attribution. At the end of this milestone, the report can show whether green signifiers, orange signifiers, and stigmergy markers contributed to reducing or increasing deviation from the optimal path, and which overruled decisions sent the agent onto a path that later proved suboptimal.

Milestone 7 improves cycle-duration visualization. At the end of this milestone, `cycle-duration-comparison.svg` reserves most of the y-axis for 0-200 ms values and compresses higher durations into a log-base-100 tail, labels high-duration outlier cycles with their actual duration, labels each run's final datapoint with its stopped cell coordinate and final step, and shows x-axis step markers every 25 steps while preserving the observed maximum step label. This keeps normal baseline and CCRS cycle timings readable even when contingency CCRS invocations create large peaks.

Milestone 8 adds zone-level reporting. At the end of this milestone, `summary.md` contains a new section before `## Generated Artifacts` that is divided into the MASE dataset zones: signifier, stigmergy, mixed, construction site, and social. Each zone reports the same families of metrics as the whole-run summary where applicable: completion, total duration, total moves, average cycle duration, cycle-duration chart, move optimality placeholders, cycle-duration comparison summary, decision breakdown, and opportunistic CCRS overruled decisions.

## Plan of Work

First, edit [dfs_baseline.asl](../src/agt/dfs_baseline.asl) and [dfs_ccrs.asl](../src/agt/dfs_ccrs.asl) in the location-update plan directly after `-+at(Target)`. Keep the existing human-readable `I'm now at: ...` print, then emit one structured `[METRIC]` line with date, time, within-day milliseconds, step, previous cell, and current cell. Add a `cycle_step/1` belief that increments once for each location update.

Next, update [parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1). When the structured parser sees `event=agent.cycle.location`, collect rows with run id, agent label, source file, line number, step, date fields, `t_ms`, previous cell, and current cell. After all logs for a run are read, sort cycle markers by source order and compute `duration_ms` from adjacent markers. Count structured `ccrs.opportunistic.detected` and `ccrs.contingency.evaluate.request` records in each cycle window and write the result as `cycle-durations.csv`.

Then update run-level aggregates in `runs.csv`. Add average cycle duration for every run. For CCRS runs, add average cycle duration grouped by `opp_ccrs_detected_count` buckets `0`, `1`, `2`, and `3_plus`, and by `contingency_ccrs_invocation_count` buckets `1`, `2`, and `3_plus`.

Then update [write-report.ps1](scripts/write-report.ps1). Add a cycle-duration summary table under the existing core metrics or directly after it. The table should have one row per batch comparison or one row per CCRS run paired with the baseline run in the same batch. Use blank cells when a run has no cycle markers.

Then add static chart generation. Prefer an SVG generated by PowerShell from `cycle-durations.csv` because it has no new dependency and can be committed or regenerated with the report. The SVG should include axes labels, a baseline line, a CCRS line, and a legend. If PowerShell SVG generation becomes too cumbersome, use a small self-contained HTML file with inline SVG, but keep the generated artifact in the report directory.

The directory layout has been consolidated. Future implementation work should edit the maintained files under `experiments/scripts/`, `experiments/README.md`, and `experiments/METRICS.md`.

For opportunistic CCRS delta attribution, first define the optimal path as an ordered set of cells or directed transitions rather than only a scalar optimal length. During parsing, annotate every movement step with whether the resulting cell or transition is on the optimal path, whether it advances along the optimal path, stays off-path, backtracks toward the optimal path, or moves farther away from it. Join this with the decision event that selected the next URI. For every overruled opportunistic decision, record the selected CCRS type with enough granularity to distinguish green signifier, orange signifier, and stigmergy marker. Then compute contribution rows as observed path-quality deltas around that decision: a negative contribution means the decision reduced deviation from the optimal path, and a positive contribution means it increased deviation.

The first accepted version of this attribution should be deliberately traceable rather than statistically causal. Add a per-decision CSV that includes run id, step, previous cell, selected cell, default cell when known, selected CCRS type, selected CCRS marker color or subtype, whether the decision overruled the default path, optimal-path classification before and after the move, and the local delta contribution. Later, if this local attribution is too noisy, add ablation experiments for signifier-only, stigmergy-only, and no-opportunistic-CCRS runs.

For the suboptimal-overrule metric, count opportunistic CCRS overrules where the selected path is classified as moving off the optimal path or increasing distance from the next optimal transition. Report this by CCRS type and subtype, with example rows linking to the detailed decision table.

For cycle-duration chart scaling, update the SVG generation in [write-report.ps1](scripts/write-report.ps1) to use a split y-axis transform. Durations from 0 through 200 ms should get 82% of the plot height. Durations above 200 ms should be compressed into the remaining space with a log-base-100 scale, drawn with a distinct marker, and labeled with the true millisecond value. The x-axis should show 25-step interval markers and also include the observed maximum step even when it is not a multiple of 25. The summary should mention the split scale near the chart.

For zone-level reporting, first add a fixed zone definition table in [write-report.ps1](scripts/write-report.ps1) or a small helper loaded by it:

| Zone | Completion cell | Notes |
| --- | --- | --- |
| signifier | `cells/13/5` | Starts at run start; completion cell is the first cell of the stigmergy zone. |
| stigmergy | `cells/28/14` | Starts after `cells/13/5`; completion cell is the first cell of the mixed zone. |
| mixed | `cells/36/37` | Starts after `cells/28/14`; completion cell is the first cell of the construction site zone. |
| construction site | `cells/39/43` | Starts after `cells/36/37`; completion cell is the first cell of the social zone. |
| social | `cells/999` | Starts after `cells/39/43`; completion cell is the exit. |

Then derive zone windows per run-agent pair from ordered movement or cycle rows. The signifier zone includes rows from the beginning of the run through the first row whose cell ends with `cells/13/5`. Each later zone uses the previous completion cell as its starting boundary, but its movement and duration counts begin after that boundary row and include rows through the first row whose cell ends with the zone completion cell. A zone is completed only when that completion cell is reached. If the start boundary for a later zone is never reached, report `completed = no` and leave duration and average-cycle fields blank.

The zone section in `summary.md` must appear after the opportunistic CCRS overruled-decision section and before `## Generated Artifacts`. Use one subsection per zone. Each zone subsection should include:

- A per-run zone core metrics table: run, JCM, completed, total duration ms, total moves, average cycle duration, and final cell in the zone.
- A zone move optimality table with optimal moves, actual moves, and delta using scenario-specific zone optimal move counts.
- A zone cycle-duration chart showing number of steps on the x-axis and cycle time on the y-axis. Prefer one SVG per zone, for example `zone-cycle-duration-signifier.svg`. Use local zone step numbers for x-axis comparability, and include run labels in the legend.
- A zone cycle-duration summary table with the same structure as the whole-run cycle summary: baseline average, CCRS average, CCRS opportunistic bucket averages for `0`, `1`, `2`, and `3+`, and dynamic contingency invocation columns if the zone contains contingency cycles.
- A zone decision breakdown table using the same definitions as the whole-run decision breakdown but filtered to decisions inside the zone window.
- A zone opportunistic CCRS overruled-decision table grouped by `selected_type`, matching the whole-run overruled-decision definition but filtered to the zone.

Zone filtering should use source-order fields from generated CSVs. Use `cycle-durations.csv` for cycle timing, average-cycle metrics, opportunistic and contingency cycle buckets, endpoint cell detection, and zone move counts when structured cycle rows are available. Use `mase-agent-moved.csv` to cross-check zone completion when needed. Use `decisions.csv` log line fields to assign decisions to the zone cycle window that contains the decision event. The generated `zone-summary.csv` should contain one row per run-zone pair so the Markdown section is traceable back to CSV data.

## Concrete Steps

From `S:\dev\ma\ccrs-bdi`, inspect the current metric parser and report writer:

    rg -n "ConvertFrom-KeyValueLine|ccrs.opportunistic.detected|ccrs.contingency.evaluate.request|average_agent_cycle" experiments/scripts src/agt

After adding the AgentSpeak marker, compile the agents:

    .\gradlew.bat classes

Run or import a manual batch using the workflow in [README.md](README.md). Then regenerate reports:

    powershell -ExecutionPolicy Bypass -File experiments\scripts\parse-experiment-logs.ps1 -BatchId baseline-vs-ccrs
    powershell -ExecutionPolicy Bypass -File experiments\scripts\write-report.ps1 -BatchId baseline-vs-ccrs

## Validation and Acceptance

Validation starts with a syntax and compilation check. Running `.\gradlew.bat classes` from `S:\dev\ma\ccrs-bdi` should complete successfully after the AgentSpeak edits.

A manually imported batch with fresh logs should produce `experiments/reports/<batch-id>/cycle-durations.csv`. The first row per run-agent pair should have an empty `duration_ms`, and subsequent rows should have non-negative duration values.

The regenerated `summary.md` should include a cycle-duration table with these columns: baseline average, CCRS average, CCRS average with zero opportunistic detections, one opportunistic detection, two opportunistic detections, three-or-more opportunistic detections, one contingency invocation, two contingency invocations, and three-or-more contingency invocations.

The report directory should include a chart artifact named `cycle-duration-comparison.svg` or another clearly named static chart file. Opening the chart should show step number on the x-axis and duration in milliseconds on the y-axis, with separate baseline and CCRS lines.

After opportunistic CCRS delta attribution is implemented, the report should include a table showing green signifier, orange signifier, and stigmergy marker contributions to the move delta. The table should separate moves that reduced deviation from moves that increased deviation, and include a count of overruled decisions that led to a suboptimal path.

After piecewise log scaling is implemented, the chart should remain readable when contingency CCRS invocations take orders of magnitude longer than normal cycles. Points above 200 ms should keep their actual relative ordering on the logarithmic part of the axis and be labeled with their actual duration, while the rest of the line should not collapse into a near-flat one-liner.

After zone-level reporting is implemented, `summary.md` should include a zone section before `## Generated Artifacts`. The section should contain exactly five zone subsections in this order: signifier, stigmergy, mixed, construction site, and social. Each subsection should include the core zone table, move optimality placeholder table, zone cycle-duration chart reference, cycle-duration summary table, decision breakdown table, and opportunistic overruled-decision table. The report directory should include `zone-summary.csv` and one SVG chart per zone that has cycle rows. Zone completion should match the defined completion cells: `cells/13/5`, `cells/28/14`, `cells/36/37`, `cells/39/43`, and `cells/999`.

After directory consolidation, documentation links should resolve. The quick-start commands in the experiment README should use `experiments\scripts\...`, and the root docs should either link to the moved experiment docs or clearly state that experiment documentation lives under `experiments/`.

## Idempotence and Recovery

Parsing and report generation should remain idempotent: rerunning the parser for the same batch should overwrite generated CSV files in the report directory without modifying archived run packages. The chart generation should also overwrite the chart artifact for the same batch.

The directory consolidation was done with file moves rather than duplicate divergent files. The old `scripts/experiments/` wrappers were removed after the maintained paths were validated.

Existing run archives under `experiments/runs/` should not be modified except by explicit import commands. Generated files under `experiments/reports/` may be overwritten.

## Artifacts and Notes

Recommended AgentSpeak marker shape:

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

Example log line after JaCaMo logging prefixes:

    [METRIC] event=agent.cycle.location step=17 y=2026 m=5 d=23 h=14 min=3 sec=12 ms=48 t_ms=50592048 previous=http://127.0.1.1:8080/cells/12/5 cell=http://127.0.1.1:8080/cells/12/6

## Interfaces and Dependencies

[parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1) already exposes `ConvertFrom-KeyValueLine`, which returns a map for `[CCRS-EVENT]` and `[METRIC]` records. Extend that path instead of adding regex parsing for timestamp strings.

The new `cycle-durations.csv` should include at least these fields: `batch_id`, `run_id`, `jcm`, `agent_label`, `step`, `file`, `line`, `timestamp_ms`, `duration_ms`, `previous_cell`, `cell`, `opp_ccrs_detected_count`, and `contingency_ccrs_invocation_count`.

The generated chart should depend only on data in `cycle-durations.csv`. It should not read raw logs directly.

The future zone report should depend on `cycle-durations.csv`, `mase-agent-moved.csv`, `decisions.csv`, and run metadata already generated in the report directory. It should not read raw logs directly after parsing has completed. Any zone-specific chart should be reproducible from generated CSVs.

The future opportunistic attribution CSV should depend on parsed movement rows, parsed decision rows, and an explicit optimal-path definition. Avoid deriving path quality only from final move count because that cannot identify which decision caused the deviation.

Revision note 2026-05-23 / Codex: Created the initial plan from the user's cycle-duration metric request and added the planned experiment-directory consolidation.

Revision note 2026-05-23 / Codex: Added progress for the `.gitignore` exception that keeps experiment Markdown plans visible while leaving generated runs and reports ignored.

Revision note 2026-05-23 / Codex: Marked the AgentSpeak timestamp-marker milestone complete after adding the marker to both DFS agents and validating with `.\gradlew.bat classes`.

Revision note 2026-05-23 / Codex: Completed the experiment directory consolidation and updated plan links to the new maintained paths.

Revision note 2026-05-23 / Codex: Removed the compatibility wrappers, implemented cycle-duration parsing, added run-level grouped averages, generated the SVG cycle chart, and wired both the table and chart into `summary.md`.

Revision note 2026-05-23 / Codex: Added move optimality reporting and removed the stale duration-alternatives prose from `summary.md`.

Revision note 2026-05-23 / Codex: Replaced the heuristic signifier/stigmergy contribution estimate with raw overruled-decision counts by type and added contingency rationale to invocation tables.

Revision note 2026-05-23 / Codex: Added planned work for optimal-path deviation attribution by opportunistic CCRS subtype, suboptimal-overrule metrics, and cycle-duration chart labeling for contingency outliers.

Revision note 2026-06-06 / Codex: Replaced the planned capped chart with a split y-axis that gives 0-200 ms 82% of plot height, compresses higher durations into a log-base-100 tail, keeps 25-step x-axis markers, retains high-duration callouts, and repositions final datapoint stopped-cell labels.

Revision note 2026-06-06 / Codex: Planned a zone-level report section before generated artifacts, with explicit MASE zone boundaries, per-zone metrics mirroring the whole-run report, optimal-move placeholders, and CSV/SVG traceability requirements.

Revision note 2026-06-06 / Codex: Implemented the zone-level report section, `zone-summary.csv`, and per-zone cycle-duration SVG charts.

Revision note 2026-06-06 / Codex: Replaced the placeholder zone optimal move counts with scenario-specific totals and zone counts for `CcrsMazeV1` and `CcrsMazeV2`.
