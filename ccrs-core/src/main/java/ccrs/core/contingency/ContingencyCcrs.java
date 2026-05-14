package ccrs.core.contingency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import ccrs.core.contingency.selection.StrategyGateDecision;
import ccrs.core.contingency.selection.StrategySelectionPlan;
import ccrs.core.contingency.selection.StrategySelectionPolicy;
import ccrs.core.contingency.selection.StrategySelectionRequest;
import ccrs.core.contingency.selection.TraceBasedStrategySelectionPolicy;
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
    private StrategySelectionPolicy strategySelectionPolicy;
    
    public ContingencyCcrs() {
        this(new StrategyRegistry());
    }
    
    public ContingencyCcrs(StrategyRegistry registry) {
        this(registry, new TraceBasedStrategySelectionPolicy());
    }
    
    public ContingencyCcrs(StrategyRegistry registry, StrategySelectionPolicy strategySelectionPolicy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = ContingencyConfiguration.defaults();
        this.strategySelectionPolicy = Objects.requireNonNull(
            strategySelectionPolicy,
            "strategySelectionPolicy");
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
     * Get current strategy selection policy.
     */
    public StrategySelectionPolicy getStrategySelectionPolicy() {
        return strategySelectionPolicy;
    }
    
    /**
     * Set strategy selection policy.
     * Available policies:
     * - DefaultStrategySelectionPolicy: No gating or reordering; strategies evaluated in default escalation order.
     * - TraceBasedStrategySelectionPolicy: Uses historical trace data to make gating and ordering decisions based on expected improvement and confidence thresholds.
     */
    public void setStrategySelectionPolicy(StrategySelectionPolicy strategySelectionPolicy) {
        this.strategySelectionPolicy = Objects.requireNonNull(
            strategySelectionPolicy,
            "strategySelectionPolicy");
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
        StrategySelectionPlan selectionPlan = buildSelectionPlan(situation, context, defaultOrder);
        List<CcrsStrategy> orderedStrategies = orderStrategies(defaultOrder, selectionPlan);
        
        int currentLevel = -1;
        boolean evaluatedCandidateAtCurrentLevel = false;
        int evaluatedCount = 0;
        
        for (CcrsStrategy strategy : orderedStrategies) {
            int level = strategy.getEscalationLevel();
            
            // Track level transitions. Policies decide per-strategy evaluation below.
            if (level != currentLevel) {
                currentLevel = level;
                evaluatedCandidateAtCurrentLevel = false;
                logger.info(String.format(
                    "[StrategySelectionPolicy] Entering escalation level L%d with policy %s",
                    level, config.getEscalationPolicy()));
            }

            // L0 is a last-resort fallback, not a confidence competitor.
            if (level == 0 && !allSuggestions.isEmpty()) {
                logger.info("[StrategySelectionPolicy] Skipping L0 (STOP) fallback because recovery suggestions already exist");
                break;
            }

            if (config.getEscalationPolicy() == ContingencyConfiguration.EscalationPolicy.BEST_PER_LEVEL
                && evaluatedCandidateAtCurrentLevel) {
                logger.info(String.format(
                    "[StrategySelectionPolicy] Skipping %s in L%d because BEST_PER_LEVEL already evaluated the most promising applicable strategy at this level",
                    strategy.getId(), level));
                continue;
            }

            // Strategy selection may prune remaining work once a suggestion
            // exists. The trace-based default compares expected improvement
            // against thresholds rather than comparing cost-discounted history
            // to raw confidence.
            if (selectionPlan != null) {
                StrategyGateDecision gateDecision;
                try {
                    gateDecision = selectionPlan.evaluateGate(strategy, allSuggestions);
                } catch (RuntimeException e) {
                    logger.warning(String.format(
                        "[StrategySelectionPolicy] Selection gate failed for %s: %s; evaluating candidate",
                        strategy.getId(),
                        e.getMessage()));
                    gateDecision = StrategyGateDecision.allow(
                        strategy.getId(),
                        bestSuggestionConfidence(allSuggestions),
                        "selection gate failed; evaluating candidate");
                }
                logger.info(String.format(
                    "[StrategySelectionPolicy] Selection gate %s %s",
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

    private StrategySelectionPlan buildSelectionPlan(
            Situation situation,
            CcrsContext context,
            List<CcrsStrategy> defaultOrder) {
        if (!config.isLearnedSelectionEnabled()) {
            logger.info("[StrategySelectionPolicy] Strategy selection policy disabled; using default escalation order");
            return null;
        }

        List<CcrsTrace> recentHistory = context.getCcrsHistory(config.getLearningHistoryLimit());
        StrategySelectionRequest request = new StrategySelectionRequest(
            situation,
            context,
            defaultOrder,
            config,
            recentHistory);
        StrategySelectionPlan plan;
        try {
            plan = strategySelectionPolicy.createPlan(request);
        } catch (Exception e) {
            logger.warning(String.format(
                "[StrategySelectionPolicy] %s failed to create plan: %s; using default escalation order",
                strategySelectionPolicy.getDescription(),
                e.getMessage()));
            return null;
        }

        if (plan == null) {
            logger.warning(String.format(
                "[StrategySelectionPolicy] %s returned no plan; using default escalation order",
                strategySelectionPolicy.getDescription()));
            return null;
        }

        try {
            logger.info("[StrategySelectionPolicy] " + plan.describeBuild());
        } catch (RuntimeException e) {
            logger.warning(String.format(
                "[StrategySelectionPolicy] %s created a plan but could not describe it: %s",
                strategySelectionPolicy.getDescription(),
                e.getMessage()));
        }
        return plan;
    }

    private List<CcrsStrategy> orderStrategies(
            List<CcrsStrategy> defaultOrder,
            StrategySelectionPlan selectionPlan) {
        if (selectionPlan == null) {
            return defaultOrder;
        }

        List<CcrsStrategy> ordered;
        try {
            ordered = selectionPlan.orderForEvaluation(defaultOrder);
        } catch (RuntimeException e) {
            logger.warning(String.format(
                "[ContingencyCcrs] Strategy selection ordering failed: %s; using default escalation order",
                e.getMessage()));
            return defaultOrder;
        }
        String orderDescription;
        try {
            orderDescription = selectionPlan.describeOrder(ordered);
        } catch (RuntimeException e) {
            logger.warning(String.format(
                "[ContingencyCcrs] Strategy selection order description failed: %s",
                e.getMessage()));
            orderDescription = ordered.stream()
                .map(CcrsStrategy::getId)
                .collect(Collectors.joining(", "));
        }

        logger.info("[ContingencyCcrs] Strategy evaluation order: " + orderDescription);
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
