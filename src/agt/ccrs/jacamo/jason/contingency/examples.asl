// =========================================================================
// Contingency CCRS evaluate() - Quick Reference Examples
// =========================================================================
// This file shows AgentSpeak usage patterns for all evaluate() signatures.
// See contingency/README.md for detailed documentation.
// =========================================================================

// -------------------------------------------------------------------------
// EXAMPLE 1: Basic Stuck (4 args) - Most Common Pattern
// -------------------------------------------------------------------------
// When: Agent stuck at a location, needs backtrack guidance
// Strategy: BacktrackStrategy (L2) finds checkpoints

+!escalate("stuck", "no_valid_options", Location) : true <-
    .print("[STUCK] No valid options at ", Location);
    ccrs.jacamo.jason.contingency.evaluate("stuck", "no_valid_options", Location, Suggestions);
    !handle_suggestions(Suggestions);
.

// -------------------------------------------------------------------------
// EXAMPLE 2: HTTP Failure (8 args) - Full Error Context
// -------------------------------------------------------------------------
// When: HTTP request fails, needs retry/recovery
// Strategy: RetryStrategy (L1) for transient errors, BacktrackStrategy (L2) fallback

// 404 Not Found - permanent error, backtrack immediately
-!navigate(TargetURI)[error(Error)] : 
    crawling & at(CurrentURI) & 
    Error = error(failed, "404 Not Found")
    <-
    .print("[HTTP 404] Target not found: ", TargetURI);
    ccrs.jacamo.jason.contingency.evaluate(
        "failure",           // Type: FAILURE (not STUCK)
        "http_error",        // Trigger description
        CurrentURI,          // Current location
        TargetURI,           // Where request failed
        "GET",               // Failed HTTP method
        "404",               // Error code
        [],                  // No strategies attempted yet
        Suggestions          // Output: strategy suggestions
    );
    !handle_http_error_suggestions(Suggestions, TargetURI);
.

// 503 Service Unavailable - transient error, retry then backtrack
-!navigate(TargetURI)[error(Error)] : 
    crawling & at(CurrentURI) & 
    Error = error(failed, "503 Service Unavailable")
    <-
    .print("[HTTP 503] Service unavailable: ", TargetURI);
    ccrs.jacamo.jason.contingency.evaluate(
        "failure", "http_error",
        CurrentURI, TargetURI, "GET", "503", [],
        Suggestions
    );
    !handle_http_error_suggestions(Suggestions, TargetURI);
.

// -------------------------------------------------------------------------
// EXAMPLE 3: Map-Based (4 args) - Flexible Composition
// -------------------------------------------------------------------------
// When: Custom field combinations or after retry attempts
// Strategy: Any (fields determine applicability)

+!retry_with_context(CurrentURI, TargetURI, AttemptCount) : true <-
    .print("[RETRY] Attempt ", AttemptCount, " failed, requesting guidance");
    
    // Build attempted strategies list
    .concat([], ["retry:", AttemptCount], AttemptedList);
    
    // Use map for flexible field composition
    ccrs.jacamo.jason.contingency.evaluate(
        "failure",
        "http_error",
        map(
            current(CurrentURI),
            target(TargetURI),
            action("GET"),
            error("503"),
            attempted(AttemptedList)
        ),
        Suggestions
    );
    !handle_suggestions(Suggestions);
.

// -------------------------------------------------------------------------
// EXAMPLE 4: Minimal (3 args) - Last Resort
// -------------------------------------------------------------------------
// When: Minimal context available, just need general advice
// Strategy: StopStrategy (L0) only

+!give_up : true <-
    .print("[GIVING UP] Requesting stop guidance");
    ccrs.jacamo.jason.contingency.evaluate("stuck", "exhausted_options", Suggestions);
    !handle_suggestions(Suggestions);
.

// -------------------------------------------------------------------------
// SUGGESTION HANDLER PATTERNS
// -------------------------------------------------------------------------

// Pattern A: Priority-based handler (try high-confidence first)
+!handle_suggestions([]) : true <-
    .print("[CONTINGENCY] No suggestions - stopping");
    !stop_crawl;
.

+!handle_suggestions([suggestion(Id, Type, Target, Conf, Cost, Reason, Params)|Rest]) : 
    Conf > 0.8 & Type = "retry"
    <-
    .print("[ACCEPT] High-confidence retry: ", Reason);
    .wait(Cost);
    !retry_action(Target);
.

+!handle_suggestions([suggestion(Id, Type, Target, Conf, Cost, Reason, Params)|Rest]) : 
    Conf > 0.7 & Type = "backtrack"
    <-
    .print("[ACCEPT] Backtrack suggestion: ", Reason);
    !execute_backtrack(Target, Params);
.

+!handle_suggestions([suggestion(Id, Type, _, Conf, _, Reason, _)|Rest]) : 
    Type = "stop"
    <-
    .print("[STOP] Strategy advises: ", Reason);
    !stop_crawl;
.

+!handle_suggestions([First|Rest]) : true <-
    .print("[SKIP] Low-confidence suggestion");
    !handle_suggestions(Rest);
.

// Pattern B: Type-specific handlers (different logic per strategy)
+!handle_http_error_suggestions([suggestion(_,Type,Target,Conf,Cost,Reason,_)|_], OriginalTarget) :
    Type = "retry" & Conf > 0.7
    <-
    .print("[RETRY] Waiting ", Cost, "ms then retrying: ", Reason);
    .wait(Cost);
    !navigate(OriginalTarget);  // Retry original request
.

+!handle_http_error_suggestions([suggestion(_,Type,Target,Conf,_,Reason,Params)|_], _) :
    Type = "backtrack" & Conf > 0.6
    <-
    .print("[BACKTRACK] Returning to checkpoint: ", Reason);
    !backtrack_to(Target, Params);
.

+!handle_http_error_suggestions([suggestion(_,Type,_,_,_,Reason,_)|_], _) :
    Type = "stop"
    <-
    .print("[STOP] Giving up: ", Reason);
    !stop_crawl;
.

+!handle_http_error_suggestions([], _) : true <-
    .print("[ERROR] No viable strategies - stopping");
    !stop_crawl;
.

// -------------------------------------------------------------------------
// BACKTRACK EXECUTION PATTERN
// -------------------------------------------------------------------------

+!execute_backtrack(CheckpointURI, Params) : true <-
    .print("[BACKTRACK] Returning to checkpoint: ", CheckpointURI);
    
    // Extract path from params
    .member(path(PathList), Params);
    .print("[BACKTRACK] Path: ", PathList);
    
    // Navigate backward along path
    !traverse_backtrack_path(PathList);
    
    // Reset exploration state
    !reset_after_backtrack(CheckpointURI);
.

+!traverse_backtrack_path([]) : true <-
    .print("[BACKTRACK] Path complete");
.

+!traverse_backtrack_path([URI|Rest]) : true <-
    .print("[BACKTRACK] -> ", URI);
    get(URI);  // HTTP GET to follow path
    !traverse_backtrack_path(Rest);
.

+!reset_after_backtrack(CheckpointURI) : true <-
    // Clear visited nodes after checkpoint
    .abolish(visited_after_checkpoint(_));
    -+at(CheckpointURI);
    .print("[BACKTRACK] Reset to checkpoint, ready to explore");
.

// -------------------------------------------------------------------------
// PROGRESS MONITORING PATTERN (Proactive Stuck Detection)
// -------------------------------------------------------------------------

+!start_progress_monitor : true <-
    +last_progress_check(0);
    +progress_check_interval(5000);  // 5 seconds
    !monitor_progress;
.

+!monitor_progress : true <-
    ?progress_check_interval(Interval);
    .wait(Interval);
    
    // Count progress
    .count(visited(_), CurrentCount);
    ?last_progress_check(LastCount);
    
    // Detect low progress
    Progress = CurrentCount - LastCount;
    if (Progress < 2) {
        .print("[MONITOR] Low progress: ", Progress, " new nodes");
        ?at(Location);
        ccrs.jacamo.jason.contingency.evaluate("stuck", "low_progress", Location, Suggestions);
        !handle_suggestions(Suggestions);
    } else {
        .print("[MONITOR] Progress OK: ", Progress, " new nodes");
    }
    
    -+last_progress_check(CurrentCount);
    !monitor_progress;
.

// -------------------------------------------------------------------------
// ERROR PARSING HELPER
// -------------------------------------------------------------------------

+!parse_http_error(error(failed, Message), Code, Msg) : true <-
    // Extract HTTP status code from error message
    if (.substring("404", Message)) {
        Code = "404";
        Msg = "Not Found";
    } elif (.substring("503", Message)) {
        Code = "503";
        Msg = "Service Unavailable";
    } elif (.substring("500", Message)) {
        Code = "500";
        Msg = "Internal Server Error";
    } elif (.substring("403", Message)) {
        Code = "403";
        Msg = "Forbidden";
    } else {
        Code = "unknown";
        Msg = Message;
    }
.

// -------------------------------------------------------------------------
// DEBUGGING: Inspect Suggestion Details
// -------------------------------------------------------------------------

+!debug_suggestions([]) : true <-
    .print("[DEBUG] --- End of suggestions ---");
.

+!debug_suggestions([suggestion(Id, Type, Target, Conf, Cost, Reason, Params)|Rest]) : true <-
    .print("[DEBUG] Suggestion:");
    .print("  ID:         ", Id);
    .print("  Type:       ", Type);
    .print("  Target:     ", Target);
    .print("  Confidence: ", Conf);
    .print("  Cost:       ", Cost);
    .print("  Rationale:  ", Reason);
    .print("  Params:     ", Params);
    !debug_suggestions(Rest);
.
