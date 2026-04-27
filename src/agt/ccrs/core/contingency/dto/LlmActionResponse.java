package ccrs.core.contingency.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified response model for LLM-generated recovery actions.
 *
 * Captures the common structure of an LLM answer used by CCRS prediction:
 * - action: What to do (navigate, get, post, retry, stop, etc.)
 * - target: Where to do it (URI or null)
 * - explanation: Why (reasoning, advice, rationale)
 * - confidence: How certain (optional)
 * - metadata: Additional parser context (optional)
 */
public class LlmActionResponse {

    private boolean valid;
    private String action;
    private String target;
    private String explanation;
    private Double confidence;
    private String method;
    private Map<String, String> headers;
    private String body;
    private String bodyContentType;
    private Map<String, Object> metadata;
    private String parseError;

    public LlmActionResponse() {
        this.headers = new HashMap<>();
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

    public LlmActionResponse withMethod(String method) {
        this.method = method;
        return this;
    }

    public LlmActionResponse withHeaders(Map<String, String> headers) {
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    public LlmActionResponse withBody(String body) {
        this.body = body;
        return this;
    }

    public LlmActionResponse withBodyContentType(String bodyContentType) {
        this.bodyContentType = bodyContentType;
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

    public String getMethod() {
        return method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public String getBodyContentType() {
        return bodyContentType;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getParseError() {
        return parseError;
    }

    public boolean hasTarget() {
        return target != null && !target.isEmpty() && !"null".equalsIgnoreCase(target);
    }

    public boolean hasExplanation() {
        return explanation != null && !explanation.isEmpty();
    }

    public boolean hasConfidence() {
        return confidence != null;
    }

    public boolean hasMethod() {
        return method != null && !method.isBlank() && !"null".equalsIgnoreCase(method);
    }

    public boolean hasHeaders() {
        return headers != null && !headers.isEmpty();
    }

    public boolean hasBody() {
        return body != null && !body.isBlank() && !"null".equalsIgnoreCase(body);
    }

    public boolean hasBodyContentType() {
        return bodyContentType != null && !bodyContentType.isBlank() && !"null".equalsIgnoreCase(bodyContentType);
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
