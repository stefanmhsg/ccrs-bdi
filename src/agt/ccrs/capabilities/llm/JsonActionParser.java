package ccrs.capabilities.llm;

import ccrs.core.contingency.LlmResponseParser;
import ccrs.core.contingency.dto.LlmActionResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON shaped response parser for LLM action responses.
 * Used by {@link ccrs.core.contingency.strategies.internal.PredictionLlmStrategy}
 * 
 * Handles the expected JSON schema:
 * {"action": "...", "target": "...", "request": {"method": "...", "headers": {...}, "body": "..."},
 *  "reasoning|advice|explanation": "..."}
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

            String request = extractJsonObject(json, "request");
            String requestSource = request != null ? request : json;

            String method = firstNonBlank(
                extractJsonField(requestSource, "method"),
                inferMethodFromAction(action));
            if (method != null) {
                response.withMethod(method.toUpperCase());
            }

            Map<String, String> headers = extractStringMap(requestSource, "headers");
            if (!headers.isEmpty()) {
                response.withHeaders(headers);
            }

            String body = extractJsonField(requestSource, "body");
            if (body != null) {
                response.withBody(body);
            }

            String bodyContentType = firstNonBlank(
                extractJsonField(requestSource, "bodyContentType"),
                extractJsonField(requestSource, "contentType"));
            if (bodyContentType != null) {
                response.withBodyContentType(bodyContentType);
            }
            
            // Try to extract confidence if present
            Double confidence = extractConfidence(json);
            if (confidence != null) {
                response.withConfidence(confidence);
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
        
        int valueTokenStart = colonIndex + 1;
        while (valueTokenStart < json.length() && Character.isWhitespace(json.charAt(valueTokenStart))) {
            valueTokenStart++;
        }

        if (startsWithLiteral(json, valueTokenStart, "null")) {
            return null;
        }

        if (valueTokenStart >= json.length() || json.charAt(valueTokenStart) != '"') {
            return null;
        }

        // Find closing quote (handle escaped quotes)
        int valueEnd = findClosingQuote(json, valueTokenStart + 1);
        if (valueEnd < 0) return null;
        
        String value = json.substring(valueTokenStart + 1, valueEnd);
        
        // Normalize "null" string to null
        return "null".equalsIgnoreCase(value.trim()) ? null : value;
    }

    private boolean startsWithLiteral(String json, int index, String literal) {
        if (index < 0 || index + literal.length() > json.length()) {
            return false;
        }
        if (!json.regionMatches(true, index, literal, 0, literal.length())) {
            return false;
        }
        int afterLiteral = index + literal.length();
        return afterLiteral >= json.length() || isJsonValueBoundary(json.charAt(afterLiteral));
    }

    private boolean isJsonValueBoundary(char c) {
        return Character.isWhitespace(c) || c == ',' || c == '}' || c == ']';
    }

    private String extractJsonObject(String json, String fieldName) {
        int keyIndex = json.indexOf("\"" + fieldName + "\"");
        if (keyIndex < 0) {
            keyIndex = json.indexOf(fieldName + ":");
        }
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex < 0) return null;

        int objectStart = json.indexOf("{", colonIndex);
        if (objectStart < 0) return null;

        int depth = 0;
        boolean inString = false;
        for (int i = objectStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(objectStart, i + 1);
                }
            }
        }

        return null;
    }

    private Map<String, String> extractStringMap(String json, String fieldName) {
        Map<String, String> values = new LinkedHashMap<>();
        String object = extractJsonObject(json, fieldName);
        if (object == null) {
            return values;
        }

        Pattern entryPattern = Pattern.compile(
            "\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""
        );
        Matcher matcher = entryPattern.matcher(object);
        while (matcher.find()) {
            values.put(unescapeJsonString(matcher.group(1)), unescapeJsonString(matcher.group(2)));
        }
        return values;
    }

    private String inferMethodFromAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.toLowerCase()) {
            case "get", "post", "put", "patch", "delete" -> action;
            default -> null;
        };
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private String unescapeJsonString(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
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

    private Double extractConfidence(String json) {
        String confidenceStr = extractJsonField(json, "confidence");
        if (confidenceStr != null) {
            return parseConfidence(confidenceStr);
        }
        return parseConfidence(extractUnquotedJsonField(json, "confidence"));
    }

    private Double parseConfidence(String confidenceStr) {
        if (confidenceStr == null || confidenceStr.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(confidenceStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractUnquotedJsonField(String json, String fieldName) {
        Pattern pattern = Pattern.compile(
            "\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*([+\\-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+\\-]?\\d+)?)"
        );
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
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
