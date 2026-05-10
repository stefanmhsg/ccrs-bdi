package ccrs.core.contingency.selection;

/**
 * Creates a per-evaluation strategy selection plan.
 *
 * <p>The policy is the architectural boundary between the core CCRS orchestrator
 * and a concrete strategy-selection approach. Implementations may use recent
 * traces, fixed heuristics, or any other agent-agnostic signal, while
 * {@code ContingencyCcrs} only depends on this interface.</p>
 */
public interface StrategySelectionPolicy {

    /**
     * Create a strategy selection plan for one CCRS evaluation.
     *
     * @param request Immutable input data available to the selector
     * @return Selection plan used by the orchestrator for ordering and gating
     * @throws Exception if a policy backed by external infrastructure cannot
     *         build a plan
     */
    StrategySelectionPlan createPlan(StrategySelectionRequest request) throws Exception;

    /**
     * Get a description of this policy for logging/debugging.
     */
    default String getDescription() {
        return this.getClass().getSimpleName();
    }
}
