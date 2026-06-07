package ccrs.core.contingency;

import java.util.Objects;
import java.util.Optional;

/**
 * Context passed to ServiceLoader strategy providers during registration.
 */
public final class CcrsStrategyProviderContext {

    private final ContingencyConfiguration configuration;
    private final ClassLoader classLoader;

    public CcrsStrategyProviderContext(
            ContingencyConfiguration configuration,
            ClassLoader classLoader) {
        this.configuration = configuration != null
            ? configuration
            : ContingencyConfiguration.defaults();
        this.classLoader = classLoader;
    }

    public static CcrsStrategyProviderContext of(
            ContingencyConfiguration configuration,
            ClassLoader classLoader) {
        return new CcrsStrategyProviderContext(configuration, classLoader);
    }

    public ContingencyConfiguration configuration() {
        return configuration;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    public <T> Optional<T> getStrategyOptions(String strategyId, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return configuration.getStrategyOptions(strategyId, type);
    }
}
