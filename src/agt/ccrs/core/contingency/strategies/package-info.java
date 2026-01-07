/**
 * Built-in contingency strategies.
 * 
 * <h2>Available Strategies</h2>
 * 
 * <h3>L1: Retry Strategy</h3>
 * {@link ccrs.core.contingency.strategies.RetryStrategy}
 * <p>Handles transient failures by repeating the same action with delay.
 * Applies to HTTP 5xx errors, timeouts, and connection issues.</p>
 * 
 * <h3>L2: Backtrack Strategy</h3>
 * {@link ccrs.core.contingency.strategies.BacktrackStrategy}
 * <p>Returns to a previous decision point when current path is blocked.
 * Uses navigation history and RDF knowledge to find alternatives.</p>
 * 
 * <h3>L2: Prediction LLM Strategy</h3>
 * {@link ccrs.core.contingency.strategies.PredictionLlmStrategy}
 * <p>Uses a Large Language Model to predict recovery actions.
 * Flexible approach for novel situations.</p>
 * 
 * <h3>L4: Consultation Strategy</h3>
 * {@link ccrs.core.contingency.strategies.ConsultationStrategy}
 * <p>Requests help from external agents (can be mocked with LLM).
 * Used when internal strategies are insufficient.</p>
 * 
 * <h3>L0: Stop Strategy</h3>
 * {@link ccrs.core.contingency.strategies.StopStrategy}
 * <p>Last resort - graceful goal abandonment when recovery is impossible.</p>
 * 
 * @see ccrs.core.contingency.CcrsStrategy Base interface
 */
package ccrs.core.contingency.strategies;
