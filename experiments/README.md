# BDI experiment procedure

The current import/report implementation and guided runner are maintained in
[ccrs-experiments](../../ccrs-experiments/README.md). The commands below invoke
that implementation directly. This document retains the BDI-specific operational
procedure; existing archives and system configuration remain in this repository.

For registered study runs, use the [guided BDI procedure](../../ccrs-experiments/docs/GUIDED_BDI.md).
It owns run allocation, per-run capture, export guidance and final packaging in
ccrs-experiments. The manual import procedure below remains available for separately
captured evidence; its BatchId is a storage label, not a comparison or repetition.

## Quick Start Checklist

Use this table as the manual experiment loop. Replace `baseline-vs-ccrs`,
`100-bdi-baseline-v1`, `.jcm`, and agent names with the current run identifiers.

Start each experiment agent only after all setup agents have finished their run or reached their final position.

Use the same explicit `-Scenario CcrsMazeV1` or `-Scenario CcrsMazeV2` on both imports, matching the MASE dataset: V1 has no locks and 116 optimal moves; V2 has three locks and 138 optimal moves, counted from `cells/0`.

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
| 9 | `\ccrs-bdi` | Import the baseline run | `powershell -ExecutionPolicy Bypass -File ../ccrs-experiments/scripts/bdi/import-manual-run.ps1 -RepositoryRoot . -BatchId baseline-vs-ccrs -RunId 100-bdi-baseline-v1 -Jcm dfs_baseline.jcm -AgentName dfs_baseline_nesw_1 -Scenario CcrsMazeV1` |
| - | ********** | ********************** | ********************** |
| 10 | `\mase` | Recreate scenario services for the next run | `docker compose -f docker-compose.ccrs.yml up -d --no-build --force-recreate ccrs-agent keyholder-agent` |
| 11 | `\ccrs-bdi` | **Run the CCRS agent** | `gradle run "-Pjcm=dfs_ccrs.jcm"` |
| 12 | Browser | Export and reset after the run | `Reset Store` -> `Export logs`; save the NDJSON file into `S:\dev\ma\ccrs-bdi\experiments\runs\latest`; confirm reset |
| 13 | `\ccrs-bdi` | Stage the JaCaMo log | `Copy-Item log\mas-0.log experiments\runs\latest\ -Force` |
| 14 | `\ccrs-bdi` | Import the CCRS run | `powershell -ExecutionPolicy Bypass -File ../ccrs-experiments/scripts/bdi/import-manual-run.ps1 -RepositoryRoot . -BatchId baseline-vs-ccrs -RunId 200-bdi-ccrs-v1 -Jcm dfs_ccrs.jcm -AgentName dfs_ccrs_1 -Scenario CcrsMazeV1` |
| - | ********** | ********************** | ********************** |
| 15 | `\ccrs-bdi` | Generate the analysis package | `python ../ccrs-experiments/scripts/experiment.py report-package --architecture bdi --package experiments/runs/baseline-vs-ccrs/100-bdi-baseline-v1` |
| 16 | `\ccrs-bdi` | Open the report | `experiments/runs/baseline-vs-ccrs/100-bdi-baseline-v1/report/summary.md` |

This README covers the local experiment analysis workflow for comparing DFS
JaCaMo agents with agent-side CCRS logs and MASE-side movement/transaction
events.

The current direction is manual execution first: start and reset MASE yourself,
run the agent you want to evaluate, export viewer logs into a staging folder,
then let the scripts import, normalize, parse, and report the logs.

Metric vocabulary and current caveats are tracked in
[MEASUREMENT_CONTRACT.md](../../ccrs-experiments/docs/MEASUREMENT_CONTRACT.md).

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
powershell -ExecutionPolicy Bypass -File ../ccrs-experiments/scripts/bdi/import-manual-run.ps1 -RepositoryRoot . `
  -BatchId baseline-vs-ccrs `
  -RunId 100-bdi-baseline-v1 `
  -Jcm dfs_baseline.jcm `
  -AgentName dfs_baseline_nesw_1 `
  -Scenario CcrsMazeV1 `
  -Notes "Manual baseline run after viewer reset"
```

The import script moves files out of `experiments/runs/latest/` by default and
stores the original exports under the run package's `source-exports/` folder.
Use `-KeepSource` when you want a copy-only dry run.

Import the next manually executed run with the same `-BatchId` and a different
`-RunId`, for example:

```powershell
powershell -ExecutionPolicy Bypass -File ../ccrs-experiments/scripts/bdi/import-manual-run.ps1 -RepositoryRoot . `
  -BatchId baseline-vs-ccrs `
  -RunId 200-bdi-ccrs-v1 `
  -Jcm dfs_ccrs.jcm `
  -AgentName dfs_ccrs_1 `
  -Scenario CcrsMazeV1
```

Generate or refresh the report package:

```powershell
python ../ccrs-experiments/scripts/experiment.py report-package --architecture bdi --package experiments/runs/baseline-vs-ccrs/100-bdi-baseline-v1
```

The command produces report/summary.md and report/metrics.json inside the selected
run package. Invoke it separately for the CCRS package. Registered runs instead use
`registry report --run-id <id>` from the experiments repository; pending, valid and
excluded runs are all reportable. Combined reports are deferred.

## Evidence and report outputs

[import-manual-run.ps1](../../ccrs-experiments/scripts/bdi/import-manual-run.ps1)
preserves original exports, normalizes viewer events into mase-events.jsonl and
records run.json. Its default manual archive is experiments/runs/<batch-id>/<run-id>/.
Guided archives use the [run register layout](../../ccrs-experiments/docs/RUN_REGISTER.md).

The shared Python reporter reads these packages directly. It retains
mase-agent-moved.csv, including initial placement as a non-move anchor, and writes
navigation-intervals.csv, decisions.csv, guidance.csv, strategy-durations.csv,
events.csv and zone-summary.csv. Full source references, case accounts and missing-
evidence reasons are in metrics.json. See the
[measurement contract](../../ccrs-experiments/docs/MEASUREMENT_CONTRACT.md) and
[MEASUREMENT_CONTRACT.md](../../ccrs-experiments/docs/MEASUREMENT_CONTRACT.md) for definitions.

Both [dfs_baseline.asl](../src/agt/dfs_baseline.asl) and
[dfs_ccrs.asl](../src/agt/dfs_ccrs.asl) emit agent.run.finished with timestamp_ms
(UTC epoch milliseconds) and reason on exit recognition or give-up, using
[epoch_millis.java](../src/agt/jia/epoch_millis.java). This observation is distinct
from MASE exit arrival; reports retain it without treating give-up as success.

`rtk proxy .\gradlew.bat --offline test classes` includes
[TerminalAuditCheck.java](../src/audit/java/TerminalAuditCheck.java): it parses both
application ASL files with framework includes omitted and checks timestamp binding
and arity. It does not execute initial goals, reasoning cycles or environment actions.
Framework integration still requires a separately authorized live run.

Offline report tests live in [ccrs-experiments/tests](../../ccrs-experiments/tests/README.md).
They use temporary fixtures and do not start agents or MASE. The removed PowerShell
report/parser commands are superseded by the Python entry point above.
