package ccrs.core.contingency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;

/**
 * Main entry point for contingency CCRS evaluation.
 * Evaluates registered strategies against a situation and returns
 * ranked suggestions for recovery actions.
 */
public class ContingencyCcrs {

    private static final Logger logger = Logger.getLogger(ContingencyCcrs.class.getName());
    
    private final StrategyRegistry registry;
    private ContingencyConfiguration config;
    
    public ContingencyCcrs() {
        this.registry = new StrategyRegistry();
        this.config = ContingencyConfiguration.defaults();
    }
    
    public ContingencyCcrs(StrategyRegistry registry) {
        this.registry = registry;
        this.config = ContingencyConfiguration.defaults();
    }
    
    /**
     * Get the strategy registry for registration/lookup.
     */
    public StrategyRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Get current configuration.
     */
    public ContingencyConfiguration getConfig() {
        return config;
    }
    
    /**
     * Set configuration.
     */
    public void setConfig(ContingencyConfiguration config) {
        this.config = config;
    }
    
    /**
     * Evaluate strategies and return ranked suggestions.
     * 
     * @param situation The situation requiring recovery
     * @param context Access to agent's knowledge base
     * @return List of suggestions, best first (may be empty)
     */
    public List<StrategyResult> evaluate(Situation situation, CcrsContext context) {
        CcrsTrace trace = evaluateWithTrace(situation, context);
        context.recordCcrsInvocation(trace);
        logger.info("[ContingencyCcrs] Recorded trace:\n" + trace.toDetailedReport());
        return trace.getSelectedResults();
    }
    
    /**
     * Evaluate strategies with full trace for debugging/learning.
     * 
     * @param situation The situation requiring recovery
     * @param context Access to agent's knowledge base
     * @return Trace containing all evaluations and selected results
     */
    public CcrsTrace evaluateWithTrace(Situation situation, CcrsContext context) {
        long startTime = System.currentTimeMillis();
        CcrsTrace.Builder traceBuilder = CcrsTrace.builder(situation);
        
        List<StrategyResult> allSuggestions = new ArrayList<>();
        List<CcrsStrategy> defaultOrder = registry.getOrderedForEvaluation(config);
        StrategySelectionModel selectionModel = buildSelectionModel(context);
        List<CcrsStrategy> orderedStrategies = orderStrategies(defaultOrder, selectionModel);
        
        int currentLevel = -1;
        boolean foundAtCurrentLevel = false;
        boolean evaluatedCandidateAtCurrentLevel = false;
        int evaluatedCount = 0;
        
        for (CcrsStrategy strategy : orderedStrategies) {
            int level = strategy.getEscalationLevel();
            
            // Track level transitions. Policies decide per-strategy evaluation below.
            if (level != currentLevel) {
                currentLevel = level;
                foundAtCurrentLevel = false;
                evaluatedCandidateAtCurrentLevel = false;
                logger.info(String.format(
                    "[ContingencyCcrs] Entering escalation level L%d with policy %s",
                    level, config.getEscalationPolicy()));
            }

            // L0 is a last-resort fallback, not a confidence competitor.
            if (level == 0 && !allSuggestions.isEmpty()) {
                logger.info("[ContingencyCcrs] Skipping L0 (STOP) fallback because recovery suggestions already exist");
                break;
            }

            if (config.getEscalationPolicy() == ContingencyConfiguration.EscalationPolicy.BEST_PER_LEVEL
                && evaluatedCandidateAtCurrentLevel) {
                logger.info(String.format(
                    "[ContingencyCcrs] Skipping %s in L%d because BEST_PER_LEVEL already evaluated the most promising applicable strategy at this level",
                    strategy.getId(), level));
                continue;
            }

            // Learned selection may prune remaining work once a suggestion
            // exists, but it compares expected improvement against thresholds
            // rather than comparing cost-discounted history to raw confidence.
            if (selectionModel != null) {
                StrategySelectionModel.GateDecision gateDecision =
                    selectionModel.evaluateGate(strategy, allSuggestions, config);
                logger.info(String.format(
                    "[ContingencyCcrs] Learned gate %s %s",
                    gateDecision.shouldEvaluate() ? "ALLOW" : "SKIP",
                    gateDecision.describe()));
                if (!gateDecision.shouldEvaluate()) {
                    continue;
                }
            }
            
            long evalStart = System.currentTimeMillis();
            CcrsStrategy.Applicability applicability;
            StrategyResult result = null;
            evaluatedCount++;
            
            try {
                // Check applicability
                applicability = strategy.appliesTo(situation, context);
                logger.info(String.format(
                    "[ContingencyCcrs] Applicability %s L%d -> %s",
                    strategy.getId(), level, applicability));
                
                if (applicability == CcrsStrategy.Applicability.APPLICABLE ||
                    applicability == CcrsStrategy.Applicability.UNKNOWN) {
                    evaluatedCandidateAtCurrentLevel = true;
                    // Evaluate
                    result = strategy.evaluate(situation, context);
                    
                    if (result.isSuggestion()) {
                        allSuggestions.add(result);
                        foundAtCurrentLevel = true;
                        logger.info(String.format(
                            "[ContingencyCcrs] %s produced suggestion with confidence %.3f",
                            strategy.getId(), result.asSuggestion().getConfidence()));
                        if (config.getEscalationPolicy() == ContingencyConfiguration.EscalationPolicy.SEQUENTIAL) {
                            long evalTime = System.currentTimeMillis() - evalStart;
                            if (config.isTraceEnabled()) {
                                traceBuilder.addEvaluation(
                                    strategy.getId(),
                                    level,
                                    applicability,
                                    result,
                                    evalTime
                                );
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // Strategy threw an exception
                applicability = CcrsStrategy.Applicability.NOT_APPLICABLE;
                result = StrategyResult.noHelp(strategy.getId(),
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Exception: " + e.getMessage());
                logger.warning(String.format(
                    "[ContingencyCcrs] %s evaluation failed: %s",
                    strategy.getId(), e.getMessage()));
            }
            
            long evalTime = System.currentTimeMillis() - evalStart;
            
            if (config.isTraceEnabled()) {
                traceBuilder.addEvaluation(
                    strategy.getId(),
                    level,
                    applicability,
                    result,
                    evalTime
                );
            }
        }
        
        // Rank already-derived suggestions by confidence only.
        List<StrategyResult> rankedResults = allSuggestions.stream()
            .sorted(Comparator.comparingDouble(
                (StrategyResult r) -> r.asSuggestion().getConfidence()).reversed())
            .limit(config.getMaxSuggestions())
            .collect(Collectors.toList());
        if (!rankedResults.isEmpty()) {
            rankedResults = keepOpportunisticGuidanceOnlyForWinningSuggestion(rankedResults);
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        
        String selectionReason = buildSelectionReason(rankedResults, evaluatedCount);
        
        return traceBuilder
            .selectedResults(rankedResults)
            .selectionReason(selectionReason)
            .totalTime(totalTime)
            .build();
    }
    
    /**
     * Quick check if any strategy might help.
     */
    public boolean hasApplicableStrategy(Situation situation, CcrsContext context) {
        for (CcrsStrategy strategy : registry.getEnabled(config)) {
            CcrsStrategy.Applicability applicability = strategy.appliesTo(situation, context);
            if (applicability != CcrsStrategy.Applicability.NOT_APPLICABLE) {
                return true;
            }
        }
        return false;
    }
    
    private String buildSelectionReason(List<StrategyResult> results, int totalEvaluated) {
        if (results.isEmpty()) {
            return String.format("No applicable strategies found (evaluated %d)", totalEvaluated);
        }
        
        StrategyResult.Suggestion top = results.get(0).asSuggestion();
        return String.format("Selected %s (confidence=%.2f) from %d candidates, evaluated %d strategies",
            top.getStrategyId(), top.getConfidence(), results.size(), totalEvaluated);
    }

    private StrategySelectionModel buildSelectionModel(CcrsContext context) {
        if (!config.isLearnedSelectionEnabled()) {
            logger.info("[StrategySelectionModel] Learned strategy selection disabled; using default escalation order");
            return null;
        }

        List<CcrsTrace> recentHistory = context.getCcrsHistory(config.getLearningHistoryLimit());
        StrategySelectionModel model = StrategySelectionModel.fromHistory(
            recentHistory,
            config.getMinimumLearningSamples());
        logger.info(String.format(
            "[StrategySelectionModel] Built strategy selection model from %d/%d requested traces, %d strategy profiles, minimumSamplesPerStrategy=%d",
            model.traceCount(),
            config.getLearningHistoryLimit(),
            model.profileCount(),
            model.minimumSamples()));
        return model;
    }

    private List<CcrsStrategy> orderStrategies(
            List<CcrsStrategy> defaultOrder,
            StrategySelectionModel selectionModel) {
        if (selectionModel == null) {
            return defaultOrder;
        }

        List<CcrsStrategy> ordered = selectionModel.orderForEvaluation(defaultOrder, config);
        logger.info("[ContingencyCcrs] Strategy evaluation order: "
            + selectionModel.describeOrder(ordered, config));
        return ordered;
    }

    private double bestSuggestionConfidence(List<StrategyResult> suggestions) {
        return suggestions.stream()
            .filter(StrategyResult::isSuggestion)
            .map(StrategyResult::asSuggestion)
            .mapToDouble(StrategyResult.Suggestion::getConfidence)
            .max()
            .orElse(0.0);
    }

    private List<StrategyResult> keepOpportunisticGuidanceOnlyForWinningSuggestion(List<StrategyResult> rankedResults) {
        if (rankedResults.size() <= 1) {
            return rankedResults;
        }

        List<StrategyResult> sanitized = new java.util.ArrayList<>(rankedResults);
        for (int i = 1; i < sanitized.size(); i++) {
            StrategyResult result = sanitized.get(i);
            if (!result.isSuggestion()) {
                continue;
            }

            StrategyResult.Suggestion suggestion = result.asSuggestion();
            if (!suggestion.hasOpportunisticGuidance()) {
                continue;
            }

            StrategyResult withoutNotes = StrategyResult.suggest(
                    suggestion.getStrategyId(),
                    suggestion.getActionType())
                .target(suggestion.getActionTarget())
                .params(suggestion.getActionParams())
                .confidence(suggestion.getConfidence())
                .rationale(suggestion.getRationale())
                .build();

            sanitized.set(i, withoutNotes);
        }

        return sanitized;
    }
    
    /**
     * Create a ContingencyCcrs instance with default strategies registered.
     */
    public static ContingencyCcrs withDefaults() {
        logger.info("Creating ContingencyCcrs with default strategies");
        ContingencyCcrs ccrs = new ContingencyCcrs();
        registerDefaultStrategies(ccrs.getRegistry());
        return ccrs;
    }
    
    /**
     * Register the default built-in strategies.
     */
    public static void registerDefaultStrategies(StrategyRegistry registry) {
        // Import strategies from strategies package
        registry.register(new ccrs.core.contingency.strategies.internal.RetryStrategy());
        registry.register(new ccrs.core.contingency.strategies.internal.BacktrackStrategy());
        registry.register(new ccrs.core.contingency.strategies.internal.StopStrategy());
        // LLM & Consultation strategies registered separately as they need configuration
    }
}
