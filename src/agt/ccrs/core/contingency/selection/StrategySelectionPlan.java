package ccrs.core.contingency.selection;

import java.util.List;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.StrategyResult;

/**
 * Strategy ordering and pre-evaluation gating for one CCRS invocation.
 *
 * <p>A plan is created once per evaluation and then consulted by
 * {@code ContingencyCcrs} while strategies are evaluated. It deliberately does
 * not rank final suggestions. Once strategies have produced suggestions, the
 * orchestrator ranks those suggestions by their returned confidence only.</p>
 */
public interface StrategySelectionPlan {

    /**
     * Return the order in which strategies should be considered for evaluation.
     */
    List<CcrsStrategy> orderForEvaluation(List<CcrsStrategy> defaultOrder);

    /**
     * Decide whether a candidate should be evaluated now.
     */
    StrategyGateDecision evaluateGate(
        CcrsStrategy candidate,
        List<StrategyResult> currentSuggestions);

    /**
     * Convenience wrapper for callers that only need the boolean decision.
     */
    default boolean shouldEvaluate(
            CcrsStrategy candidate,
            List<StrategyResult> currentSuggestions) {
        return evaluateGate(candidate, currentSuggestions).shouldEvaluate();
    }

    /**
     * Describe the selected order for logging/debugging.
     */
    String describeOrder(List<CcrsStrategy> orderedStrategies);

    /**
     * Describe how this plan was built for logging/debugging.
     */
    default String describeBuild() {
        return this.getClass().getSimpleName();
    }
}
