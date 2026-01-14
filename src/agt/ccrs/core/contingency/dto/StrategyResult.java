package ccrs.core.contingency.dto;

import ccrs.core.opportunistic.OpportunisticResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result from a contingency strategy evaluation.
 * Either a Suggestion (recommended action) or NoHelp (cannot help).
 * 
 * Use the static factory methods to create instances:
 * - StrategyResult.suggest(...)
 * - StrategyResult.noHelp(...)
 */
public abstract class StrategyResult {
    
    protected final String strategyId;
    
    protected StrategyResult(String strategyId) {
        this.strategyId = Objects.requireNonNull(strategyId);
    }
    
    public String getStrategyId() {
        return strategyId;
    }
    
    public abstract boolean isSuggestion();
    
    public Suggestion asSuggestion() {
        if (this instanceof Suggestion) {
            return (Suggestion) this;
        }
        throw new IllegalStateException("Result is NoHelp, not a Suggestion");
    }
    
    public NoHelp asNoHelp() {
        if (this instanceof NoHelp) {
            return (NoHelp) this;
        }
        throw new IllegalStateException("Result is Suggestion, not NoHelp");
    }
    
    // Factory methods
    
    public static Suggestion.Builder suggest(String strategyId, String actionType) {
        return new Suggestion.Builder(strategyId, actionType);
    }
    
    public static NoHelp noHelp(String strategyId, NoHelpReason reason, String explanation) {
        return new NoHelp(strategyId, reason, explanation);
    }
    
    // ========== Suggestion ==========
    
    /**
     * A concrete recovery suggestion from a strategy.
     */
    public static class Suggestion extends StrategyResult {
        
        private final String actionType;
        private final String actionTarget;
        private final Map<String, Object> actionParams;
        private final double confidence;
        private final double estimatedCost;
        private final String rationale;
        private final List<OpportunisticResult> opportunisticGuidance;  // B2: Optional preference shaping
        
        private Suggestion(Builder builder) {
            super(builder.strategyId);
            this.actionType = Objects.requireNonNull(builder.actionType);
            this.actionTarget = builder.actionTarget;
            this.actionParams = Collections.unmodifiableMap(new HashMap<>(builder.actionParams));
            this.confidence = builder.confidence;
            this.estimatedCost = builder.estimatedCost;
            this.rationale = builder.rationale;
            this.opportunisticGuidance = builder.opportunisticGuidance != null ? 
                List.copyOf(builder.opportunisticGuidance) : List.of();
        }
        
        @Override
        public boolean isSuggestion() {
            return true;
        }
        
        public String getActionType() {
            return actionType;
        }
        
        public String getActionTarget() {
            return actionTarget;
        }
        
        public Map<String, Object> getActionParams() {
            return actionParams;
        }
        
        @SuppressWarnings("unchecked")
        public <T> T getActionParam(String key) {
            return (T) actionParams.get(key);
        }
        
        public double getConfidence() {
            return confidence;
        }
        
        public double getEstimatedCost() {
            return estimatedCost;
        }
        
        public String getRationale() {
            return rationale;
        }
        
        /**
         * Get opportunistic guidance for preference shaping (B2 extension).
         * Empty list if no guidance available.
         */
        public List<OpportunisticResult> getOpportunisticGuidance() {
            return opportunisticGuidance;
        }
        
        /**
         * Check if opportunistic guidance is available.
         */
        public boolean hasOpportunisticGuidance() {
            return !opportunisticGuidance.isEmpty();
        }
        
        /**
         * Score combining confidence and cost for ranking.
         * Higher is better.
         */
        public double getScore() {
            return confidence * (1.0 - estimatedCost);
        }
        
        @Override
        public String toString() {
            return String.format("Suggestion{strategy=%s, action=%s, target=%s, confidence=%.2f, cost=%.2f}",
                strategyId, actionType, actionTarget, confidence, estimatedCost);
        }
        
        public static class Builder {
            private final String strategyId;
            private final String actionType;
            private String actionTarget;
            private Map<String, Object> actionParams = new HashMap<>();
            private double confidence = 0.5;
            private double estimatedCost = 0.5;
            private String rationale;
            private List<OpportunisticResult> opportunisticGuidance;  // B2: Optional
            
            private Builder(String strategyId, String actionType) {
                this.strategyId = strategyId;
                this.actionType = actionType;
            }
            
            public Builder target(String actionTarget) {
                this.actionTarget = actionTarget;
                return this;
            }
            
            public Builder param(String key, Object value) {
                this.actionParams.put(key, value);
                return this;
            }
            
            public Builder params(Map<String, Object> params) {
                this.actionParams.putAll(params);
                return this;
            }
            
            public Builder confidence(double confidence) {
                this.confidence = Math.max(0.0, Math.min(1.0, confidence));
                return this;
            }
            
            public Builder cost(double estimatedCost) {
                this.estimatedCost = Math.max(0.0, Math.min(1.0, estimatedCost));
                return this;
            }
            
            public Builder rationale(String rationale) {
                this.rationale = rationale;
                return this;
            }
            
            /**
             * Add opportunistic guidance for preference shaping (B2 extension).
             * Will be used to guide agent's option selection during backtracking.
             */
            public Builder opportunisticGuidance(List<OpportunisticResult> guidance) {
                this.opportunisticGuidance = guidance;
                return this;
            }
            
            public Suggestion build() {
                return new Suggestion(this);
            }
        }
    }
    
    // ========== NoHelp ==========
    
    /**
     * Indicates a strategy cannot help with the situation.
     */
    public static class NoHelp extends StrategyResult {
        
        private final NoHelpReason reason;
        private final String explanation;
        
        private NoHelp(String strategyId, NoHelpReason reason, String explanation) {
            super(strategyId);
            this.reason = Objects.requireNonNull(reason);
            this.explanation = explanation;
        }
        
        @Override
        public boolean isSuggestion() {
            return false;
        }
        
        public NoHelpReason getReason() {
            return reason;
        }
        
        public String getExplanation() {
            return explanation;
        }
        
        @Override
        public String toString() {
            return String.format("NoHelp{strategy=%s, reason=%s, explanation='%s'}",
                strategyId, reason, explanation);
        }
    }
    
    /**
     * Reasons why a strategy cannot help.
     */
    public enum NoHelpReason {
        /** Strategy does not apply to this type of situation */
        NOT_APPLICABLE,
        /** Required precondition or configuration is missing */
        PRECONDITION_MISSING,
        /** Strategy was already attempted maximum times */
        ALREADY_ATTEMPTED,
        /** Not enough context information to make a suggestion */
        INSUFFICIENT_CONTEXT,
        /** Strategy evaluation failed with an error */
        EVALUATION_FAILED
    }
}
