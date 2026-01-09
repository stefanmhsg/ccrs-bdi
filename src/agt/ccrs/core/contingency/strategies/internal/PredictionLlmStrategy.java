package ccrs.core.contingency.strategies.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.LlmClient;
import ccrs.core.contingency.LlmResponseParser;
import ccrs.core.contingency.PromptBuilder;
import ccrs.core.contingency.dto.Interaction;
import ccrs.core.contingency.dto.LlmActionResponse;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
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
    
    // Configuration
    private LlmClient llmClient;
    private PromptBuilder promptBuilder;
    private LlmResponseParser responseParser;
    private double baseConfidence = 0.6;
    private int maxHistoryActions = 5;
    private int maxKnowledgeTriples = 50;

        
    public PredictionLlmStrategy(LlmClient llmClient, PromptBuilder promptBuilder, LlmResponseParser responseParser) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
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
        
        if (responseParser == null) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "Response parser not configured");
        }
        
        try {
            // Prepare context map (strategy responsibility, not prompt builder's)
            Map<String, Object> contextMap = prepareContextMap(situation, context);
            
            // Build prompt using configured builder
            String prompt = promptBuilder.buildPredictionPrompt(contextMap);
            
            // Call LLM
            String rawResponse = llmClient.complete(prompt);
            
            // Parse response using centralized parser
            LlmActionResponse response = responseParser.parse(rawResponse);
            
            if (!response.isValid()) {
                return StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Could not parse valid action from LLM: " + response.getParseError());
            }
            
            // Determine confidence (use parser's if available, otherwise base)
            double confidence = response.hasConfidence() ? response.getConfidence() : baseConfidence;
            
            // Build result
            return StrategyResult.suggest(ID, response.getAction())
                .target(response.getTarget())
                .param("llmGenerated", true)
                .param("originalReasoning", response.getExplanation())
                .param("parseMethod", response.getMetadata().get("parseMethod"))
                .confidence(confidence)
                .cost(0.4)  // Moderate cost - LLM call + uncertainty
                .rationale(buildRationale(response))
                .build();
            
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
        
        List<Interaction> interaction = context.getRecentInteractions(maxHistoryActions);
        if (interaction.isEmpty()) {
            return "(no recent interaction)";
        }
        
        return interaction.stream()
            .map(a -> String.format("%s %s -> %s", a.method(), a.requestUri(), a.outcome()))
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
    
    // Rationale building
    
    private String buildRationale(LlmActionResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("LLM suggests: ").append(response.getAction());
        if (response.hasTarget()) {
            sb.append(" to ").append(response.getTarget());
        }
        sb.append(". ");
        if (response.hasExplanation()) {
            // Truncate long explanation
            String explanation = response.getExplanation();
            if (explanation.length() > 200) {
                explanation = explanation.substring(0, 197) + "...";
            }
            sb.append("Reasoning: ").append(explanation);
        }
        return sb.toString();
    }
    
    // Configuration methods
    
    public PredictionLlmStrategy withClient(LlmClient client) {
        this.llmClient = client;
        return this;
    }
    
    public PredictionLlmStrategy withPromptBuilder(PromptBuilder builder) {
        this.promptBuilder = builder;
        return this;
    }
    
    public PredictionLlmStrategy withResponseParser(LlmResponseParser parser) {
        this.responseParser = parser;
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
