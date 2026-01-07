package ccrs.capabilities.llm.langchain4j;

import ccrs.core.contingency.LlmClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Langchain4j-based implementation of the LlmClient interface.
 * 
 * Uses Langchain4j's ChatModel for chat completions, supporting:
 * - OpenAI (default)
 * - Azure OpenAI
 * - Any OpenAI-compatible API (Groq, Together, local LLMs, etc.)
 * 
 * Example usage:
 * <pre>
 * // From environment (OPENAI_API_KEY)
 * LlmClient client = Langchain4jLlmClient.fromEnvironment();
 * 
 * // With explicit API key
 * LlmClient client = Langchain4jLlmClient.create("sk-...");
 * 
 * // With custom configuration
 * LlmClient client = Langchain4jLlmClient.builder()
 *     .config(Langchain4jConfig.builder()
 *         .apiKey("sk-...")
 *         .modelName("gpt-4o")
 *         .temperature(0.3)
 *         .build())
 *     .build();
 * 
 * // With custom model (e.g., local LLM)
 * ChatModel customModel = OpenAiChatModel.builder()
 *     .baseUrl("http://localhost:11434/v1")
 *     .apiKey("ollama")
 *     .modelName("llama3")
 *     .build();
 * LlmClient client = Langchain4jLlmClient.fromModel(customModel);
 * </pre>
 */
public class Langchain4jLlmClient implements LlmClient {
    
    private final ChatModel chatModel;
    private final Langchain4jConfig config;
    private final String description;
    
    private Langchain4jLlmClient(ChatModel chatModel, Langchain4jConfig config, String description) {
        this.chatModel = chatModel;
        this.config = config;
        this.description = description;
    }
    
    @Override
    public String complete(String prompt) throws Exception {
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel not initialized. Check API key configuration.");
        }
        
        try {
            return chatModel.chat(prompt);
        } catch (Exception e) {
            // Wrap with more context
            throw new Exception("LLM completion failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isAvailable() {
        return chatModel != null;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    /**
     * Get the underlying configuration.
     */
    public Langchain4jConfig getConfig() {
        return config;
    }
    
    /**
     * Get the underlying Langchain4j ChatModel.
     */
    public ChatModel getChatModel() {
        return chatModel;
    }
    
    // Factory methods
    
    /**
     * Create a client from environment variables.
     * Requires OPENAI_API_KEY or LLM_API_KEY to be set.
     */
    public static Langchain4jLlmClient fromEnvironment() {
        Langchain4jConfig config = Langchain4jConfig.fromEnvironment()
            .modelName("gpt-4o-mini")
            .temperature(0.7)
            .build();
        
        return builder().config(config).build();
    }
    
    /**
     * Create a client with just an API key (uses OpenAI defaults).
     */
    public static Langchain4jLlmClient create(String apiKey) {
        Langchain4jConfig config = Langchain4jConfig.openAi(apiKey);
        return builder().config(config).build();
    }
    
    /**
     * Create a client from a pre-configured ChatModel.
     */
    public static Langchain4jLlmClient fromModel(ChatModel model) {
        return fromModel(model, "Custom ChatModel");
    }
    
    /**
     * Create a client from a pre-configured ChatModel with description.
     */
    public static Langchain4jLlmClient fromModel(ChatModel model, String description) {
        return new Langchain4jLlmClient(model, null, description);
    }
    
    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for Langchain4jLlmClient.
     */
    public static class Builder {
        private Langchain4jConfig config;
        private ChatModel chatModel;
        
        /**
         * Set the configuration.
         */
        public Builder config(Langchain4jConfig config) {
            this.config = config;
            return this;
        }
        
        /**
         * Set a pre-built ChatModel directly.
         * If set, this takes precedence over config.
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }
        
        /**
         * Build the client.
         */
        public Langchain4jLlmClient build() {
            // If chatModel provided directly, use it
            if (chatModel != null) {
                return new Langchain4jLlmClient(chatModel, config, "Custom ChatModel");
            }
            
            // Otherwise build from config
            if (config == null) {
                throw new IllegalStateException("Either config or chatModel must be provided");
            }
            
            if (!config.isValid()) {
                throw new IllegalStateException("Invalid configuration: API key is required");
            }
            
            ChatModel model = buildChatModel(config);
            String description = buildDescription(config);
            
            return new Langchain4jLlmClient(model, config, description);
        }
        
        private ChatModel buildChatModel(Langchain4jConfig cfg) {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(cfg.getApiKey());
            
            // Base URL (for custom endpoints)
            if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
                builder.baseUrl(cfg.getBaseUrl());
            }
            
            // Model name
            if (cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
                builder.modelName(cfg.getModelName());
            }
            
            // Model parameters
            if (cfg.getTemperature() != null) {
                builder.temperature(cfg.getTemperature());
            }
            if (cfg.getMaxTokens() != null) {
                builder.maxTokens(cfg.getMaxTokens());
            }
            if (cfg.getTopP() != null) {
                builder.topP(cfg.getTopP());
            }
            
            // Client parameters
            if (cfg.getTimeout() != null) {
                builder.timeout(cfg.getTimeout());
            }
            if (cfg.getMaxRetries() != null) {
                builder.maxRetries(cfg.getMaxRetries());
            }
            
            // Logging
            builder.logRequests(cfg.isLogRequests());
            builder.logResponses(cfg.isLogResponses());
            
            // OpenAI-specific
            if (cfg.getOrganizationId() != null && !cfg.getOrganizationId().isBlank()) {
                builder.organizationId(cfg.getOrganizationId());
            }
            if (cfg.getProjectId() != null && !cfg.getProjectId().isBlank()) {
                builder.projectId(cfg.getProjectId());
            }
            
            return builder.build();
        }
        
        private String buildDescription(Langchain4jConfig cfg) {
            StringBuilder sb = new StringBuilder("Langchain4j[");
            
            if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
                sb.append(cfg.getBaseUrl());
            } else {
                sb.append("OpenAI");
            }
            
            sb.append("/").append(cfg.getModelName());
            sb.append("]");
            
            return sb.toString();
        }
    }
}
