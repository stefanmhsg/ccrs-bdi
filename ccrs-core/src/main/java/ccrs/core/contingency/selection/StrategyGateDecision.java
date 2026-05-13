package ccrs.core.contingency.selection;

import java.util.Locale;

/**
 * Pre-evaluation decision for one strategy candidate.
 *
 * <p>The decision is intentionally generic so different selection policies can
 * explain their gating behavior without exposing their internal model types to
 * {@code ContingencyCcrs}.</p>
 */
public final class StrategyGateDecision {
    private final String strategyId;
    private final boolean shouldEvaluate;
    private final double currentBestConfidence;
    private final String reason;
    private final String diagnostics;

    private StrategyGateDecision(
            String strategyId,
            boolean shouldEvaluate,
            double currentBestConfidence,
            String reason,
            String diagnostics) {
        this.strategyId = strategyId;
        this.shouldEvaluate = shouldEvaluate;
        this.currentBestConfidence = currentBestConfidence;
        this.reason = reason;
        this.diagnostics = diagnostics;
    }

    public static StrategyGateDecision allow(
            String strategyId,
            double currentBestConfidence,
            String reason) {
        return allow(strategyId, currentBestConfidence, reason, null);
    }

    public static StrategyGateDecision allow(
            String strategyId,
            double currentBestConfidence,
            String reason,
            String diagnostics) {
        return new StrategyGateDecision(
            strategyId,
            true,
            currentBestConfidence,
            reason,
            diagnostics);
    }

    public static StrategyGateDecision skip(
            String strategyId,
            double currentBestConfidence,
            String reason) {
        return skip(strategyId, currentBestConfidence, reason, null);
    }

    public static StrategyGateDecision skip(
            String strategyId,
            double currentBestConfidence,
            String reason,
            String diagnostics) {
        return new StrategyGateDecision(
            strategyId,
            false,
            currentBestConfidence,
            reason,
            diagnostics);
    }

    public String strategyId() {
        return strategyId;
    }

    public boolean shouldEvaluate() {
        return shouldEvaluate;
    }

    public double currentBestConfidence() {
        return currentBestConfidence;
    }

    public String reason() {
        return reason;
    }

    public String diagnostics() {
        return diagnostics;
    }

    public String describe() {
        String base = String.format(Locale.ROOT,
            "%s: %s; currentBest=%.3f",
            strategyId,
            reason,
            currentBestConfidence);

        if (diagnostics == null || diagnostics.isBlank()) {
            return base;
        }

        return base + ", " + diagnostics;
    }
}
