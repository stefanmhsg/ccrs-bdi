package ccrs.core.contingency;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Registry for contingency strategies.
 * Manages strategy registration, lookup, and filtering.
 */
public class StrategyRegistry {
    
    private static final Logger logger = Logger.getLogger(StrategyRegistry.class.getName());

    private final Map<String, CcrsStrategy> strategies = new LinkedHashMap<>();
    
    /**
     * Register a strategy.
     * 
     * @param strategy The strategy to register
     * @throws IllegalArgumentException if strategy with same ID already exists
     */
    public void register(CcrsStrategy strategy) {
        if (strategies.containsKey(strategy.getId())) {
            throw new IllegalArgumentException(
                "Strategy already registered: " + strategy.getId());
        }
        strategies.put(strategy.getId(), strategy);
        logger.info("Registered strategy: " + strategy.getId());
    }
    
    /**
     * Register multiple strategies.
     */
    public void registerAll(CcrsStrategy... strategies) {
        for (CcrsStrategy strategy : strategies) {
            register(strategy);
        }
    }
    
    /**
     * Unregister a strategy by ID.
     * 
     * @param strategyId The strategy ID to remove
     * @return The removed strategy, or null if not found
     */
    public CcrsStrategy unregister(String strategyId) {
        logger.info("Unregistering strategy: " + strategyId);
        return strategies.remove(strategyId);
    }
    
    /**
     * Get a strategy by ID.
     */
    public Optional<CcrsStrategy> getStrategy(String strategyId) {
        return Optional.ofNullable(strategies.get(strategyId));
    }
    
    /**
     * Get all registered strategies.
     */
    public Collection<CcrsStrategy> getAll() {
        return Collections.unmodifiableCollection(strategies.values());
    }
    
    /**
     * Get strategies by category.
     */
    public List<CcrsStrategy> getByCategory(CcrsStrategy.Category category) {
        return strategies.values().stream()
            .filter(s -> s.getCategory() == category)
            .collect(Collectors.toList());
    }
    
    /**
     * Get strategies by escalation level.
     */
    public List<CcrsStrategy> getByLevel(int level) {
        return strategies.values().stream()
            .filter(s -> s.getEscalationLevel() == level)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all enabled strategies, filtered by configuration.
     */
    public List<CcrsStrategy> getEnabled(ContingencyConfiguration config) {
        return strategies.values().stream()
            .filter(config::isStrategyEnabled)
            .collect(Collectors.toList());
    }
    
    /**
     * Get strategies sorted by escalation level for evaluation.
     * Order: L1 → L2 → L3 → L4 → L0 (stop is last resort)
     */
    public List<CcrsStrategy> getOrderedForEvaluation(ContingencyConfiguration config) {
        List<CcrsStrategy> enabled = getEnabled(config);
        
        // Custom comparator: L1, L2, L3, L4, then L0 (last resort)
        Comparator<CcrsStrategy> escalationOrder = (a, b) -> {
            int levelA = a.getEscalationLevel() == 0 ? 100 : a.getEscalationLevel();
            int levelB = b.getEscalationLevel() == 0 ? 100 : b.getEscalationLevel();
            return Integer.compare(levelA, levelB);
        };
        
        enabled.sort(escalationOrder);

        logger.info("Ordered strategies for evaluation: " +
            enabled.stream().map(CcrsStrategy::getId).collect(Collectors.joining(", ")));

        return enabled;
    }
    
    /**
     * Check if registry has any strategies.
     */
    public boolean isEmpty() {
        return strategies.isEmpty();
    }
    
    /**
     * Get count of registered strategies.
     */
    public int size() {
        return strategies.size();
    }
    
    /**
     * Clear all registered strategies.
     */
    public void clear() {
        strategies.clear();
    }
    
    @Override
    public String toString() {
        return String.format("StrategyRegistry{%d strategies: %s}",
            strategies.size(), strategies.keySet());
    }
}
