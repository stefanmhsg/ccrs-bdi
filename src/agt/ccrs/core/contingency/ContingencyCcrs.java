package ccrs.core.contingency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import ccrs.core.rdf.CcrsContext;

/**
 * Main entry point for contingency CCRS evaluation.
 * Evaluates registered strategies against a situation and returns
 * ranked suggestions for recovery actions.
 */
public class ContingencyCcrs {
    
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
        return evaluateWithTrace(situation, context).getSelectedResults();
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
        List<CcrsStrategy> orderedStrategies = registry.getOrderedForEvaluation(config);
        
        int currentLevel = -1;
        boolean foundAtCurrentLevel = false;
        
        for (CcrsStrategy strategy : orderedStrategies) {
            int level = strategy.getEscalationLevel();
            
            // Track level transitions for SEQUENTIAL policy
            if (level != currentLevel) {
                // If SEQUENTIAL and we found something at previous level, stop
                if (config.getEscalationPolicy() == ContingencyConfiguration.EscalationPolicy.SEQUENTIAL
                    && foundAtCurrentLevel && currentLevel > 0) {
                    break;
                }
                currentLevel = level;
                foundAtCurrentLevel = false;
            }
            
            long evalStart = System.currentTimeMillis();
            CcrsStrategy.Applicability applicability;
            StrategyResult result = null;
            
            try {
                // Check applicability
                applicability = strategy.appliesTo(situation, context);
                
                if (applicability == CcrsStrategy.Applicability.APPLICABLE ||
                    applicability == CcrsStrategy.Applicability.UNKNOWN) {
                    // Evaluate
                    result = strategy.evaluate(situation, context);
                    
                    if (result.isSuggestion()) {
                        allSuggestions.add(result);
                        foundAtCurrentLevel = true;
                    }
                }
            } catch (Exception e) {
                // Strategy threw an exception
                applicability = CcrsStrategy.Applicability.NOT_APPLICABLE;
                result = StrategyResult.noHelp(strategy.getId(),
                    StrategyResult.NoHelpReason.EVALUATION_FAILED,
                    "Exception: " + e.getMessage());
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
        
        // Rank suggestions by score (confidence * (1 - cost))
        List<StrategyResult> rankedResults = allSuggestions.stream()
            .sorted(Comparator.comparingDouble(
                r -> -r.asSuggestion().getScore()))  // Descending
            .limit(config.getMaxSuggestions())
            .collect(Collectors.toList());
        
        long totalTime = System.currentTimeMillis() - startTime;
        
        String selectionReason = buildSelectionReason(rankedResults, orderedStrategies.size());
        
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
        return String.format("Selected %s (score=%.2f) from %d candidates, evaluated %d strategies",
            top.getStrategyId(), top.getScore(), results.size(), totalEvaluated);
    }
    
    /**
     * Create a ContingencyCcrs instance with default strategies registered.
     */
    public static ContingencyCcrs withDefaults() {
        ContingencyCcrs ccrs = new ContingencyCcrs();
        registerDefaultStrategies(ccrs.getRegistry());
        return ccrs;
    }
    
    /**
     * Register the default built-in strategies.
     */
    public static void registerDefaultStrategies(StrategyRegistry registry) {
        // Import strategies from strategies package
        registry.register(new ccrs.core.contingency.strategies.RetryStrategy());
        registry.register(new ccrs.core.contingency.strategies.BacktrackStrategy());
        registry.register(new ccrs.core.contingency.strategies.StopStrategy());
        // LLM strategies registered separately as they need configuration
    }
}
