package ccrs.core.contingency;

import ccrs.core.contingency.dto.LlmActionResponse;

/**
 * Parser for LLM responses in CCRS recovery scenarios.
 * 
 * Centralizes response parsing logic and schema assumptions,
 * making strategies cleaner and allowing better error handling.
 * 
 * Implementations should handle:
 * - Multiple response formats (JSON, plain text fallbacks)
 * - Field name variations (reasoning/advice/explanation)
 * - Malformed or incomplete responses
 * - Extraction of action, target, and rationale
 */
@FunctionalInterface
public interface LlmResponseParser {
    
    /**
     * Parse an LLM response into a structured action response.
     * 
     * @param rawResponse The raw text response from the LLM
     * @return Parsed action response, or invalid response if parsing fails
     */
    LlmActionResponse parse(String rawResponse);
    
    /**
     * Get a description of this parser (for debugging/logging).
     */
    default String getDescription() {
        return this.getClass().getSimpleName();
    }
}
