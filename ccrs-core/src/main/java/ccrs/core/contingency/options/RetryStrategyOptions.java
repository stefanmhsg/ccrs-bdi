package ccrs.core.contingency.options;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable value options for retry strategy behavior.
 */
public final class RetryStrategyOptions {

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double backoffMultiplier;
    private final Set<String> retriableCodes;
    private final int retryLookbackLimit;

    private RetryStrategyOptions(Builder builder) {
        this.maxAttempts = Math.max(0, builder.maxAttempts);
        this.initialDelayMs = Math.max(0L, builder.initialDelayMs);
        this.backoffMultiplier = Math.max(0.0, builder.backoffMultiplier);
        this.retriableCodes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.retriableCodes));
        this.retryLookbackLimit = Math.max(1, builder.retryLookbackLimit);
    }

    public static RetryStrategyOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .maxAttempts(maxAttempts)
            .initialDelayMs(initialDelayMs)
            .backoffMultiplier(backoffMultiplier)
            .retriableCodes(retriableCodes)
            .retryLookbackLimit(retryLookbackLimit);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public Set<String> getRetriableCodes() {
        return retriableCodes;
    }

    public int getRetryLookbackLimit() {
        return retryLookbackLimit;
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private long initialDelayMs = 1000L;
        private double backoffMultiplier = 2.0;
        private Set<String> retriableCodes = defaultRetriableCodes();
        private int retryLookbackLimit = 25;

        public Builder maxAttempts(int max) {
            this.maxAttempts = max;
            return this;
        }

        public Builder initialDelayMs(long delayMs) {
            this.initialDelayMs = delayMs;
            return this;
        }

        public Builder backoffMultiplier(double multiplier) {
            this.backoffMultiplier = multiplier;
            return this;
        }

        public Builder retriableCodes(Set<String> codes) {
            this.retriableCodes = normalizeCodes(codes);
            return this;
        }

        public Builder addRetriableCode(String code) {
            if (code != null && !code.isBlank()) {
                this.retriableCodes.add(code);
            }
            return this;
        }

        public Builder retryLookbackLimit(int maxRecentTraces) {
            this.retryLookbackLimit = maxRecentTraces;
            return this;
        }

        public RetryStrategyOptions build() {
            return new RetryStrategyOptions(this);
        }

        private static Set<String> normalizeCodes(Set<String> codes) {
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if (codes != null) {
                codes.stream()
                    .filter(code -> code != null && !code.isBlank())
                    .forEach(normalized::add);
            }
            return normalized;
        }

        private static Set<String> defaultRetriableCodes() {
            LinkedHashSet<String> codes = new LinkedHashSet<>();
            codes.add("500");
            codes.add("502");
            codes.add("503");
            codes.add("504");
            codes.add("timeout");
            codes.add("connection_reset");
            codes.add("connection_refused");
            return codes;
        }
    }
}
