# Opportunistic CCRS Implementation Guide

This module implements **Opportunistic Course Check and Revision Strategies (CCRS)** for agents. It allows agents to automatically detect affordances and dis-affordances within standard RDF perceptions without explicit reasoning overhead.

## 📂 Module Overview

```text
ccrs/
├── core/                                   # AGENT-AGNOSTIC CORE
│   ├── opportunistic/
│   │   ├── OpportunisticCcrs.java          # Core interface for scanning logic.
│   │   ├── OpportunisticResult.java        # DTO for detected patterns (type, subject, value).
│   │   ├── VocabularyMatcher.java          # Runtime engine orchestrating Fast/Slow pattern matching.
│   │   ├── StructuralPatternMatcher.java   # "Fast Path" engine: Matches graph patterns using pure Java.
│   │   └── CcrsScannerFactory.java         # Factory interface for instantiating scanners.
│   │
│   └── rdf/
│       ├── RdfTriple.java                  # Lightweight, immutable Triple POJO.
│       ├── CcrsVocabulary.java             # "Compiler": Loads Turtle, parses SPARQL, builds optimized indexes.
│       ├── CcrsVocabularyLoader.java       # Utility to load vocabularies from files/URLs.
│       └── CcrsContext.java                # Interface for context-aware validation (future contingency).
│
├── jason/                                  # JASON PLATFORM ADAPTERS
│   ├── CcrsAgent.java                      # Custom Agent class; intercepts perceptions via BRF override.
│   ├── JasonRdfAdapter.java                # Converts between Jason Literals and RdfTriples.
│   ├── CcrsConfiguration.java              # Helper for configuring agent vocabularies.
│   └── JasonCcrsContext.java               # Context implementation using Jason's Belief Base.
│
├── jaca/                                   # CArtAgO ARCHITECTURE ADAPTERS
│   └── CcrsAgentArch.java                  # Intercepts artifact observables; batches triples per cycle.
│
└── resources/
    └── ccrs-vocabulary.ttl                 # Default vocabulary definitions and SPARQL patterns.
```

---

## 📄 File Descriptions

### Core Module (Agent-Agnostic)
*   **`OpportunisticCcrs.java`**: Defines the contract `scan(triple)` and `scanAll(triples)`. Completely stateless.
*   **`VocabularyMatcher.java`**: The main runtime component. It delegates simple patterns to O(1) lookups and structural patterns to either the Fast or Slow path engines.
*   **`StructuralPatternMatcher.java`**: A high-performance, recursion-based matcher for basic graph patterns. Avoids Jena overhead entirely.
*   **`RdfTriple.java`**: A minimal data carrier to decouple the Core from specific libraries like Jason or Jena.
*   **`CcrsVocabulary.java`**: The "Brain". It reads Turtle files at startup. It analyzes SPARQL patterns: if simple, it compiles them to Java objects (Fast Path); if complex (FILTER/UNION), it keeps them as Jena Queries (Slow Path).

### Jason Adapters
*   **`CcrsAgent.java`**: Extends standard `Agent`. Intercepts incoming perceptions in the Belief Revision Function (`brf`). Detected opportunities are added as beliefs immediately.
*   **`JasonRdfAdapter.java`**: Bridges the gap between Jason's `rdf(S,P,O)` literals and the Core's `RdfTriple`.

### JaCa Adapters
*   **`CcrsAgentArch.java`**: Extends `CAgentArch`. Intercepts `addObsPropertiesBel`. Since artifacts emit properties sequentially, this buffers them and processes them as a batch at the end of the perception cycle to allow structural matching.

---

## ⚡ Key Implementation Considerations

### 1. Hybrid Compilation Strategy
To ensure <10μs latency per cycle, we avoid heavy RDF engines in the "hot path":
*   **Simple Patterns:** Compiled to `HashSet` lookups. **O(1)**.
*   **Simple Structures:** Compiled to Java Object Graphs. **O(N)**.
*   **Complex Structures:** Fallback to standard Jena SPARQL engine. **O(Query)**.

### 2. Batching & Temporal Coupling
Structural patterns (e.g., Stigmergy Markers) span multiple triples.
*   **Jason:** `CcrsAgent` treats a single Percept (GET response) as a complete batch.
*   **CArtAgO:** `CcrsAgentArch` uses a short-lived buffer. It schedules a "flush" operation at the `BeginOfNextCycle`, ensuring all properties from the current environmental update are processed together before reasoning starts.

### 3. Extensibility
*   **No Hardcoding:** Developers define patterns solely in `.ttl` files.
*   **Priorities:** Patterns include `ccrs:priority` to determine matching order.
*   **IDs:** Anonymous structures are identified by URI or Label from the vocabulary.

### 4. Zero-Touch Integration
*   **Hypermedea:** The architecture works without modifying the Hypermedea artifact. It purely observes the standard `rdf/3` properties flowing into the agent.
*   **Visibility:** We use `super` calls and internal helpers to bypass visibility restrictions in the sealed CArtAgO library.

---
Here is the visual representation of the execution flow, highlighting how the **CCRS Architecture** intercepts the standard CArtAgO flow to inject semantic interpretations alongside raw perceptions.

```mermaid
graph_flow
 ┌─────────────────────────────┐
 │  Environment (Artifact)     │
 └─────────────┬───────────────┘
               │ 1. commitObsStateChanges()
               │    (e.g., Hypermedea updates RDF state)
               ▼
 ┌─────────────────────────────┐
 │  CArtAgO Infrastructure     │
 └─────────────┬───────────────┘
               │ 2. notifyObsEvent()
               ▼
 ┌───────────────────────────────────────────────┐
 │  CcrsAgentArch (ccrs.jaca)                    │ ◄── INTERCEPTION POINT
 │  (Extends CAgentArch)                         │
 └──────┬───────────────────────────────┬────────┘
        │                               │
        │ 3A. STANDARD PATH             │ 3B. CCRS PATH (Opportunistic)
        │ (via super.add...)            │
        │                               │ a. Convert ObsProperty → Literal
        ▼                               │ b. Check if isRdfObservable()
 ┌──────────────────────┐               │ c. Buffer into 'perceptionBatch'
 │  CAgentArch (Legacy) │               │    (Groups triples by Source URI)
 └──────┬───────────────┘               │
        │                               │ 4. FLUSH (End of Perception Phase)
        │ obsPropToLiteral()            │    getTS().runAtBeginOfNextCycle()
        │                               │
        │                               ▼
        │                     ┌─────────────────────────────────┐
        │                     │  VocabularyMatcher (ccrs.core)  │ ◄── AGENT AGNOSTIC CORE
        │                     └─────────┬───────────────────────┘
        │                               │ 5. scanAll(Batch)
        │                               │    (Hybrid: HashSets + Java Graph Matcher)
        │                               │
        │                               │ Returns List<OpportunisticResult>
        │                               ▼
        │                     ┌─────────────────────────────────┐
        │                     │  JasonRdfAdapter (ccrs.jason)   │
        │                     └─────────┬───────────────────────┘
        │                               │ 6. createCcrsBelief()
        │                               │    (Result → Literal)
        │                               ▼
        │                     ┌─────────────────────────────────┐
        │                     │  Agent.getBB().add()            │ ◄── DIRECT INJECTION
        │                     └─────────┬───────────────────────┘
        │                               │
        ▼                               ▼
 ┌──────────────────────────────────────────────────────────────┐
 │  Agent Belief Base (Mental Model)                            │
 │                                                              │
 │  +rdf(S,P,O)[source(H)]        +ccrs(Sub,Val)[type(stig)]    │
 │  (Raw Perception)              (Derived Opportunity)         │
 └──────────────────────────────────────────────────────────────┘
```

### Key Flow Characteristics:

1.  **Non-Blocking Interception:** `CcrsAgentArch` allows the standard flow (Path 3A) to proceed immediately, ensuring the agent always receives the raw `rdf` perception.
2.  **Architecture-Level Batching:** Step 3B collects individual RDF triples into a batch. This is critical for **Structural Pattern Matching** (e.g., detecting a Stigmergy marker composed of 4 distinct triples).
3.  **Core Isolation:** The actual intelligence logic happens in Step 5 within `ccrs.core`. This layer knows nothing about Jason or CArtAgO, operating purely on `RdfTriple` objects.
4.  **Same-Cycle Awareness:** By using `runAtBeginOfNextCycle` (Step 4), the system flushes the batch and injects the `ccrs` belief *before* the agent begins its reasoning/plan selection phase for the current event. The agent "wakes up" seeing both the raw RDF and the detected Opportunity simultaneously.