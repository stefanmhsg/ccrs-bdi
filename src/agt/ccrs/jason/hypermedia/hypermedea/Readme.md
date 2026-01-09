# Hypermedia Interaction Logging for CCRS

This package provides a transparent instrumentation layer for Hypermedea-based hypermedia agents, enabling Contingency-CCRS strategies to reason over full HTTP interactions without modifying the Hypermedea library itself.

The design relies on Java SPI, subclassing, and a shared interaction log to capture requests and responses as first-class domain objects.

---

## Purpose

Hypermedia agents act exclusively through HTTP interactions.  
For CCRS, these interactions are the atomic unit of history.

This module captures:
- outgoing HTTP requests
- incoming responses
- timestamps and logical sources
- structured RDF payloads

and exposes them via `CcrsContext` for strategy evaluation.

---

## Architecture Overview

```
AgentSpeak Plan
     ↓
HypermedeaArtifact
     ↓
ProtocolBindings (SPI)
     ↓
CcrsHttpBinding
     ↓
CcrsHttpOperation
     ↓
InteractionLogSink
     ↓
JasonInteractionLog
     ↓
CcrsContext
```

---

## Components

### CcrsGlobalRegistry
A static, thread-safe registry used to expose the active `InteractionLogSink` to SPI-instantiated protocol bindings.

Reason:
SPI bindings are created by `ServiceLoader` and cannot receive constructor parameters.

---

### CcrsHttpBinding
A custom `ProtocolBinding` discovered via Java SPI.

Responsibilities:
- claims support for `http` and `https`
- intercepts operation creation
- returns `CcrsHttpOperation` when a sink is installed
- falls back to standard Hypermedea behavior otherwise

This enables transparent interception without subclassing `HypermedeaArtifact`.

---

### CcrsHttpOperation
A subclass of Hypermedea’s `HttpOperation`.

Hooks:
- `sendRequest()` → logs request metadata
- `onResponse()` → logs response payload and timestamp
- `onError()` → logs failed interactions

Important:
This class is instantiated directly, avoiding double HTTP client creation.

---

### InteractionBuilder
Internal mutable builder used to assemble an `Interaction` across request and response callbacks.

Responsibilities:
- extract method, headers, payload
- convert response payload to `RdfTriple`
- assign timestamps
- build immutable `Interaction` DTO

Not exposed outside the logging layer.

---

### InteractionLogSink
A small interface defining lifecycle hooks:

- `onRequest(Operation, timestamp)`
- `onResponse(Operation, Response, timestamp)`
- `onError(Operation, timestamp)`

This decouples logging from storage and platform specifics.

---

### JasonInteractionLog
Jason-specific implementation of `InteractionLogSink`.

Responsibilities:
- maintain a bounded, ordered interaction list
- ensure thread safety
- expose read-only accessors:
  - `getRecentInteractions(int)`
  - `getLastInteraction()`

This is the single source of truth for interaction history.

---

## Integration

### SPI Registration
The binding is registered via:

```
META-INF/services/org.hypermedea.op.ProtocolBinding
```

Containing:
```
ccrs.jason.hypermedia.hypermedea.CcrsHttpBinding
```

---

### Agent Architecture Setup

The interaction log is installed during agent startup:

```java
@Override
public void init() {
    super.init();
    JasonInteractionLog log = new JasonInteractionLog();
    CcrsGlobalRegistry.setSink(log);
}
```

---

## Design Rationale

- No modification of Hypermedea library
- No belief base pollution
- No duplication of response data
- Interaction is the single historical unit
- Platform-agnostic CCRS reasoning

This keeps CCRS strategies independent of Jason, HTTP details, and artifact internals.

---

## Intended Usage

CCRS strategies query interactions via `CcrsContext`:

- detect failed requests
- analyze missing affordances
- reason over temporal interaction patterns
- support backtracking and retry strategies

---
