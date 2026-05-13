package ccrs.jacamo.jason.contingency;

import java.util.List;
import java.util.Optional;

import ccrs.core.contingency.dto.Interaction;

/**
 * Agent-scoped interaction history for the JaCaMo adapter.
 *
 * <p>Hypermedia artifacts, HTTP bindings, tests, or other environment
 * integrations can provide interaction history without making
 * {@link JasonCcrsContext} depend on a concrete artifact implementation.</p>
 */
public interface InteractionHistoryProvider {

    List<Interaction> getRecentInteractions(String agentName, int maxCount);

    Optional<Interaction> getLastInteraction(String agentName);

    List<Interaction> getInteractionsFor(String agentName, String logicalSource);

    default String formatAgentHistory(String agentName) {
        return "[InteractionHistoryProvider] {agent='" + agentName + "', history=unavailable}";
    }

    static InteractionHistoryProvider empty() {
        return EmptyInteractionHistoryProvider.INSTANCE;
    }
}

final class EmptyInteractionHistoryProvider implements InteractionHistoryProvider {

    static final EmptyInteractionHistoryProvider INSTANCE = new EmptyInteractionHistoryProvider();

    private EmptyInteractionHistoryProvider() {
    }

    @Override
    public List<Interaction> getRecentInteractions(String agentName, int maxCount) {
        return List.of();
    }

    @Override
    public Optional<Interaction> getLastInteraction(String agentName) {
        return Optional.empty();
    }

    @Override
    public List<Interaction> getInteractionsFor(String agentName, String logicalSource) {
        return List.of();
    }

    @Override
    public String formatAgentHistory(String agentName) {
        return "[InteractionHistoryProvider] {agent='" + agentName + "', history=empty}";
    }
}
