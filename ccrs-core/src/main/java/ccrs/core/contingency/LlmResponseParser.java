package ccrs.core.contingency;

import ccrs.core.contingency.dto.LlmActionResponse;

/**
 * Parser for LLM responses in CCRS recovery scenarios.
 *
 * This interface is intentionally LLM-specific. It is used by CCRS strategies
 * that ask an LLM to return an action recommendation and then need to parse
 * that response into a structured form.
 */
@FunctionalInterface
public interface LlmResponseParser {

    /**
     * Parse a raw LLM response into a structured action response.
     *
     * @param rawResponse The raw text returned by the LLM
     * @return Parsed LLM action response, or invalid response if parsing fails
     */
    LlmActionResponse parse(String rawResponse);

    /**
     * Get a description of this parser (for debugging/logging).
     */
    default String getDescription() {
        return this.getClass().getSimpleName();
    }
}
