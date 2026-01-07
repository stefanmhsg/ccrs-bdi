package ccrs.core.contingency;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified response model for LLM-generated recovery actions.
 * 
 * Captures the common structure across different LLM interaction types:
 * - action: What to do (navigate, get, post, retry, stop, etc.)
 * - target: Where to do it (URI or null)
 * - explanation: Why (reasoning, advice, rationale)
 * - confidence: How certain (optional)
 * - metadata: Additional context (optional)
 */
public class LlmActionResponse {
    
    private boolean valid;
    private String action;
    private String target;
    private String explanation;
    private Double confidence;
    private Map<String, Object> metadata;
    private String parseError;
    
    public LlmActionResponse() {
        this.metadata = new HashMap<>();
    }
    
    // Factory methods
    
    public static LlmActionResponse valid(String action, String target, String explanation) {
        LlmActionResponse response = new LlmActionResponse();
        response.valid = true;
        response.action = action;
        response.target = target;
        response.explanation = explanation;
        return response;
    }
    
    public static LlmActionResponse invalid(String parseError) {
        LlmActionResponse response = new LlmActionResponse();
        response.valid = false;
        response.parseError = parseError;
        return response;
    }
    
    // Fluent setters
    
    public LlmActionResponse withConfidence(double confidence) {
        this.confidence = confidence;
        return this;
    }
    
    public LlmActionResponse withMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }
    
    public LlmActionResponse withMetadata(Map<String, Object> metadata) {
        this.metadata.putAll(metadata);
        return this;
    }
    
    // Getters
    
    public boolean isValid() {
        return valid && action != null && !action.isEmpty();
    }
    
    public String getAction() {
        return action;
    }
    
    public String getTarget() {
        return target;
    }
    
    public String getExplanation() {
        return explanation;
    }
    
    public Double getConfidence() {
        return confidence;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public String getParseError() {
        return parseError;
    }
    
    // Validation
    
    public boolean hasTarget() {
        return target != null && !target.isEmpty() && !"null".equalsIgnoreCase(target);
    }
    
    public boolean hasExplanation() {
        return explanation != null && !explanation.isEmpty();
    }
    
    public boolean hasConfidence() {
        return confidence != null;
    }
    
    @Override
    public String toString() {
        if (!valid) {
            return "LlmActionResponse[invalid: " + parseError + "]";
        }
        return String.format("LlmActionResponse[action=%s, target=%s, explanation=%s]",
            action, target, explanation != null && explanation.length() > 50 ? 
                explanation.substring(0, 47) + "..." : explanation);
    }
}
