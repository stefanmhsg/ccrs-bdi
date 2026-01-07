package ccrs.core.contingency.strategies.internal;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ccrs.core.contingency.ActionRecord;
import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.LlmClient;
import ccrs.core.contingency.Situation;
import ccrs.core.contingency.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;

/**
 * L2: Prediction Strategy (LLM-based)
 * 
 * Uses a Large Language Model to predict a recovery action based on 
 * the situation and available context. This is a more flexible approach
 * that can handle novel situations.
 * 
 * For POC: Uses a pluggable LLM interface that can be mocked or connected
 * to real LLM services.
 */
public class PredictionLlmStrategy implements CcrsStrategy {
    
    public static final String ID = "prediction_llm";
    
    /**
     * Parsed LLM response for recovery suggestion.
     */
    public static class LlmSuggestion {
        public String action;
        public String target;
        public String reasoning;
        public Map<String, Object> params;
        
        public boolean isValid() {
            return action != null && !action.isEmpty();
        }
    }
    
    // Configuration
    private LlmClient llmClient;
    private String promptTemplate;
    private double baseConfidence = 0.6;
    
    public PredictionLlmStrategy() {
        this.promptTemplate = buildDefaultPromptTemplate();
    }
    
    public PredictionLlmStrategy(LlmClient llmClient) {
        this();
        this.llmClient = llmClient;
    }
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Prediction (LLM)";
    }
    
    @Override
    public Category getCategory() {
        return Category.INTERNAL;
    }
    
    @Override
    public int getEscalationLevel() {
        return 2;
    }
    
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        // Need LLM client configured
        if (llmClient == null && !context.hasLlmAccess()) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Don't apply if already tried
        if (situation.hasAttempted(ID)) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Need enough context to be useful
        if (situation.getCurrentResource() == null && 
            context.getCurrentResource().isEmpty()) {
            return Applicability.NOT_APPLICABLE;
        }
        
        return Applicability.APPLICABLE;
    }
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        if (llmClient == null) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "LLM client not configured");
        }
        
        try {
            // Build prompt
            String prompt = buildPrompt(situation, context);
            
            // Call LLM
            String response = llmClient.complete(prompt);
            
            // Parse response
            LlmSuggestion suggestion = parseResponse(response);
            
            if (!suggestion.isValid()) {
                return StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Could not parse valid suggestion from LLM response");
            }
            
            // Build result
            StrategyResult.Suggestion.Builder builder = StrategyResult.suggest(ID, suggestion.action)
                .target(suggestion.target)
                .param("llmGenerated", true)
                .param("originalReasoning", suggestion.reasoning)
                .confidence(baseConfidence)
                .cost(0.4)  // Moderate cost - LLM call + uncertainty
                .rationale(buildRationale(suggestion));
            
            if (suggestion.params != null) {
                builder.params(suggestion.params);
            }
            
            return builder.build();
            
        } catch (Exception e) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.EVALUATION_FAILED,
                "LLM call failed: " + e.getMessage());
        }
    }
    
    private String buildPrompt(Situation situation, CcrsContext context) {
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse("unknown");
        }
        
        // Gather context
        String historyStr = formatHistory(context);
        String triplesStr = formatTriples(context);
        String errorStr = formatError(situation);
        
        return promptTemplate
            .replace("{currentResource}", currentResource)
            .replace("{targetResource}", nullSafe(situation.getTargetResource()))
            .replace("{failedAction}", nullSafe(situation.getFailedAction()))
            .replace("{errorInfo}", errorStr)
            .replace("{recentActions}", historyStr)
            .replace("{knowledgeBase}", triplesStr)
            .replace("{attemptedStrategies}", situation.getAttemptedStrategies().toString());
    }
    
    private String formatHistory(CcrsContext context) {
        if (!context.hasHistory()) {
            return "(no history available)";
        }
        
        List<ActionRecord> actions = context.getRecentActions(5);
        if (actions.isEmpty()) {
            return "(no recent actions)";
        }
        
        return actions.stream()
            .map(a -> String.format("%s %s -> %s", a.getActionType(), a.getTarget(), a.getOutcome()))
            .collect(Collectors.joining("\n"));
    }
    
    private String formatTriples(CcrsContext context) {
        List<RdfTriple> triples = context.queryAll();
        if (triples.isEmpty()) {
            return "(no triples available)";
        }
        
        // Limit to avoid prompt explosion
        return triples.stream()
            .limit(50)
            .map(RdfTriple::toString)
            .collect(Collectors.joining("\n"));
    }
    
    private String formatError(Situation situation) {
        Map<String, Object> errorInfo = situation.getErrorInfo();
        if (errorInfo.isEmpty()) {
            return nullSafe(situation.getTrigger());
        }
        
        return errorInfo.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining(", "));
    }
    
    private LlmSuggestion parseResponse(String response) {
        LlmSuggestion suggestion = new LlmSuggestion();
        
        // Try to parse JSON-like response
        // Expected format: {"action": "...", "target": "...", "reasoning": "..."}
        
        // Simple parsing - could use a JSON library for robustness
        suggestion.action = extractJsonValue(response, "action");
        suggestion.target = extractJsonValue(response, "target");
        suggestion.reasoning = extractJsonValue(response, "reasoning");
        
        // Fallback: try to extract action from plain text
        if (suggestion.action == null) {
            suggestion.action = extractActionFromText(response);
            suggestion.reasoning = response;
        }
        
        return suggestion;
    }
    
    // TODO: Replace with proper JSON parsing
    private String extractJsonValue(String json, String key) {
        // Simple regex-free extraction for "key": "value"
        String pattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(pattern);
        if (keyIndex < 0) {
            // Try without quotes on key
            pattern = key + ":";
            keyIndex = json.indexOf(pattern);
        }
        if (keyIndex < 0) return null;
        
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex < 0) return null;
        
        int valueStart = json.indexOf("\"", colonIndex);
        if (valueStart < 0) return null;
        
        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd < 0) return null;
        
        return json.substring(valueStart + 1, valueEnd);
    }
    
    private String extractActionFromText(String text) {
        String lower = text.toLowerCase();
        
        if (lower.contains("navigate") || lower.contains("go to") || lower.contains("move to")) {
            return "navigate";
        }
        if (lower.contains("retry") || lower.contains("try again")) {
            return "retry";
        }
        if (lower.contains("post") || lower.contains("submit")) {
            return "post";
        }
        if (lower.contains("get") || lower.contains("fetch")) {
            return "get";
        }
        if (lower.contains("stop") || lower.contains("give up") || lower.contains("abort")) {
            return "stop";
        }
        if (lower.contains("backtrack") || lower.contains("go back")) {
            return "navigate";  // Backtrack is a navigation
        }
        
        return null;
    }
    
    private String buildRationale(LlmSuggestion suggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("LLM suggests: ").append(suggestion.action);
        if (suggestion.target != null) {
            sb.append(" to ").append(suggestion.target);
        }
        sb.append(". ");
        if (suggestion.reasoning != null && !suggestion.reasoning.isEmpty()) {
            // Truncate long reasoning
            String reasoning = suggestion.reasoning;
            if (reasoning.length() > 200) {
                reasoning = reasoning.substring(0, 197) + "...";
            }
            sb.append("Reasoning: ").append(reasoning);
        }
        return sb.toString();
    }
    
    private String buildDefaultPromptTemplate() {
        return """
            You are assisting a navigation agent in a hypermedia environment.
            The agent has encountered a problem and needs guidance on how to recover.
            
            ## Current Situation
            - Current location: {currentResource}
            - Failed action: {failedAction} on {targetResource}
            - Error: {errorInfo}
            - Already tried: {attemptedStrategies}
            
            ## Recent Actions
            {recentActions}
            
            ## Available Knowledge (RDF triples)
            {knowledgeBase}
            
            ## Task
            Analyze the situation and suggest a recovery action.
            Consider what resources are available and what the agent might do next.
            
            Respond with JSON in this exact format:
            {"action": "<action_type>", "target": "<uri_or_null>", "reasoning": "<brief_explanation>"}
            
            Where action_type is one of: navigate, get, post, retry, stop
            """;
    }
    
    private String nullSafe(String value) {
        return value != null ? value : "unknown";
    }
    
    // Configuration
    
    public PredictionLlmStrategy withClient(LlmClient client) {
        this.llmClient = client;
        return this;
    }
    
    public PredictionLlmStrategy withPromptTemplate(String template) {
        this.promptTemplate = template;
        return this;
    }
        
    public PredictionLlmStrategy withConfidence(double confidence) {
        this.baseConfidence = confidence;
        return this;
    }
}
