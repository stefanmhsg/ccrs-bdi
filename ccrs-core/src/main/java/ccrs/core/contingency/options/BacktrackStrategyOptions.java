package ccrs.core.contingency.options;

/**
 * Immutable value options for backtrack strategy resource bounds.
 */
public final class BacktrackStrategyOptions {

    private final int maxRecentInteractions;

    private BacktrackStrategyOptions(Builder builder) {
        this.maxRecentInteractions = Math.max(1, builder.maxRecentInteractions);
    }

    public static BacktrackStrategyOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().maxRecentInteractions(maxRecentInteractions);
    }

    public int getMaxRecentInteractions() {
        return maxRecentInteractions;
    }

    public static final class Builder {
        private int maxRecentInteractions = 1000;

        public Builder maxRecentInteractions(int max) {
            this.maxRecentInteractions = max;
            return this;
        }

        public BacktrackStrategyOptions build() {
            return new BacktrackStrategyOptions(this);
        }
    }
}
