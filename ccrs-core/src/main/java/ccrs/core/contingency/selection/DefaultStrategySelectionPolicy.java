package ccrs.core.contingency.selection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.StrategyResult;

/**
 * Deterministic baseline strategy selection policy.
 *
 * <p>This policy preserves the registry/default escalation order exactly:
 * L1 -> L2 -> L3 -> L4 -> L0. It does not learn from history, inspect the
 * current context, or prune candidates once a suggestion exists.</p>
 *
 * <p>Use it for tests, debugging, comparison baselines, and modes where
 * predictable evaluation order is more important than adaptive scheduling.</p>
 */
public final class DefaultStrategySelectionPolicy implements StrategySelectionPolicy {

    @Override
    public StrategySelectionPlan createPlan(StrategySelectionRequest request) {
        return new DefaultStrategySelectionPlan();
    }

    private static final class DefaultStrategySelectionPlan implements StrategySelectionPlan {

        @Override
        public List<CcrsStrategy> orderForEvaluation(List<CcrsStrategy> defaultOrder) {
            return new ArrayList<>(defaultOrder);
        }

        @Override
        public StrategyGateDecision evaluateGate(
                CcrsStrategy candidate,
                List<StrategyResult> currentSuggestions) {
            return StrategyGateDecision.allow(
                candidate.getId(),
                bestKnownConfidence(currentSuggestions),
                "default escalation policy evaluates every enabled candidate");
        }

        @Override
        public String describeOrder(List<CcrsStrategy> orderedStrategies) {
            return orderedStrategies.stream()
                .map(strategy -> String.format(Locale.ROOT,
                    "%s(default, L%d)",
                    strategy.getId(),
                    strategy.getEscalationLevel()))
                .collect(Collectors.joining(", "));
        }

        @Override
        public String describeBuild() {
            return "Using default escalation strategy selection policy";
        }

        private double bestKnownConfidence(List<StrategyResult> suggestions) {
            return suggestions.stream()
                .filter(StrategyResult::isSuggestion)
                .map(StrategyResult::asSuggestion)
                .mapToDouble(StrategyResult.Suggestion::getConfidence)
                .max()
                .orElse(0.0);
        }
    }
}
