package ccrs.capabilities.llm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ccrs.core.contingency.PromptBuilder;

/**
 * Standard reference implementation of PromptBuilder using template-based formatting.
 * 
 * This is a pure formatter that accepts already-prepared context maps.
 * It does NOT access Situation, CcrsContext, or any CCRS internals.
 * 
 * Backend agnostic - works with any LLM client (OpenAI, Azure, Anthropic, etc.)
 * 
 * Features:
 * - Structured variable substitution
 * - Clear section markers
 * - Consistent JSON output format
 * - Optimized token usage
 * - Smart formatting of complex data types
 */
public class TemplatePromptBuilder implements PromptBuilder {
    
    private String predictionTemplate;
    
    public TemplatePromptBuilder() {
        this.predictionTemplate = buildPredictionTemplate();
    }
    
    @Override
    public String buildPredictionPrompt(Map<String, Object> contextMap) {
        return predictionTemplate
            .replace("{situationDetails}", getString(contextMap, "situationDetails", "No situation details available."))
            .replace("{currentResource}", getString(contextMap, "currentResource", "unknown"))
            .replace("{targetResource}", getString(contextMap, "targetResource", "unknown"))
            .replace("{failedAction}", getString(contextMap, "failedAction", "unknown"))
            .replace("{errorInfo}", getString(contextMap, "errorInfo", "none"))
            .replace("{recentActions}", getString(contextMap, "recentActions", "No action history available."))
            .replace("{ccrsHistory}", getString(contextMap, "ccrsHistory", "No previous CCRS invocations available."))
            .replace("{localNeighborhood}", getString(contextMap, "localNeighborhood", "No local neighborhood available."))
            .replace("{rawMemory}", getString(contextMap, "rawMemory", "No raw RDF memory available."));
    }
       
    @Override
    public String getDescription() {
        return "TemplatePromptBuilder[standard]";
    }
    
    // Formatting helpers
    
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value);
    }
    
    private String formatContextMap(Map<String, Object> contextMap) {
        if (contextMap == null || contextMap.isEmpty()) {
            return "No additional context provided.";
        }
        
        return contextMap.entrySet().stream()
            .map(e -> {
                Object value = e.getValue();
                String valueStr;
                
                // Format complex types nicely
                if (value instanceof List) {
                    valueStr = String.format("List[%d items]", ((List<?>) value).size());
                } else if (value instanceof Map) {
                    valueStr = String.format("Map[%d entries]", ((Map<?, ?>) value).size());
                } else {
                    valueStr = truncate(String.valueOf(value), 100);
                }
                
                return String.format("- %s: %s", e.getKey(), valueStr);
            })
            .collect(Collectors.joining("\n"));
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
    
    // Standard templates
    
    private String buildPredictionTemplate() {
        return """
            You are a specialized assistant helping a hypermedia navigation agent choose a recovery or next-step action.
            
            # Current Situation
            {situationDetails}
            
            # Recent Action History
            {recentActions}

            # Previous CCRS Invocations
            {ccrsHistory}
            
            # Local RDF Neighborhood
            {localNeighborhood}

            # Raw RDF Memory
            {rawMemory}
            
            # Instructions
            Analyze the situation and suggest ONE concrete recovery action.
            Consider:
            1. What is the current situation and state of the agent
            2. What exact requests, responses, and perceived RDF states appear in recent interaction history
            3. If interaction history contains requests that did not have the desired effect and if the request may be wrong - even if not resulting in an error.
            4. What resources/links are available in the local neighborhood
            5. What recovery strategies or suggestions have already been tried in previous CCRS invocations
            6. Whether the error suggests a specific recovery path
            7. Hypermedia environment is modeled as RDF in Text/Turtle format. Triples are in the form <subject> <predicate> <object>.
            8. Estimated confidence that the suggested action will improve the agent's situation
            9. A discovered operation is an affordance description, not the payload:

                When suggesting an HTTP action:
                1. Select the operation from hydra:operation or another advertised affordance.
                2. Use hydra:method as the HTTP method.
                3. Use hydra:target as the request target if present, otherwise use the resource exposing the operation.
                4. Use hydra:expects only to derive the payload constraints.
                5. Do not copy operation metadata into the body unless the expected shape explicitly requires it.

                Payload construction:
                1. If hydra:expects points to a SHACL shape, inspect its sh:property constraints.
                2. For each required property, use sh:path as the predicate in the payload.
                3. Fill values from recent RDF observations, local memory, or literals linked to compatible resources.
                4. Respect sh:datatype, sh:class, sh:nodeKind, sh:minCount, sh:maxCount, sh:in, and sh:hasValue when present.
                5. If no safe value can be inferred, prefer GET, navigation, or stop over inventing a payload.

                Validation before final answer:
                1. The request body satisfies the expected shape.
                2. The body uses predicates from sh:path, not operation names.
                3. The operation URI appears only as target metadata, unless required by the shape.
                4. The content type matches the body syntax.

            Before answering, privately derive:
                operation_uri, method, target, expected_shape, required_paths, candidate_values, body_serialization.
            Only then output the final JSON.
            
            # Response Format
            Respond ONLY with valid JSON (no markdown, no explanations):
            {
              "action": "<action_type>",
              "target": "<uri_or_null>",
              "request": {
                "method": "<GET|POST|PUT|PATCH|DELETE|null>",
                "headers": {"<header_name>": "<header_value>"},
                "body": "<request_body_or_null>",
                "bodyContentType": "<mime_type_or_null>"
              },
              "reasoning": "<one_sentence>",
              "confidence": <0.0_to_1.0>
            }
            
            Use an empty headers object when no headers are needed. Use null for request fields that are not needed.
            Valid action_type values: navigate, get, post, put, patch, delete, retry, stop
            """;
    }
        
    // Configuration
    
    public TemplatePromptBuilder withPredictionTemplate(String template) {
        this.predictionTemplate = template;
        return this;
    }
       
    /**
     * Create a new instance with standard templates.
     * This is the recommended prompt builder for most use cases.
     */
    public static TemplatePromptBuilder create() {
        return new TemplatePromptBuilder();
    }
}
