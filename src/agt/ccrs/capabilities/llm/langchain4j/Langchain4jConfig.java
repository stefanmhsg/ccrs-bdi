package ccrs.capabilities.llm.langchain4j;

import java.time.Duration;

import ccrs.capabilities.ConfigResolver;

/**
 * Configuration for Langchain4j-based LLM clients.
 * 
 * Supports configuration via:
 * - Direct builder pattern
 * - Environment variables (for API keys)
 * - System properties
 */
public class Langchain4jConfig {
    
    // Connection settings
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    
    // Model parameters
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    
    // Client settings
    private final Duration timeout;
    private final Integer maxRetries;
    private final boolean logRequests;
    private final boolean logResponses;
    
    // Provider-specific
    private final String organizationId;
    private final String projectId;
    
    private Langchain4jConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.modelName = builder.modelName;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.logRequests = builder.logRequests;
        this.logResponses = builder.logResponses;
        this.organizationId = builder.organizationId;
        this.projectId = builder.projectId;
    }
    
    // Getters
    
    public String getApiKey() {
        return apiKey;
    }
    
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public String getModelName() {
        return modelName;
    }
    
    public Double getTemperature() {
        return temperature;
    }
    
    public Integer getMaxTokens() {
        return maxTokens;
    }
    
    public Double getTopP() {
        return topP;
    }
    
    public Duration getTimeout() {
        return timeout;
    }
    
    public Integer getMaxRetries() {
        return maxRetries;
    }
    
    public boolean isLogRequests() {
        return logRequests;
    }
    
    public boolean isLogResponses() {
        return logResponses;
    }
    
    public String getOrganizationId() {
        return organizationId;
    }
    
    public String getProjectId() {
        return projectId;
    }
    
    /**
     * Check if the configuration has the minimum required settings.
     */
    public boolean isValid() {
        return apiKey != null && !apiKey.isBlank();
    }
    
    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Create a builder initialized from environment variables.
     * 
     * Looks for:
     * - OPENAI_API_KEY or LLM_API_KEY
     * - OPENAI_BASE_URL or LLM_BASE_URL
     * - OPENAI_MODEL or LLM_MODEL
     * - OPENAI_ORGANIZATION_ID
     */
    public static Builder fromEnvironment() {
        Builder builder = new Builder();
    
        String apiKey =
            firstNonNull(
                ConfigResolver.resolve("OPENAI_API_KEY"),
                ConfigResolver.resolve("LLM_API_KEY")
            );
        
        if (apiKey == null) {
            throw new IllegalStateException(
                "No API key found in environment, system properties, or .env"
            );
        }
    
        builder.apiKey(apiKey);
    
        String baseUrl =
            firstNonNull(
                ConfigResolver.resolve("OPENAI_BASE_URL"),
                ConfigResolver.resolve("LLM_BASE_URL")
            );
        if (baseUrl != null) builder.baseUrl(baseUrl);
        
        String model =
            firstNonNull(
                ConfigResolver.resolve("OPENAI_MODEL"),
                ConfigResolver.resolve("LLM_MODEL")
            );
        if (model != null) builder.modelName(model);
        
        String orgId = ConfigResolver.resolve("OPENAI_ORGANIZATION_ID");
        if (orgId != null) builder.organizationId(orgId);
        
        return builder;
    }
    
    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
    
    /**
     * Create a minimal configuration for OpenAI with just an API key.
     */
    public static Langchain4jConfig openAi(String apiKey) {
        return builder()
            .apiKey(apiKey)
            .modelName("gpt-4o-mini")
            .temperature(0.7)
            .build();
    }
    
    /**
     * Create a minimal configuration for OpenAI from environment.
     */
    public static Langchain4jConfig openAiFromEnv() {
        return fromEnvironment()
            .modelName("gpt-4o-mini")
            .temperature(0.7)
            .build();
    }
    
    @Override
    public String toString() {
        return "Langchain4jConfig{" +
            "baseUrl='" + baseUrl + '\'' +
            ", modelName='" + modelName + '\'' +
            ", apiKey='" + (apiKey != null ? "[REDACTED]" : "null") + '\'' +
            ", temperature=" + temperature +
            ", maxTokens=" + maxTokens +
            '}';
    }
    
    /**
     * Builder for Langchain4jConfig.
     */
    public static class Builder {
        private String apiKey;
        private String baseUrl;
        private String modelName = "gpt-4o-mini";
        private Double temperature = 0.7;
        private Integer maxTokens;
        private Double topP;
        private Duration timeout = Duration.ofSeconds(60);
        private Integer maxRetries = 3;
        private boolean logRequests = false;
        private boolean logResponses = false;
        private String organizationId;
        private String projectId;
        
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }
        
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }
        
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }
        
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }
        
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }
        
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }
        
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }
        
        public Builder timeoutSeconds(long seconds) {
            this.timeout = Duration.ofSeconds(seconds);
            return this;
        }
        
        public Builder maxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder logRequests(boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }
        
        public Builder logResponses(boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }
        
        public Builder organizationId(String organizationId) {
            this.organizationId = organizationId;
            return this;
        }
        
        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }
        
        public Langchain4jConfig build() {
            return new Langchain4jConfig(this);
        }
    }
}
