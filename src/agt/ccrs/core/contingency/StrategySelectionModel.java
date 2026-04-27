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
 * <p>Recent traces are weighted more strongly than older traces. A strategy's
 * learned value is:</p>
 *
 * <pre>
 * suggestionRate * learnedConfidence / (1 + avgEvaluationTime / levelReferenceTime)
 * </pre>
 *
 * <p>The level reference time is not a measured strategy cost. It is an
 * escalation-level prior that says what runtime is expected for that class of
 * strategy before enough measurements have accumulated.</p>
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
            long levelReferenceTimeMs) {
        if (currentSuggestions.isEmpty()) {
            return true;
        }

        Profile profile = profiles.get(candidate.getId());
        if (profile == null || !profile.hasEnoughSamples(minimumSamples)) {
            return true;
        }

        double bestKnownConfidence = currentSuggestions.stream()
            .filter(StrategyResult::isSuggestion)
            .map(StrategyResult::asSuggestion)
            .mapToDouble(StrategyResult.Suggestion::getConfidence)
            .max()
            .orElse(0.0);

        return profile.preEvaluationValue(levelReferenceTimeMs) > bestKnownConfidence;
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
                        "%s(default, L%d reference=%dms)",
                        strategy.getId(),
                        strategy.getEscalationLevel(),
                        config.getCostReferenceTimeMs(strategy.getEscalationLevel()));
                }
                return String.format(Locale.ROOT,
                    "%s(value=%.3f, expectedConfidence=%.2f, suggestionRate=%.2f, avgConfidence=%.2f, avgTime=%.0fms, reference=%dms, n=%d)",
                    strategy.getId(),
                    profile.preEvaluationValue(config.getCostReferenceTimeMs(strategy.getEscalationLevel())),
                    profile.expectedConfidence(),
                    profile.suggestionRate(),
                    profile.averageSuggestionConfidence(),
                    profile.averageEvaluationTimeMs(),
                    config.getCostReferenceTimeMs(strategy.getEscalationLevel()),
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
            long referenceTimeMs = config.getCostReferenceTimeMs(left.getEscalationLevel());
            int byValue = Double.compare(
                rightProfile.preEvaluationValue(referenceTimeMs),
                leftProfile.preEvaluationValue(referenceTimeMs));
            if (byValue != 0) {
                return byValue;
            }
        }

        return Integer.compare(
            defaultIndex.getOrDefault(left.getId(), Integer.MAX_VALUE),
            defaultIndex.getOrDefault(right.getId(), Integer.MAX_VALUE));
    }

    private static int normalizedLevel(int level) {
        return level == 0 ? 100 : level;
    }

    private static Double outcomeScore(CcrsTrace.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> 1.0;
            case PARTIAL -> 0.5;
            case FAILED -> 0.0;
            case PENDING, UNKNOWN -> null;
        };
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

        double preEvaluationValue(double costReferenceTimeMs) {
            double effortMultiplier = 1.0 + (averageEvaluationTimeMs() / costReferenceTimeMs);
            return expectedConfidence() / effortMultiplier;
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
