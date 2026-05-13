# CCRS RDF Context

This package contains the RDF-facing context contract used by Contingency CCRS.

## Purpose

`CcrsContext` is the bridge between core CCRS logic and a concrete agent/runtime adapter.

Core CCRS uses it for:

- RDF lookup via `query(...)` and `contains(...)`
- bounded raw RDF memory access via `getMemoryTriples(int maxCount)`
- interaction history access
- current-resource lookup
- CCRS trace history access

The core evaluator does not know how a Jason agent, another BDI runtime, or a different platform exposes beliefs and interaction history. That integration still belongs to the adapter implementation of `CcrsContext`.

## RDF Context Shapes

`CcrsContext` exposes two different RDF access patterns on purpose:

- `getNeighborhood(resource, maxOutgoing, maxIncoming)` returns the local graph shape around one resource. It is for questions such as "where am I and what links touch this resource?"
- `getMemoryTriples(maxCount)` returns a broader bounded snapshot of the RDF triples currently known by the context. It is for memory-style access, for example when an LLM prediction strategy needs up to 1000 triples instead of only the default neighborhood limits.

Do not use neighborhood as a substitute for memory access. The default neighborhood limits are intentionally small so local link analysis stays cheap and predictable. Strategies that need broader graph evidence should call `getMemoryTriples(...)` and apply their own prompt or processing limits.

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
