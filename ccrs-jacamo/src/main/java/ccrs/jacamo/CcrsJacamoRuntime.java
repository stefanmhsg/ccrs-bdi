package ccrs.jacamo;

import java.util.Objects;
import java.util.function.Supplier;

import ccrs.core.contingency.ContingencyCcrs;
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

    private static volatile Supplier<ContingencyCcrs> contingencyCcrsSupplier =
        ContingencyCcrsFactory::withDefaultsAndDiscoveredProviders;

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

    public static ContingencyCcrs createContingencyCcrs() {
        return contingencyCcrsSupplier.get();
    }

    public static void setContingencyCcrsSupplier(Supplier<ContingencyCcrs> supplier) {
        contingencyCcrsSupplier = Objects.requireNonNull(supplier, "supplier");
    }

    public static void reset() {
        interactionHistoryProvider = InteractionHistoryProvider.empty();
        contingencyCcrsSupplier = ContingencyCcrsFactory::withDefaultsAndDiscoveredProviders;
    }
}
