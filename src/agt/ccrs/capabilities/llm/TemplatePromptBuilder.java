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
    private String consultationTemplate;
    
    public TemplatePromptBuilder() {
        this.predictionTemplate = buildPredictionTemplate();
        this.consultationTemplate = buildConsultationTemplate();
    }
    
    @Override
    public String buildPredictionPrompt(Map<String, Object> contextMap) {
        return predictionTemplate
            .replace("{currentResource}", getString(contextMap, "currentResource", "unknown"))
            .replace("{targetResource}", getString(contextMap, "targetResource", "unknown"))
            .replace("{failedAction}", getString(contextMap, "failedAction", "unknown"))
            .replace("{errorInfo}", getString(contextMap, "errorInfo", "none"))
            .replace("{attemptedStrategies}", getString(contextMap, "attemptedStrategies", "[]"))
            .replace("{recentActions}", getString(contextMap, "recentActions", "No action history available."))
            .replace("{knowledgeBase}", getString(contextMap, "knowledge", "No knowledge available."));
    }
    
    @Override
    public String buildConsultationPrompt(String question, Map<String, Object> contextMap) {
        return consultationTemplate
            .replace("{question}", question)
            .replace("{context}", formatContextMap(contextMap));
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
            You are a specialized assistant helping a hypermedia navigation agent recover from failures.
            
            # Current Situation
            Location: {currentResource}
            Failed Action: {failedAction} on {targetResource}
            Error Details: {errorInfo}
            Previously Attempted Recovery: {attemptedStrategies}
            
            # Recent Action History
            {recentActions}
            
            # Available Knowledge (RDF Graph)
            {knowledgeBase}
            
            # Instructions
            Analyze the situation and suggest ONE concrete recovery action.
            Consider:
            1. What resources/links are available in the knowledge base
            2. What actions have already been tried (avoid repeating)
            3. Whether the error suggests a specific recovery path
            
            # Response Format
            Respond ONLY with valid JSON (no markdown, no explanations):
            {"action": "<action_type>", "target": "<uri_or_null>", "reasoning": "<one_sentence>"}
            
            Valid action_type values: navigate, get, post, retry, stop
            """;
    }
    
    private String buildConsultationTemplate() {
        return """
            You are an expert consultant advising an autonomous navigation agent facing difficulties.
            
            # Agent's Problem
            {question}
            
            # Context Information
            {context}
            
            # Instructions
            The agent has already tried several approaches and needs your expertise.
            Provide clear, actionable advice considering:
            1. The agent operates in a hypermedia environment (RESTful navigation)
            2. It can navigate links, make GET/POST requests, or abandon the task
            3. Your advice should be immediately executable
            
            # Response Format
            Respond ONLY with valid JSON (no markdown, no explanations):
            {"action": "<action_type>", "target": "<uri_or_null>", "advice": "<concrete_guidance>"}
            
            Valid action_type values: navigate, get, post, retry, backtrack, stop
            """;
    }
    
    // Configuration
    
    public TemplatePromptBuilder withPredictionTemplate(String template) {
        this.predictionTemplate = template;
        return this;
    }
    
    public TemplatePromptBuilder withConsultationTemplate(String template) {
        this.consultationTemplate = template;
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
