package ccrs.core.contingency.strategies.social;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Interaction;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;

/**
 * L4: Consultation Strategy (Social)
 * 
 * Requests help from external agents when internal strategies are insufficient.
 * Uses a pluggable consultation channel. For the current A2A flow, the
 * strategy discovers available agent cards from RDF context and delegates
 * the actual invocation to the configured channel.
 */
public class ConsultationStrategy implements CcrsStrategy {
    
    private static final Logger logger = Logger.getLogger(ConsultationStrategy.class.getName());
    private static final String MAZE_CONTAINS = "https://kaefer3000.github.io/2021-02-dagstuhl/vocab#contains";
    private static final String A2A_AGENT_CARD = "https://example.org/a2a#agentCard";
    private static final String A2A_PROVIDES_TYPE = "https://example.org/a2a#providesType";
    private static final String A2A_PROVIDES_PROPERTY = "https://example.org/a2a#providesProperty";
    private static final int MAX_AGENT_CANDIDATES = 3;

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
            logger.info("[Consultation] Not applicable - no channel configured");
            return Applicability.NOT_APPLICABLE;
        }
        
        // Check if channel is available
        if (channel != null && !channel.isAvailable()) {
            logger.info("[Consultation] Not applicable - channel not available");
            return Applicability.NOT_APPLICABLE;
        }
        
        // Don't exceed consultation limit
        int consultationCount = situation.getAttemptCount(ID);
        if (consultationCount >= maxConsultationsPerSituation) {
            logger.info(String.format("[Consultation] Not applicable - consultation limit reached (%d/%d)",
                consultationCount, maxConsultationsPerSituation));
            return Applicability.NOT_APPLICABLE;
        }
        
        // Require at least one discovered consultation target from recent interactions.
        List<Map<String, Object>> consultationTargets = discoverConsultationTargets(context);
        if (consultationTargets.isEmpty()) {
            logger.info("[Consultation] Not applicable - no consultation target discovered in context");
            return Applicability.NOT_APPLICABLE;
        }

        // Only consult for complex situations (after simpler strategies tried)
        if (situation.getAttemptedStrategies().isEmpty()) {
            // Could be applicable but should try simpler things first
            logger.info("[Consultation] Unknown - no strategies tried yet");
            return Applicability.UNKNOWN;
        }
        
        logger.info(String.format("[Consultation] Applicable - channel '%s' available, %d strategies already tried",
            channel.getChannelType(), situation.getAttemptedStrategies().size()));
        return Applicability.APPLICABLE;
    }
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        logger.info("[Consultation] Evaluating consultation strategy via " + 
            (channel != null ? channel.getChannelType() : "unknown channel"));
        
        if (channel == null) {
            logger.warning("[Consultation] No channel configured");
            StrategyResult result = StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "No consultation channel configured");
            logger.info("[Consultation] Returning noHelp: " + result.asNoHelp().getReason());
            return result;
        }
        
        if (!channel.isAvailable()) {
            logger.warning("[Consultation] Channel not available: " + channel.getChannelType());
            StrategyResult result = StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "Consultation channel is not available");
            logger.info("[Consultation] Returning noHelp: " + result.asNoHelp().getReason());
            return result;
        }
        
        try {
            // Build consultation request for the configured channel implementation.
            String question = buildQuestion(situation, context);
            Map<String, Object> consultContext = buildContext(situation, context);
            
            logger.fine("[Consultation] Prepared consultation request (" + question.length() + " chars)");
            
            // Query the channel
            logger.info("[Consultation] Querying external consultant via " + channel.getChannelType());
            ConsultationResponse response = channel.query(question, consultContext);
            
            if (!response.success) {
                logger.warning("[Consultation] Consultation failed: " + response.suggestion);
                StrategyResult result = StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Consultation failed: " + response.suggestion);
                logger.info("[Consultation] Returning noHelp: " + result.asNoHelp().getReason());
                return result;
            }
            
            if (response.action == null || response.action.isEmpty()) {
                logger.warning("[Consultation] Consultant provided no actionable advice");
                StrategyResult result = StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.INSUFFICIENT_CONTEXT,
                    "Consultant could not provide actionable advice");
                logger.info("[Consultation] Returning noHelp: " + result.asNoHelp().getReason());
                return result;
            }
            
            logger.info(String.format("[Consultation] Consultant suggests action '%s' to '%s' (confidence=%.2f, source=%s)",
                response.action, response.target, response.confidence, response.source));
            
            // Build result
            StrategyResult result = StrategyResult.suggest(ID, response.action)
                .target(response.target)
                .param("consulted", true)
                .param("consultationSource", channel.getChannelType() + 
                    (response.source != null ? ":" + response.source : ""))
                .param("originalAdvice", response.suggestion)
                .params(response.metadata != null ? response.metadata : Map.of())
                .confidence(response.confidence > 0 ? response.confidence : 0.5)
                .cost(0.6)  // Higher cost - social/external dependency
                .rationale(buildRationale(response))
                .build();
            
            logger.info(String.format("[Consultation] Returning suggestion: %s to '%s' (confidence=%.2f)",
                response.action, response.target, result.asSuggestion().getConfidence()));
            return result;
            
        } catch (Exception e) {
            logger.warning("[Consultation] Consultation error: " + e.getMessage());
            StrategyResult result = StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.EVALUATION_FAILED,
                "Consultation error: " + e.getMessage());
            logger.info("[Consultation] Returning noHelp: " + result.asNoHelp().getReason());
            return result;
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
        Map<String, Object> ctx = new HashMap<>();
        
        ctx.put("situationType", situation.getType().name());
        ctx.put("currentResource", situation.getCurrentResource());
        ctx.put("targetResource", situation.getTargetResource());
        ctx.put("failedAction", situation.getFailedAction());
        ctx.put("errorInfo", situation.getErrorInfo());
        ctx.put("attemptedStrategies", situation.getAttemptedStrategies());
        
        // Add history if available
        if (context.hasHistory()) {
            List<Interaction> actions = context.getRecentInteractions(10);
            ctx.put("recentActions", actions.stream()
                .map(a -> Map.of(
                    "action", a.method(),
                    "target", a.requestUri(),
                    "outcome", a.outcome().name()))
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
        
        List<Map<String, Object>> consultationTargets = discoverConsultationTargets(context);
        ctx.put("consultationTargets", consultationTargets);
        if (!consultationTargets.isEmpty()) {
            ctx.put("agentUri", consultationTargets.get(0).get("agentUri"));
            Object agentCardUri = consultationTargets.get(0).get("agentCardUri");
            if (agentCardUri != null) {
                ctx.put("agentCardUri", agentCardUri);
            }
        }
        
        return ctx;
    }

    private List<Map<String, Object>> discoverConsultationTargets(CcrsContext context) {
        List<String> candidates = discoverRecentAgentCandidates(context);
        logger.info("[Consultation] Recent agent candidates: " + candidates);
        List<Map<String, Object>> targets = new ArrayList<>();

        for (String agentUri : candidates) {
            Map<String, Object> target = new HashMap<>();
            target.put("agentUri", agentUri);

            List<RdfTriple> cardTriples = context.query(agentUri, A2A_AGENT_CARD, null);
            if (!cardTriples.isEmpty()) {
                target.put("agentCardUri", cardTriples.get(0).object);
                logger.info("[Consultation] Found in-context agent card for " + agentUri + ": " + cardTriples.get(0).object);
            } else {
                logger.info("[Consultation] No in-context agent card for " + agentUri + " - channel must dereference");
            }

            List<RdfTriple> providesTypeTriples = context.query(agentUri, A2A_PROVIDES_TYPE, null);
            if (!providesTypeTriples.isEmpty()) {
                target.put("providesType", providesTypeTriples.stream()
                    .map(t -> t.object)
                    .distinct()
                    .collect(Collectors.toList()));
            }

            List<RdfTriple> providesPropertyTriples = context.query(agentUri, A2A_PROVIDES_PROPERTY, null);
            if (!providesPropertyTriples.isEmpty()) {
                target.put("providesProperty", providesPropertyTriples.stream()
                    .map(t -> t.object)
                    .distinct()
                    .collect(Collectors.toList()));
            }

            targets.add(target);
            logger.info("[Consultation] Added consultation target: " + target);

            if (targets.size() >= MAX_AGENT_CANDIDATES) {
                logger.info("[Consultation] Reached consultation candidate limit of " + MAX_AGENT_CANDIDATES);
                break;
            }
        }

        return targets;
    }

    private List<String> discoverRecentAgentCandidates(CcrsContext context) {
        if (!context.hasHistory()) {
            return List.of();
        }

        List<String> orderedAgents = new ArrayList<>();
        String selfAgentId = context.getAgentId();
        List<Interaction> interactions = context.getRecentInteractions(10);

        for (Interaction interaction : interactions) {
            if (interaction == null || interaction.perceivedState() == null) {
                continue;
            }

            logger.info("[Consultation] Inspecting interaction from " + interaction.requestUri()
                + " with " + interaction.perceivedState().size() + " perceived triples");

            for (RdfTriple triple : interaction.perceivedState()) {
                if (triple == null || !MAZE_CONTAINS.equals(triple.predicate)) {
                    continue;
                }

                String candidate = triple.object;
                logger.info("[Consultation] Encountered peer candidate via maze:contains: " + candidate);
                if (!isConsultableAgent(candidate, selfAgentId)) {
                    logger.info("[Consultation] Skipping non-consultable candidate: " + candidate);
                    continue;
                }

                if (!orderedAgents.contains(candidate)) {
                    orderedAgents.add(candidate);
                    logger.info("[Consultation] Added candidate in recency order: " + candidate);
                }
            }
        }

        return orderedAgents;
    }

    private boolean isConsultableAgent(String agentUri, String selfAgentId) {
        if (agentUri == null || agentUri.isBlank()) {
            return false;
        }

        if (selfAgentId == null || selfAgentId.isBlank()) {
            return true;
        }

        boolean consultable = !agentUri.equals(selfAgentId) && !agentUri.endsWith("/" + selfAgentId);
        if (!consultable) {
            logger.info("[Consultation] Filtering self candidate " + agentUri + " for agentId=" + selfAgentId);
        }
        return consultable;
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
}
