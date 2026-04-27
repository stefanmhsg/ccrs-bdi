package ccrs.core.contingency.strategies.social;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.io.StringReader;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Interaction;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

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

            DerivedConsultationAction derivedAction = deriveActionFromConsultation(response, situation, context);
            if (derivedAction != null) {
                logger.info(String.format("[Consultation] Derived actionable projection from consultation: %s to '%s'",
                    derivedAction.action(), derivedAction.target()));
            }

            String actionType = derivedAction != null ? derivedAction.action() : response.action;
            String actionTarget = derivedAction != null ? derivedAction.target() : response.target;
            Map<String, Object> actionParams = new LinkedHashMap<>();
            actionParams.put("consulted", true);
            actionParams.put("consultationSource", channel.getChannelType() +
                (response.source != null ? ":" + response.source : ""));
            actionParams.put("originalAdvice", response.suggestion);
            if (response.metadata != null) {
                actionParams.putAll(response.metadata);
            }
            if (derivedAction != null) {
                actionParams.putAll(derivedAction.params());
            }
            
            // Build result
            StrategyResult result = StrategyResult.suggest(ID, actionType)
                .target(actionTarget)
                .params(actionParams)
                .confidence(response.confidence > 0 ? response.confidence : 0.5)
                .rationale(buildRationale(response, derivedAction))
                .build();
            
            logger.info(String.format("[Consultation] Returning suggestion: %s to '%s' (confidence=%.2f)",
                actionType, actionTarget, result.asSuggestion().getConfidence()));
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
    
    private DerivedConsultationAction deriveActionFromConsultation(
        ConsultationResponse response,
        Situation situation,
        CcrsContext context
    ) {
        if (response == null || response.metadata == null) {
            return null;
        }

        Object contentType = response.metadata.get("artifactContentType");
        Object rawResponse = response.metadata.get("rawResponse");
        if (!(contentType instanceof String ct) || !(rawResponse instanceof String rdfText)) {
            return null;
        }

        if (!"text/turtle".equalsIgnoreCase(ct.trim())) {
            return null;
        }

        String focus = situation.getCurrentResource() != null
            ? situation.getCurrentResource()
            : context.getCurrentResource().orElse(null);
        if (focus == null || focus.isBlank()) {
            logger.info("[Consultation] No focus resource available for consultation projection");
            return null;
        }

        try {
            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(rdfText), null, "TURTLE");

            StmtIterator statements = model.listStatements();
            try {
                while (statements.hasNext()) {
                    Statement stmt = statements.nextStatement();
                    if (!stmt.getObject().isLiteral()) {
                        continue;
                    }
                    Literal literal = stmt.getObject().asLiteral();
                    logger.info(String.format(
                        "[Consultation] Found literal-valued RDF statement for projection: <%s> \"%s\"",
                        stmt.getPredicate().getURI(), literal.getLexicalForm()));
                    return buildProjectedConsultationAction(focus, stmt);
                }
            } finally {
                statements.close();
            }

            logger.info("[Consultation] No literal-valued RDF statement found for consultation projection");
            return null;

        } catch (Exception e) {
            logger.warning("[Consultation] Failed to project consultation RDF artifact into action: " + e.getMessage());
            return null;
        }
    }

    private DerivedConsultationAction buildProjectedConsultationAction(String focus, Statement statement) {
        Literal literal = statement.getObject().asLiteral();
        String predicate = statement.getPredicate().getURI();
        String lexicalForm = literal.getLexicalForm();
        String body = String.format("<%s> <%s> \"%s\" .",
            focus,
            predicate,
            escapeTurtleLiteral(lexicalForm));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectionHeuristic", "first_literal_projection");
        params.put("subject", focus);
        params.put("predicate", predicate);
        params.put("object", lexicalForm);
        params.put("objectType", "literal");
        params.put("body", body);
        params.put("bodyContentType", "text/turtle");
        params.put("extractedFromSubject", statement.getSubject().isURIResource()
            ? statement.getSubject().getURI()
            : statement.getSubject().toString());

        if (literal.getDatatypeURI() != null) {
            params.put("objectDatatype", literal.getDatatypeURI());
        }
        if (literal.getLanguage() != null && !literal.getLanguage().isBlank()) {
            params.put("objectLanguage", literal.getLanguage());
        }

        logger.info(String.format(
            "[Consultation] Projected first literal from consultation artifact onto focus '%s': <%s> \"%s\"",
            focus, predicate, lexicalForm));

        return new DerivedConsultationAction("post", focus, params,
            "Projected first literal-valued statement from consultation artifact onto current focus resource");
    }

    private String buildRationale(ConsultationResponse response, DerivedConsultationAction derivedAction) {
        StringBuilder sb = new StringBuilder();
        sb.append("External consultation via ").append(channel.getChannelType());
        if (response.source != null) {
            sb.append(" (").append(response.source).append(")");
        }
        if (derivedAction != null) {
            sb.append(" projected into action: ").append(derivedAction.action());
            if (derivedAction.target() != null) {
                sb.append(" to ").append(derivedAction.target());
            }
            sb.append(". ").append(derivedAction.rationale()).append(". ");
        } else {
            sb.append(" suggests: ").append(response.action);
            if (response.target != null) {
                sb.append(" to ").append(response.target);
            }
            sb.append(". ");
        }
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

    private String escapeTurtleLiteral(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private record DerivedConsultationAction(
        String action,
        String target,
        Map<String, Object> params,
        String rationale
    ) {}
}
