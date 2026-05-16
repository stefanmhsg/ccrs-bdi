package ccrs.core.contingency.strategies.internal.prediction;

import java.util.Map;

import ccrs.core.contingency.PromptBuilder;

/**
 * Default prompt builder for {@link PredictionLlmStrategy}.
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
public class DefaultPredictionPromptBuilder implements PromptBuilder {
    
    private String predictionTemplate;
    
    public DefaultPredictionPromptBuilder() {
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
            .replace("{localNeighborhood}", getString(contextMap, "localNeighborhood", "No local neighborhood available."));
    }
       
    @Override
    public String getDescription() {
        return "DefaultPredictionPromptBuilder";
    }
    
    // Formatting helpers
    
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value);
    }
    
    // Standard templates
    
    private String buildPredictionTemplate() {
        return """
            You are a specialized assistant helping a hypermedia navigation agent choose a recovery or next-step action.
            You are part of a framework called "Course Check and Revision Strategies" (CCRS) that bundles several strategies to provide runtime guidance to autonomous agents.
            You are considered the "prediction_llm" strategy and you now have to consider the following context and instructions to output your guidance.
            You should not interfere with other strategies, so if you find that the situation is outside your scope or that there is not enough evidence to support a concrete suggestion, you should explicitly return no suggestion instead of trying to guess.
                        
            # Current Situation
            {situationDetails}
            
            # Recent Action History
            {recentActions}

            # Previous CCRS Invocations
            {ccrsHistory}
            
            # Local RDF Neighborhood
            {localNeighborhood}
            
            # Instructions
            Analyze the situation and suggest ONE concrete recovery action only when the available evidence supports it.
            Consider:
            1. What is the current situation and state of the agent
            2. What exact requests, responses, and perceived RDF states appear in recent interaction history
            3. If interaction history contains requests that did not have the desired effect and if the request may be wrong - even if not resulting in an error.
            4. What resources/links are available in the local neighborhood
            5. What recovery strategies or suggestions have already been tried in previous CCRS invocations
            6. Whether the error suggests a specific recovery path
            7. Hypermedia environment is modeled as RDF in Text/Turtle format. Triples are in the form <subject> <predicate> <object>.
            8. Estimated confidence that the suggested action will improve the agent's situation
            9. If there is no safe, concrete, helpful action, explicitly return no suggestion by leaving the action and action-related fields null. This will be treated as a NoHelp result, not as an error. Always include a concise reasoning value explaining why no suggestion is safer than guessing.
            10. A discovered operation is an affordance description, not the payload:

                When suggesting an HTTP action:
                1. Select the operation from hydra:operation or another advertised affordance.
                2. Use the HTTP method as specified.
                3. Use the request target as specified, otherwise use the resource exposing the operation.
                4. Use the request target also as the <subject> value in the body if no other instructions provided.
                4. Understand that the operation could point to additional information for example to derive the payload constraints.
                5. Do not copy operation metadata into the body unless the expected shape explicitly requires it.

                Payload construction:
                1. If the operation points to a shape - for example, a SHACL shape, inspect its constraints.
                2. For each required property, use it as required in the payload.
                3. Fill values from recent RDF observations, local memory, or literals linked to compatible resources.
                4. Respect SHACL or any other validation rules when present.
                5. If no safe value can be inferred, prefer GET, navigation, or stop over inventing a payload.

                Validation before final answer:
                1. The request body satisfies the expected shape.
                2. The body uses predicates as required, not operation names.
                3. The operation URI appears only as target metadata, unless required by the shape.
                4. The content type matches the body syntax.
                5. The <subject> in the body is consistent with the request target when required.

            Before answering, privately derive:
                operation_uri, method, target, expected_shape, required_paths, candidate_values, body_serialization.
            Only then output the final JSON.
            
            # Response Format
            Respond ONLY with valid JSON (no markdown, no explanations):
            {
              "action": "<action_type_or_null>",
              "target": "<uri_or_null>",
              "request": {
                "method": "<GET|POST|PUT|PATCH|DELETE|null>",
                "headers": {},
                "body": "<request_body_or_null>",
                "bodyContentType": "<mime_type_or_null>"
              },
              "reasoning": "<one_sentence_explanation>",
              "confidence": <0.0_to_1.0_or_null>
            }
            
            Use an empty headers object when no headers are needed for a suggested action. Use null for request fields that are not needed.
            The reasoning field is always required, including for no suggestion responses.
            To explicitly provide no suggestion, return: {"action": null, "target": null, "request": {"method": null, "headers": null, "body": null, "bodyContentType": null}, "reasoning": "<why_no_safe_concrete_helpful_action_is_supported>", "confidence": null}
            Valid action_type values: get, post, put, patch, delete
            """;
    }
        
    // Configuration
    
    public DefaultPredictionPromptBuilder withPredictionTemplate(String template) {
        this.predictionTemplate = template;
        return this;
    }
       
    /**
     * Create a new instance with standard templates.
     * This is the recommended prompt builder for most use cases.
     */
    public static DefaultPredictionPromptBuilder create() {
        return new DefaultPredictionPromptBuilder();
    }
}
