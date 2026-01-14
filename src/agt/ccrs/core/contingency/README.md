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
    ├── PredictionLlmStrategy.java # L2: LLM-based path prediction.
    ├── ConsultationStrategy.java  # L4: External help via consultation channel.
    └── StopStrategy.java       # L0: Graceful failure when exhausted.

ccrs/jason/contingency/         # JASON PLATFORM ADAPTERS
├── evaluate.java               # Internal action: ccrs.contingency.evaluate(...)
└── report_outcome.java         # Internal action: ccrs.contingency.report_outcome(...)
```

---

## 📄 File Descriptions

### Core Module (Agent-Agnostic)

*   **`Situation.java`**: Primary input POJO with builder pattern. Describes the problematic context including situation type (FAILURE, STUCK, UNCERTAINTY, PROACTIVE), current/target resources, failed action details, error information, and previously attempted strategies.

*   **`StrategyResult.java`**: Sealed output type with two variants:
    *   `Suggestion`: Actionable recommendation with actionType, target, confidence, cost, rationale, and parameters.
    *   `NoHelp`: Explicit "cannot help" response with reason enum and explanation.

*   **`CcrsStrategy.java`**: Base interface defining the contract for all strategies:
    *   `getId()`, `getName()`: Strategy identification.
    *   `getCategory()`: INTERNAL, KNOWLEDGE, or SOCIAL.
    *   `getEscalationLevel()`: 0-4 indicating escalation tier.
    *   `appliesTo()`: Quick applicability check (APPLICABLE, NOT_APPLICABLE, MAYBE).
    *   `evaluate()`: Full evaluation returning StrategyResult.

*   **`ContingencyCcrs.java`**: Main orchestrator that evaluates strategies in escalation order. Supports both simple `evaluate()` and `evaluateWithTrace()` for debugging/learning.

*   **`StrategyRegistry.java`**: Manages strategy registration with filtering by category and escalation level. Supports custom escalation policies.

*   **`CcrsTrace.java`**: Captures the full evaluation process for debugging and future learning. Records which strategies were consulted, their results, and the final selection. Supports `reportOutcome()` for feedback.

*   **`ActionRecord.java`**: Immutable record of an action: type, target, outcome (SUCCESS/FAILURE/PENDING), and timestamp.

*   **`StateSnapshot.java`**: Immutable snapshot of agent state: resource location and timestamp.

### Strategy Implementations

| Strategy | Level | Category | Description |
|----------|-------|----------|-------------|
| `RetryStrategy` | L1 | INTERNAL | Handles transient HTTP errors (408, 429, 5xx) with exponential backoff |
| `BacktrackStrategy` | L2 | INTERNAL | Returns to parent resource using hypermedia link heuristic |
| `PredictionLlmStrategy` | L2 | KNOWLEDGE | LLM-based prediction for optimal path selection |
| `ConsultationStrategy` | L4 | SOCIAL | Requests external help via pluggable consultation channel |
| `StopStrategy` | L0 | INTERNAL | Graceful failure when all options exhausted (always last resort) |

### Jason Adapters

*   **`evaluate.java`**: Internal action `ccrs.contingency.evaluate(Type, Trigger, Current, Target, Action, Error, Attempted, ResultList)` that invokes contingency evaluation and returns suggestions as a list of literals.

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
L2 (Moderate) → Backtrack, Prediction: Requires more context/resources
L4 (Social)   → Consultation: Involves external entities
L0 (Last)     → Stop: Graceful failure when exhausted
```

**Evaluation Order:** L1 → L2 → L4 → L0

The system iterates through applicable strategies by level. Once a `Suggestion` is returned, evaluation stops. `StopStrategy` (L0) is always evaluated last as the fallback.

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

Every evaluation can produce a `CcrsTrace` capturing:
*   All strategies consulted and their results
*   The selected suggestion (if any)
*   Timestamp and situation context
*   Outcome feedback (via `reportOutcome()`)

This enables:
*   **Debugging:** Understanding why a particular strategy was chosen
*   **Learning:** Collecting data for improving strategy selection
*   **Auditing:** Recording decision rationale for review

### 5. Context Integration

Strategies access agent context via `CcrsContext`:

```java
public interface CcrsContext {
    // RDF queries
    List<RdfTriple> query(String s, String p, String o);
    Optional<String> getCurrentResource();
    
    // History (for contingency)
    boolean hasHistory();
    List<ActionRecord> getRecentActions(int maxCount);
    List<StateSnapshot> getRecentStates(int maxCount);
    
    // Capabilities
    boolean hasLlmAccess();
    boolean hasConsultationChannel();
}
```

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
 │  Iterate L1 → L2 → L4 → L0                  │
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
            [],                  // Previously attempted strategies
            Suggestions          // Output: list of suggestions
        ) ;
        !handle_suggestions(Suggestions) ;
    .

// Process suggestions
+!handle_suggestions([suggest(ActionType, Target, Confidence, Cost, Rationale, Params)|_]) :
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

// Evaluate with trace
CcrsTrace trace = ccrs.evaluateWithTrace(situation, context);
StrategyResult result = trace.getSelectedResult().orElse(null);

if (result instanceof StrategyResult.Suggestion suggestion) {
    System.out.println("Action: " + suggestion.actionType());
    System.out.println("Target: " + suggestion.actionTarget());
    System.out.println("Confidence: " + suggestion.confidence());
    
    // Execute and report outcome
    boolean success = executeAction(suggestion);
    trace.reportOutcome(success ? Outcome.SUCCESS : Outcome.FAILURE);
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


