# Contingency CCRS - Internal Action Usage Guide

This document provides examples for using the `evaluate` internal action to invoke contingency strategies from AgentSpeak agent code.

## Overview

The `evaluate` internal action bridges AgentSpeak agents with Contingency-CCRS strategies. It supports multiple signatures for different situation complexity levels.

## Supported Signatures

### 1. Basic (3 args) - Minimal Context

```asl
// Simplest form - just type and trigger
ccrs.jacamo.jason.contingency.evaluate("stuck", "no_valid_options", Suggestions)
```

**Use when:** You have minimal context and just need general guidance.

**Strategies that work:** StopStrategy (always applicable)

---

### 2. With Focus (4 args) - Add Current Location

```asl
// Include current resource URI
ccrs.jacamo.jason.contingency.evaluate("stuck", "low_progress", Location, Suggestions)
```

**Use when:** Agent is stuck at a specific location and needs recovery options.

**Strategies that work:** 
- **BacktrackStrategy** (L2) - Finds checkpoints and computes backtrack paths
- **StopStrategy** (L0) - Fallback advice

**Example from contingency_ccrs.asl:**
```asl
+!escalate("stuck", "low_progress", Location) : true <-
    .print("[CONTINGENCY] Stuck at ", Location, " - requesting recovery strategies");
    ccrs.jacamo.jason.contingency.evaluate("stuck", "low_progress", Location, Suggestions);
    !handle_suggestions(Suggestions);
.
```

---

### 3. Failure Details (8 args) - Full Context for HTTP Errors

```asl
// Complete failure context with error details
ccrs.jacamo.jason.contingency.evaluate(
    "failure",                    // Type
    "http_error",                 // Trigger
    CurrentURI,                   // Current resource
    TargetURI,                    // Target resource (where request failed)
    "GET",                        // Failed action (HTTP method)
    "404",                        // Error code/message
    [],                           // Attempted strategies (empty list initially)
    Suggestions                   // Output: list of suggestions
)
```

**Use when:** An HTTP request fails and you want retry/recovery strategies.

**Strategies that work:**
- **RetryStrategy** (L1) - Retries transient errors with exponential backoff
- **BacktrackStrategy** (L2) - Falls back to last checkpoint if retry exhausted
- **StopStrategy** (L0) - Gives up gracefully

**Example usage:**
```asl
// HTTP 404 failure
-!navigate(TargetURI)[error(Error)] : 
    crawling & at(CurrentURI) & 
    Error = error(failed, "404 Not Found") 
    <-
    .print("[HTTP ERROR] Failed to reach ", TargetURI, " - ", Error);
    ccrs.jacamo.jason.contingency.evaluate(
        "failure", "http_error",
        CurrentURI, TargetURI, "GET", "404", [],
        Suggestions
    );
    !handle_suggestions(Suggestions);
.

// HTTP 503 Service Unavailable (retriable)
-!navigate(TargetURI)[error(Error)] : 
    crawling & at(CurrentURI) & 
    Error = error(failed, "503 Service Unavailable")
    <-
    .print("[HTTP ERROR] Service unavailable at ", TargetURI);
    ccrs.jacamo.jason.contingency.evaluate(
        "failure", "http_error",
        CurrentURI, TargetURI, "GET", "503", [],
        Suggestions
    );
    !handle_suggestions(Suggestions);
.
```

---

### 4. Map-Based (4 args) - Flexible Field Composition

```asl
// Use map structure for custom field combinations
ccrs.jacamo.jason.contingency.evaluate(
    "failure",
    "http_error",
    map(
        current("http://example.org/cells/5"),
        target("http://example.org/cells/10"),
        action("POST"),
        error("403 Forbidden")
    ),
    Suggestions
)
```

**Use when:** You want flexibility to include only relevant fields without positional arguments.

**Map Keys:**
- `current(URI)` - Current resource location
- `target(URI)` - Target resource (for failures)
- `action(Method)` - Failed action name
- `error(Message)` - Error message or HTTP status code
- `attempted([list])` - Previously attempted strategies

**Example with attempted strategies:**
```asl
// After first retry failed, try again with context
ccrs.jacamo.jason.contingency.evaluate(
    "failure",
    "http_error",
    map(
        current(CurrentURI),
        target(TargetURI),
        action("GET"),
        error("503"),
        attempted(["retry:1"])
    ),
    Suggestions
)
```

---

## Situation Types

### FAILURE
**When to use:** An action failed with an error (HTTP error, timeout, invalid response)

**Required fields for strategies:**
- RetryStrategy: `current`, `target`, `action`, `error`
- BacktrackStrategy: `current` (fallback if retry exhausted)

**Example:**
```asl
ccrs.jacamo.jason.contingency.evaluate("failure", "timeout", CurrentURI, TargetURI, "GET", "timeout", [], Suggestions)
```

---

### STUCK
**When to use:** Agent has no clear path forward (low progress, no valid options, cyclic navigation)

**Required fields for strategies:**
- BacktrackStrategy: `current` (finds checkpoint and computes backtrack path)

**Example:**
```asl
ccrs.jacamo.jason.contingency.evaluate("stuck", "no_valid_transitions", CurrentURI, Suggestions)
```

---

### UNCERTAINTY
**When to use:** Agent faces ambiguous choices and needs guidance

**Required fields:** Minimal (type, trigger)

**Example:**
```asl
ccrs.jacamo.jason.contingency.evaluate("uncertainty", "multiple_paths", Suggestions)
```

---

### PROACTIVE
**When to use:** Before taking risky actions, check if it's advisable

**Required fields:** Depends on context (usually `current` and `target`)

**Example:**
```asl
ccrs.jacamo.jason.contingency.evaluate("proactive", "risky_action", CurrentURI, TargetURI, "DELETE", "", [], Suggestions)
```

---

## Strategy Requirements Summary

| Strategy | Level | Situation Type | Required Fields |
|----------|-------|----------------|-----------------|
| **RetryStrategy** | L1 | FAILURE | `target`, `action`, `error` (HTTP status) |
| **BacktrackStrategy** | L2 | STUCK | `current` |
| **PredictionLLM** | L2 | STUCK, UNCERTAINTY | `current` (optional) |
| **ConsultationStrategy** | L4 | ANY | None (always applicable) |
| **StopStrategy** | L0 | ANY | None (always applicable) |

---

## Handling Suggestions

All `evaluate` calls return a list of `suggestion/7` structures:

```asl
suggestion(
    StrategyId,        // e.g., "retry:1", "backtrack:2"
    ActionType,        // "retry", "backtrack", "stop", etc.
    ActionTarget,      // URI or "null"
    Confidence,        // 0.0 to 1.0
    EstimatedCost,     // Relative cost
    Rationale,         // Human-readable explanation
    ActionParams       // List of key(value) pairs
)
```

### Example Handler

```asl
+!handle_suggestions([]) : true <-
    .print("[CONTINGENCY] No suggestions available - giving up");
    !stop_crawl;
.

+!handle_suggestions([suggestion(Id, Type, Target, Conf, Cost, Reason, Params)|Rest]) : 
    Conf > 0.7 & Type = "backtrack"
    <-
    .print("[CONTINGENCY] Accepting ", Type, " suggestion: ", Reason);
    !execute_backtrack(Target, Params);
.

+!handle_suggestions([suggestion(Id, Type, Target, Conf, Cost, Reason, Params)|Rest]) : 
    Conf > 0.8 & Type = "retry"
    <-
    .print("[CONTINGENCY] Accepting retry suggestion: ", Reason);
    .wait(Cost);  // Wait suggested backoff time
    !retry_action(Target);
.

+!handle_suggestions([_|Rest]) : true <-
    .print("[CONTINGENCY] Skipping low-confidence suggestion");
    !handle_suggestions(Rest);
.
```

---

## Best Practices

1. **Match signature to situation complexity:**
   - Simple stuck? Use 4-arg with location
   - HTTP failure? Use 8-arg with full details
   - Custom needs? Use map-based

2. **Track attempted strategies:**
   - After first attempt fails, include `attempted` list
   - Prevents infinite retry loops

3. **Check confidence levels:**
   - High confidence (>0.8): Execute immediately
   - Medium confidence (0.5-0.8): Consider alternatives
   - Low confidence (<0.5): Skip or ask human

4. **Use appropriate types:**
   - Don't use FAILURE when just stuck (no actual error)
   - STUCK is for navigation impasses, not HTTP errors

5. **Provide error details for FAILURE:**
   - HTTP status codes help RetryStrategy decide retriability
   - Timeout vs 404 vs 503 trigger different strategies

---

## Common Patterns

### Pattern 1: HTTP Error with Escalation
```asl
// Try HTTP request
+!navigate(TargetURI) : crawling & at(CurrentURI) <-
    get(TargetURI);
.

// Handle failure with contingency
-!navigate(TargetURI)[error(Error)] : at(CurrentURI) <-
    .print("[ERROR] Navigation failed: ", Error);
    
    // Extract error details
    parse_error(Error, ErrorCode, ErrorMsg);
    
    // Request contingency strategies
    ccrs.jacamo.jason.contingency.evaluate(
        "failure", "http_error",
        CurrentURI, TargetURI, "GET", ErrorCode, [],
        Suggestions
    );
    
    // Handle suggestions
    !process_contingency(Suggestions, TargetURI, ErrorCode);
.

// First attempt: retry if suggested
+!process_contingency([suggestion(_,Type,_,Conf,Cost,_,_)|_], Target, Error) :
    Type = "retry" & Conf > 0.7 & Error = "503"
    <-
    .wait(Cost);
    !navigate(Target);  // Retry
.

// Second attempt: backtrack if retry failed
+!process_contingency([suggestion(_,Type,Target,Conf,_,_,_)|_], _, _) :
    Type = "backtrack" & Conf > 0.6
    <-
    !backtrack_to(Target);
.

// Give up
+!process_contingency([], _, _) : true <-
    .print("[CONTINGENCY] No viable strategies - stopping");
    !stop_crawl;
.
```

### Pattern 2: Progress Monitoring with Stuck Detection
```asl
// Check progress periodically
+!monitor_progress : true <-
    .count(visited(_), VisitedCount);
    ?last_progress_check(LastCount);
    
    if (VisitedCount - LastCount < 2) {
        // Low progress - might be stuck
        ?at(Location);
        .print("[MONITOR] Low progress detected at ", Location);
        ccrs.jacamo.jason.contingency.evaluate("stuck", "low_progress", Location, Suggestions);
        !handle_suggestions(Suggestions);
    }
    
    -+last_progress_check(VisitedCount);
    .wait(5000);
    !monitor_progress;
.
```

---

## Troubleshooting

**Problem:** RetryStrategy says "Not applicable - missing failed action or target resource"
- **Solution:** Use 8-arg signature with full error details, not 4-arg signature

**Problem:** BacktrackStrategy says "Not applicable - no current resource"
- **Solution:** Include current location (4-arg or map with `current` key)

**Problem:** All strategies return "No help"
- **Solution:** Check situation type matches context (STUCK vs FAILURE)
- **Solution:** Verify required fields are provided for target strategies

**Problem:** Infinite retry loops
- **Solution:** Track attempted strategies and include in `attempted` field
- **Solution:** Check confidence levels and set maximum attempts

---

## References

- **Situation.java**: DTO with all supported fields
- **ContingencyCcrs.java**: Strategy orchestration
- **Strategy implementations**: RetryStrategy, BacktrackStrategy, etc.
- **contingency_ccrs.asl**: Example agent using contingency CCRS
