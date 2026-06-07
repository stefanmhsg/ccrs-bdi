package ccrs.jacamo;

import java.util.Objects;
import java.util.function.Supplier;

import ccrs.core.contingency.ContingencyCcrs;
import ccrs.core.contingency.ContingencyConfiguration;
import ccrs.core.contingency.ContingencyCcrsFactory;
import ccrs.jacamo.jason.contingency.InteractionHistoryProvider;

/**
 * Runtime wiring points for the JaCaMo adapter.
 *
 * <p>This class keeps JaCaMo/Jason integration independent from concrete
 * HTTP artifact implementations and optional capability modules. Applications
 * or optional modules can install providers here before or during MAS startup.</p>
 */
public final class CcrsJacamoRuntime {

    private static volatile InteractionHistoryProvider interactionHistoryProvider =
        InteractionHistoryProvider.empty();

    private static volatile ContingencyConfiguration contingencyConfiguration =
        ContingencyConfiguration.defaults();

    private static volatile Supplier<ContingencyCcrs> contingencyCcrsSupplier =
        CcrsJacamoRuntime::createDefaultContingencyCcrs;

    private CcrsJacamoRuntime() {
    }

    public static InteractionHistoryProvider getInteractionHistoryProvider() {
        return interactionHistoryProvider;
    }

    public static void setInteractionHistoryProvider(InteractionHistoryProvider provider) {
        interactionHistoryProvider = provider != null
            ? provider
            : InteractionHistoryProvider.empty();
    }

    public static ContingencyConfiguration getContingencyConfiguration() {
        return contingencyConfiguration;
    }

    public static void setContingencyConfiguration(ContingencyConfiguration configuration) {
        contingencyConfiguration = configuration != null
            ? configuration
            : ContingencyConfiguration.defaults();
    }

    public static ContingencyCcrs createContingencyCcrs() {
        return contingencyCcrsSupplier.get();
    }

    public static void setContingencyCcrsSupplier(Supplier<ContingencyCcrs> supplier) {
        contingencyCcrsSupplier = Objects.requireNonNull(supplier, "supplier");
    }

    public static void reset() {
        interactionHistoryProvider = InteractionHistoryProvider.empty();
        contingencyConfiguration = ContingencyConfiguration.defaults();
        contingencyCcrsSupplier = CcrsJacamoRuntime::createDefaultContingencyCcrs;
    }

    private static ContingencyCcrs createDefaultContingencyCcrs() {
        return ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(
            contingencyConfiguration);
    }
}
