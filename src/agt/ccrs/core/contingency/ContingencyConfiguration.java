package ccrs.core.contingency;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration for contingency CCRS evaluation.
 */
public class ContingencyConfiguration {
    
    /**
     * Policy for selecting strategies across escalation levels.
     */
    public enum EscalationPolicy {
        /** Evaluate learned/default order and stop at the first suggestion */
        SEQUENTIAL,
        /** Evaluate the most promising applicable strategy per escalation level */
        BEST_PER_LEVEL,
        /** Consider all enabled strategies, then sort returned suggestions by confidence */
        PARALLEL
    }
    
    private final Set<String> enabledStrategies;
    private final Set<String> disabledStrategies;
    private final Set<CcrsStrategy.Category> enabledCategories;
    private final EscalationPolicy escalationPolicy;
    private final int maxEscalationLevel;
    private final int maxSuggestions;
    private final boolean traceEnabled;
    private final boolean learnedSelectionEnabled;
    private final int learningHistoryLimit;
    private final int minimumLearningSamples;
    private final long l0CostReferenceTimeMs;
    private final long l1CostReferenceTimeMs;
    private final long l2CostReferenceTimeMs;
    private final long l3CostReferenceTimeMs;
    private final long l4CostReferenceTimeMs;
    
    private ContingencyConfiguration(Builder builder) {
        this.enabledStrategies = Collections.unmodifiableSet(new HashSet<>(builder.enabledStrategies));
        this.disabledStrategies = Collections.unmodifiableSet(new HashSet<>(builder.disabledStrategies));
        this.enabledCategories = Collections.unmodifiableSet(new HashSet<>(builder.enabledCategories));
        this.escalationPolicy = builder.escalationPolicy;
        this.maxEscalationLevel = builder.maxEscalationLevel;
        this.maxSuggestions = builder.maxSuggestions;
        this.traceEnabled = builder.traceEnabled;
        this.learnedSelectionEnabled = builder.learnedSelectionEnabled;
        this.learningHistoryLimit = builder.learningHistoryLimit;
        this.minimumLearningSamples = builder.minimumLearningSamples;
        this.l0CostReferenceTimeMs = builder.l0CostReferenceTimeMs;
        this.l1CostReferenceTimeMs = builder.l1CostReferenceTimeMs;
        this.l2CostReferenceTimeMs = builder.l2CostReferenceTimeMs;
        this.l3CostReferenceTimeMs = builder.l3CostReferenceTimeMs;
        this.l4CostReferenceTimeMs = builder.l4CostReferenceTimeMs;
    }
    
    /**
     * Check if a strategy is enabled by this configuration.
     */
    public boolean isStrategyEnabled(CcrsStrategy strategy) {
        // Explicit disable takes precedence
        if (disabledStrategies.contains(strategy.getId())) {
            return false;
        }
        // Explicit enable if whitelist is used
        if (!enabledStrategies.isEmpty() && !enabledStrategies.contains(strategy.getId())) {
            return false;
        }
        // Category filter
        if (!enabledCategories.isEmpty() && !enabledCategories.contains(strategy.getCategory())) {
            return false;
        }
        // Escalation level filter
        if (strategy.getEscalationLevel() > maxEscalationLevel && strategy.getEscalationLevel() != 0) {
            // L0 (stop) is always allowed as last resort
            return false;
        }
        // Strategy's own enabled state
        return strategy.isEnabled();
    }
    
    public Set<String> getEnabledStrategies() {
        return enabledStrategies;
    }
    
    public Set<String> getDisabledStrategies() {
        return disabledStrategies;
    }
    
    public Set<CcrsStrategy.Category> getEnabledCategories() {
        return enabledCategories;
    }
    
    public EscalationPolicy getEscalationPolicy() {
        return escalationPolicy;
    }
    
    public int getMaxEscalationLevel() {
        return maxEscalationLevel;
    }
    
    public int getMaxSuggestions() {
        return maxSuggestions;
    }
    
    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public boolean isLearnedSelectionEnabled() {
        return learnedSelectionEnabled;
    }

    public int getLearningHistoryLimit() {
        return learningHistoryLimit;
    }

    public int getMinimumLearningSamples() {
        return minimumLearningSamples;
    }

    public long getCostReferenceTimeMs(int escalationLevel) {
        return switch (escalationLevel) {
            case 0 -> l0CostReferenceTimeMs;
            case 1 -> l1CostReferenceTimeMs;
            case 2 -> l2CostReferenceTimeMs;
            case 3 -> l3CostReferenceTimeMs;
            case 4 -> l4CostReferenceTimeMs;
            default -> l4CostReferenceTimeMs;
        };
    }
    
    /**
     * Default configuration with all strategies enabled.
     */
    public static ContingencyConfiguration defaults() {
        return builder().build();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Set<String> enabledStrategies = new HashSet<>();
        private Set<String> disabledStrategies = new HashSet<>();
        private Set<CcrsStrategy.Category> enabledCategories = new HashSet<>();
        private EscalationPolicy escalationPolicy = EscalationPolicy.PARALLEL; // Default to considering all enabled strategies
        private int maxEscalationLevel = 4;
        private int maxSuggestions = 7;
        private boolean traceEnabled = true;
        private boolean learnedSelectionEnabled = true;
        private int learningHistoryLimit = 25;
        private int minimumLearningSamples = 2;
        private long l0CostReferenceTimeMs = 50L;
        private long l1CostReferenceTimeMs = 100L;
        private long l2CostReferenceTimeMs = 1000L;
        private long l3CostReferenceTimeMs = 2000L;
        private long l4CostReferenceTimeMs = 3000L;
        
        /**
         * Only enable specific strategies (whitelist).
         */
        public Builder enableOnly(String... strategyIds) {
            Collections.addAll(this.enabledStrategies, strategyIds);
            return this;
        }
        
        /**
         * Disable specific strategies (blacklist).
         */
        public Builder disable(String... strategyIds) {
            Collections.addAll(this.disabledStrategies, strategyIds);
            return this;
        }
        
        /**
         * Only enable strategies from specific categories.
         */
        public Builder enableCategories(CcrsStrategy.Category... categories) {
            Collections.addAll(this.enabledCategories, categories);
            return this;
        }
        
        /**
         * Set the escalation policy.
         */
        public Builder policy(EscalationPolicy policy) {
            this.escalationPolicy = policy;
            return this;
        }
        
        /**
         * Set maximum escalation level to consider.
         * Strategies above this level won't be evaluated (except L0).
         */
        public Builder maxLevel(int level) {
            this.maxEscalationLevel = level;
            return this;
        }
        
        /**
         * Disable social strategies (L4).
         */
        public Builder noSocial() {
            this.maxEscalationLevel = Math.min(this.maxEscalationLevel, 3);
            return this;
        }
        
        /**
         * Set maximum number of suggestions to return.
         */
        public Builder maxSuggestions(int max) {
            this.maxSuggestions = max;
            return this;
        }
        
        /**
         * Enable or disable trace generation.
         */
        public Builder trace(boolean enabled) {
            this.traceEnabled = enabled;
            return this;
        }

        /**
         * Enable or disable trace-based strategy ordering and pruning.
         */
        public Builder learnedSelection(boolean enabled) {
            this.learnedSelectionEnabled = enabled;
            return this;
        }

        /**
         * Set how many recent traces are used for learned strategy priors.
         * A bounded window keeps selection adaptive and avoids unbounded memory
         * or old data dominating current conditions.
         */
        public Builder learningHistoryLimit(int max) {
            this.learningHistoryLimit = Math.max(1, max);
            return this;
        }

        /**
         * Set the minimum evaluations per strategy before it can be reordered or pruned.
         */
        public Builder minimumLearningSamples(int min) {
            this.minimumLearningSamples = Math.max(1, min);
            return this;
        }

        /**
         * Set the time scale used when converting evaluation time into effort
         * for one escalation level. This is a prior for interpreting measured
         * runtime, not a suggestion-level cost.
         */
        public Builder costReferenceTimeMs(int escalationLevel, long ms) {
            long normalized = Math.max(1L, ms);
            switch (escalationLevel) {
                case 0 -> this.l0CostReferenceTimeMs = normalized;
                case 1 -> this.l1CostReferenceTimeMs = normalized;
                case 2 -> this.l2CostReferenceTimeMs = normalized;
                case 3 -> this.l3CostReferenceTimeMs = normalized;
                case 4 -> this.l4CostReferenceTimeMs = normalized;
                default -> throw new IllegalArgumentException("Unsupported escalation level: " + escalationLevel);
            }
            return this;
        }
        
        public ContingencyConfiguration build() {
            return new ContingencyConfiguration(this);
        }
    }
}
