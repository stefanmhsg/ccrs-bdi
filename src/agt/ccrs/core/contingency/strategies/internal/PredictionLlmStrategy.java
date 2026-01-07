package ccrs.core.contingency.strategies.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ccrs.core.contingency.ActionRecord;
import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.LlmClient;
import ccrs.core.contingency.PromptBuilder;
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
    private PromptBuilder promptBuilder;
    private double baseConfidence = 0.6;
    private int maxHistoryActions = 5;
    private int maxKnowledgeTriples = 50;

        
    public PredictionLlmStrategy(LlmClient llmClient, PromptBuilder promptBuilder) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
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
            // Prepare context map (strategy responsibility, not prompt builder's)
            Map<String, Object> contextMap = prepareContextMap(situation, context);
            
            // Build prompt using configured builder
            String prompt = promptBuilder.buildPredictionPrompt(contextMap);
            
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
    
    // Context preparation (strategy's responsibility)
    
    /**
     * Prepare context map from situation and bounded context.
     * This is where we extract relevant data - NOT in the prompt builder.
     */
    private Map<String, Object> prepareContextMap(Situation situation, CcrsContext context) {
        Map<String, Object> ctx = new HashMap<>();
        
        // Core situation data
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse("unknown");
        }
        ctx.put("currentResource", currentResource);
        ctx.put("targetResource", nullSafe(situation.getTargetResource()));
        ctx.put("failedAction", nullSafe(situation.getFailedAction()));
        ctx.put("errorInfo", formatError(situation));
        ctx.put("attemptedStrategies", situation.getAttemptedStrategies().toString());
        
        // Format history (bounded)
        ctx.put("recentActions", formatHistory(context));
        
        // Get bounded neighborhood knowledge (NOT queryAll!)
        ctx.put("knowledge", formatKnowledge(context, currentResource));
        
        return ctx;
    }
    
    private String formatHistory(CcrsContext context) {
        if (!context.hasHistory()) {
            return "(no history available)";
        }
        
        List<ActionRecord> actions = context.getRecentActions(maxHistoryActions);
        if (actions.isEmpty()) {
            return "(no recent actions)";
        }
        
        return actions.stream()
            .map(a -> String.format("%s %s -> %s", a.getActionType(), a.getTarget(), a.getOutcome()))
            .collect(Collectors.joining("\n"));
    }
    
    private String formatKnowledge(CcrsContext context, String currentResource) {
        // Use bounded neighborhood, not queryAll!
        CcrsContext.Neighborhood neighborhood = context.getNeighborhood(currentResource);
        if (neighborhood.size() == 0) {
            return "(no knowledge available)";
        }
        
        // Combine outgoing and incoming triples from neighborhood (already bounded)
        List<RdfTriple> allTriples = new java.util.ArrayList<>();
        allTriples.addAll(neighborhood.outgoing());
        allTriples.addAll(neighborhood.incoming());
        
        if (allTriples.isEmpty()) {
            return "(no triples in neighborhood)";
        }
        
        String result = allTriples.stream()
            .limit(maxKnowledgeTriples)
            .map(RdfTriple::toString)
            .collect(Collectors.joining("\n"));
        
        if (allTriples.size() > maxKnowledgeTriples) {
            result += String.format("\n... (%d more triples not shown)", allTriples.size() - maxKnowledgeTriples);
        }
        
        return result;
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
    
    private String nullSafe(String value) {
        return value != null ? value : "unknown";
    }
    
    // Configuration methods
    
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
    
    public PredictionLlmStrategy withClient(LlmClient client) {
        this.llmClient = client;
        return this;
    }
    
    public PredictionLlmStrategy withPromptBuilder(PromptBuilder builder) {
        this.promptBuilder = builder;
        return this;
    }
    
    public PredictionLlmStrategy maxHistoryActions(int max) {
        this.maxHistoryActions = max;
        return this;
    }
    
    public PredictionLlmStrategy maxKnowledgeTriples(int max) {
        this.maxKnowledgeTriples = max;
        return this;
    }
        
    public PredictionLlmStrategy withConfidence(double confidence) {
        this.baseConfidence = confidence;
        return this;
    }
}
