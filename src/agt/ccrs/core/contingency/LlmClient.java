package ccrs.core.contingency;

/**
 * Interface for LLM calls - allows mocking and different implementations.
 * 
 * This interface abstracts the LLM interaction, enabling:
 * - Mocking for tests
 * - Different LLM providers (OpenAI, Azure, local, etc.)
 * - Configuration flexibility (API keys, models, parameters)
 */
@FunctionalInterface
public interface LlmClient {
    
    /**
     * Send a prompt to the LLM and get a response.
     * 
     * @param prompt The prompt to send
     * @return LLM response text
     * @throws Exception if LLM call fails
     */
    String complete(String prompt) throws Exception;
    
    /**
     * Check if the client is properly configured and ready to use.
     * Default implementation returns true.
     * 
     * @return true if the client can accept requests
     */
    default boolean isAvailable() {
        return true;
    }
    
    /**
     * Get a description of this client for logging/debugging.
     * 
     * @return A human-readable description of the client
     */
    default String getDescription() {
        return "LlmClient";
    }
}
