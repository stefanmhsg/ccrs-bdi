package ccrs.capabilities.a2a;

import ccrs.capabilities.ConfigResolver;

/**
 * Configuration for the A2A-backed consultation channel.
 */
public class A2aConfig {

    private final boolean logEvents;

    private A2aConfig(Builder builder) {
        this.logEvents = builder.logEvents;
    }

    public boolean isLogEvents() {
        return logEvents;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder fromEnvironment() {
        return builder()
            .logEvents(Boolean.parseBoolean(firstNonNull(
                ConfigResolver.resolve("CCRS_A2A_LOG_EVENTS"),
                "false"
            )));
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    @Override
    public String toString() {
        return "A2aConfig{" +
            ", logEvents=" + logEvents +
            '}';
    }

    public static class Builder {
        private boolean logEvents;

        public Builder logEvents(boolean logEvents) {
            this.logEvents = logEvents;
            return this;
        }

        public A2aConfig build() {
            return new A2aConfig(this);
        }
    }
}
