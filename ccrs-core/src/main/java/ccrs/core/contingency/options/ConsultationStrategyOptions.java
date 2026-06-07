package ccrs.core.contingency.options;

/**
 * Immutable value options for consultation strategy context bounds and fallback confidence.
 */
public final class ConsultationStrategyOptions {

    private final int maxRecentInteractions;
    private final int maxAgentCandidates;
    private final double defaultConfidence;
    private final int maxCcrsTraces;

    private ConsultationStrategyOptions(Builder builder) {
        this.maxRecentInteractions = Math.max(1, builder.maxRecentInteractions);
        this.maxAgentCandidates = Math.max(1, builder.maxAgentCandidates);
        this.defaultConfidence = clampConfidence(builder.defaultConfidence);
        this.maxCcrsTraces = Math.max(0, builder.maxCcrsTraces);
    }

    public static ConsultationStrategyOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .maxRecentInteractions(maxRecentInteractions)
            .maxAgentCandidates(maxAgentCandidates)
            .defaultConfidence(defaultConfidence)
            .maxCcrsTraces(maxCcrsTraces);
    }

    public int getMaxRecentInteractions() {
        return maxRecentInteractions;
    }

    public int getMaxAgentCandidates() {
        return maxAgentCandidates;
    }

    public double getDefaultConfidence() {
        return defaultConfidence;
    }

    public int getMaxCcrsTraces() {
        return maxCcrsTraces;
    }

    private static double clampConfidence(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static final class Builder {
        private int maxRecentInteractions = 10;
        private int maxAgentCandidates = 5;
        private double defaultConfidence = 0.5;
        private int maxCcrsTraces = 3;

        public Builder maxRecentInteractions(int max) {
            this.maxRecentInteractions = max;
            return this;
        }

        public Builder maxAgentCandidates(int max) {
            this.maxAgentCandidates = max;
            return this;
        }

        public Builder defaultConfidence(double confidence) {
            this.defaultConfidence = confidence;
            return this;
        }

        public Builder maxCcrsTraces(int max) {
            this.maxCcrsTraces = max;
            return this;
        }

        public ConsultationStrategyOptions build() {
            return new ConsultationStrategyOptions(this);
        }
    }
}
