# Experiment Log Analysis

## Quick Start Checklist

Use this table as the manual experiment loop. Replace `baseline-vs-ccrs`,
`001-baseline`, `.jcm`, and agent names with the current run identifiers.

| Step | Where | Action | Command or UI action |
| ---: | --- | --- | --- |
| 1 | `\ccrs-bdi` | Prepare the staging folder | `New-Item -ItemType Directory -Force experiments\runs\latest` |
| 2 | `\ccrs-bdi` | Compile the agent repo once after code changes | `.\gradlew.bat classes` |
| - | ********** | ********************** | ********************** |
| 3 | `\mase` | Start MASE server and viewer | `docker compose -f docker-compose.ccrs.yml --profile viewer up --build -d mase-viewer` |
| 4 | `\mase` | Start CCRS scenario services for the run | `docker compose -f docker-compose.ccrs.yml up --build -d ccrs-agent keyholder-agent` |
| 5 | Browser | Open the viewer | `http://localhost:3000` |
| - | ********** | ********************** | ********************** |
| 6 | `\ccrs-bdi` | **Run the baseline agent** | `gradle run "-Pjcm=dfs_baseline.jcm"` |
| 7 | Browser | Export and reset after the run | `Reset Store` -> `Export logs`; save the NDJSON file into `S:\dev\ma\ccrs-bdi\experiments\runs\latest`; confirm reset |
| 8 | `\ccrs-bdi` | Stage the JaCaMo log | `Copy-Item log\mas-0.log experiments\runs\latest\ -Force` |
| 9 | `\ccrs-bdi` | Import the baseline run | `powershell -ExecutionPolicy Bypass -File experiments/scripts/import-manual-run.ps1 -BatchId baseline-vs-ccrs -RunId 001-baseline -Jcm dfs_baseline.jcm -AgentName dfs_baseline_nesw_1` |
| - | ********** | ********************** | ********************** |
| 10 | `\mase` | Recreate scenario services for the next run | `docker compose -f docker-compose.ccrs.yml up -d --no-build --force-recreate ccrs-agent keyholder-agent` |
| 11 | `\ccrs-bdi` | **Run the CCRS agent** | `gradle run "-Pjcm=dfs_ccrs.jcm"` |
| 12 | Browser | Export and reset after the run | `Reset Store` -> `Export logs`; save the NDJSON file into `S:\dev\ma\ccrs-bdi\experiments\runs\latest`; confirm reset |
| 13 | `\ccrs-bdi` | Stage the JaCaMo log | `Copy-Item log\mas-0.log experiments\runs\latest\ -Force` |
| 14 | `\ccrs-bdi` | Import the CCRS run | `powershell -ExecutionPolicy Bypass -File experiments/scripts/import-manual-run.ps1 -BatchId baseline-vs-ccrs -RunId 002-ccrs -Jcm dfs_ccrs.jcm -AgentName dfs_ccrs_1` |
| - | ********** | ********************** | ********************** |
| 15 | `\ccrs-bdi` | Generate the analysis package | `powershell -ExecutionPolicy Bypass -File experiments/scripts/write-report.ps1 -BatchId baseline-vs-ccrs` |
| 16 | `\ccrs-bdi` | Open the report | `experiments\reports\baseline-vs-ccrs\summary.md` |

This README covers the local experiment analysis workflow for comparing DFS
JaCaMo agents with agent-side CCRS logs and MASE-side movement/transaction
events.

The current direction is manual execution first: start and reset MASE yourself,
run the agent you want to evaluate, export viewer logs into a staging folder,
then let the scripts import, normalize, parse, and report the logs.

Metric vocabulary and current caveats are tracked in
[METRICS.md](METRICS.md).

## Manual Run Workflow

Run MASE from the sibling repository and keep the operational loop under your
control. For the CCRS scenario, the usual Docker commands from `S:\dev\ma\mase`
are:

```powershell
docker compose -f docker-compose.ccrs.yml --profile viewer up --build -d mase-viewer
docker compose -f docker-compose.ccrs.yml up --build -d ccrs-agent keyholder-agent
```

For later runs, reset the scenario in the MASE viewer and recreate the one-shot
scenario agents without rebuilding:

```powershell
docker compose -f docker-compose.ccrs.yml up -d --no-build --force-recreate ccrs-agent keyholder-agent
```

The viewer reset flow should be used for manual runs because it can export or
discard the browser IndexedDB archive before resetting the server store.

Use this staging directory for a single just-finished run:

```text
experiments/runs/latest/
```

Put these files in that directory:

- the MASE viewer NDJSON export from IndexedDB;
- any JaCaMo logs you want parsed, usually `log/mas-*.log`;
- optional notes or raw files that should be preserved with the run package.

Then import the staged files into a durable run package:

```powershell
powershell -ExecutionPolicy Bypass -File experiments/scripts/import-manual-run.ps1 `
  -BatchId baseline-vs-ccrs `
  -RunId 001-baseline `
  -Jcm dfs_baseline.jcm `
  -AgentName dfs_baseline_nesw_1 `
  -Notes "Manual baseline run after viewer reset"
```

The import script moves files out of `experiments/runs/latest/` by default and
stores the original exports under the run package's `source-exports/` folder.
Use `-KeepSource` when you want a copy-only dry run.

Import the next manually executed run with the same `-BatchId` and a different
`-RunId`, for example:

```powershell
powershell -ExecutionPolicy Bypass -File experiments/scripts/import-manual-run.ps1 `
  -BatchId baseline-vs-ccrs `
  -RunId 002-ccrs `
  -Jcm dfs_ccrs.jcm `
  -AgentName dfs_ccrs_1
```

Generate or refresh the report package:

```powershell
powershell -ExecutionPolicy Bypass -File experiments/scripts/write-report.ps1 -BatchId baseline-vs-ccrs
```

The main report is:

```text
experiments/reports/<batch-id>/summary.md
```

The most important CSVs for the new manual workflow are:

- `runs.csv`: one row per imported run.
- `agents.csv`: one row per observed agent per run.
- `mase-agent-moved.csv`: one environment-observed movement event per row.
- `mase-transactions.csv`: one MASE transaction event per row.
- `decisions.csv` and `contingency.csv`: CCRS-specific records from JaCaMo logs.

## Analysis Scripts

[import-manual-run.ps1](scripts/import-manual-run.ps1) consumes the
manual staging folder, writes `run.json`, normalizes viewer exports into
`mase-events.jsonl`, preserves original exports under `source-exports/`, and
updates the batch `manifest.json`.

[parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1)
converts run packages into normalized CSV files. It accepts both structured
`[CCRS-EVENT]` records and older fallback free-text markers. For MASE viewer
exports, it keeps only events whose `agent` matches the run's `-AgentName`
metadata so scenario-service logs do not enter the report.

[write-report.ps1](scripts/write-report.ps1) creates `summary.md`
from those CSV files. It invokes the parser automatically so the CSV tables are
refreshed before the Markdown report is written.

## Generate The Report

After a batch exists, generate the Markdown report and CSV/JSON tables:

```powershell
powershell -ExecutionPolicy Bypass -File experiments/scripts/write-report.ps1 -BatchId smoke
```

[write-report.ps1](scripts/write-report.ps1) invokes
[parse-experiment-logs.ps1](scripts/parse-experiment-logs.ps1) before writing
the report.

## Result Locations

Per-run archives are written to:

```text
experiments/runs/<batch-id>/
```

The main report is:

```text
experiments/reports/<batch-id>/summary.md
```

For the `smoke` examples above, open:

```text
experiments/reports/smoke/summary.md
```

The report directory also contains these machine-readable outputs:

- `runs.csv`
- `decisions.csv`
- `contingency.csv`
- `actions.csv`
- `agents.csv`
- `mase-events.csv`
- `mase-agent-moved.csv`
- `mase-transactions.csv`
- `summary.json`

`runs.csv` includes `run_stop_reason`, `run_terminal_pattern`, and
`run_terminal_line` so marker-stopped JaCaMo processes can be distinguished from
timeouts and natural process exits.

`agents.csv` separates MASE movement and transaction totals by experiment
agent. Viewer-exported records for scenario services such as `ccrs-agent` or
`keyholder-agent` are filtered out during parsing when the run was imported with
`-AgentName`.

## MASE Viewer Path Analysis

The MASE viewer now has an offline Path Analysis feature. The report pipeline
creates paste-ready movement sequences from captured `AGENT_MOVED` events:

```text
experiments/reports/<batch-id>/path-analysis-inputs/*.cells.txt
```

Each `.cells.txt` file contains one normalized cell path per line, such as
`/cells/23/5`. Paste the full file content into the MASE viewer Path Analysis
input to render the run path as a frontend-only overlay on the maze canvas.

Metadata for those path files is stored in:

```text
experiments/reports/<batch-id>/path-analysis-inputs.csv
```

The manual workflow uses viewer IndexedDB NDJSON exports as the MASE event
source. Import each export into a run package before resetting or starting the
next run.
