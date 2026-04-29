package ccrs.core.contingency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.StrategyResult;

/**
 * Learns lightweight strategy-selection priors from recent CCRS traces.
 *
 * <p>The model answers a pre-evaluation question: "Is it worth spending effort
 * to run this strategy now?" It deliberately does not rank final suggestions.
 * Once strategies have produced suggestions, {@link ContingencyCcrs} ranks
 * those suggestions by their returned confidence only.</p>
 *
 * <p>The model is intentionally agent-agnostic. It consumes only data already
 * present in {@link CcrsTrace}: strategy id, measured evaluation time, whether
 * a suggestion was produced, suggestion confidence, and optional outcome
 * feedback reported later by the caller.</p>
 *
 * <p>Recent traces are weighted more strongly than older traces. Strategy
 * quality and evaluation cost are learned separately. A strategy's learned
 * quality is:</p>
 *
 * <pre>
 * suggestionRate * learnedConfidence
 * </pre>
 *
 * <p>Evaluation cost is then used only for the pre-evaluation question of
 * whether running one more strategy is worth the extra time.</p>
 */
final class StrategySelectionModel {

    private static final double RECENCY_DECAY = 0.85;

    private final Map<String, Profile> profiles;
    private final int minimumSamples;
    private final int traceCount;

    private StrategySelectionModel(
            Map<String, Profile> profiles,
            int minimumSamples,
            int traceCount) {
        this.profiles = profiles;
        this.minimumSamples = minimumSamples;
        this.traceCount = traceCount;
    }

    static StrategySelectionModel fromHistory(
            List<CcrsTrace> recentTraces,
            int minimumSamples) {
        Map<String, MutableProfile> mutableProfiles = new LinkedHashMap<>();
        double weight = 1.0;

        for (CcrsTrace trace : recentTraces) {
            StrategyResult topResult = trace.getSelectedResults().stream()
                .filter(StrategyResult::isSuggestion)
                .findFirst()
                .orElse(null);
            Double outcomeScore = outcomeScore(trace.getOutcome());

            for (CcrsTrace.StrategyEvaluation evaluation : trace.getEvaluations()) {
                // Learn only from strategies that were actually evaluated in a
                // context where they could apply. NOT_APPLICABLE is context
                // filtering, not negative evidence about strategy quality.
                if (!isMeaningfulEvaluation(evaluation)) {
                    continue;
                }

                MutableProfile profile = mutableProfiles.computeIfAbsent(
                    evaluation.getStrategyId(),
                    MutableProfile::new);

                profile.addEvaluation(evaluation.getEvaluationTimeMs(), weight);

                StrategyResult result = evaluation.getResult();
                if (result != null && result.isSuggestion()) {
                    profile.addSuggestion(result.asSuggestion().getConfidence(), weight);
                }

                if (outcomeScore != null && result == topResult) {
                    profile.addOutcome(outcomeScore, weight);
                }
            }

            weight *= RECENCY_DECAY;
        }

        Map<String, Profile> profiles = new HashMap<>();
        for (MutableProfile profile : mutableProfiles.values()) {
            profiles.put(profile.strategyId, profile.toProfile());
        }

        return new StrategySelectionModel(
            profiles,
            Math.max(1, minimumSamples),
            recentTraces.size());
    }

    int traceCount() {
        return traceCount;
    }

    int profileCount() {
        return profiles.size();
    }

    int minimumSamples() {
        return minimumSamples;
    }

    List<CcrsStrategy> orderForEvaluation(
            List<CcrsStrategy> defaultOrder,
            ContingencyConfiguration config) {
        Map<String, Integer> defaultIndex = new HashMap<>();
        for (int i = 0; i < defaultOrder.size(); i++) {
            defaultIndex.put(defaultOrder.get(i).getId(), i);
        }

        List<CcrsStrategy> ordered = new ArrayList<>(defaultOrder);
        ordered.sort(Comparator
            .comparingInt((CcrsStrategy strategy) -> normalizedLevel(strategy.getEscalationLevel()))
            .thenComparing((left, right) -> compareWithinLevel(left, right, defaultIndex, config)));
        return ordered;
    }

    boolean shouldEvaluate(
            CcrsStrategy candidate,
            List<StrategyResult> currentSuggestions,
            ContingencyConfiguration config) {
        return evaluateGate(candidate, currentSuggestions, config).shouldEvaluate();
    }

    GateDecision evaluateGate(
            CcrsStrategy candidate,
            List<StrategyResult> currentSuggestions,
            ContingencyConfiguration config) {
        double bestKnownConfidence = bestKnownConfidence(currentSuggestions);
        Profile profile = profiles.get(candidate.getId());

        if (currentSuggestions.isEmpty()) {
            return GateDecision.allow(
                candidate.getId(),
                profile,
                bestKnownConfidence,
                "no current recovery suggestion exists");
        }

        if (profile == null) {
            return GateDecision.allow(
                candidate.getId(),
                null,
                bestKnownConfidence,
                "no learned profile yet");
        }

        if (!profile.hasEnoughSamples(minimumSamples)) {
            return GateDecision.allow(
                candidate.getId(),
                profile,
                bestKnownConfidence,
                String.format(Locale.ROOT,
                    "only %d/%d applicable samples are available",
                    profile.evaluationCount(),
                    minimumSamples));
        }

        // Keep quality and cost separate. An expensive high-confidence strategy
        // should not be treated as low-confidence; cost only decides whether
        // the expected improvement over the current best is worth more work.
        double expectedGain = profile.expectedConfidence() - bestKnownConfidence;
        if (expectedGain >= config.getMinimumExpectedConfidenceGain()) {
            return GateDecision.allow(
                candidate.getId(),
                profile,
                bestKnownConfidence,
                String.format(Locale.ROOT,
                    "expected gain %.3f meets min gain %.3f",
                    expectedGain,
                    config.getMinimumExpectedConfidenceGain()));
        }

        if (profile.expectedConfidence() >= config.getHighConfidenceEvaluationFloor()) {
            return GateDecision.allow(
                candidate.getId(),
                profile,
                bestKnownConfidence,
                String.format(Locale.ROOT,
                    "expected confidence %.3f meets high-confidence floor %.3f",
                    profile.expectedConfidence(),
                    config.getHighConfidenceEvaluationFloor()));
        }

        if (profile.averageEvaluationTimeMs() <= config.getCheapEvaluationTimeMs()) {
            return GateDecision.allow(
                candidate.getId(),
                profile,
                bestKnownConfidence,
                String.format(Locale.ROOT,
                    "avg time %.0fms is within cheap threshold %dms",
                    profile.averageEvaluationTimeMs(),
                    config.getCheapEvaluationTimeMs()));
        }

        return GateDecision.skip(
            candidate.getId(),
            profile,
            bestKnownConfidence,
            String.format(Locale.ROOT,
                "expected gain %.3f is below min gain %.3f, expected confidence %.3f is below high-confidence floor %.3f, and avg time %.0fms exceeds cheap threshold %dms",
                expectedGain,
                config.getMinimumExpectedConfidenceGain(),
                profile.expectedConfidence(),
                config.getHighConfidenceEvaluationFloor(),
                profile.averageEvaluationTimeMs(),
                config.getCheapEvaluationTimeMs()));
    }

    Profile profileFor(String strategyId) {
        return profiles.get(strategyId);
    }

    String describeOrder(List<CcrsStrategy> orderedStrategies, ContingencyConfiguration config) {
        return orderedStrategies.stream()
            .map(strategy -> {
                Profile profile = profiles.get(strategy.getId());
                if (profile == null || !profile.hasEnoughSamples(minimumSamples)) {
                    return String.format(Locale.ROOT,
                        "%s(default, L%d)",
                        strategy.getId(),
                        strategy.getEscalationLevel());
                }
                return String.format(Locale.ROOT,
                    "%s(expectedConfidence=%.2f, suggestionRate=%.2f, avgConfidence=%.2f, avgTime=%.0fms, n=%d)",
                    strategy.getId(),
                    profile.expectedConfidence(),
                    profile.suggestionRate(),
                    profile.averageSuggestionConfidence(),
                    profile.averageEvaluationTimeMs(),
                    profile.evaluationCount());
            })
            .collect(Collectors.joining(", "));
    }

    private int compareWithinLevel(
            CcrsStrategy left,
            CcrsStrategy right,
            Map<String, Integer> defaultIndex,
            ContingencyConfiguration config) {
        Profile leftProfile = profiles.get(left.getId());
        Profile rightProfile = profiles.get(right.getId());
        boolean leftLearned = leftProfile != null && leftProfile.hasEnoughSamples(minimumSamples);
        boolean rightLearned = rightProfile != null && rightProfile.hasEnoughSamples(minimumSamples);

        if (leftLearned && rightLearned) {
            // Order learned candidates by expected quality. Runtime is only a
            // tie-breaker so cheap strategies win when quality is equivalent.
            int byExpectedConfidence = Double.compare(
                rightProfile.expectedConfidence(),
                leftProfile.expectedConfidence());
            if (byExpectedConfidence != 0) {
                return byExpectedConfidence;
            }

            int byEvaluationTime = Double.compare(
                leftProfile.averageEvaluationTimeMs(),
                rightProfile.averageEvaluationTimeMs());
            if (byEvaluationTime != 0) {
                return byEvaluationTime;
            }
        }

        return Integer.compare(
            defaultIndex.getOrDefault(left.getId(), Integer.MAX_VALUE),
            defaultIndex.getOrDefault(right.getId(), Integer.MAX_VALUE));
    }

    private static int normalizedLevel(int level) {
        return level == 0 ? 100 : level;
    }

    private double bestKnownConfidence(List<StrategyResult> suggestions) {
        return suggestions.stream()
            .filter(StrategyResult::isSuggestion)
            .map(StrategyResult::asSuggestion)
            .mapToDouble(StrategyResult.Suggestion::getConfidence)
            .max()
            .orElse(0.0);
    }

    private static Double outcomeScore(CcrsTrace.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> 1.0;
            case PARTIAL -> 0.5;
            case FAILED -> 0.0;
            case PENDING, UNKNOWN -> null;
        };
    }

    private static boolean isMeaningfulEvaluation(CcrsTrace.StrategyEvaluation evaluation) {
        return evaluation.getApplicability() != CcrsStrategy.Applicability.NOT_APPLICABLE
            && evaluation.getResult() != null;
    }

    static final class Profile {
        private final String strategyId;
        private final int evaluationCount;
        private final double evaluationWeight;
        private final double suggestionWeight;
        private final double totalEvaluationTimeMs;
        private final double totalSuggestionConfidence;
        private final double outcomeWeight;
        private final double totalOutcomeScore;

        private Profile(
                String strategyId,
                int evaluationCount,
                double evaluationWeight,
                double suggestionWeight,
                double totalEvaluationTimeMs,
                double totalSuggestionConfidence,
                double outcomeWeight,
                double totalOutcomeScore) {
            this.strategyId = strategyId;
            this.evaluationCount = evaluationCount;
            this.evaluationWeight = evaluationWeight;
            this.suggestionWeight = suggestionWeight;
            this.totalEvaluationTimeMs = totalEvaluationTimeMs;
            this.totalSuggestionConfidence = totalSuggestionConfidence;
            this.outcomeWeight = outcomeWeight;
            this.totalOutcomeScore = totalOutcomeScore;
        }

        int evaluationCount() {
            return evaluationCount;
        }

        boolean hasEnoughSamples(int minimumSamples) {
            return evaluationCount >= minimumSamples;
        }

        double averageEvaluationTimeMs() {
            if (evaluationWeight == 0.0) {
                return 0.0;
            }
            return totalEvaluationTimeMs / evaluationWeight;
        }

        double suggestionRate() {
            if (evaluationWeight == 0.0) {
                return 0.0;
            }
            return suggestionWeight / evaluationWeight;
        }

        double averageSuggestionConfidence() {
            if (suggestionWeight == 0.0) {
                return 0.0;
            }
            return totalSuggestionConfidence / suggestionWeight;
        }

        double learnedConfidence() {
            double suggestionConfidence = averageSuggestionConfidence();
            if (outcomeWeight == 0.0) {
                return suggestionConfidence;
            }

            double outcomeReliability = totalOutcomeScore / outcomeWeight;
            return 0.7 * suggestionConfidence + 0.3 * outcomeReliability;
        }

        double expectedConfidence() {
            return suggestionRate() * learnedConfidence();
        }

    }

    static final class GateDecision {
        private final String strategyId;
        private final boolean shouldEvaluate;
        private final Profile profile;
        private final double currentBestConfidence;
        private final String reason;

        private GateDecision(
                String strategyId,
                boolean shouldEvaluate,
                Profile profile,
                double currentBestConfidence,
                String reason) {
            this.strategyId = strategyId;
            this.shouldEvaluate = shouldEvaluate;
            this.profile = profile;
            this.currentBestConfidence = currentBestConfidence;
            this.reason = reason;
        }

        static GateDecision allow(
                String strategyId,
                Profile profile,
                double currentBestConfidence,
                String reason) {
            return new GateDecision(strategyId, true, profile, currentBestConfidence, reason);
        }

        static GateDecision skip(
                String strategyId,
                Profile profile,
                double currentBestConfidence,
                String reason) {
            return new GateDecision(strategyId, false, profile, currentBestConfidence, reason);
        }

        boolean shouldEvaluate() {
            return shouldEvaluate;
        }

        Profile profile() {
            return profile;
        }

        double currentBestConfidence() {
            return currentBestConfidence;
        }

        String describe() {
            if (profile == null) {
                return String.format(Locale.ROOT,
                    "%s: %s; currentBest=%.3f",
                    strategyId,
                    reason,
                    currentBestConfidence);
            }

            return String.format(Locale.ROOT,
                "%s: %s; currentBest=%.3f, expectedConfidence=%.3f, suggestionRate=%.3f, avgConfidence=%.3f, avgTime=%.0fms, n=%d",
                strategyId,
                reason,
                currentBestConfidence,
                profile.expectedConfidence(),
                profile.suggestionRate(),
                profile.averageSuggestionConfidence(),
                profile.averageEvaluationTimeMs(),
                profile.evaluationCount());
        }
    }

    private static final class MutableProfile {
        private final String strategyId;
        private int evaluationCount;
        private double evaluationWeight;
        private double suggestionWeight;
        private double totalEvaluationTimeMs;
        private double totalSuggestionConfidence;
        private double outcomeWeight;
        private double totalOutcomeScore;

        private MutableProfile(String strategyId) {
            this.strategyId = strategyId;
        }

        private void addEvaluation(long evaluationTimeMs, double weight) {
            evaluationCount++;
            evaluationWeight += weight;
            totalEvaluationTimeMs += Math.max(0L, evaluationTimeMs) * weight;
        }

        private void addSuggestion(double confidence, double weight) {
            suggestionWeight += weight;
            totalSuggestionConfidence += confidence * weight;
        }

        private void addOutcome(double outcomeScore, double weight) {
            outcomeWeight += weight;
            totalOutcomeScore += outcomeScore * weight;
        }

        private Profile toProfile() {
            return new Profile(
                strategyId,
                evaluationCount,
                evaluationWeight,
                suggestionWeight,
                totalEvaluationTimeMs,
                totalSuggestionConfidence,
                outcomeWeight,
                totalOutcomeScore);
        }
    }
}
