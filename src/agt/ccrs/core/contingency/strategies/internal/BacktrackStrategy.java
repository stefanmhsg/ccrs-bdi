package ccrs.core.contingency.strategies.internal;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.Interaction;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.opportunistic.OpportunisticResult;
import ccrs.core.rdf.CcrsContext;

/**
 * L2: Checkpoint-Based Backtrack Strategy
 * 
 * <p>Implements backtracking by identifying decision points (checkpoints) with
 * unexplored alternatives and computing optimal return paths when the current exploration
 * path becomes blocked.</p>
 * 
 * <h2>Checkpoint Detection (Interaction Graph)</h2>
 * <p>Builds a domain-independent graph from past hypermedia interactions:</p>
 * <ul>
 *   <li><b>Nodes:</b> requested resources</li>
 *   <li><b>Observed edges:</b> consecutive requests in chronological history</li>
 *   <li><b>Advertised edges:</b> URI objects found in the response description of a requested resource</li>
 * </ul>
 * 
 * <h2>Interaction-Based Exhaustion Detection</h2>
 * <p>Alternatives classified by interaction outcomes, not positions:</p>
 * <ul>
 *   <li><b>Exhausted:</b> Interaction failed OR the alternative is the current blocked resource</li>
 *   <li><b>Unexplored:</b> The advertised target was never requested in interaction history</li>
 * </ul>
 * 
 * <h2>Backtrack Distance Metric</h2>
 * <p>Distance = number of predecessor steps from the current resource back to the checkpoint
 * in the observed interaction path. This favors nearby checkpoints over old branch points
 * with many alternatives.</p>
 * 
 * <h2>Multi-Criteria Ranking</h2>
 * <p>Checkpoints ranked by priority:</p>
 * <ol>
 *   <li>Backtrack distance (ASC) - closer checkpoints are cheaper to revisit</li>
 *   <li>Unexplored alternatives count (DESC) - more options = better when distance is comparable</li>
 *   <li>Recency timestamp (DESC) - more recent = better</li>
 *   <li>Validation score (DESC) - higher quality = better</li>
 * </ol>
 * 
 * <h2>Validation with Exhaustion Penalty</h2>
 * <ul>
 *   <li>Score = 0.0 if all alternatives exhausted (dead-end checkpoint)</li>
 *   <li>Score += 0.4 for having unexplored alternatives</li>
 *   <li>Score += 0.3 for appearing in interaction history</li>
 *   <li>Score += 0.3 for having multiple outgoing links (true decision point)</li>
 * </ul>
 * 
 * <h2>Checkpoint-Specific Attempt Tracking</h2>
 * <p>Attempt key format: {@code "backtrack:<checkpointUri>"}</p>
 * <p>Different checkpoints maintain separate attempt counters to avoid premature 
 * suppression when exploring different branches.</p>
 * 
 * <h2>Backtrack Path Computation</h2>
 * <p>Extracts navigation path from interaction history: sequence of unique requestUri values
 * showing steps from current location to checkpoint. Path excludes current location (only
 * includes steps to take). Each URI appears at most once. Uses predecessor map derived from
 * chronological interaction transitions to build an implicit tree - no explicit graph search
 * required.</p>
 * 
 * <h2>Opportunistic CCRS Mental Notes (B2)</h2>
 * <p>Generates structured mental notes for integration with prioritization:</p>
 * <ul>
 *   <li><b>Unexplored options:</b> type="unexplored_option", utility=0.7</li>
 *   <li><b>Metadata:</b> source="contingency-ccrs", strategy="backtrack", checkpoint=uri</li>
 * </ul>
 * 
 * @see CheckpointCandidate
 * @see OpportunisticResult
 */
public class BacktrackStrategy implements CcrsStrategy {
    
    private static final Logger logger = Logger.getLogger(BacktrackStrategy.class.getName());

    public static final String ID = "backtrack";
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Backtrack";
    }
    
    @Override
    public Category getCategory() {
        return Category.INTERNAL;
    }
    
    @Override
    public int getEscalationLevel() {
        return 2;
    }
    
    /**
     * Check if strategy applies to the given situation and context.
     * Is applicable for STUCK or FAILURE situations with available checkpoints.
     * @param situation The current situation
     * @param context The current context
     * @return Applicability status
     */
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        if (situation.getType() != Situation.Type.STUCK && 
            situation.getType() != Situation.Type.FAILURE) {
                logger.fine("[Backtrack] Situation type not applicable: " + situation.getType());
                return Applicability.NOT_APPLICABLE;
        }
        
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse(null);
        }
        if (currentResource == null) {
            logger.fine("[Backtrack] Cannot determine current resource");
            return Applicability.NOT_APPLICABLE;
        }
        
        // Quick check: do we have any potential checkpoints?
        if (context.hasHistory()) {
            logger.fine("[Backtrack] is applicable because hasHistory() is true");
            return Applicability.APPLICABLE;
        }
        
        logger.info("[Backtrack] No evidence for backtrack applicability");
        return Applicability.NOT_APPLICABLE;
    }
    
    /**
     * Checkpoint candidate with validation metadata.
     * 
     */
    private record CheckpointCandidate(
        String uri,
        Source source,
        List<String> outgoingLinks,
        List<String> unexploredAlternatives,
        List<String> exhaustedAlternatives,
        double validationScore,
        long recencyTimestamp,
        int backtrackDistance
    ) {
        enum Source { HISTORY }
    }

    /**
     * Domain-independent navigation graph inferred from interaction history.
     *
     * <p>Resources are linked by two forms of evidence: links advertised in a
     * successful response, and transitions implied by consecutive requests.</p>
     */
    private record InteractionGraph(
        List<Interaction> recentFirstHistory,
        Set<String> requestedResources,
        Set<String> failedResources,
        Set<String> successfulResources,
        Map<String, List<String>> outgoingLinksByResource,
        Map<String, String> predecessors,
        Map<String, Long> recencyByResource,
        int observedTransitionCount,
        int advertisedLinkCount
    ) {
        List<String> outgoingLinks(String resource) {
            return outgoingLinksByResource.getOrDefault(resource, List.of());
        }

        long recencyTimestamp(String resource) {
            return recencyByResource.getOrDefault(resource, 0L);
        }

        int nodeCount() {
            return requestedResources.size();
        }

        int edgeCount() {
            return outgoingLinksByResource.values().stream()
                .mapToInt(List::size)
                .sum();
        }

    }

    private record ConfidenceBreakdown(
        double base,
        double optionBonus,
        double distanceDiscount,
        double raw,
        double value
    ) {}
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        logger.info("[Backtrack] Evaluating backtrack strategy for situation: " + situation.getType());
        
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse(null);
        }
        
        if (currentResource == null) {
            logger.warning("Cannot determine current resource location");
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.INSUFFICIENT_CONTEXT,
                "Cannot determine current resource location");
        }
        
        final String current = currentResource;  // Make effectively final for lambdas
        logger.fine("[Backtrack] Current resource: " + current);
        
        // STEP 1: Build a domain-independent graph from interaction history
        InteractionGraph graph = buildInteractionGraph(context);
        logger.info(String.format(
            "[Backtrack] Built interaction graph: %d nodes, %d unique edges (%d observed transitions, %d advertised links)",
            graph.nodeCount(), graph.edgeCount(), graph.observedTransitionCount(), graph.advertisedLinkCount()));
        
        // STEP 2: Collect checkpoint candidates from graph nodes
        Map<String, CheckpointCandidate> mergedCandidates = collectInteractionGraphCheckpoints(current, graph);
        logger.info(String.format("[Backtrack] Collected %d interaction-graph checkpoint candidates",
            mergedCandidates.size()));
        
        // STEP 3: Classify alternatives for each checkpoint
        for (String uri : mergedCandidates.keySet()) {
            CheckpointCandidate classified = classifyAlternatives(mergedCandidates.get(uri), current, graph);
            mergedCandidates.put(uri, classified);
            logger.info(String.format("[Backtrack]   %s: %d unexplored, %d exhausted",
                uri, classified.unexploredAlternatives().size(), 
                classified.exhaustedAlternatives().size()));
        }
        
        // STEP 4: Validate and filter checkpoints
        List<CheckpointCandidate> validatedCheckpoints = mergedCandidates.values().stream()
            .map(this::validateCheckpoint)
            .filter(c -> c.validationScore() > 0)
            .filter(c -> !c.unexploredAlternatives().isEmpty())
            .toList();
        
        logger.fine(String.format("[Backtrack] %d checkpoints passed validation", validatedCheckpoints.size()));
        
        if (validatedCheckpoints.isEmpty()) {
            logger.warning("[Backtrack] No valid checkpoints with unexplored alternatives found");
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "No valid checkpoints with unexplored alternatives found");
        }
        
        // STEP 5: Calculate observed-path backtrack distances
        List<CheckpointCandidate> withDistances = validatedCheckpoints.stream()
            .map(c -> new CheckpointCandidate(
                c.uri(), c.source(), c.outgoingLinks(), c.unexploredAlternatives(),
                c.exhaustedAlternatives(), c.validationScore(), c.recencyTimestamp(),
                calculateBacktrackDistance(current, c.uri(), graph)))
            .filter(c -> c.backtrackDistance() < Integer.MAX_VALUE)
            .toList();
        
        logger.finer(String.format("[Backtrack] %d checkpoints reachable on the observed backtrack path",
            withDistances.size()));
        
        if (withDistances.isEmpty()) {
            logger.warning("[Backtrack] All checkpoints exceed maximum graph distance");
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "All checkpoints exceed maximum graph distance");
        }
        
        // STEP 6: Rank checkpoints
        List<CheckpointCandidate> rankedCheckpoints = rankCheckpoints(withDistances);
        CheckpointCandidate bestCheckpoint = rankedCheckpoints.get(0);
        
        logger.info(String.format("[Backtrack] Selected checkpoint: %s (source=%s, unexplored=%d, distance=%d, validation=%.2f)",
            bestCheckpoint.uri(), bestCheckpoint.source(),
            bestCheckpoint.unexploredAlternatives().size(), bestCheckpoint.backtrackDistance(),
            bestCheckpoint.validationScore()));
        
        // STEP 7: Compute backtrack path from current to checkpoint
        List<String> backtrackPath = computeBacktrackPath(current, bestCheckpoint.uri(), graph);
        logger.fine(String.format("[Backtrack] Path length: %d steps", backtrackPath.size()));
        
        // STEP 8: Build result with rich metadata
        // Use checkpoint-specific attempt key to avoid different checkpoints sharing counters
        String attemptKey = "backtrack:" + bestCheckpoint.uri();
        ConfidenceBreakdown confidence = calculateConfidence(bestCheckpoint);
        logger.info(String.format(
            "[Backtrack] Confidence formula: clamp(base %.2f + optionBonus %.3f - distanceDiscount %.3f = raw %.3f, min 0.10, max 0.90) -> %.3f " +
            "(unexplored=%d, distance=%d)",
            confidence.base(), confidence.optionBonus(), confidence.distanceDiscount(),
            confidence.raw(), confidence.value(),
            bestCheckpoint.unexploredAlternatives().size(), bestCheckpoint.backtrackDistance()));
        
        // Build parameter maps
        Map<String, List<String>> alternativesByCheckpoint = rankedCheckpoints.stream()
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                CheckpointCandidate::unexploredAlternatives));
        
        Map<String, List<String>> exhaustedByCheckpoint = rankedCheckpoints.stream()
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                CheckpointCandidate::exhaustedAlternatives));
        
        Map<String, Integer> backtrackDistances = rankedCheckpoints.stream()
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                CheckpointCandidate::backtrackDistance));
        
        // B2: Generate OpportunisticResult mental notes
        // TODO: Correct the opportunistic result, following the standard structure.
        List<OpportunisticResult> opportunisticGuidance = generateOpportunisticNotes(
            current, backtrackPath, bestCheckpoint, attemptKey, confidence.value());
        logger.info(String.format("[Backtrack] Generated %d opportunistic notes (%d path steps, %d unexplored options)",
            opportunisticGuidance.size(), backtrackPath.size(), bestCheckpoint.unexploredAlternatives().size()));
        
        return StrategyResult.suggest(attemptKey, "navigate")
            .target(bestCheckpoint.uri())
            .param("reason", "backtrack_to_checkpoint")
            .param("fromResource", current)
            .param("backtrackPath", backtrackPath)
            .param("alternativesByCheckpoint", alternativesByCheckpoint)
            .param("exhaustedByCheckpoint", exhaustedByCheckpoint)
            .param("backtrackDistances", backtrackDistances)
            .param("checkpointSource", bestCheckpoint.source().name())
            .param("unexploredCount", bestCheckpoint.unexploredAlternatives().size())
            .param("exhaustedCount", bestCheckpoint.exhaustedAlternatives().size())
            .param("backtrackDistance", bestCheckpoint.backtrackDistance())
            .param("temporalDistance", bestCheckpoint.backtrackDistance())
            .param("validationScore", bestCheckpoint.validationScore())
            .param("interactionGraphNodeCount", graph.nodeCount())
            .param("interactionGraphEdgeCount", graph.edgeCount())
            .confidence(confidence.value())
            .rationale(buildRationale(current, bestCheckpoint, rankedCheckpoints.size()))
            .opportunisticGuidance(opportunisticGuidance)
            .build();
    }
    
    private InteractionGraph buildInteractionGraph(CcrsContext context) {
        List<Interaction> recentFirst = context.getRecentInteractions(1000);
        List<Interaction> chronological = new ArrayList<>(recentFirst);
        Collections.reverse(chronological);

        Set<String> requested = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        Set<String> successful = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        Map<String, Long> recency = new HashMap<>();

        String previousSuccessfulRequest = null;
        int observedTransitionCount = 0;
        int advertisedLinkCount = 0;

        for (Interaction interaction : chronological) {
            String requestUri = interaction.requestUri();
            if (!isValidUri(requestUri)) {
                continue;
            }

            requested.add(requestUri);
            recency.merge(requestUri, interaction.requestTimestamp(), Math::max);

            if (previousSuccessfulRequest != null && !previousSuccessfulRequest.equals(requestUri)) {
                observedTransitionCount++;
                outgoing.computeIfAbsent(previousSuccessfulRequest, k -> new LinkedHashSet<>()).add(requestUri);
                predecessors.put(requestUri, previousSuccessfulRequest);
            }

            if (interaction.outcome() == Interaction.Outcome.CLIENT_FAILURE ||
                interaction.outcome() == Interaction.Outcome.SERVER_FAILURE) {
                failed.add(requestUri);
                continue;
            }

            if (interaction.outcome() != Interaction.Outcome.SUCCESS) {
                continue;
            }

            successful.add(requestUri);
            List<String> advertisedLinks = extractAdvertisedLinks(interaction);
            LinkedHashSet<String> outgoingLinks = outgoing.computeIfAbsent(requestUri, k -> new LinkedHashSet<>());
            for (String advertisedLink : advertisedLinks) {
                if (outgoingLinks.add(advertisedLink)) {
                    advertisedLinkCount++;
                }
            }

            previousSuccessfulRequest = requestUri;
        }

        Map<String, List<String>> outgoingLists = outgoing.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> List.copyOf(e.getValue()),
                (left, right) -> left,
                LinkedHashMap::new));

        return new InteractionGraph(
            recentFirst,
            Collections.unmodifiableSet(requested),
            Collections.unmodifiableSet(failed),
            Collections.unmodifiableSet(successful),
            Collections.unmodifiableMap(outgoingLists),
            Collections.unmodifiableMap(predecessors),
            Collections.unmodifiableMap(recency),
            observedTransitionCount,
            advertisedLinkCount
        );
    }

    private List<String> extractAdvertisedLinks(Interaction interaction) {
        if (interaction.perceivedState() == null) {
            return List.of();
        }

        String requestUri = interaction.requestUri();
        return interaction.perceivedState().stream()
            .filter(t -> requestUri.equals(t.subject))
            .map(t -> t.object)
            .filter(this::isValidUri)
            .filter(uri -> !uri.equals(requestUri))
            .distinct()
            .toList();
    }

    private Map<String, CheckpointCandidate> collectInteractionGraphCheckpoints(
            String currentResource,
            InteractionGraph graph) {
        return graph.successfulResources().stream()
            .filter(uri -> !uri.equals(currentResource))
            .map(uri -> new CheckpointCandidate(
                uri,
                CheckpointCandidate.Source.HISTORY,
                graph.outgoingLinks(uri),
                List.of(),
                List.of(),
                0.0,
                graph.recencyTimestamp(uri),
                Integer.MAX_VALUE))
            .filter(candidate -> !candidate.outgoingLinks().isEmpty())
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                candidate -> candidate,
                (left, right) -> left,
                LinkedHashMap::new));
    }

    /**
     * Classify advertised alternatives from graph state.
     *
     * <p>Unexplored means no prior request to that resource. Failed attempts and
     * the current blocked resource are exhausted for this checkpoint. Previously
     * requested successful resources are explored, but not marked as dead ends.</p>
     */
    private CheckpointCandidate classifyAlternatives(CheckpointCandidate candidate,
                                                      String currentResource,
                                                      InteractionGraph graph) {
        List<String> unexplored = new ArrayList<>();
        List<String> exhausted = new ArrayList<>();

        for (String alternative : candidate.outgoingLinks()) {
            if (alternative.equals(currentResource) || graph.failedResources().contains(alternative)) {
                exhausted.add(alternative);
            } else if (!graph.requestedResources().contains(alternative)) {
                unexplored.add(alternative);
            }
        }

        return new CheckpointCandidate(
            candidate.uri(), candidate.source(), candidate.outgoingLinks(),
            unexplored, exhausted, candidate.validationScore(),
            candidate.recencyTimestamp(), candidate.backtrackDistance());
    }
    
    /**
     * Validate checkpoint and calculate validation score.
     * Score based on: has unexplored alternatives, has outgoing links, appears in history.
     */
    private CheckpointCandidate validateCheckpoint(CheckpointCandidate candidate) {
        double score = 0.0;
        
        // Penalize if all alternatives exhausted (checkpoint is dead end)
        if (candidate.exhaustedAlternatives().size() == candidate.outgoingLinks().size()) {
            logger.info(String.format("[Backtrack] Checkpoint %s is dead end (all alternatives exhausted)", 
                candidate.uri()));
            return new CheckpointCandidate(candidate.uri(), candidate.source(), 
                candidate.outgoingLinks(), candidate.unexploredAlternatives(),
                candidate.exhaustedAlternatives(), 0.0, candidate.recencyTimestamp(), 
                candidate.backtrackDistance());
        }
        
        // Required: has outgoing links
        if (candidate.outgoingLinks().isEmpty()) {
            logger.info(String.format("[Backtrack] Checkpoint %s has no outgoing links", candidate.uri()));
            return new CheckpointCandidate(candidate.uri(), candidate.source(), 
                candidate.outgoingLinks(), candidate.unexploredAlternatives(),
                candidate.exhaustedAlternatives(), 0.0, candidate.recencyTimestamp(), 
                candidate.backtrackDistance());
        }
        
        // Has unexplored alternatives (+0.4)
        if (!candidate.unexploredAlternatives().isEmpty()) {
            score += 0.4;
        }
        
        // Appears in interaction history (+0.3)
        if (candidate.recencyTimestamp() > 0 || 
            candidate.source() == CheckpointCandidate.Source.HISTORY) {
            score += 0.3;
        }
        
        // Has multiple outgoing links (+0.3)
        if (candidate.outgoingLinks().size() > 1) {
            score += 0.3;
        }
        
        return new CheckpointCandidate(candidate.uri(), candidate.source(),
            candidate.outgoingLinks(), candidate.unexploredAlternatives(),
            candidate.exhaustedAlternatives(), score, candidate.recencyTimestamp(),
            candidate.backtrackDistance());
    }
    
    /**
     * Calculate backtrack distance: number of predecessor steps from current resource
     * back to checkpoint in the observed interaction path.
     */
    private int calculateBacktrackDistance(String currentResource, String checkpointUri,
                                        InteractionGraph graph) {
        if (checkpointUri.equals(currentResource)) {
            return 0;
        }

        int distance = 0;
        Set<String> seen = new HashSet<>();
        seen.add(currentResource);
        String node = graph.predecessors().get(currentResource);

        while (node != null && seen.add(node)) {
            distance++;
            if (node.equals(checkpointUri)) {
                return distance;
            }
            node = graph.predecessors().get(node);
        }

        return Integer.MAX_VALUE;
    }
    
    /**
     * Compute backtrack path: sequence of unique resources from current location to checkpoint.
     * Returns list of navigation steps (excludes current location).
     * Each URI appears at most once. Path length is finite and ends at checkpoint.
     * 
     * Uses predecessor map derived from successful interactions to build implicit tree.
     */
    private List<String> computeBacktrackPath(String current, String checkpoint, InteractionGraph graph) {
        if (current.equals(checkpoint)) {
            return List.of(); // Already at checkpoint, no steps needed
        }

        // Trace path from current back to checkpoint
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        // Start with current but DON'T add it to path (it's where we are, not where we're going)
        seen.add(current);
        String node = graph.predecessors().get(current);
        
        while (node != null && !seen.contains(node)) {
            path.add(node);
            seen.add(node);
            
            if (node.equals(checkpoint)) {
                return path; // Found valid path
            }
            
            node = graph.predecessors().get(node);
        }
        
        // No path found - fallback to direct jump
        logger.warning(String.format("[Backtrack] No predecessor path from %s to %s, using direct jump", 
            current, checkpoint));
        return List.of(checkpoint);
    }
    
    /**
     * Rank checkpoints by: distance ASC, unexplored alternatives DESC, recency DESC, score DESC.
     */
    private List<CheckpointCandidate> rankCheckpoints(List<CheckpointCandidate> candidates) {
        return candidates.stream()
            .sorted(Comparator
                .comparingInt(CheckpointCandidate::backtrackDistance)
                .thenComparing(Comparator
                    .comparingInt((CheckpointCandidate c) -> c.unexploredAlternatives().size())
                    .reversed())
                .thenComparing(Comparator.comparingLong(CheckpointCandidate::recencyTimestamp).reversed())
                .thenComparing(Comparator.comparingDouble(CheckpointCandidate::validationScore).reversed()))
            .toList();
    }
    
    /**
     * Calculate confidence from checkpoint utility.
     *
     * <p>Starts from a neutral 0.5. Nearby checkpoints lose little confidence;
     * distant checkpoints are discounted logarithmically. Multiple unexplored
     * options increase confidence with diminishing returns.</p>
     */
    private ConfidenceBreakdown calculateConfidence(CheckpointCandidate checkpoint) {
        double base = 0.5;
        double optionBonus = Math.min(0.25,
            Math.log1p(checkpoint.unexploredAlternatives().size()) * 0.10);
        double distanceDiscount = Math.min(0.35,
            Math.log1p(checkpoint.backtrackDistance()) * 0.06);
        double raw = base + optionBonus - distanceDiscount;
        double value = clamp(raw, 0.1, 0.9);

        return new ConfidenceBreakdown(base, optionBonus, distanceDiscount, raw, value);
    }
    
    /**
     * Build human-readable rationale with structured context.
     */
    private String buildRationale(String current, CheckpointCandidate checkpoint,
                                   int totalCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Blocked at ").append(current).append(". ");
        sb.append("Backtracking to checkpoint ").append(checkpoint.uri());
        sb.append(" with ").append(checkpoint.unexploredAlternatives().size())
          .append(" unexplored alternative(s)");
        
        if (checkpoint.backtrackDistance() < Integer.MAX_VALUE) {
            sb.append(" (distance: ").append(checkpoint.backtrackDistance()).append(" steps)");
        }
        
        if (totalCandidates > 1) {
            sb.append(" [selected from ").append(totalCandidates).append(" candidates]");
        }
        
        return sb.toString();
    }
    
    /**
     * Generate opportunistic CCRS mental notes for unexplored options.
     * 
     */
    private List<OpportunisticResult> generateOpportunisticNotes(
            String currentResource,
            List<String> backtrackPath,
            CheckpointCandidate checkpoint,
            String attemptKey,
            double utility) {
        
        List<OpportunisticResult> results = new ArrayList<>();
        
        // Metadata for all notes
        String checkpointUri = checkpoint.uri();

        String from = currentResource;
        for (int i = 0; i < backtrackPath.size(); i++) {
            String to = backtrackPath.get(i);
            OpportunisticResult note = new OpportunisticResult(
                "backtrack_step",
                to,
                attemptKey,
                utility
            )
            .withMetadata("origin", "contingency-ccrs")
            .withMetadata("strategy", "backtrack")
            .withMetadata("checkpoint", checkpointUri)
            .withMetadata("from", from)
            .withMetadata("to", to)
            .withMetadata("step", String.valueOf(i + 1))
            .withMetadata("totalSteps", String.valueOf(backtrackPath.size()));
            results.add(note);
            from = to;
        }
        
        // 1. Generate notes for unexplored options at checkpoint
        for (String unexploredOption : checkpoint.unexploredAlternatives()) {
            OpportunisticResult note = new OpportunisticResult(
                "unexplored_option",
                unexploredOption,
                attemptKey,
                utility
            )
            .withMetadata("origin", "contingency-ccrs")
            .withMetadata("strategy", "backtrack")
            .withMetadata("checkpoint", checkpointUri)
            .withMetadata("from", checkpointUri)
            .withMetadata("to", unexploredOption);
            results.add(note);
        }
        
        return results;
    }
    
    // ========== Utility Methods ==========
    
    /**
     * Validates if a URI is suitable for checkpoint consideration.
     * Excludes fragments and ensures proper URI format.
     * 
     * @param uri The URI to validate
     * @return true if URI is valid (starts with http and has no fragments)
     */
    private boolean isValidUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        
        // Must be HTTP(S) URI
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            return false;
        }
        
        // Exclude URIs with fragments (contain #)
        if (uri.contains("#") || uri.contains("agent")) {
            return false;
        }
        
        return true;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
