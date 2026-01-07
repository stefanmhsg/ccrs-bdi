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
        /** Evaluate L1→L4→L0, stop at first applicable suggestion */
        SEQUENTIAL,
        /** Evaluate all at each level, pick best by score */
        BEST_PER_LEVEL,
        /** Evaluate all strategies, sort by cost-weighted confidence */
        PARALLEL
    }
    
    private final Set<String> enabledStrategies;
    private final Set<String> disabledStrategies;
    private final Set<CcrsStrategy.Category> enabledCategories;
    private final EscalationPolicy escalationPolicy;
    private final int maxEscalationLevel;
    private final int maxSuggestions;
    private final boolean traceEnabled;
    
    private ContingencyConfiguration(Builder builder) {
        this.enabledStrategies = Collections.unmodifiableSet(new HashSet<>(builder.enabledStrategies));
        this.disabledStrategies = Collections.unmodifiableSet(new HashSet<>(builder.disabledStrategies));
        this.enabledCategories = Collections.unmodifiableSet(new HashSet<>(builder.enabledCategories));
        this.escalationPolicy = builder.escalationPolicy;
        this.maxEscalationLevel = builder.maxEscalationLevel;
        this.maxSuggestions = builder.maxSuggestions;
        this.traceEnabled = builder.traceEnabled;
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
        private EscalationPolicy escalationPolicy = EscalationPolicy.SEQUENTIAL;
        private int maxEscalationLevel = 4;
        private int maxSuggestions = 3;
        private boolean traceEnabled = true;
        
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
        
        public ContingencyConfiguration build() {
            return new ContingencyConfiguration(this);
        }
    }
}
