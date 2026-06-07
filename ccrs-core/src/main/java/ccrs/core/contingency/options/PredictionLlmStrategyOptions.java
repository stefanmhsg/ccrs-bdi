package ccrs.core.contingency.options;

import java.util.List;

import ccrs.core.rdf.CcrsContext;

/**
 * Immutable value options for the default LLM prediction strategy policy.
 */
public final class PredictionLlmStrategyOptions {

    public static final String DEFAULT_FILTERED_TRIPLE_NAMESPACE = "https://example.org/ui";

    private final int maxHistoryActions;
    private final int maxInteractionStateTriples;
    private final int maxCcrsTraces;
    private final int maxNeighborhoodOutgoing;
    private final int maxNeighborhoodIncoming;
    private final List<String> filteredTripleNamespaces;
    private final double baseConfidence;
    private final boolean plainTextFallbackEnabled;

    private PredictionLlmStrategyOptions(Builder builder) {
        this.maxHistoryActions = Math.max(0, builder.maxHistoryActions);
        this.maxInteractionStateTriples = Math.max(0, builder.maxInteractionStateTriples);
        this.maxCcrsTraces = Math.max(0, builder.maxCcrsTraces);
        this.maxNeighborhoodOutgoing = Math.max(0, builder.maxNeighborhoodOutgoing);
        this.maxNeighborhoodIncoming = Math.max(0, builder.maxNeighborhoodIncoming);
        this.filteredTripleNamespaces = normalizeNamespaces(builder.filteredTripleNamespaces);
        this.baseConfidence = clampConfidence(builder.baseConfidence);
        this.plainTextFallbackEnabled = builder.plainTextFallbackEnabled;
    }

    public static PredictionLlmStrategyOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .maxHistoryActions(maxHistoryActions)
            .maxInteractionStateTriples(maxInteractionStateTriples)
            .maxCcrsTraces(maxCcrsTraces)
            .maxNeighborhood(maxNeighborhoodOutgoing, maxNeighborhoodIncoming)
            .filteredTripleNamespaces(filteredTripleNamespaces)
            .baseConfidence(baseConfidence)
            .plainTextFallbackEnabled(plainTextFallbackEnabled);
    }

    public int getMaxHistoryActions() {
        return maxHistoryActions;
    }

    public int getMaxInteractionStateTriples() {
        return maxInteractionStateTriples;
    }

    public int getMaxCcrsTraces() {
        return maxCcrsTraces;
    }

    public int getMaxNeighborhoodOutgoing() {
        return maxNeighborhoodOutgoing;
    }

    public int getMaxNeighborhoodIncoming() {
        return maxNeighborhoodIncoming;
    }

    public List<String> getFilteredTripleNamespaces() {
        return filteredTripleNamespaces;
    }

    public double getBaseConfidence() {
        return baseConfidence;
    }

    public boolean isPlainTextFallbackEnabled() {
        return plainTextFallbackEnabled;
    }

    private static List<String> normalizeNamespaces(List<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return List.of();
        }
        return namespaces.stream()
            .filter(namespace -> namespace != null && !namespace.isBlank())
            .distinct()
            .toList();
    }

    private static double clampConfidence(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static final class Builder {
        private int maxHistoryActions = 50;
        private int maxInteractionStateTriples = 100;
        private int maxCcrsTraces = 10;
        private int maxNeighborhoodOutgoing = CcrsContext.DEFAULT_MAX_OUTGOING;
        private int maxNeighborhoodIncoming = CcrsContext.DEFAULT_MAX_INCOMING;
        private List<String> filteredTripleNamespaces = List.of(DEFAULT_FILTERED_TRIPLE_NAMESPACE);
        private double baseConfidence = 0.6;
        private boolean plainTextFallbackEnabled = true;

        public Builder maxHistoryActions(int max) {
            this.maxHistoryActions = max;
            return this;
        }

        public Builder maxInteractionStateTriples(int max) {
            this.maxInteractionStateTriples = max;
            return this;
        }

        public Builder maxCcrsTraces(int max) {
            this.maxCcrsTraces = max;
            return this;
        }

        public Builder maxNeighborhood(int maxOutgoing, int maxIncoming) {
            this.maxNeighborhoodOutgoing = maxOutgoing;
            this.maxNeighborhoodIncoming = maxIncoming;
            return this;
        }

        public Builder maxNeighborhoodOutgoing(int max) {
            this.maxNeighborhoodOutgoing = max;
            return this;
        }

        public Builder maxNeighborhoodIncoming(int max) {
            this.maxNeighborhoodIncoming = max;
            return this;
        }

        public Builder filteredTripleNamespaces(List<String> namespaces) {
            this.filteredTripleNamespaces = namespaces;
            return this;
        }

        public Builder filterTripleNamespace(String namespace) {
            if (namespace == null || namespace.isBlank()) {
                return this;
            }
            List<String> existing = normalizeNamespaces(this.filteredTripleNamespaces);
            if (existing.contains(namespace)) {
                return this;
            }
            java.util.ArrayList<String> updated = new java.util.ArrayList<>(existing);
            updated.add(namespace);
            this.filteredTripleNamespaces = List.copyOf(updated);
            return this;
        }

        public Builder baseConfidence(double confidence) {
            this.baseConfidence = confidence;
            return this;
        }

        public Builder plainTextFallbackEnabled(boolean enabled) {
            this.plainTextFallbackEnabled = enabled;
            return this;
        }

        public PredictionLlmStrategyOptions build() {
            return new PredictionLlmStrategyOptions(this);
        }
    }
}
