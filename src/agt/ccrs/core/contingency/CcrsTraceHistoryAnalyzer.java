package ccrs.core.contingency;

import java.util.List;
import java.util.function.BiPredicate;

import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Situation;

/**
 * Query helpers for recent CCRS trace history.
 *
 * This keeps cross-trace interpretation out of the trace DTO and out of the
 * concrete history store. Callers can pass any bounded list from CcrsContext.
 */
public final class CcrsTraceHistoryAnalyzer {

    private CcrsTraceHistoryAnalyzer() {
    }

    /**
     * Summarize how often a strategy was seen in recent traces, both evaluated
     * and as a suggestion in historical context.
     */
    public static StrategyUsageSummary summarizeStrategyUsage(
        List<CcrsTrace> traces,
        Situation currentSituation,
        BiPredicate<Situation, Situation> situationMatch,
        String strategyId
    ) {
        int evaluated = 0;
        int suggestions = 0;
        int topConfidenceSuggestions = 0;
        int considered = 0;

        if (traces == null || traces.isEmpty()) {
            return new StrategyUsageSummary(strategyId, 0, 0, 0, 0);
        }

        for (CcrsTrace trace : traces) {
            if (trace == null || trace.getSituation() == null) {
                continue;
            }

            if (situationMatch != null && !situationMatch.test(trace.getSituation(), currentSituation)) {
                continue;
            }

            considered++;
            if (trace.wasStrategyEvaluated(strategyId)) {
                evaluated++;
            }
            if (trace.didStrategySuggest(strategyId)) {
                suggestions++;
            }
            if (trace.wasStrategyTopConfidenceSuggestion(strategyId)) {
                topConfidenceSuggestions++;
            }
        }

        return new StrategyUsageSummary(strategyId, considered, evaluated, suggestions, topConfidenceSuggestions);
    }

    /**
     * Count traces in a history where any non-excluded strategy was evaluated.
     * Useful for "fallback after trying something else first" checks.
     */
    public static int countTracesWithEvaluatedStrategy(
        List<CcrsTrace> traces,
        Situation currentSituation,
        BiPredicate<Situation, Situation> situationMatch,
        String excludedStrategyId
    ) {
        if (traces == null || traces.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (CcrsTrace trace : traces) {
            if (trace == null || trace.getSituation() == null) {
                continue;
            }

            if (situationMatch != null && !situationMatch.test(trace.getSituation(), currentSituation)) {
                continue;
            }

            if (trace.hasAnyEvaluatedStrategyExcluding(excludedStrategyId)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Summary of how often a strategy appeared in a trace history window.
     */
    public static record StrategyUsageSummary(
        String strategyId,
        int consideredTraces,
        int evaluatedCount,
        int suggestionCount,
        int topConfidenceSuggestionCount
    ) {}
}
