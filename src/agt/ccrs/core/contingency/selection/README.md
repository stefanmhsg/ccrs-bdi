# Strategy Selection Policies

This package contains the core strategy-selection port and deterministic
implementations used by `ContingencyCcrs`.

## Architecture

`ContingencyCcrs` depends only on `StrategySelectionPolicy` and
`StrategySelectionPlan`.

* `StrategySelectionPolicy` creates one plan for one CCRS invocation.
* `StrategySelectionPlan` orders enabled strategies and gates candidates before
  evaluation.
* `StrategySelectionRequest` carries the current situation, context, default
  registry order, configuration, and recent traces.
* `StrategyGateDecision` is the policy-neutral explanation object logged by the
  orchestrator.

## Policy Types

`DefaultStrategySelectionPolicy` is the deterministic baseline. It preserves
the registry/default escalation order and evaluates every enabled candidate.

`TraceBasedStrategySelectionPolicy` is the current adaptive default. It builds a
trace-based model from recent `CcrsTrace` records and can reorder or skip
strategies when enough local evidence exists.
