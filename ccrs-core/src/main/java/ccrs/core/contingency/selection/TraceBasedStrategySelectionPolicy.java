package ccrs.core.contingency.selection;

/**
 * Creates trace-based strategy selection plans from recent CCRS history.
 */
public final class TraceBasedStrategySelectionPolicy implements StrategySelectionPolicy {

    @Override
    public StrategySelectionPlan createPlan(StrategySelectionRequest request) {
        return TraceBasedStrategySelectionModel.fromHistory(
            request.recentTraces(),
            request.config());
    }
}
