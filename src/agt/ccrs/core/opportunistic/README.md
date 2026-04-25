# Opportunistic CCRS Implementation Guide

This module implements **Opportunistic Course Check and Revision Strategies (CCRS)** over RDF perceptions. The core is agent-agnostic: it receives `RdfTriple` values, detects known CCRS patterns, can discover new CCRS vocabulary embedded in incoming RDF batches, and returns `OpportunisticResult` objects for platform adapters to inject into an agent's belief base.

## Module Overview

```text
ccrs/
├── core/                                   # AGENT-AGNOSTIC CORE
│   ├── opportunistic/
│   │   ├── OpportunisticCcrs.java          # Scanner contract: scan one triple or a batch.
│   │   ├── OpportunisticResult.java        # DTO for detected target, type, pattern id, utility, metadata.
│   │   ├── VocabularyMatcher.java          # Runtime scanner: discovers vocabulary, matches patterns, scores results.
│   │   ├── StructuralPatternMatcher.java   # Fast-path matcher for basic graph patterns.
│   │   ├── CcrsScannerFactory.java         # Factory interface for scanner creation.
│   │   ├── ScoringStrategy.java            # Utility scoring contract.
│   │   └── DefaultScoringStrategy.java     # Default priority/relevance scoring.
│   │
│   └── rdf/
│       ├── RdfTriple.java                  # Lightweight RDF triple representation.
│       ├── CcrsVocabulary.java             # Vocabulary model, constants, compiler, runtime integration.
│       ├── CcrsVocabularyLoader.java       # Loads initial vocabulary from classpath, file, or URL sources.
│       └── CcrsContext.java                # Context interface used by contingency CCRS.
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

## 📄 File Descriptions

### Core Module (Agent-Agnostic)

* **[OpportunisticCcrs.java](../../../../agt/ccrs/core/opportunistic/OpportunisticCcrs.java)**: Defines the scanner contract. `scan(triple)` handles one RDF triple; `scanAll(triples)` handles a batch and enables structural matching.
* **[VocabularyMatcher.java](../../../../agt/ccrs/core/opportunistic/VocabularyMatcher.java)**: Main runtime component. It first discovers embedded CCRS vocabulary definitions in incoming batches, asks the vocabulary to integrate them, then delegates simple patterns to O(1)-style lookups and structural patterns to the fast or slow path engines.
* **[StructuralPatternMatcher.java](../../../../agt/ccrs/core/opportunistic/StructuralPatternMatcher.java)**: High-performance recursive matcher for compiled basic graph patterns. Avoids Jena overhead for simple structural patterns.
* **[ScoringStrategy.java](../../../../agt/ccrs/core/opportunistic/ScoringStrategy.java)**: Interface separating what is scored (priority and relevance) from how utility is calculated. Enables custom utility calculation via subclassing.
* **[DefaultScoringStrategy.java](../../../../agt/ccrs/core/opportunistic/DefaultScoringStrategy.java)**: Standard CCRS scoring with saturating normalization `x/(x+1)`. Handles simple patterns, structural patterns without relevance variables, and structural patterns with extracted relevance variables.
* **[RdfTriple.java](../../../../agt/ccrs/core/rdf/RdfTriple.java)**: Minimal immutable RDF triple representation. Keeps the core independent from Jason, CArtAgO, and Jena-specific runtime objects.
* **[CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java)**: Vocabulary model and compiler. Centralizes CCRS namespace constants, recognized definition predicates, compiled simple indexes, structural pattern compilation, validation, and runtime vocabulary integration.
* **[CcrsVocabularyLoader.java](../../../../agt/ccrs/core/rdf/CcrsVocabularyLoader.java)**: Loads initial vocabulary sources from classpath resources, file paths, or HTTP(S) URLs. `loadDefault()` loads `classpath:ccrs-vocabulary.ttl`.

### JaCaMo Platform Adapters

**Jason Adapters** (`jacamo/jason/opportunistic`):

* **[CcrsAgent.java](../../../../agt/ccrs/jacamo/jason/opportunistic/CcrsAgent.java)**: Extends standard Jason `Agent`. Intercepts incoming perceptions in the belief revision function (BRF) and adds detected opportunities as beliefs.
* **[CcrsConfiguration.java](../../../../agt/ccrs/jacamo/jason/opportunistic/CcrsConfiguration.java)**: Java-side helper for configuring scanners and startup vocabularies.
* **[prioritize.java](../../../../agt/ccrs/jacamo/jason/opportunistic/prioritize.java)**: Internal action (`ccrs.prioritize/2`) that reorders hypermedia options (candidate links) based on CCRS utilities. It accesses the belief base to match options with `ccrs(Target, PatternType, Utility)` beliefs and preserves rich annotations in the detailed output for rich decision-making.

**Jason Utilities** (`jacamo/jason`):

* **[JasonRdfAdapter.java](../../../../agt/ccrs/jacamo/jason/JasonRdfAdapter.java)**: Converts Jason `rdf(S,P,O)` literals to [RdfTriple.java](../../../../agt/ccrs/core/rdf/RdfTriple.java) and converts [OpportunisticResult.java](../../../../agt/ccrs/core/opportunistic/OpportunisticResult.java) to `ccrs/3` beliefs.
* **[TimestampedBeliefBase.java](../../../../agt/ccrs/jacamo/jason/TimestampedBeliefBase.java)**: Belief base implementation with temporal tracking.

**Hypermedea Instrumentation** (`jacamo/jason/hypermedia/hypermedea`):

* Provides transparent HTTP interaction logging via Java SPI without modifying the Hypermedea artifact.
* **[CcrsHttpBinding.java](../../../../agt/ccrs/jacamo/jason/hypermedia/hypermedea/CcrsHttpBinding.java)**: Protocol binding discovered via SPI; intercepts HTTP operation creation.
* **[CcrsHttpOperation.java](../../../../agt/ccrs/jacamo/jason/hypermedia/hypermedea/CcrsHttpOperation.java)**: Logging wrapper for HTTP operations.
* **[InteractionLogSink.java](../../../../agt/ccrs/jacamo/jason/hypermedia/hypermedea/InteractionLogSink.java)**: Interface for interaction logging.
* **[JasonInteractionLog.java](../../../../agt/ccrs/jacamo/jason/hypermedia/hypermedea/JasonInteractionLog.java)**: Jason implementation of bounded interaction history, exposing context for contingency strategies.
* See **[Readme.md](../../../../agt/ccrs/jacamo/jason/hypermedia/hypermedea/Readme.md)** for Hypermedea-specific architecture details.
* **Note:** This instrumentation is artifact-specific. Other artifacts require similar instrumentation.

**CArtAgO Adapters** (`jacamo/jaca`):

* **[CcrsAgentArch.java](../../../../agt/ccrs/jacamo/jaca/CcrsAgentArch.java)**: Extends `CAgentArch`. Intercepts observable properties, forwards the standard path with `super`, buffers RDF triples per perception cycle, calls `scanAll(...)`, and injects derived `ccrs/3` beliefs.

---

## Vocabulary Handling and Runtime Integration

### Initial Loading

The default vocabulary is loaded by [CcrsVocabularyLoader.java](../../../../agt/ccrs/core/rdf/CcrsVocabularyLoader.java):

```java
CcrsVocabularyLoader.loadDefault()
```

This resolves `classpath:ccrs-vocabulary.ttl`, reads it as Turtle into a Jena `Model`, and constructs [CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java). `load(String... sources)` can also merge multiple startup sources before compilation. Sources may be classpath resources, file paths, or HTTP(S) URLs.

Runtime recompilation does not reread [ccrs-vocabulary.ttl](../../../../resources/ccrs-vocabulary.ttl) from disk or classpath. The initially loaded Jena model remains in memory and is recompiled together with discovered additions.

### Vocabulary Structure

The active CCRS namespace is:

```ttl
@prefix ccrs: <https://example.org/ccrs#> .
```

The core recognizes two pattern classes:

* `ccrs:SimplePattern`: matches one RDF triple by subject, predicate, or object.
* `ccrs:StructuralPattern`: matches a batch of RDF triples using a SPARQL `SELECT` pattern.

Recognized definition predicates are centralized in [CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java):

* `rdf:type`
* `ccrs:patternType`
* `ccrs:matchesPosition`
* `ccrs:priority`
* `ccrs:sparqlPattern`
* `ccrs:extractTargetVariable`
* `ccrs:extractedRelevanceVariable`
* `rdfs:label`
* `rdfs:comment`

### Compilation

When [CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java) is constructed or recompiled:

* **Simple Patterns** are indexed by `patternType + matchesPosition` for fast lookup.
* **Simple Structural Patterns** are parsed from `ccrs:sparqlPattern` and compiled to Java constraints handled by [StructuralPatternMatcher.java](../../../../agt/ccrs/core/opportunistic/StructuralPatternMatcher.java).
* **Complex Structural Patterns** with unsupported fast-path constructs fall back to Jena `Query` execution.
* **Priorities** are validated to be in `[-1, 1]`.
* **Structural Patterns** are sorted by descending priority.

### Runtime Discovery

Runtime discovery is implemented inside [VocabularyMatcher.java](../../../../agt/ccrs/core/opportunistic/VocabularyMatcher.java), before normal matching in `scanAll(...)`. There is no agent-facing load action.

For each incoming RDF batch:

1. The scanner searches for subjects declared as `ccrs:SimplePattern` or `ccrs:StructuralPattern`.
2. For each discovered pattern subject, it collects same-subject triples using only recognized definition predicates.
3. It validates required fields:
   * every pattern needs `ccrs:patternType`;
   * every pattern needs valid `ccrs:priority`;
   * simple patterns need `ccrs:matchesPosition` as `subject`, `predicate`, or `object`;
   * structural patterns need `ccrs:sparqlPattern`.
4. Already known pattern IDs are ignored.
5. Valid definitions are converted to a small Jena `Model`.
6. [CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java) merges the new triples, compiles the merged candidate model first, and only updates the active vocabulary if compilation succeeds.

This allows a GET response to define a new pattern and contain matching RDF data in the same payload. The new pattern is integrated before that same batch is matched.

Example discovered simple pattern:

```ttl
<https://kaefer3000.github.io/2021-02-dagstuhl/vocab#orange>
    a ccrs:SimplePattern ;
    ccrs:patternType "signifier" ;
    ccrs:matchesPosition "predicate" ;
    ccrs:priority 0.8 ;
    rdfs:label "Orange Signifier" ;
    rdfs:comment "Visual signifier indicating redirect due to broken link ahead" .
```

### Matching Process

`scanAll(...)` in [VocabularyMatcher.java](../../../../agt/ccrs/core/opportunistic/VocabularyMatcher.java) runs in this order:

1. Discover and integrate embedded CCRS vocabulary definitions.
2. Match all compiled structural patterns against the full batch.
3. Match simple patterns against each triple.
4. Score each result through [ScoringStrategy.java](../../../../agt/ccrs/core/opportunistic/ScoringStrategy.java).
5. Return a list of [OpportunisticResult.java](../../../../agt/ccrs/core/opportunistic/OpportunisticResult.java).

Simple pattern matching uses exact URI equality. If a pattern is defined for `https://kaefer3000.github.io/2021-02-dagstuhl/vocab#orange`, the incoming predicate must use that exact URI.

### Output

The core returns [OpportunisticResult.java](../../../../agt/ccrs/core/opportunistic/OpportunisticResult.java) values containing:

* detected `type`, such as `signifier` or `stigmergy`;
* matched `target`;
* source `patternId`;
* computed `utility`;
* metadata, including matched variables for structural patterns and adapter-provided context.

Platform adapters convert these results into agent-facing beliefs. In the JaCaMo path, [JasonRdfAdapter.java](../../../../agt/ccrs/jacamo/jason/JasonRdfAdapter.java) creates:

```prolog
ccrs(Target, PatternType, Utility)[source(Source), pattern_id(PatternId), ...]
```

---

## ⚡ Key Implementation Considerations

### 1. Hybrid Compilation Strategy

To keep RDF processing out of the hot path where possible:

* **Simple Patterns:** Compiled to hash-backed lookups. O(1)-style matching.
* **Simple Structures:** Compiled to Java object constraints. O(N)-style matching over the batch.
* **Complex Structures:** Fallback to standard Jena SPARQL engine. O(Query).

### 2. Batching and Temporal Coupling

Structural patterns, such as stigmergy markers, span multiple triples.

* **Jason:** [CcrsAgent.java](../../../../agt/ccrs/jacamo/jason/opportunistic/CcrsAgent.java) scans RDF percepts in the BRF path.
* **CArtAgO:** [CcrsAgentArch.java](../../../../agt/ccrs/jacamo/jaca/CcrsAgentArch.java) uses a short-lived buffer and schedules a flush at the `BeginOfNextCycle`, ensuring RDF properties from the current environmental update are processed together before reasoning starts.

### 3. Extensibility

* **No hardcoded domain patterns:** Developers define patterns in TTL using the CCRS vocabulary.
* **Runtime discovery:** Incoming RDF batches may contribute new `ccrs:SimplePattern` or `ccrs:StructuralPattern` definitions.
* **Priorities:** Patterns include `ccrs:priority` in `[-1, 1]` to determine matching order and utility strength.
* **Relevance Variables:** Structural patterns can extract runtime values, such as pheromone strength, through `ccrs:extractedRelevanceVariable`.
* **Custom Scoring:** Implement [ScoringStrategy.java](../../../../agt/ccrs/core/opportunistic/ScoringStrategy.java) and inject it through `VocabularyMatcher.Factory`.
* **IDs:** URI resources are used directly; anonymous structures are identified by label or generated id in [CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java).

### 4. Zero-Touch Integration

* **Hypermedea:** The architecture works without modifying the Hypermedea artifact. It observes standard `rdf/3` properties flowing into the agent.
* **CArtAgO visibility:** [CcrsAgentArch.java](../../../../agt/ccrs/jacamo/jaca/CcrsAgentArch.java) uses `super` calls and internal helpers to preserve standard CArtAgO behavior while adding CCRS interpretation.

### 5. Action Prioritization

The `ccrs.jacamo.jason.opportunistic.prioritize` internal action bridges perception and deliberation:

* **Positive utilities** (`>= 0`): sorted descending, highest first.
* **Unmatched options**: kept in original order.
* **Negative utilities** (`< 0`): sorted descending, so less negative options are preferred.

Agents call `ccrs.prioritize(OptionsIn, PlainCcrsList, DetailedCcrsList)` after receiving hypermedia options, enabling utility-guided navigation without explicit reasoning overhead.

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
        │                               │    a. Discover embedded CCRS vocabulary
        │                               │    b. Integrate and recompile vocabulary
        │                               │    c. Match structural and simple patterns
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

### Key Flow Characteristics

1. **Non-Blocking Interception:** [CcrsAgentArch.java](../../../../agt/ccrs/jacamo/jaca/CcrsAgentArch.java) allows the standard flow to proceed immediately, so the agent still receives the raw `rdf/3` perceptions.
2. **Architecture-Level Batching:** Step 3B collects individual RDF triples into a batch. This is critical for structural pattern matching and runtime vocabulary discovery.
3. **Core Isolation:** The interpretation logic happens in [VocabularyMatcher.java](../../../../agt/ccrs/core/opportunistic/VocabularyMatcher.java) and [CcrsVocabulary.java](../../../../agt/ccrs/core/rdf/CcrsVocabulary.java). This layer knows nothing about Jason or CArtAgO.
4. **Same-Cycle Awareness:** `runAtBeginOfNextCycle` lets the architecture flush the batch and inject `ccrs/3` beliefs before the agent begins reasoning over the current perceptual update. The agent "wakes up" seeing both the raw RDF and the detected Opportunity simultaneously.
