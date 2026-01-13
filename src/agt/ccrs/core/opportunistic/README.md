# Opportunistic CCRS Implementation Guide

This module implements **Opportunistic Course Check and Revision Strategies (CCRS)** for agents. It allows agents to automatically detect affordances and dis-affordances within standard RDF perceptions without explicit reasoning overhead.

## 📂 Module Overview

```text
ccrs/
├── core/                                   # AGENT-AGNOSTIC CORE
│   ├── opportunistic/
│   │   ├── OpportunisticCcrs.java          # Core interface for scanning logic.
│   │   ├── OpportunisticResult.java        # DTO for detected patterns (target, type, utility).
│   │   ├── VocabularyMatcher.java          # Runtime engine orchestrating Fast/Slow pattern matching.
│   │   ├── StructuralPatternMatcher.java   # "Fast Path" engine: Matches graph patterns using pure Java.
│   │   ├── CcrsScannerFactory.java         # Factory interface for instantiating scanners.
│   │   ├── ScoringStrategy.java            # Interface for customizable utility calculation.
│   │   └── DefaultScoringStrategy.java     # Standard scoring: saturating normalization, relevance logic.
│   │
│   └── rdf/
│       ├── RdfTriple.java                  # Lightweight, immutable Triple POJO.
│       ├── CcrsVocabulary.java             # "Compiler": Loads Turtle, parses SPARQL, builds optimized indexes.
│       ├── CcrsVocabularyLoader.java       # Utility to load vocabularies from files/URLs.
│       └── CcrsContext.java                # Interface for context-aware validation (future contingency).
│
├── jacamo/                                 # JACAMO PLATFORM ADAPTERS
│   ├── jason/                              # Jason-specific components
│   │   ├── opportunistic/
│   │   │   ├── CcrsAgent.java              # Custom Agent class; intercepts perceptions via BRF override.
│   │   │   ├── CcrsConfiguration.java      # Helper for configuring agent vocabularies.
│   │   │   └── prioritize.java             # Internal action for prioritizing options by CCRS utilities.
│   │   ├── hypermedia/
│   │   │   └── hypermedea/                 # Hypermedea-specific instrumentation
│   │   │       ├── CcrsHttpBinding.java    # SPI-based HTTP operation interceptor.
│   │   │       ├── CcrsHttpOperation.java  # Logging wrapper for HTTP operations.
│   │   │       ├── InteractionLogSink.java # Interface for interaction logging.
│   │   │       ├── JasonInteractionLog.java# Jason implementation of interaction history.
│   │   │       └── ...                     # (See hypermedia/hypermedea/Readme.md)
│   │   ├── JasonRdfAdapter.java            # Converts between Jason Literals and RdfTriples.
│   │   └── TimestampedBeliefBase.java      # Belief base with temporal tracking.
│   │
│   └── jaca/                               # CArtAgO-specific components
│       └── CcrsAgentArch.java              # Intercepts artifact observables; batches triples per cycle.
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
*   **`ScoringStrategy.java`**: Interface separating "what is scored" (priorities, relevance) from "how it is scored" (normalization). Enables custom utility calculation via subclassing.
*   **`DefaultScoringStrategy.java`**: Implements standard CCRS scoring with saturating normalization `x/(x+1)`. Handles three relevance modes: simple patterns (1.0), structural without variable (1.0), structural with variable (normalize or 0.0 if absent).
*   **`RdfTriple.java`**: A minimal data carrier to decouple the Core from specific libraries like Jason or Jena.
*   **`CcrsVocabulary.java`**: The "Brain". It reads Turtle files at startup. It analyzes SPARQL patterns: if simple, it compiles them to Java objects (Fast Path); if complex (FILTER/UNION), it keeps them as Jena Queries (Slow Path). Validates priorities ∈ [-1, 1] at load time.

### JaCaMo Platform Adapters
**Jason Adapters** (`jacamo/jason/opportunistic`):
*   **`CcrsAgent.java`**: Extends standard `Agent`. Intercepts incoming perceptions in the Belief Revision Function (`brf`). Detected opportunities are added as beliefs immediately.
*   **`CcrsConfiguration.java`**: Helper for configuring agent vocabularies and scanning strategies.
*   **`prioritize.java`**: Internal action (`ccrs.prioritize/2`) that reorders hypermedia options based on CCRS utilities. Accesses the belief base to match options with `ccrs(Target, PatternType, Utility)` beliefs, preserving complete literals with annotations for rich decision-making.

**Jason Utilities** (`jacamo/jason`):
*   **`JasonRdfAdapter.java`**: Bridges the gap between Jason's `rdf(S,P,O)` literals and the Core's `RdfTriple`.
*   **`TimestampedBeliefBase.java`**: Belief base implementation with temporal tracking capabilities.

**Hypermedea Instrumentation** (`jacamo/jason/hypermedia/hypermedea`):
*   Provides transparent HTTP interaction logging via Java SPI without modifying Hypermedea artifact.
*   **`CcrsHttpBinding.java`**: Protocol binding discovered via SPI; intercepts HTTP operation creation.
*   **`JasonInteractionLog.java`**: Maintains bounded interaction history, exposing `CcrsContext` for contingency strategies.
*   See `hypermedia/hypermedea/Readme.md` for architecture details.
*   **Note:** Artifact-specific implementation. Other artifacts require similar instrumentation.

**CArtAgO Adapters** (`jacamo/jaca`):
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
*   **Priorities:** Patterns include `ccrs:priority` (range [-1, 1]) to determine matching order and utility strength.
*   **Relevance Variables:** Structural patterns can extract runtime values (e.g., pheromone strength) via `ccrs:extractedRelevanceVariable` for dynamic utility calculation.
*   **Custom Scoring:** Implement `ScoringStrategy` to override normalization or utility formulas. Inject via `VocabularyMatcher.Factory`.
*   **IDs:** Anonymous structures are identified by URI or Label from the vocabulary.

### 4. Zero-Touch Integration
*   **Hypermedea:** The architecture works without modifying the Hypermedea artifact. It purely observes the standard `rdf/3` properties flowing into the agent.
*   **Visibility:** We use `super` calls and internal helpers to bypass visibility restrictions in the sealed CArtAgO library.

### 5. Action Prioritization
The `ccrs.prioritize/2` internal action bridges perception and deliberation:
*   **Categorization Logic:** Options are grouped into three categories:
    *   **Positive utilities** (≥0): Sorted descending (highest first) — affordances
    *   **Unmatched**: Kept in original order (middle) — neutral options
    *   **Negative utilities** (<0): Sorted descending (least negative first) — dis-affordances deprioritized
*   **Usage Pattern:** Agents call `ccrs.prioritize(OptionsIn, OptionsOut)` in plans after receiving hypermedia options, enabling utility-guided navigation without explicit reasoning overhead.

---
Here is the visual representation of the execution flow, highlighting how the **CCRS Architecture** intercepts the standard CArtAgO flow to inject semantic interpretations alongside raw perceptions.

```text
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
 │  CcrsAgentArch (ccrs.jacamo.jaca)             │ ◄── INTERCEPTION POINT
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
        │                     │  JasonRdfAdapter                │
        │                     │  (ccrs.jacamo.jason)            │
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
 │  +rdf(S,P,O)[source(H)]       +ccrs(Target,Type,Util)[Annots]│
 │  (Raw Perception)             (Derived Opportunity)          │
 └──────────────────────────────────────────────────────────────┘
```

### Key Flow Characteristics:

1.  **Non-Blocking Interception:** `CcrsAgentArch` allows the standard flow (Path 3A) to proceed immediately, ensuring the agent always receives the raw `rdf` perception.
2.  **Architecture-Level Batching:** Step 3B collects individual RDF triples into a batch. This is critical for **Structural Pattern Matching** (e.g., detecting a Stigmergy marker composed of 4 distinct triples).
3.  **Core Isolation:** The actual intelligence logic happens in Step 5 within `ccrs.core`. This layer knows nothing about Jason or CArtAgO, operating purely on `RdfTriple` objects.
4.  **Same-Cycle Awareness:** By using `runAtBeginOfNextCycle` (Step 4), the system flushes the batch and injects the `ccrs` belief *before* the agent begins its reasoning/plan selection phase for the current event. The agent "wakes up" seeing both the raw RDF and the detected Opportunity simultaneously.