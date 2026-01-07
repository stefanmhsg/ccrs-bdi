package ccrs.capabilities.llm;

import ccrs.core.contingency.LlmActionResponse;
import ccrs.core.contingency.LlmResponseParser;

/**
 * JSON shaped response parser for LLM action responses.
 * 
 * Handles the expected JSON schema:
 * {"action": "...", "target": "...", "reasoning|advice|explanation": "..."}
 * 
 * Features:
 * - Flexible field name matching (reasoning, advice, explanation)
 * - Fallback to plain text extraction if JSON parsing fails
 * - Robust handling of malformed responses
 * - Confidence estimation based on parse quality
 */
public class JsonActionParser implements LlmResponseParser {
    
    private boolean enablePlainTextFallback = true;
    
    @Override
    public LlmActionResponse parse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return LlmActionResponse.invalid("Empty response");
        }
        
        String trimmed = rawResponse.trim();
        
        // Try JSON parsing first
        LlmActionResponse jsonResult = tryJsonParse(trimmed);
        if (jsonResult.isValid()) {
            return jsonResult;
        }
        
        // Fallback to plain text extraction
        if (enablePlainTextFallback) {
            LlmActionResponse textResult = tryPlainTextParse(trimmed);
            if (textResult.isValid()) {
                textResult.withMetadata("parseMethod", "plainText");
                return textResult;
            }
        }
        
        return LlmActionResponse.invalid("Could not parse as JSON or extract action from text");
    }
    
    @Override
    public String getDescription() {
        return "JsonActionParser[plainTextFallback=" + enablePlainTextFallback + "]";
    }
    
    // JSON parsing
    
    private LlmActionResponse tryJsonParse(String json) {
        try {
            String action = extractJsonField(json, "action");
            if (action == null || action.isEmpty()) {
                return LlmActionResponse.invalid("No 'action' field in JSON");
            }
            
            String target = extractJsonField(json, "target");
            
            // Try multiple field names for explanation
            String explanation = extractJsonField(json, "reasoning");
            if (explanation == null) {
                explanation = extractJsonField(json, "advice");
            }
            if (explanation == null) {
                explanation = extractJsonField(json, "explanation");
            }
            if (explanation == null) {
                explanation = extractJsonField(json, "rationale");
            }
            
            LlmActionResponse response = LlmActionResponse.valid(action, target, explanation);
            response.withMetadata("parseMethod", "json");
            
            // Try to extract confidence if present
            String confidenceStr = extractJsonField(json, "confidence");
            if (confidenceStr != null) {
                try {
                    double conf = Double.parseDouble(confidenceStr);
                    response.withConfidence(conf);
                } catch (NumberFormatException e) {
                    // Ignore invalid confidence
                }
            }
            
            return response;
            
        } catch (Exception e) {
            return LlmActionResponse.invalid("JSON parse error: " + e.getMessage());
        }
    }
    
    /**
     * Simple JSON field extraction without dependencies.
     * Handles: "field": "value" and field: "value"
     */
    private String extractJsonField(String json, String fieldName) {
        // Try with quotes first: "field"
        String pattern = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(pattern);
        
        // Try without quotes: field:
        if (keyIndex < 0) {
            pattern = fieldName + "\"?\\s*:";
            keyIndex = json.indexOf(fieldName + "\"");
            if (keyIndex < 0) {
                keyIndex = json.indexOf(fieldName + ":");
            }
        }
        
        if (keyIndex < 0) return null;
        
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex < 0) return null;
        
        // Find opening quote of value
        int valueStart = json.indexOf("\"", colonIndex);
        if (valueStart < 0) return null;
        
        // Find closing quote (handle escaped quotes)
        int valueEnd = findClosingQuote(json, valueStart + 1);
        if (valueEnd < 0) return null;
        
        String value = json.substring(valueStart + 1, valueEnd);
        
        // Normalize "null" string to null
        return "null".equalsIgnoreCase(value.trim()) ? null : value;
    }
    
    private int findClosingQuote(String str, int start) {
        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '"') {
                // Check if escaped
                if (i > 0 && str.charAt(i - 1) == '\\') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }
    
    // Plain text fallback
    
    private LlmActionResponse tryPlainTextParse(String text) {
        String action = extractActionFromText(text);
        if (action == null) {
            return LlmActionResponse.invalid("No recognizable action in text");
        }
        
        // Try to extract target (look for URIs)
        String target = extractUriFromText(text);
        
        // Use the full text as explanation (truncated)
        String explanation = text.length() > 200 ? text.substring(0, 197) + "..." : text;
        
        LlmActionResponse response = LlmActionResponse.valid(action, target, explanation);
        response.withConfidence(0.3);  // Lower confidence for text extraction
        return response;
    }
    
    private String extractActionFromText(String text) {
        String lower = text.toLowerCase();
        
        // Order matters - check more specific patterns first
        if (lower.contains("navigate") || lower.contains("go to") || lower.contains("move to")) {
            return "navigate";
        }
        if (lower.contains("backtrack") || lower.contains("go back")) {
            return "backtrack";
        }
        if (lower.contains("retry") || lower.contains("try again")) {
            return "retry";
        }
        if (lower.contains("post") || lower.contains("submit")) {
            return "post";
        }
        if (lower.contains("get") || lower.contains("fetch") || lower.contains("retrieve")) {
            return "get";
        }
        if (lower.contains("stop") || lower.contains("give up") || lower.contains("abort") || lower.contains("abandon")) {
            return "stop";
        }
        
        return null;
    }
    
    private String extractUriFromText(String text) {
        // Simple URI detection - look for http(s):// patterns
        int httpIndex = text.indexOf("http://");
        if (httpIndex < 0) {
            httpIndex = text.indexOf("https://");
        }
        
        if (httpIndex >= 0) {
            // Find end of URI (space, quote, or end of string)
            int endIndex = text.length();
            for (int i = httpIndex; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ' ' || c == '"' || c == '\'' || c == '\n' || c == ')') {
                    endIndex = i;
                    break;
                }
            }
            return text.substring(httpIndex, endIndex);
        }
        
        return null;
    }
    
    // Configuration
    
    public JsonActionParser enablePlainTextFallback(boolean enable) {
        this.enablePlainTextFallback = enable;
        return this;
    }
    
    /**
     * Create a new parser with default settings.
     * Plain text fallback enabled.
     */
    public static JsonActionParser create() {
        return new JsonActionParser();
    }
    
    /**
     * Create a strict parser that only accepts valid JSON.
     */
    public static JsonActionParser strict() {
        return new JsonActionParser().enablePlainTextFallback(false);
    }
}
