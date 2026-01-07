/**
 * Core contingency CCRS module - Agent-agnostic recovery strategies.
 * 
 * <h2>Overview</h2>
 * This package provides the core interfaces and implementations for
 * contingency CCRS (Check, Check, Recover, and Strategize). Contingency
 * strategies help agents recover from failures and unexpected situations.
 * 
 * <h2>Main Components</h2>
 * <ul>
 *   <li>{@link ccrs.core.contingency.Situation} - Describes a situation requiring recovery</li>
 *   <li>{@link ccrs.core.contingency.StrategyResult} - Result from strategy evaluation (Suggestion or NoHelp)</li>
 *   <li>{@link ccrs.core.contingency.CcrsStrategy} - Interface for all strategies</li>
 *   <li>{@link ccrs.core.contingency.ContingencyCcrs} - Main entry point for evaluation</li>
 *   <li>{@link ccrs.core.contingency.StrategyRegistry} - Registry for strategy management</li>
 *   <li>{@link ccrs.core.contingency.CcrsTrace} - Trace object for debugging and learning</li>
 * </ul>
 * 
 * <h2>Escalation Levels</h2>
 * Strategies are organized by escalation level (cost/invasiveness):
 * <ul>
 *   <li>L1: Low cost - Retry (same action, minor delay)</li>
 *   <li>L2: Moderate - Backtrack, Prediction (state change, may use LLM)</li>
 *   <li>L3: High - Planning (future, not in POC)</li>
 *   <li>L4: Social - Consultation (external agent/LLM)</li>
 *   <li>L0: Last resort - Stop (graceful failure)</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create CCRS instance with default strategies
 * ContingencyCcrs ccrs = ContingencyCcrs.withDefaults();
 * 
 * // Build a situation
 * Situation situation = Situation.failure("http_error")
 *     .currentResource("http://maze/cell/5")
 *     .targetResource("http://maze/cell/6")
 *     .failedAction("get")
 *     .httpError(503, "Service Unavailable")
 *     .build();
 * 
 * // Evaluate and get suggestions
 * CcrsTrace trace = ccrs.evaluateWithTrace(situation, context);
 * 
 * if (trace.hasSuggestions()) {
 *     StrategyResult.Suggestion top = trace.getTopSuggestion();
 *     // Execute suggestion...
 *     trace.reportOutcome(CcrsTrace.Outcome.SUCCESS, "Retry worked");
 * }
 * }</pre>
 * 
 * @see ccrs.core.contingency.strategies Built-in strategy implementations
 */
package ccrs.core.contingency;
