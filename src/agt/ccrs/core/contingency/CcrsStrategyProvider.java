package ccrs.core.contingency;

/**
 * Extension point for optional CCRS strategy modules.
 *
 * <p>Core strategies can be registered directly through {@link StrategyRegistry}.
 * Adapter or capability modules can expose implementations of this interface
 * via {@link java.util.ServiceLoader} so platform glue does not need to import
 * provider-specific classes.</p>
 */
@FunctionalInterface
public interface CcrsStrategyProvider {

    /**
     * Register all strategies supplied by this provider.
     *
     * @param registry target registry
     */
    void registerStrategies(StrategyRegistry registry);

    /**
     * Human-readable provider name for logs.
     */
    default String getName() {
        return getClass().getName();
    }
}
