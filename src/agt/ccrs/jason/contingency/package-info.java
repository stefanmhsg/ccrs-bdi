/**
 * Jason platform adapter for contingency CCRS.
 * 
 * <h2>Internal Actions</h2>
 * 
 * <h3>ccrs.contingency.evaluate</h3>
 * Evaluates contingency strategies for a situation.
 * <pre>
 * Usage:
 *   ccrs.contingency.evaluate(SituationType, Trigger, ResultList)
 *   ccrs.contingency.evaluate(SituationType, Trigger, Current, Target, ResultList)
 *   ccrs.contingency.evaluate(SituationType, Trigger, Current, Target, Action, ErrorInfo, Attempted, ResultList)
 * 
 * Example:
 *   -!navigate(Target) : true
 *     <- ccrs.contingency.evaluate("failure", "http_error", Cell, Target, "get",
 *            [httpStatus("503")], [], Suggestions);
 *        !try_recovery(Suggestions).
 * </pre>
 * 
 * <h3>ccrs.contingency.track</h3>
 * Records actions and state for CCRS history.
 * <pre>
 * Usage:
 *   ccrs.contingency.track(action, ActionType, Target, Outcome)
 *   ccrs.contingency.track(action, ActionType, Target, Outcome, Details)
 *   ccrs.contingency.track(state, Resource)
 * 
 * Example:
 *   ccrs.contingency.track(action, "get", "http://maze/cell/5", "success");
 *   ccrs.contingency.track(state, "http://maze/cell/5");
 * </pre>
 * 
 * <h3>ccrs.contingency.report_outcome</h3>
 * Reports the outcome of executing a CCRS suggestion.
 * <pre>
 * Usage:
 *   ccrs.contingency.report_outcome(Outcome)
 *   ccrs.contingency.report_outcome(Outcome, Details)
 * 
 * Example:
 *   ccrs.contingency.report_outcome("success");
 *   ccrs.contingency.report_outcome("failed", "Still locked");
 * </pre>
 * 
 * <h2>Result Format</h2>
 * The evaluate action returns a list of suggestions:
 * <pre>
 * [suggestion(StrategyId, ActionType, Target, Confidence, Cost, Rationale, Params), ...]
 * </pre>
 * 
 * @see ccrs.jason.contingency.evaluate
 * @see ccrs.jason.contingency.track
 * @see ccrs.jason.contingency.report_outcome
 */
package ccrs.jason.contingency;
