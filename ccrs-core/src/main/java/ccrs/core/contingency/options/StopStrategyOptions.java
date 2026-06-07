package ccrs.core.contingency.options;

/**
 * Immutable value options for stop strategy behavior.
 */
public final class StopStrategyOptions {

    private final boolean requireExhaustion;
    private final int exhaustionThreshold;
    private final int stopLookbackLimit;

    private StopStrategyOptions(Builder builder) {
        this.requireExhaustion = builder.requireExhaustion;
        this.exhaustionThreshold = Math.max(0, builder.exhaustionThreshold);
        this.stopLookbackLimit = Math.max(1, builder.stopLookbackLimit);
    }

    public static StopStrategyOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .requireExhaustion(requireExhaustion)
            .exhaustionThreshold(exhaustionThreshold)
            .stopLookbackLimit(stopLookbackLimit);
    }

    public boolean isRequireExhaustion() {
        return requireExhaustion;
    }

    public int getExhaustionThreshold() {
        return exhaustionThreshold;
    }

    public int getStopLookbackLimit() {
        return stopLookbackLimit;
    }

    public static final class Builder {
        private boolean requireExhaustion = true;
        private int exhaustionThreshold = 2;
        private int stopLookbackLimit = 30;

        /**
         * Require other non-stop strategy attempts before suggesting graceful stop.
         */
        public Builder requireExhaustion(boolean require) {
            this.requireExhaustion = require;
            return this;
        }

        /**
         * Minimum recent non-stop strategy attempts before stop becomes applicable.
         */
        public Builder exhaustionThreshold(int threshold) {
            this.exhaustionThreshold = threshold;
            return this;
        }

        /**
         * Limit recent CCRS traces inspected when checking exhaustion.
         */
        public Builder stopLookbackLimit(int maxRecentTraces) {
            this.stopLookbackLimit = maxRecentTraces;
            return this;
        }

        public StopStrategyOptions build() {
            return new StopStrategyOptions(this);
        }
    }
}
