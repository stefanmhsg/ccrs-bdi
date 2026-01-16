
# Hypermedia Interaction Logging for CCRS

This package provides a transparent instrumentation layer for Hypermedea-based hypermedia agents, enabling Contingency-CCRS strategies to reason over full HTTP interactions without modifying the core Hypermedea library.

The design relies on a shared interaction log and a proxy-based artifact wrapper to capture requests and responses as first-class domain objects, attributed correctly to each agent even in multi-threaded environments.

---

## Purpose

Hypermedia agents act exclusively through HTTP interactions.  
For CCRS, these interactions are the atomic unit of history used to diagnose failures (e.g., 404s, loops).

This module captures:
- Outgoing HTTP requests (method, URI, headers)
- Incoming responses (status, payload)
- Timestamps
- Agent Attribution (who made the request)

---

## Architecture Overview

```
Agent (Jason)
     ↓
CcrsHypermedeaArtifact (Wrapper)
     ↓
[Proxy Sink] → (ThreadLocal) → [CcrsGlobalRegistry]
     ↓
HypermedeaArtifact (Superclass)
     ↓
CcrsHttpBinding (Java SPI) reads ThreadLocal
     ↓
CcrsHttpOperation (Captures Sink)
     ↓
[Shared Log] (JasonInteractionLog) ← (Writes via Proxy)
     ↓
CcrsContext (Reads for specific Agent)
```

---

## Components

### CcrsGlobalRegistry
A static registry that holds two key components:
1. **Shared Log Instance:** A singleton `JasonInteractionLog` accessible by all artifacts (writers) and agents (readers).
2. **ThreadLocal Sink:** A `ThreadLocal` storage used to pass a logging context (Proxy) from the artifact layer to the SPI layer.

### JasonInteractionLog
A centralized, thread-safe log implementation.
- **Partitioned Storage:** Stores interaction history separated by Agent Name (`Map<AgentName, Deque<Interaction>>`).
- **Shared Access:** Allows multiple agents to write to the same log instance concurrently without mixing data.
- **Unified Querying:** Allows CCRS strategies to query "my recent history" by simply passing the current agent's name.

### CcrsHypermedeaArtifact
A wrapper around the standard `HypermedeaArtifact`.
- **Interception:** Overrides all `@OPERATION` methods (get, post, put, etc.).
- **Context Injection:** Before delegating to the superclass, it creates a **Proxy Sink** bound to the current agent's name (`getCurrentOpAgentId()`) and registers it in the `CcrsGlobalRegistry`.
- **Transparency:** The agent plans use this artifact exactly like the standard one (`h.get(...)`), but side-effects are automatically logged.

### CcrsHttpBinding (SPI)
A standard Java SPI implementation of Hypermedea's `ProtocolBinding`.
- **Discovery:** Registered via `META-INF/services/org.hypermedea.op.ProtocolBinding`.
- **Logic:** It checks `CcrsGlobalRegistry.getSink()` (the ThreadLocal). If a sink is present (meaning the call came from our wrapper artifact), it returns an instrumented `CcrsHttpOperation`. Otherwise, it falls back to standard behavior.

### CcrsHttpOperation
Subclass of Hypermedea's `HttpOperation`.
- **State:** Captures the Proxy Sink from the registry during creation.
- **Hooks:** Overrides `sendRequest`, `onResponse`, and `onError` to report events back to the shared log via the captured proxy.

### JasonCcrsContext
The bridge between the Jason Agent and the Shared Log.
- Initialized by `CcrsAgentArch` at startup.
- Automatically connects to the `SHARED_LOG` in the registry.
- When queried (e.g., via `evaluate`), it retrieves history specifically for the agent defined in its constructor.

---

## Integration

### 1. Artifact Setup
In your `.jcm` project file, use the custom artifact instead of the standard one:

```
artifact h: ccrs.jacamo.jason.hypermedia.hypermedea.CcrsHypermedeaArtifact()
```

### 2. Agent Architecture
Ensure your agents use `CcrsAgentArch` in the project file. This initializes the context at startup:

```
agent bob : ccrs_agent.asl {
    ag-arch: ccrs.jacamo.jaca.CcrsAgentArch
}
```

### 3. Usage in Plans
No changes required in AgentSpeak code!

---

## Design Rationale

- **Decoupling:** The Agent logic (Jason) doesn't need to know about HTTP bindings. The HTTP logic doesn't need to know about Jason. The `CcrsGlobalRegistry` bridges them via ThreadLocal.
- **Thread Safety:** `ThreadLocal` ensures that concurrent requests from different agents do not overwrite each other's logging context.
- **Isolation:** The shared log partitions data by agent name, ensuring that Agent A never sees Agent B's interaction history during contingency reasoning.
- **Wrapper + SPI:** We use `CcrsHypermedeaArtifact` (Wrapper) to set the context, and a standard Hypermedea SPI Binding (`CcrsHttpBinding`) to execute the logging hooks. This works around the restricted visibility of standard Hypermedea internals.

---
```