package ccrs.core.contingency;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Trace of a CCRS contingency evaluation.
 * Captures the full evaluation process for debugging, learning, and analysis.
 */
public class CcrsTrace {
    
    /**
     * Final outcome after agent executes suggestion.
     */
    public enum Outcome {
        /** Suggestion worked, problem resolved */
        SUCCESS,
        /** Suggestion partially helped */
        PARTIAL,
        /** Suggestion did not help */
        FAILED,
        /** Outcome not yet known */
        PENDING,
        /** Outcome was never reported */
        UNKNOWN
    }
    
    /**
     * Record of evaluating a single strategy.
     */
    public static class StrategyEvaluation {
        private final String strategyId;
        private final int escalationLevel;
        private final CcrsStrategy.Applicability applicability;
        private final StrategyResult result;
        private final long evaluationTimeMs;
        
        public StrategyEvaluation(String strategyId, int escalationLevel,
                                   CcrsStrategy.Applicability applicability,
                                   StrategyResult result, long evaluationTimeMs) {
            this.strategyId = strategyId;
            this.escalationLevel = escalationLevel;
            this.applicability = applicability;
            this.result = result;
            this.evaluationTimeMs = evaluationTimeMs;
        }
        
        public String getStrategyId() { return strategyId; }
        public int getEscalationLevel() { return escalationLevel; }
        public CcrsStrategy.Applicability getApplicability() { return applicability; }
        public StrategyResult getResult() { return result; }
        public long getEvaluationTimeMs() { return evaluationTimeMs; }
        
        @Override
        public String toString() {
            return String.format("Eval{%s L%d: %s -> %s (%dms)}", 
                strategyId, escalationLevel, applicability, 
                result != null ? (result.isSuggestion() ? "SUGGESTION" : "NO_HELP") : "N/A",
                evaluationTimeMs);
        }
    }
    
    // Identity
    private final String id;
    private final Instant timestamp;
    
    // Input
    private final Situation situation;
    
    // Evaluation process
    private final List<StrategyEvaluation> evaluations;
    
    // Output
    private final List<StrategyResult> selectedResults;
    private final String selectionReason;
    private final long totalEvaluationTimeMs;
    
    // Outcome (updated later)
    private Outcome outcome = Outcome.PENDING;
    private String outcomeDetails;
    
    private CcrsTrace(Builder builder) {
        this.id = builder.id;
        this.timestamp = builder.timestamp;
        this.situation = builder.situation;
        this.evaluations = Collections.unmodifiableList(new ArrayList<>(builder.evaluations));
        this.selectedResults = Collections.unmodifiableList(new ArrayList<>(builder.selectedResults));
        this.selectionReason = builder.selectionReason;
        this.totalEvaluationTimeMs = builder.totalEvaluationTimeMs;
    }
    
    // Getters
    
    public String getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public Situation getSituation() { return situation; }
    public List<StrategyEvaluation> getEvaluations() { return evaluations; }
    public List<StrategyResult> getSelectedResults() { return selectedResults; }
    public String getSelectionReason() { return selectionReason; }
    public long getTotalEvaluationTimeMs() { return totalEvaluationTimeMs; }
    public Outcome getOutcome() { return outcome; }
    public String getOutcomeDetails() { return outcomeDetails; }
    
    /**
     * Report the outcome after attempting the suggested action.
     */
    public void reportOutcome(Outcome outcome, String details) {
        this.outcome = outcome;
        this.outcomeDetails = details;
    }
    
    public void reportSuccess() {
        this.outcome = Outcome.SUCCESS;
    }
    
    public void reportFailure(String reason) {
        this.outcome = Outcome.FAILED;
        this.outcomeDetails = reason;
    }
    
    /**
     * Check if any suggestion was found.
     */
    public boolean hasSuggestions() {
        return !selectedResults.isEmpty() && selectedResults.stream()
            .anyMatch(StrategyResult::isSuggestion);
    }
    
    /**
     * Get the top suggestion if available.
     */
    public StrategyResult.Suggestion getTopSuggestion() {
        return selectedResults.stream()
            .filter(StrategyResult::isSuggestion)
            .map(StrategyResult::asSuggestion)
            .findFirst()
            .orElse(null);
    }
    
    @Override
    public String toString() {
        int suggestions = (int) selectedResults.stream()
            .filter(StrategyResult::isSuggestion).count();
        return String.format("CcrsTrace{id=%s, situation=%s, evaluated=%d, suggestions=%d, outcome=%s}",
            id.substring(0, 8), situation.getType(), evaluations.size(), suggestions, outcome);
    }
    
    /**
     * Generate a detailed report for debugging.
     */
    public String toDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CCRS Trace ").append(id).append(" ===\n");
        sb.append("Timestamp: ").append(timestamp).append("\n");
        sb.append("Situation: ").append(situation).append("\n\n");
        
        sb.append("Evaluations (").append(evaluations.size()).append("):\n");
        for (StrategyEvaluation eval : evaluations) {
            sb.append("  ").append(eval).append("\n");
        }
        
        sb.append("\nSelected Results (").append(selectedResults.size()).append("):\n");
        for (StrategyResult result : selectedResults) {
            sb.append("  ").append(result).append("\n");
        }
        
        sb.append("\nSelection Reason: ").append(selectionReason).append("\n");
        sb.append("Total Time: ").append(totalEvaluationTimeMs).append("ms\n");
        sb.append("Outcome: ").append(outcome);
        if (outcomeDetails != null) {
            sb.append(" (").append(outcomeDetails).append(")");
        }
        sb.append("\n");
        
        return sb.toString();
    }
    
    // Builder
    
    public static Builder builder(Situation situation) {
        return new Builder(situation);
    }
    
    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private Instant timestamp = Instant.now();
        private final Situation situation;
        private List<StrategyEvaluation> evaluations = new ArrayList<>();
        private List<StrategyResult> selectedResults = new ArrayList<>();
        private String selectionReason;
        private long totalEvaluationTimeMs;
        
        private Builder(Situation situation) {
            this.situation = situation;
        }
        
        public Builder addEvaluation(StrategyEvaluation evaluation) {
            this.evaluations.add(evaluation);
            return this;
        }
        
        public Builder addEvaluation(String strategyId, int level,
                                      CcrsStrategy.Applicability applicability,
                                      StrategyResult result, long timeMs) {
            return addEvaluation(new StrategyEvaluation(
                strategyId, level, applicability, result, timeMs));
        }
        
        public Builder selectedResults(List<StrategyResult> results) {
            this.selectedResults = new ArrayList<>(results);
            return this;
        }
        
        public Builder selectionReason(String reason) {
            this.selectionReason = reason;
            return this;
        }
        
        public Builder totalTime(long timeMs) {
            this.totalEvaluationTimeMs = timeMs;
            return this;
        }
        
        public CcrsTrace build() {
            return new CcrsTrace(this);
        }
    }
}
