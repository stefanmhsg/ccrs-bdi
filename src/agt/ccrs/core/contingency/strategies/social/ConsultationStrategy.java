package ccrs.core.contingency.strategies.social;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.LlmClient;
import ccrs.core.contingency.LlmResponseParser;
import ccrs.core.contingency.PromptBuilder;
import ccrs.core.contingency.dto.ActionRecord;
import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.LlmActionResponse;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.capabilities.llm.TemplatePromptBuilder;

/**
 * L4: Consultation Strategy (Social)
 * 
 * Requests help from external agents when internal strategies are insufficient.
 * For POC: Can be mocked with an LLM acting as an external advisor.
 * 
 * Key difference from Prediction-LLM:
 * - Prediction: "What would I do?" - quick, uses agent's own context
 * - Consultation: "Help me please" - framed as asking for help, includes
 *   admission of prior failures, may involve richer context sharing
 */
public class ConsultationStrategy implements CcrsStrategy {
    
    public static final String ID = "consultation";
    
    /**
     * Abstract interface for consultation channels.
     * Implementations can be LLM-based, multi-agent, or human-in-the-loop.
     */
    public interface ConsultationChannel {
        
        /**
         * Check if the channel is available.
         */
        boolean isAvailable();
        
        /**
         * Send a consultation request and get a response.
         * 
         * @param question The question/request
         * @param context Additional context for the consultation
         * @return Response from the consulted entity
         * @throws Exception if consultation fails
         */
        ConsultationResponse query(String question, Map<String, Object> context) throws Exception;
        
        /**
         * Get the type of channel (for logging/tracing).
         */
        String getChannelType();
    }
    
    /**
     * Response from a consultation.
     */
    public static class ConsultationResponse {
        public boolean success;
        public String suggestion;
        public String action;
        public String target;
        public double confidence;
        public String source;
        public Map<String, Object> metadata;
        
        public static ConsultationResponse success(String action, String target, String suggestion) {
            ConsultationResponse r = new ConsultationResponse();
            r.success = true;
            r.action = action;
            r.target = target;
            r.suggestion = suggestion;
            r.confidence = 0.5;
            return r;
        }
        
        public static ConsultationResponse failure(String reason) {
            ConsultationResponse r = new ConsultationResponse();
            r.success = false;
            r.suggestion = reason;
            return r;
        }
    }
    
    // Configuration
    private ConsultationChannel channel;
    private int maxConsultationsPerSituation = 1;
    
    public ConsultationStrategy() {
    }
    
    public ConsultationStrategy(ConsultationChannel channel) {
        this.channel = channel;
    }
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Consultation (Social)";
    }
    
    @Override
    public Category getCategory() {
        return Category.SOCIAL;
    }
    
    @Override
    public int getEscalationLevel() {
        return 4;
    }
    
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        // Need a consultation channel
        if (channel == null) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Check if channel is available
        if (channel != null && !channel.isAvailable()) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Don't exceed consultation limit
        int consultationCount = situation.getAttemptCount(ID);
        if (consultationCount >= maxConsultationsPerSituation) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Only consult for complex situations (after simpler strategies tried)
        if (situation.getAttemptedStrategies().isEmpty()) {
            // Could be applicable but should try simpler things first
            return Applicability.UNKNOWN;
        }
        
        return Applicability.APPLICABLE;
    }
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        if (channel == null) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "No consultation channel configured");
        }
        
        if (!channel.isAvailable()) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "Consultation channel is not available");
        }
        
        try {
            // Build consultation request
            String question = buildQuestion(situation, context); //TODO: remove llm-mock channel and query building.
            Map<String, Object> consultContext = buildContext(situation, context);
            
            // Query the channel
            ConsultationResponse response = channel.query(question, consultContext);
            
            if (!response.success) {
                return StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Consultation failed: " + response.suggestion);
            }
            
            if (response.action == null || response.action.isEmpty()) {
                return StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.INSUFFICIENT_CONTEXT,
                    "Consultant could not provide actionable advice");
            }
            
            // Build result
            return StrategyResult.suggest(ID, response.action)
                .target(response.target)
                .param("consulted", true)
                .param("consultationSource", channel.getChannelType() + 
                    (response.source != null ? ":" + response.source : ""))
                .param("originalAdvice", response.suggestion)
                .confidence(response.confidence > 0 ? response.confidence : 0.5)
                .cost(0.6)  // Higher cost - social/external dependency
                .rationale(buildRationale(response))
                .build();
            
        } catch (Exception e) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.EVALUATION_FAILED,
                "Consultation error: " + e.getMessage());
        }
    }
    
    private String buildQuestion(Situation situation, CcrsContext context) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("I am an agent at ");
        sb.append(situation.getCurrentResource() != null ? 
            situation.getCurrentResource() : 
            context.getCurrentResource().orElse("an unknown location"));
        sb.append(" and I need help.\n\n");
        
        // Describe the problem
        sb.append("Problem: ");
        if (situation.getFailedAction() != null) {
            sb.append("My action '").append(situation.getFailedAction())
              .append("' on ").append(situation.getTargetResource())
              .append(" failed");
            
            String error = situation.getErrorInfoString("message");
            if (error != null) {
                sb.append(" with error: ").append(error);
            }
            String httpStatus = situation.getErrorInfoString("httpStatus");
            if (httpStatus != null) {
                sb.append(" (HTTP ").append(httpStatus).append(")");
            }
        } else {
            sb.append(situation.getTrigger() != null ? 
                situation.getTrigger() : "I am stuck");
        }
        sb.append(".\n\n");
        
        // What was already tried
        if (!situation.getAttemptedStrategies().isEmpty()) {
            sb.append("I have already tried: ");
            sb.append(String.join(", ", situation.getAttemptedStrategies()));
            sb.append(" - none of these worked.\n\n");
        }
        
        sb.append("What should I do next?");
        
        return sb.toString();
    }
    
    private Map<String, Object> buildContext(Situation situation, CcrsContext context) {
        Map<String, Object> ctx = new java.util.HashMap<>();
        
        ctx.put("situationType", situation.getType().name());
        ctx.put("currentResource", situation.getCurrentResource());
        ctx.put("targetResource", situation.getTargetResource());
        ctx.put("failedAction", situation.getFailedAction());
        ctx.put("errorInfo", situation.getErrorInfo());
        ctx.put("attemptedStrategies", situation.getAttemptedStrategies());
        
        // Add history if available
        if (context.hasHistory()) {
            List<ActionRecord> actions = context.getRecentActions(10);
            ctx.put("recentActions", actions.stream()
                .map(a -> Map.of(
                    "action", a.getActionType(),
                    "target", a.getTarget(),
                    "outcome", a.getOutcome().name()))
                .collect(Collectors.toList()));
            
            // Add previous CCRS invocations
            List<CcrsTrace> ccrsHistory = context.getCcrsHistory(3);
            ctx.put("previousCcrsInvocations", ccrsHistory.stream()
                .map(t -> Map.of(
                    "situation", t.getSituation().getType().name(),
                    "outcome", t.getOutcome().name()))
                .collect(Collectors.toList()));
        }
        
        // Add RDF knowledge - bounded neighborhood around current resource
        String currentResource = situation.getCurrentResource() != null ?
            situation.getCurrentResource() :
            context.getCurrentResource().orElse(null);
        
        // Get structured neighborhood from context
        // TODO: The strategy should still decide how to serialize/send this info
        CcrsContext.Neighborhood neighborhood = context.getNeighborhood(currentResource);
        ctx.put("neighborhood", neighborhood);
        ctx.put("neighborhoodSize", neighborhood.size());
        
        return ctx;
    }
    
    private String buildRationale(ConsultationResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("External consultation via ").append(channel.getChannelType());
        if (response.source != null) {
            sb.append(" (").append(response.source).append(")");
        }
        sb.append(" suggests: ").append(response.action);
        if (response.target != null) {
            sb.append(" to ").append(response.target);
        }
        sb.append(". ");
        if (response.suggestion != null && !response.suggestion.isEmpty()) {
            String advice = response.suggestion;
            if (advice.length() > 150) {
                advice = advice.substring(0, 147) + "...";
            }
            sb.append(advice);
        }
        return sb.toString();
    }
    
    // Configuration
    
    public ConsultationStrategy withChannel(ConsultationChannel channel) {
        this.channel = channel;
        return this;
    }
    
    public ConsultationStrategy maxConsultations(int max) {
        this.maxConsultationsPerSituation = max;
        return this;
    }
    
    /**
     * Create an LLM-based consultation channel (mock for POC).
     */
    public static ConsultationChannel llmChannel(LlmClient llmClient, LlmResponseParser parser) {
        return llmChannel(llmClient, TemplatePromptBuilder.create(), parser);
    }
    
    /**
     * Create an LLM-based consultation channel with custom prompt builder and parser.
     */
    public static ConsultationChannel llmChannel(LlmClient llmClient, PromptBuilder promptBuilder, LlmResponseParser parser) {
        return new ConsultationChannel() {
            @Override
            public boolean isAvailable() {
                return llmClient != null && promptBuilder != null && parser != null;
            }
            
            @Override
            public ConsultationResponse query(String question, Map<String, Object> context) throws Exception {
                String prompt = promptBuilder.buildConsultationPrompt(question, context);
                String rawResponse = llmClient.complete(prompt);
                
                // Use centralized parser
                LlmActionResponse parsed = parser.parse(rawResponse);
                
                // Convert to ConsultationResponse
                if (!parsed.isValid()) {
                    return ConsultationResponse.failure("Parse error: " + parsed.getParseError());
                }
                
                ConsultationResponse r = ConsultationResponse.success(
                    parsed.getAction(),
                    parsed.getTarget(),
                    parsed.getExplanation()
                );
                r.source = "llm-advisor";
                r.confidence = parsed.hasConfidence() ? parsed.getConfidence() : 0.5;
                r.metadata = parsed.getMetadata();
                
                return r;
            }
            
            @Override
            public String getChannelType() {
                return "llm(" + promptBuilder.getDescription() + ", " + parser.getDescription() + ")";
            }
        };
    }
}
