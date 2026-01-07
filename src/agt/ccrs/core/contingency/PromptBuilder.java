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
     * - "currentResource": Current location URI
     * - "targetResource": Target of failed action
     * - "failedAction": Name of the failed action
     * - "errorInfo": Error details (string or map)
     * - "attemptedStrategies": List of already-tried strategies
     * - "recentActions": Pre-formatted action history string
     * - "knowledge": Pre-formatted knowledge string (bounded neighborhood)
     * 
     * @param contextMap Prepared context data for the prompt
     * @return A complete prompt string ready for the LLM
     */
    String buildPredictionPrompt(Map<String, Object> contextMap);
    
    /**
     * Build a prompt for consulting an external advisor.
     * 
     * Used by social consultation strategies. The prompt should frame
     * the request as asking for help, including admission of prior failures
     * and richer context sharing.
     * 
     * @param question The agent's question or problem description
     * @param contextMap Additional context to help the advisor
     * @return A complete prompt string ready for the LLM
     */
    String buildConsultationPrompt(String question, Map<String, Object> contextMap);
    
    /**
     * Get a description of this prompt builder for logging/debugging.
     * 
     * @return A human-readable description
     */
    default String getDescription() {
        return "PromptBuilder";
    }
}
