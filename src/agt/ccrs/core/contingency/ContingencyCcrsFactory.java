package ccrs.core.contingency;

import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory methods for assembling a {@link ContingencyCcrs} instance.
 *
 * <p>The core default remains explicit and small. Optional modules can
 * contribute strategies with {@link CcrsStrategyProvider} and Java's
 * {@link ServiceLoader} without making adapters depend on concrete LLM,
 * consultation, or other capability implementations.</p>
 */
public final class ContingencyCcrsFactory {

    private static final Logger logger = Logger.getLogger(ContingencyCcrsFactory.class.getName());

    private ContingencyCcrsFactory() {
    }

    /**
     * Create a CCRS instance with built-in core strategies only.
     */
    public static ContingencyCcrs withCoreDefaults() {
        return ContingencyCcrs.withDefaults();
    }

    /**
     * Create a CCRS instance with core strategies plus all ServiceLoader
     * strategy providers visible to the current thread context class loader.
     */
    public static ContingencyCcrs withDefaultsAndDiscoveredProviders() {
        return withDefaultsAndDiscoveredProviders(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Create a CCRS instance with core strategies plus all ServiceLoader
     * strategy providers visible to the given class loader.
     */
    public static ContingencyCcrs withDefaultsAndDiscoveredProviders(ClassLoader classLoader) {
        ContingencyCcrs ccrs = ContingencyCcrs.withDefaults();
        registerDiscoveredProviders(ccrs.getRegistry(), classLoader);
        return ccrs;
    }

    /**
     * Register ServiceLoader-provided strategies into an existing registry.
     */
    public static void registerDiscoveredProviders(StrategyRegistry registry, ClassLoader classLoader) {
        ServiceLoader<CcrsStrategyProvider> providers = classLoader != null
            ? ServiceLoader.load(CcrsStrategyProvider.class, classLoader)
            : ServiceLoader.load(CcrsStrategyProvider.class);

        for (CcrsStrategyProvider provider : providers) {
            try {
                logger.info("[ContingencyCcrsFactory] Loading strategy provider: " + provider.getName());
                provider.registerStrategies(registry);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                    "[ContingencyCcrsFactory] Strategy provider failed: " + provider.getName(), e);
            }
        }
    }
}
