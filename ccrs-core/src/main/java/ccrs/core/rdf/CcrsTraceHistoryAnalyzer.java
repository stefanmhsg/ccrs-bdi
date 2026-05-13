package ccrs.core.rdf;

import java.util.List;
import java.util.function.Predicate;

import ccrs.core.contingency.dto.CcrsTrace;

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
        Predicate<CcrsTrace> traceFilter,
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

            if (traceFilter != null && !traceFilter.test(trace)) {
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
        Predicate<CcrsTrace> traceFilter,
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

            if (traceFilter != null && !traceFilter.test(trace)) {
                continue;
            }

            if (trace.hasAnyEvaluatedStrategyExcluding(excludedStrategyId)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Format recent traces for prompt or diagnostic text.
     */
    public static String formatTraceHistory(List<CcrsTrace> traces, int maxTraces) {
        if (traces == null || traces.isEmpty()) {
            return "(no previous CCRS invocations)";
        }

        int limit = Math.max(0, Math.min(maxTraces, traces.size()));
        if (limit == 0) {
            return "(CCRS trace output disabled by limit)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(most recent first; up to ").append(maxTraces).append(" traces)\n");

        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            appendTrace(sb, traces.get(i), i + 1);
        }

        if (traces.size() > limit) {
            sb.append('\n')
                .append("... (")
                .append(traces.size() - limit)
                .append(" more traces not shown)");
        }

        return sb.toString().trim();
    }

    private static void appendTrace(StringBuilder sb, CcrsTrace trace, int index) {
        if (trace == null) {
            sb.append('[').append(index).append("] (missing CCRS trace)\n");
            return;
        }

        sb.append('[').append(index).append("] ");
        appendIndentedBlock(sb, trace.toDetailedReport());
    }

    private static void appendIndentedBlock(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        sb.append(text.replace("\n", "\n  "));
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
