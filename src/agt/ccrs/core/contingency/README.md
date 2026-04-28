# Contingency CCRS Implementation Guide

This module implements **Contingency Course Check and Revision Strategies (CCRS)** for agents. It provides a structured approach to failure recovery and proactive problem-solving through an escalation hierarchy of strategies.

## 📂 Module Overview

```text
ccrs/core/contingency/
├── Situation.java              # Input POJO describing the problematic situation.
├── StrategyResult.java         # Output sealed type: Suggestion | NoHelp.
├── CcrsStrategy.java           # Base interface for all recovery strategies.
├── ContingencyCcrs.java        # Main entry point orchestrating strategy evaluation.
├── StrategyRegistry.java       # Registry managing available strategies.
├── ContingencyConfiguration.java # Configuration options for the system.
├── CcrsTrace.java              # Trace object for debugging and learning.
├── ActionRecord.java           # Record of an action taken by the agent.
├── StateSnapshot.java          # Snapshot of agent state at a point in time.
│
└── strategies/                 # CONCRETE STRATEGY IMPLEMENTATIONS
    ├── RetryStrategy.java      # L1: Retry with exponential backoff.
    ├── BacktrackStrategy.java  # L2: Return to previous decision point.
    ├── PredictionLlmStrategy.java # L4: LLM-based path prediction.
    ├── ConsultationStrategy.java  # L3: External help via consultation channel.
    └── StopStrategy.java       # L0: Graceful failure when exhausted.

ccrs/jason/contingency/         # JASON PLATFORM ADAPTERS
├── evaluate.java               # Internal action: ccrs.contingency.evaluate(...)
└── report_outcome.java         # Internal action: ccrs.contingency.report_outcome(...)
```

---

## 📄 File Descriptions

### Core Module (Agent-Agnostic)

*   **`Situation.java`**: Primary input POJO with builder pattern. Describes the problematic context including situation type (FAILURE, STUCK, UNCERTAINTY, PROACTIVE), current/target resources, failed action details, and error information.

*   **`StrategyResult.java`**: Sealed output type with two variants:
    *   `Suggestion`: Actionable recommendation with actionType, target, confidence, rationale, and parameters.
    *   `NoHelp`: Explicit "cannot help" response with reason enum and explanation.

*   **`CcrsStrategy.java`**: Base interface defining the contract for all strategies:
    *   `getId()`, `getName()`: Strategy identification.
    *   `getCategory()`: INTERNAL, KNOWLEDGE, or SOCIAL.
    *   `getEscalationLevel()`: 0-4 indicating escalation tier.
    *   `appliesTo()`: Quick applicability check (APPLICABLE, NOT_APPLICABLE, MAYBE).
    *   `evaluate()`: Full evaluation returning StrategyResult.

*   **`ContingencyCcrs.java`**: Main orchestrator that evaluates strategies in escalation order. `evaluate()` is the default path and always delegates to `evaluateWithTrace()`, records the resulting trace through `CcrsContext`, and returns the selected results. `evaluateWithTrace()` remains available for callers that want the full trace object directly.

*   **`StrategyRegistry.java`**: Manages strategy registration with filtering by category and escalation level. Supports custom escalation policies.

*   **`CcrsTrace.java`**: Captures the full evaluation process for debugging and future learning. Records which strategies were consulted, their results, and the final selection. Supports `reportOutcome()` for feedback.

*   **`ActionRecord.java`**: Immutable record of an action: type, target, outcome (SUCCESS/FAILURE/PENDING), and timestamp.

*   **`StateSnapshot.java`**: Immutable snapshot of agent state: resource location and timestamp.

### Strategy Implementations

| Strategy | Level | Category | Description |
|----------|-------|----------|-------------|
| `RetryStrategy` | L1 | INTERNAL | Handles transient HTTP errors (408, 429, 5xx) with exponential backoff |
| `BacktrackStrategy` | L2 | INTERNAL | Returns to parent resource using hypermedia link heuristic |
| `PredictionLlmStrategy` | L4 | INTERNAL | LLM-based prediction for optimal path selection (ANY situation type) |
| `ConsultationStrategy` | L3 | SOCIAL | Requests external help via pluggable consultation channel (ANY situation type) |
| `StopStrategy` | L0 | INTERNAL | Graceful failure when all options exhausted (ANY situation type) |

### Jason Adapters

*   **`evaluate.java`**: Internal action `ccrs.contingency.evaluate(Type, Trigger, Current, Target, Action, Error, ResultList)` that invokes the default `ContingencyCcrs.evaluate(...)` path. This returns suggestions as a list of literals and also records trace history in the background.

*   **`track.java`**: Internal action for history tracking:
    *   `ccrs.contingency.track(action, Type, Target, Outcome)` — record an action.
    *   `ccrs.contingency.track(state, Resource)` — record current state.

*   **`report_outcome.java`**: Internal action for reporting suggestion outcomes:
    *   `ccrs.contingency.report_outcome("success")` — suggestion worked.
    *   `ccrs.contingency.report_outcome("failed", "reason")` — suggestion failed.

---

## ⚡ Key Implementation Considerations

### 1. Escalation Hierarchy

Strategies are organized into escalation levels, evaluated in ascending order:

```text
L1 (Low)      → Retry: Quick, cheap recovery attempts
L2 (Moderate) → Backtrack: Requires more context/resources
L3 (Social)   → Consultation: Involves external entities
L4 (LLM)      → Prediction: Requires model inference and larger context
L0 (Last)     → Stop: Graceful failure when exhausted
```

**Default Evaluation Order:** L1 -> L2 -> L3 -> L4 -> L0

This order is the default prior when there is not enough trace history for learned scheduling. `StopStrategy` (L0) is treated as a fallback and is skipped when any recovery suggestion already exists.

The configured escalation policy controls how much of the ordered list is evaluated:
*   `SEQUENTIAL`: stop after the first suggestion.
*   `BEST_PER_LEVEL`: evaluate the most promising applicable strategy in each escalation level, then continue to the next level.
*   `PARALLEL`: consider all enabled strategies, while still allowing learned scheduling to skip strategies whose expected value is lower than the confidence of an already available suggestion.

### 2. Hypermedia-Oriented Design

The core operates on RDF triples without domain-specific assumptions:

*   **Backtrack Heuristic:** A "parent" is any resource that links TO the current resource (where current appears as the object in a triple). This follows linked data principles.
*   **No Hardcoded Predicates:** Strategies query the RDF graph generically rather than looking for specific vocabulary terms.

### 3. Pluggable External Services

Knowledge and Social strategies use pluggable interfaces:

```java
// LLM Client for PredictionLlmStrategy
public interface LlmClient {
    CompletableFuture<LlmResponse> query(String prompt);
}

// Consultation Channel for ConsultationStrategy
public interface ConsultationChannel {
    CompletableFuture<ConsultationResponse> requestHelp(ConsultationRequest request);
}
```

Implementations can be injected via configuration, allowing adaptation to different LLM providers or consultation mechanisms.

### 4. Trace-Based Learning

Every evaluation produces a `CcrsTrace` capturing:
*   All strategies consulted and their results
*   The selected suggestion (if any)
*   Timestamp and situation context
*   Outcome feedback (via `reportOutcome()`)

This enables:
*   **Debugging:** Understanding why a particular strategy was chosen
*   **Learning:** Collecting data for improving strategy selection
*   **Auditing:** Recording decision rationale for review

The default `ContingencyCcrs.evaluate(...)` path records this trace through `CcrsContext`. Adapters can expose that history using the reusable in-memory helper in `ccrs.core.rdf.InMemoryCcrsTraceHistory`.

### 5. Learned Evaluation Tradeoff

Suggestion confidence and strategy evaluation cost are handled at different points in the pipeline.

After a strategy has run, its suggestion is ranked only by `confidence`. At that point the evaluation cost has already been paid, so reducing the suggestion's rank because the strategy was expensive would mix two different decisions.

Before future strategies are run, `ContingencyCcrs` builds a lightweight strategy profile from recent `CcrsTrace` records:
*   measured evaluation time from `StrategyEvaluation.getEvaluationTimeMs()`,
*   how often the strategy produced a suggestion,
*   the confidence of those suggestions,
*   optional reported outcome feedback when available.

The learned pre-evaluation value is:

```text
expectedConfidence / (1 + averageEvaluationTimeMs / levelReferenceTimeMs)
```

`expectedConfidence` combines the learned suggestion rate with the learned confidence of produced suggestions. When outcome feedback has been reported, it is folded into confidence as a reliability signal.

`levelReferenceTimeMs` is configured per escalation level. It is not a fixed cost charged to suggestions; it is a default effort prior for interpreting measured runtime:
*   L1 defaults to `100ms`,
*   L2 defaults to `1000ms`,
*   L3 defaults to `2000ms`,
*   L4 defaults to `3000ms`,
*   L0 defaults to `50ms` because `StopStrategy` is only a fallback.

This value is used to reorder strategies within their escalation level and to skip a remaining strategy when a current suggestion already has higher confidence than the learned value of spending more effort. If a strategy does not yet have enough samples, the default escalation order is preserved so new strategies are still explored.

`learningHistoryLimit` bounds how many recent traces are read into the model. It keeps the runtime selector local, cheap, and adaptive to recent behavior instead of letting old evaluations dominate forever. `minimumLearningSamples` is counted per strategy profile, not globally; with the default value `2`, a strategy needs two recorded evaluations before its learned value can reorder or prune it.

### 6. Context Integration

Strategies access agent context via `CcrsContext`:

```java
public interface CcrsContext {
    // RDF queries
    List<RdfTriple> query(String s, String p, String o);
    List<RdfTriple> getMemoryTriples(int maxCount);
    Neighborhood getNeighborhood(String resource, int maxOutgoing, int maxIncoming);
    Optional<String> getCurrentResource();
    
    // History (for contingency)
    boolean hasHistory();
    List<Interaction> getRecentInteractions(int maxCount);
    Optional<CcrsTrace> getLastCcrsInvocation();
    List<CcrsTrace> getCcrsHistory(int maxCount);
    void recordCcrsInvocation(CcrsTrace trace);
    
    // Capabilities
    boolean hasLlmAccess();
    boolean hasConsultationChannel();
}
```

`getNeighborhood(...)` is a local link-context API: it returns bounded outgoing and incoming triples around one focus resource. It should remain cheap and small. `getMemoryTriples(...)` is the memory-style API for broader bounded RDF access; LLM prompt generation uses it for raw graph evidence while keeping neighborhood output as a separate local-map section.

For LLM prediction, recent `Interaction` records are formatted with request headers/body, outcome, timing, and perceived RDF triples. This is intentionally separate from `Interaction.toString()`, which remains compact for logs and debug summaries.

### 7. LLM Prompt Triple Filtering

`PredictionLlmStrategy` performs strategy-level filtering before serializing RDF triples into the LLM prompt. The default filter removes every triple whose subject, predicate, or object contains the `https://example.org/ui` namespace.

This does not remove data from `CcrsContext`, the belief base, interaction history, or other strategies. It is only a prompt-shaping step for L4 prediction.

The rationale is similar to preparing web content for an LLM: a browser page contains HTML, CSS, ARIA, layout metadata, and visual decoration, but question-answering prompts often convert it to markdown or another content-focused representation first. For CCRS prediction, `https://example.org/ui` triples describe presentation details such as UI elements, layers, fills, and drawing properties. Those triples can dominate the token budget while usually adding little to recovery-action selection. Filtering them keeps the prompt focused on actionable hypermedia state, links, previous interactions, and CCRS traces.

The filter is configurable on `PredictionLlmStrategy` through `filteredTripleNamespaces(...)` and `filterTripleNamespace(...)` when a domain needs to retain or suppress different presentation namespaces.

---

## 🔄 Execution Flow

```text
 ┌─────────────────────────────┐
 │  Agent Detects Problem      │
 │  (Failure, Stuck, etc.)     │
 └─────────────┬───────────────┘
               │
               ▼
 ┌─────────────────────────────┐
 │  Build Situation Object     │
 │  (Type, Resources, Error)   │
 └─────────────┬───────────────┘
               │
               ▼
 ┌─────────────────────────────────────────────┐
 │  ContingencyCcrs.evaluate(situation, ctx)   │
 └─────────────┬───────────────────────────────┘
               │
               ▼
 ┌─────────────────────────────────────────────┐
 │  StrategyRegistry.getByLevel()              │
 │  Iterate L1 → L2 → L3 → L4 → L0             │
 └─────────────┬───────────────────────────────┘
               │
      ┌────────┴────────┐
      │  For each level │
      └────────┬────────┘
               │
               ▼
 ┌─────────────────────────────────────────────┐
 │  strategy.appliesTo(situation, context)     │
 │  Quick filter: APPLICABLE / NOT_APPLICABLE  │
 └─────────────┬───────────────────────────────┘
               │ If APPLICABLE
               ▼
 ┌─────────────────────────────────────────────┐
 │  strategy.evaluate(situation, context)      │
 │  Returns: Suggestion | NoHelp               │
 └─────────────┬───────────────────────────────┘
               │
      ┌────────┴────────┐
      │  If Suggestion  │──────────────────────┐
      └────────┬────────┘                      │
               │ If NoHelp                     │
               ▼                               ▼
      Continue to next strategy    ┌───────────────────────┐
                                   │  Return Suggestion    │
                                   │  (Stop evaluation)    │
                                   └───────────────────────┘
               │
               ▼ (All exhausted)
 ┌─────────────────────────────────────────────┐
 │  StopStrategy.evaluate() → Stop Suggestion  │
 │  (Graceful failure)                         │
 └─────────────────────────────────────────────┘
```

---

## 📝 Usage Examples

### AgentSpeak (Jason) Usage

```asl
// Track navigation for history
+!crawl(URI) : true
    <-
        ccrs.contingency.track(state, URI) ;
        get(URI, [header("urn:hypermedea:http:authorization", "agent bob")]) ;
    .

// Handle HTTP failure with contingency CCRS
-!crawl(URI) : true
    <-
        .my_name(Me) ;
        ccrs.contingency.evaluate(
            "FAILURE",           // Situation type
            "http_error",        // Trigger
            URI,                 // Current resource
            TargetURI,           // Target (if known)
            "GET",               // Failed action
            "404",               // Error info
            Suggestions          // Output: list of suggestions
        ) ;
        !handle_suggestions(Suggestions) ;
    .

// Process suggestions
+!handle_suggestions([suggest(ActionType, Target, Confidence, Rationale, Params)|_]) :
    Confidence > 0.5
    <-
        .print("Trying: ", ActionType, " on ", Target) ;
        !execute_suggestion(ActionType, Target, Params) ;
    .

+!handle_suggestions([no_help(Reason, Explanation)|Rest]) : true
    <-
        .print("Strategy couldn't help: ", Explanation) ;
        !handle_suggestions(Rest) ;
    .

// Report outcome for learning
+!execute_suggestion(ActionType, Target, Params) : true
    <-
        !perform_action(ActionType, Target, Params) ;
        ccrs.contingency.report_outcome("success") ;
    .

-!execute_suggestion(ActionType, Target, Params) : true
    <-
        ccrs.contingency.report_outcome("failed", "action_failed") ;
    .
```

### Java API Usage

```java
// Build situation
Situation situation = Situation.builder()
    .type(Situation.Type.STUCK)
    .currentResource("http://example.org/cell/5")
    .targetResource("http://example.org/cell/exit")
    .failedAction("navigate")
    .errorInfo("no valid transitions")
    .build();

// Create contingency evaluator
ContingencyCcrs ccrs = new ContingencyCcrs();
ccrs.registerDefaultStrategies();

// Default evaluation path: records trace via context and returns selected results
List<StrategyResult> results = ccrs.evaluate(situation, context);
StrategyResult result = results.isEmpty() ? null : results.get(0);

if (result instanceof StrategyResult.Suggestion suggestion) {
    System.out.println("Action: " + suggestion.actionType());
    System.out.println("Target: " + suggestion.actionTarget());
    System.out.println("Confidence: " + suggestion.confidence());
    
    // Access recorded trace if needed
    CcrsTrace trace = context.getLastCcrsInvocation().orElse(null);

    // Execute and report outcome
    boolean success = executeAction(suggestion);
    if (trace != null) {
        trace.reportOutcome(success ? Outcome.SUCCESS : Outcome.FAILURE);
    }
}
```

---

## 🔧 Configuration

```java
ContingencyConfiguration config = ContingencyConfiguration.builder()
    .maxEscalationLevel(4)           // Cap escalation at L4
    .enableTracing(true)             // Capture evaluation traces
    .llmClient(myLlmClient)          // Inject LLM provider
    .consultationChannel(myChannel)  // Inject consultation mechanism
    .build();

ContingencyCcrs ccrs = new ContingencyCcrs(config);
```


