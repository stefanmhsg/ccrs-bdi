package ccrs.core.contingency.selection;

import java.util.List;
import java.util.Objects;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.ContingencyConfiguration;
import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.rdf.CcrsContext;

/**
 * Immutable input available to a strategy selection policy.
 *
 * <p>The request keeps selection policies independent from
 * {@code ContingencyCcrs}. A trace-based policy can use {@link #recentTraces()},
 * while other policies can use the current {@link #situation()},
 * {@link #context()}, and candidate strategy metadata.</p>
 */
public record StrategySelectionRequest(
    Situation situation,
    CcrsContext context,
    List<CcrsStrategy> defaultOrder,
    ContingencyConfiguration config,
    List<CcrsTrace> recentTraces
) {

    public StrategySelectionRequest {
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(defaultOrder, "defaultOrder");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(recentTraces, "recentTraces");

        defaultOrder = List.copyOf(defaultOrder);
        recentTraces = List.copyOf(recentTraces);
    }
}
