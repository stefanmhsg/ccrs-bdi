# CCRS RDF Context

This package contains the RDF-facing context contract used by Contingency CCRS.

## Purpose

`CcrsContext` is the bridge between core CCRS logic and a concrete agent/runtime adapter.

Core CCRS uses it for:

- RDF lookup via `query(...)` and `contains(...)`
- interaction history access
- current-resource lookup
- CCRS trace history access

The core evaluator does not know how a Jason agent, another BDI runtime, or a different platform exposes beliefs and interaction history. That integration still belongs to the adapter implementation of `CcrsContext`.

## Evaluation Flow

Agents do not usually call `ContingencyCcrs` directly. In the Jason integration, they call the `ccrs.jacamo.jason.contingency.evaluate(...)` internal action, which delegates to:

```java
ContingencyCcrs.evaluate(situation, context)
```

`ContingencyCcrs.evaluate(...)` always delegates internally to `evaluateWithTrace(...)`, then records the resulting `CcrsTrace` through the context before returning the selected `StrategyResult` list.

This means trace creation is part of the core evaluation flow by default, not an optional debugging side path.

## Trace History Contract

Each `CcrsContext` implementation must provide:

- `recordCcrsInvocation(CcrsTrace trace)`
- `getLastCcrsInvocation()`
- `getCcrsHistory(int maxCount)`

The in-memory storage implementation is reusable core code in `InMemoryCcrsTraceHistory`. Adapters do not need to invent their own list management. They can delegate to that helper and only decide the lifecycle and scope of the history instance.

The important split is:

- core CCRS is responsible for producing a `CcrsTrace` on every evaluation
- core RDF provides a reusable in-memory trace-history store
- the adapter is responsible for exposing that history through `CcrsContext` and scoping it correctly

## Design Note

In the current Jason integration, each agent gets its own `JasonCcrsContext`, and that context delegates trace storage to `InMemoryCcrsTraceHistory`. The storage code is reusable core infrastructure; the adapter still decides whether that store is per-agent, shared, reset on restart, or replaced with another implementation later.
