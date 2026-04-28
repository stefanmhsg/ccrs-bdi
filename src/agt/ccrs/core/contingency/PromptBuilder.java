package ccrs.core.contingency;

import java.util.Map;

/**
 * Interface for building LLM prompts for CCRS contingency strategies.
 * 
 * Provides a stable semantic contract for prompt generation.
 * 
 * This interface separates prompt engineering concerns from strategy logic,
 * making prompts easier to version, test, and optimize independently.
 * 
 * ARCHITECTURAL PRINCIPLE: PromptBuilder is a pure formatter that accepts
 * already-prepared data. It must NOT depend on Situation, CcrsContext, or
 * any other CCRS internals. Strategies are responsible for preparing the
 * context map before calling the prompt builder.
 */
public interface PromptBuilder {
    
    /**
     * Build a prompt for predicting a recovery action.
     * 
     * Used by internal prediction strategies (e.g., LLM-based prediction).
     * The prompt should help the LLM understand the current situation and
     * suggest an appropriate recovery action.
     * 
     * Expected context keys (all optional, builders should handle missing keys):
     * - "situationDetails": Pre-formatted generic situation and invocation details
     * - "currentResource": Current location URI
     * - "targetResource": Target of failed action
     * - "failedAction": Name of the failed action
     * - "errorInfo": Error details (string or map)
     * - "recentActions": Pre-formatted interaction history, including request/response details and perceived triples
     * - "ccrsHistory": Pre-formatted previous CCRS invocation traces
     * - "localNeighborhood": Pre-formatted bounded neighborhood around the current resource
     * - "rawMemory": Pre-formatted broader bounded RDF memory snapshot
     * 
     * @param contextMap Prepared context data for the prompt
     * @return A complete prompt string ready for the LLM
     */
    String buildPredictionPrompt(Map<String, Object> contextMap);
    
    /**
     * Get a description of this prompt builder for logging/debugging.
     * 
     * @return A human-readable description
     */
    default String getDescription() {
        return "PromptBuilder";
    }
}
