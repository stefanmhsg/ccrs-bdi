package ccrs.core.contingency.strategies.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
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
import ccrs.core.rdf.CcrsTraceHistoryAnalyzer;
import ccrs.core.rdf.RdfTriple;

/**
 * L4: Prediction Strategy (LLM-based)
 * Applies to: ANY situation type.
 * 
 * Uses a Large Language Model to predict a recovery action from four
 * deliberately separate context sources:
 * <ul>
 *   <li>the current {@link Situation},</li>
 *   <li>detailed hypermedia interactions and their perceived RDF triples,</li>
 *   <li>a local RDF neighborhood around the current resource, and</li>
 *   <li>a broader bounded raw RDF memory snapshot.</li>
 * </ul>
 *
 * The neighborhood is intentionally local link context. It must not be used as
 * the only memory-access path when the LLM needs a wider view of the graph.
 * 
 */
public class PredictionLlmStrategy implements CcrsStrategy {
    
    private static final Logger logger = Logger.getLogger(PredictionLlmStrategy.class.getName());
    
    public static final String ID = "prediction_llm";
    private static final String UI_NAMESPACE = "https://example.org/ui";
    private static final int RAW_MEMORY_SCAN_MULTIPLIER = 5;
    
    // Configuration
    private LlmClient llmClient;
    private PromptBuilder promptBuilder;
    private LlmResponseParser responseParser;
    private double baseConfidence = 0.6;
    private int maxHistoryActions = 20;
    private int maxInteractionStateTriples = 100;
    private int maxKnowledgeTriples = 1000;
    private int maxCcrsTraces = 5;
    private int maxNeighborhoodOutgoing = CcrsContext.DEFAULT_MAX_OUTGOING;
    private int maxNeighborhoodIncoming = CcrsContext.DEFAULT_MAX_INCOMING;
    private List<String> filteredTripleNamespaces = List.of(UI_NAMESPACE);

        
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
        return 4;
    }
    
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        // Need LLM client configured
        if (llmClient == null && !context.hasLlmAccess()) {
            logger.info("[PredictionLLM] Not applicable - no LLM client configured");
            return Applicability.NOT_APPLICABLE;
        }
        
        // Need enough context to be useful
        if (situation.getCurrentResource() == null && 
            context.getCurrentResource().isEmpty()) {
            logger.info("[PredictionLLM] Not applicable - insufficient context (no current resource)");
            return Applicability.NOT_APPLICABLE;
        }
        
        logger.info("[PredictionLLM] Applicable - LLM available with sufficient context");
        return Applicability.APPLICABLE;
    }
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        logger.info("[PredictionLLM] Evaluating LLM prediction strategy");
        
        if (llmClient == null) {
            logger.warning("[PredictionLLM] LLM client not configured");
            StrategyResult result = StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "LLM client not configured");
            logger.info("[PredictionLLM] Returning noHelp: " + result.asNoHelp().getReason());
            return result;
        }
        
        if (responseParser == null) {
            logger.warning("[PredictionLLM] Response parser not configured");
            StrategyResult result = StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "Response parser not configured");
            logger.info("[PredictionLLM] Returning noHelp: " + result.asNoHelp().getReason());
            return result;
        }
        
        try {
            // Prepare context map (strategy responsibility, not prompt builder's)
            Map<String, Object> contextMap = prepareContextMap(situation, context);
            logger.fine("[PredictionLLM] Prepared context map with " + contextMap.size() + " entries");
            
            // Build prompt using configured builder
            String prompt = promptBuilder.buildPredictionPrompt(contextMap);
            logger.finer("[PredictionLLM] Built prompt (" + prompt.length() + " chars)");
            
            // Call LLM
            logger.info("[PredictionLLM] Calling LLM for prediction...");
            logger.info("[PredictionLLM] Full LLM request:\n" + prompt);
            String rawResponse = llmClient.complete(prompt);
            logger.info("[PredictionLLM] Full LLM response:\n" + rawResponse);
            
            // Parse response using centralized parser
            LlmActionResponse response = responseParser.parse(rawResponse);
            
            if (!response.isValid()) {
                logger.warning("[PredictionLLM] Failed to parse LLM response: " + response.getParseError());
                StrategyResult result = StrategyResult.noHelp(ID,
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Could not parse valid action from LLM: " + response.getParseError());
                logger.info("[PredictionLLM] Returning noHelp: " + result.asNoHelp().getReason());
                return result;
            }
            
            // Determine confidence (use parser's if available, otherwise base)
            double confidence;
            if (response.hasConfidence()) {
                confidence = response.getConfidence();
            } else {
                confidence = baseConfidence;
                logger.warning(String.format(
                    "[PredictionLLM] LLM did not provide confidence; defaulting to configured baseConfidence=%.2f for action='%s' target='%s'",
                    baseConfidence, response.getAction(), response.getTarget()
                ));
            }
            
            logger.info(String.format("[PredictionLLM] LLM suggests action '%s' to '%s' (confidence=%.2f)",
                response.getAction(), response.getTarget(), confidence));
            
            // Build result
            StrategyResult result = StrategyResult.suggest(ID, response.getAction())
                .target(response.getTarget())
                .param("llmGenerated", true)
                .param("originalReasoning", response.getExplanation())
                .params(buildHttpActionParams(response))
                .param("parseMethod", response.getMetadata().get("parseMethod"))
                .confidence(confidence)
                .rationale(buildRationale(response))
                .build();
            
            logger.info(String.format("[PredictionLLM] Returning suggestion: %s to '%s' (confidence=%.2f)",
                response.getAction(), response.getTarget(), result.asSuggestion().getConfidence()));
            return result;
            
        } catch (Exception e) {
            logger.warning("[PredictionLLM] LLM call failed: " + e.getMessage());
            StrategyResult result = StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.EVALUATION_FAILED,
                "LLM call failed: " + e.getMessage());
            logger.info("[PredictionLLM] Returning noHelp: " + result.asNoHelp().getReason());
            return result;
        }
    }
    
    // Context preparation (strategy's responsibility)
    
    /**
     * Prepare context map from situation and bounded context.
     * This is where we extract relevant data - NOT in the prompt builder.
     *
     * <p>The prompt receives local neighborhood context and raw RDF memory as
     * distinct sections. This keeps {@code CcrsContext.getNeighborhood(...)}
     * focused on local graph shape while still allowing the LLM to inspect a
     * broader bounded memory snapshot.</p>
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
        ctx.put("situationDetails", formatSituationDetails(situation, context, currentResource));
        
        // Format history (bounded)
        ctx.put("recentActions", formatHistory(context));

        // Previous CCRS decisions are separate from raw action history.
        ctx.put("ccrsHistory", CcrsTraceHistoryAnalyzer.formatTraceHistory(
            context.getCcrsHistory(maxCcrsTraces), maxCcrsTraces));

        // Local graph shape around the current resource.
        ctx.put("localNeighborhood", formatNeighborhood(context, currentResource));

        // Broader bounded RDF memory snapshot.
        // ctx.put("rawMemory", formatRawMemory(context));
        
        return ctx;
    }

    private String formatSituationDetails(Situation situation, CcrsContext context, String currentResource) {
        StringBuilder sb = new StringBuilder();

        appendLine(sb, "Situation type", situation.getType());
        appendLine(sb, "Trigger", situation.getTrigger());
        appendLine(sb, "Agent", context.getAgentId());
        appendLine(sb, "Current resource", currentResource);
        appendLine(sb, "Target resource", situation.getTargetResource());
        appendLine(sb, "Requested or failed action", situation.getFailedAction());
        appendLine(sb, "Error details", formatError(situation));

        if (!situation.getMetadata().isEmpty()) {
            appendLine(sb, "Situation metadata", situation.getMetadata());
        }

        context.getLastInteraction()
            .ifPresentOrElse(
                interaction -> appendLine(sb, "Last interaction", formatInteractionDetails(interaction)),
                () -> appendLine(sb, "Last interaction", "none available"));

        context.getLastCcrsInvocation()
            .ifPresent(trace -> appendLine(sb, "Previous CCRS invocation", trace.toDetailedReport()));

        return sb.toString().trim();
    }

    private String formatInteractionDetails(Interaction interaction) {
        StringBuilder sb = new StringBuilder();
        sb.append(interaction.method()).append(' ')
            .append(interaction.requestUri())
            .append(" -> ")
            .append(interaction.outcome());

        if (interaction.requestBody() != null) {
            sb.append("; body=").append(interaction.requestBody());
        }
        if (interaction.logicalSource() != null) {
            sb.append("; source=").append(interaction.logicalSource());
        }
        if (interaction.requestTimestamp() > 0 && interaction.responseTimestamp() > 0) {
            sb.append("; durationMs=")
                .append(interaction.responseTimestamp() - interaction.requestTimestamp());
        }
        if (interaction.perceivedState() != null) {
            sb.append("; perceivedState=")
                .append(interaction.perceivedState().size())
                .append(" triples");
        }
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String label, Object value) {
        if (value == null) {
            return;
        }

        String text = String.valueOf(value);
        if (text.isBlank() || text.equals("unknown") || text.equals("none")) {
            return;
        }

        sb.append(label).append(": ").append(text).append('\n');
    }
    
    private String formatHistory(CcrsContext context) {
        if (!context.hasHistory()) {
            return "(no history available)";
        }
        
        List<Interaction> interactions = context.getRecentInteractions(maxHistoryActions);
        if (interactions.isEmpty()) {
            return "(no recent interaction)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(most recent first; perceived RDF triples shown up to ")
            .append(maxInteractionStateTriples)
            .append(" per interaction)\n");

        for (int i = 0; i < interactions.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            appendInteractionForPrompt(sb, interactions.get(i), i + 1);
        }

        return sb.toString().trim();
    }

    private void appendInteractionForPrompt(StringBuilder sb, Interaction interaction, int index) {
        if (interaction == null) {
            sb.append('[').append(index).append("] (missing interaction)\n");
            return;
        }

        sb.append('[').append(index).append("] ")
            .append(nullSafe(interaction.method())).append(' ')
            .append(nullSafe(interaction.requestUri()))
            .append(" -> ")
            .append(interaction.outcome() != null ? interaction.outcome() : "UNKNOWN")
            .append('\n');

        appendIndentedLine(sb, "source", interaction.logicalSource(), "  ");
        appendIndentedLine(sb, "requestHeaders", interaction.requestHeaders(), "  ");
        appendIndentedLine(sb, "requestBody", interaction.requestBody(), "  ");

        if (interaction.requestTimestamp() > 0) {
            appendIndentedLine(sb, "requestTs", interaction.requestTimestamp(), "  ");
        }
        if (interaction.responseTimestamp() > 0) {
            appendIndentedLine(sb, "responseTs", interaction.responseTimestamp(), "  ");
        }
        if (interaction.requestTimestamp() > 0 && interaction.responseTimestamp() > 0) {
            appendIndentedLine(sb, "durationMs",
                interaction.responseTimestamp() - interaction.requestTimestamp(), "  ");
        }

        List<RdfTriple> perceivedState = interaction.perceivedState();
        int stateSize = perceivedState == null ? 0 : perceivedState.size();
        int visibleStateSize = filterPromptTriples(perceivedState).size();
        sb.append("  perceivedState: ").append(stateSize).append(" triples\n");
        if (stateSize > 0) {
            sb.append("  perceivedStateTriples: ")
                .append(visibleStateSize)
                .append(" shown after prompt filtering\n");
            appendTriples(sb, perceivedState, maxInteractionStateTriples, "    ");
        }
    }


    private String formatNeighborhood(CcrsContext context, String currentResource) {
        if (currentResource == null || currentResource.isBlank() || "unknown".equals(currentResource)) {
            return "(no current resource available for local neighborhood)";
        }

        CcrsContext.Neighborhood neighborhood = context.getNeighborhood(
            currentResource,
            maxNeighborhoodOutgoing,
            maxNeighborhoodIncoming);
        if (neighborhood.size() == 0) {
            return "(no local neighborhood triples available)";
        }

        StringBuilder sb = new StringBuilder();
        List<RdfTriple> outgoing = filterPromptTriples(neighborhood.outgoing());
        List<RdfTriple> incoming = filterPromptTriples(neighborhood.incoming());
        sb.append("Resource: ").append(currentResource).append('\n');
        sb.append("Outgoing triples (")
            .append(outgoing.size())
            .append(", max ")
            .append(maxNeighborhoodOutgoing)
            .append(", filtered from ")
            .append(neighborhood.outgoing().size())
            .append("):\n");
        appendTriples(sb, outgoing, outgoing.size(), "  ");

        sb.append("Incoming triples (")
            .append(incoming.size())
            .append(", max ")
            .append(maxNeighborhoodIncoming)
            .append(", filtered from ")
            .append(neighborhood.incoming().size())
            .append("):\n");
        appendTriples(sb, incoming, incoming.size(), "  ");

        return sb.toString().trim();
    }

    private String formatRawMemory(CcrsContext context) {
        int scanLimit = Math.max(maxKnowledgeTriples, maxKnowledgeTriples * RAW_MEMORY_SCAN_MULTIPLIER);
        List<RdfTriple> unfilteredTriples = context.getMemoryTriples(scanLimit);
        List<RdfTriple> triples = filterPromptTriples(unfilteredTriples);
        if (triples.isEmpty()) {
            return "(no raw RDF memory triples available)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(bounded raw memory snapshot; max ")
            .append(maxKnowledgeTriples)
            .append(" triples; filtered ")
            .append(unfilteredTriples.size() - triples.size())
            .append(" presentation/UI triples from ")
            .append(unfilteredTriples.size())
            .append(" scanned triples)\n");
        appendTriples(sb, triples, maxKnowledgeTriples, "");

        if (maxKnowledgeTriples > 0 && triples.size() >= maxKnowledgeTriples) {
            sb.append("... (raw memory limit reached; context may contain more triples)\n");
        }

        return sb.toString().trim();
    }

    private void appendTriples(StringBuilder sb, List<RdfTriple> triples, int maxCount, String indent) {
        List<RdfTriple> visibleTriples = filterPromptTriples(triples);
        int removed = triples == null ? 0 : triples.size() - visibleTriples.size();
        if (visibleTriples.isEmpty()) {
            sb.append(indent).append("(none)\n");
            if (removed > 0) {
                sb.append(indent)
                    .append("(filtered ")
                    .append(removed)
                    .append(" presentation/UI triples)\n");
            }
            return;
        }

        if (removed > 0) {
            sb.append(indent)
                .append("(filtered ")
                .append(removed)
                .append(" presentation/UI triples)\n");
        }

        int limit = Math.max(0, Math.min(maxCount, visibleTriples.size()));
        if (limit == 0) {
            sb.append(indent).append("(triple output disabled by limit)\n");
        }

        for (int i = 0; i < limit; i++) {
            RdfTriple triple = visibleTriples.get(i);
            if (triple == null) {
                continue;
            }
            sb.append(indent).append(triple).append('\n');
        }

        if (visibleTriples.size() > limit) {
            sb.append(indent)
                .append("... (")
                .append(visibleTriples.size() - limit)
                .append(" more triples not shown)\n");
        }
    }

    private List<RdfTriple> filterPromptTriples(List<RdfTriple> triples) {
        if (triples == null || triples.isEmpty() || filteredTripleNamespaces.isEmpty()) {
            return triples == null ? List.of() : triples;
        }

        return triples.stream()
            .filter(triple -> triple != null && !containsFilteredNamespace(triple))
            .toList();
    }

    private boolean containsFilteredNamespace(RdfTriple triple) {
        return filteredTripleNamespaces.stream()
            .filter(ns -> ns != null && !ns.isBlank())
            .anyMatch(ns ->
                containsNamespace(triple.subject, ns) ||
                containsNamespace(triple.predicate, ns) ||
                containsNamespace(triple.object, ns));
    }

    private boolean containsNamespace(String value, String namespace) {
        return value != null && value.contains(namespace);
    }

    private void appendIndentedLine(StringBuilder sb, String label, Object value, String indent) {
        if (value == null) {
            return;
        }

        String text = String.valueOf(value);
        if (text.isBlank()) {
            return;
        }

        sb.append(indent).append(label).append(": ").append(text).append('\n');
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

    private Map<String, Object> buildHttpActionParams(LlmActionResponse response) {
        Map<String, Object> params = new HashMap<>();

        if (response.hasMethod()) {
            params.put("method", response.getMethod());
        }
        if (response.hasHeaders()) {
            params.put("headers", response.getHeaders());
        }
        if (response.hasBody()) {
            params.put("body", response.getBody());
        }
        if (response.hasBodyContentType()) {
            params.put("bodyContentType", response.getBodyContentType());
        }

        return params;
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
        this.maxHistoryActions = Math.max(0, max);
        return this;
    }

    /**
     * Limit perceived RDF triples shown for each formatted interaction.
     *
     * <p>This does not change {@link Interaction#toString()}, which remains
     * compact for logging. It only controls LLM prompt context detail.</p>
     */
    public PredictionLlmStrategy maxInteractionStateTriples(int max) {
        this.maxInteractionStateTriples = Math.max(0, max);
        return this;
    }
    
    /**
     * Limit raw RDF memory triples included in the LLM prompt.
     */
    public PredictionLlmStrategy maxKnowledgeTriples(int max) {
        this.maxKnowledgeTriples = Math.max(0, max);
        return this;
    }

    /**
     * Limit previous CCRS invocation traces included in the LLM prompt.
     */
    public PredictionLlmStrategy maxCcrsTraces(int max) {
        this.maxCcrsTraces = Math.max(0, max);
        return this;
    }

    /**
     * Configure local RDF neighborhood limits independently from raw memory.
     */
    public PredictionLlmStrategy maxNeighborhood(int maxOutgoing, int maxIncoming) {
        this.maxNeighborhoodOutgoing = Math.max(0, maxOutgoing);
        this.maxNeighborhoodIncoming = Math.max(0, maxIncoming);
        return this;
    }

    /**
     * Configure RDF namespaces removed from LLM prompt triple sections.
     *
     * <p>The default removes {@code https://example.org/ui} because those
     * triples describe presentation details rather than actionable hypermedia
     * state.</p>
     */
    public PredictionLlmStrategy filteredTripleNamespaces(List<String> namespaces) {
        if (namespaces == null) {
            this.filteredTripleNamespaces = List.of();
            return this;
        }

        this.filteredTripleNamespaces = namespaces.stream()
            .filter(ns -> ns != null && !ns.isBlank())
            .distinct()
            .toList();
        return this;
    }

    /**
     * Add one RDF namespace to remove from LLM prompt triple sections.
     */
    public PredictionLlmStrategy filterTripleNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return this;
        }

        List<String> namespaces = new ArrayList<>(this.filteredTripleNamespaces);
        if (!namespaces.contains(namespace)) {
            namespaces.add(namespace);
        }
        this.filteredTripleNamespaces = List.copyOf(namespaces);
        return this;
    }
        
    public PredictionLlmStrategy withConfidence(double confidence) {
        this.baseConfidence = confidence;
        return this;
    }
}
