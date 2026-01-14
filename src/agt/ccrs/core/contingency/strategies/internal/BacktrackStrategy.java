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
import ccrs.core.rdf.CcrsVocabulary;
import ccrs.core.rdf.RdfTriple;

/**
 * L2: Checkpoint-Based Backtrack Strategy
 * 
 * <p>Implements intelligent backtracking by identifying decision points (checkpoints) with
 * unexplored alternatives and computing optimal return paths when the current exploration
 * path becomes blocked.</p>
 * 
 * <h2>Checkpoint Detection (Dual-Source Evidence)</h2>
 * <ul>
 *   <li><b>RDF-based:</b> Resources with incoming links to current resource (graph topology)</li>
 *   <li><b>History-based:</b> Resources from SUCCESS interactions whose perceived state 
 *       contained links to current resource</li>
 *   <li><b>Evidence merging:</b> Same URI from both sources = stronger evidence (Source.BOTH)</li>
 * </ul>
 * 
 * <h2>Interaction-Based Exhaustion Detection</h2>
 * <p>Alternatives classified by interaction outcomes, not positions:</p>
 * <ul>
 *   <li><b>Exhausted:</b> Interaction failed OR (succeeded but agent later returned to 
 *       checkpoint or predecessor)</li>
 *   <li><b>Unexplored:</b> Never visited in interaction history</li>
 *   <li><b>Viable:</b> Visited successfully without return (still explorable)</li>
 * </ul>
 * 
 * <h2>Temporal Distance Metric</h2>
 * <p>Distance = number of interactions since last visit to checkpoint (not graph hops).
 * Favors recently visited checkpoints as they're closer in the temporal exploration sequence.</p>
 * 
 * <h2>Multi-Criteria Ranking</h2>
 * <p>Checkpoints ranked by priority:</p>
 * <ol>
 *   <li>Unexplored alternatives count (DESC) - more options = better</li>
 *   <li>Temporal distance (ASC) - closer = better</li>
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
 * <p>Extracts monotonic path from interaction history: sequence of requestUri values
 * from most recent back to checkpoint, then reversed to forward order. No graph search
 * required - path already exists in successful navigation history.</p>
 * 
 * <h2>Opportunistic CCRS Mental Notes (B2)</h2>
 * <p>Generates structured mental notes for integration with prioritization:</p>
 * <ul>
 *   <li><b>Unexplored options:</b> type="unexplored_option", utility=0.2</li>
 *   <li><b>Dead ends:</b> type="dead_end", utility=-0.9 (current + exhausted alternatives)</li>
 *   <li><b>Metadata:</b> source="contingency-ccrs", strategy="backtrack", checkpoint=uri</li>
 * </ul>
 * 
 * <h2>Configuration Parameters</h2>
 * <ul>
 *   <li>{@code maxBacktrackDepth}: Max repetitions per strategy ID (default: 5)</li>
 *   <li>{@code requireUnexploredAlternatives}: Filter checkpoints without options (default: true)</li>
 *   <li>{@code minUnexploredAlternatives}: Minimum unexplored count threshold (default: 1)</li>
 *   <li>{@code maxCheckpointsToEvaluate}: Limit candidates for efficiency (default: 10)</li>
 *   <li>{@code maxGraphDistance}: Max temporal distance threshold (default: unlimited)</li>
 * </ul>
 * 
 * @see CheckpointCandidate
 * @see OpportunisticResult
 */
public class BacktrackStrategy implements CcrsStrategy {
    
    private static final Logger logger = Logger.getLogger(BacktrackStrategy.class.getName());

    public static final String ID = "backtrack";
    
    // Configuration
    private int maxBacktrackDepth = 5;
    private boolean requireUnexploredAlternatives = true;
    private int minUnexploredAlternatives = 1;
    private int maxCheckpointsToEvaluate = 10;
    private int maxGraphDistance = Integer.MAX_VALUE;
    
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
    
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        if (situation.getType() != Situation.Type.STUCK && 
            situation.getType() != Situation.Type.FAILURE) {
            return Applicability.NOT_APPLICABLE;
        }
        
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse(null);
        }
        if (currentResource == null) {
            return Applicability.NOT_APPLICABLE;
        }
        
        int backtrackCount = situation.getAttemptCount(ID);
        if (backtrackCount >= maxBacktrackDepth) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Quick check: do we have any potential checkpoints?
        if (context.hasHistory()) {
            return Applicability.APPLICABLE;
        }
        
        // Check RDF for incoming links
        List<RdfTriple> incomingLinks = context.query(null, null, currentResource);
        if (!incomingLinks.isEmpty()) {
            return Applicability.APPLICABLE;
        }
        
        return Applicability.NOT_APPLICABLE;
    }
    
    /**
     * Checkpoint candidate with validation metadata.
     */
    private record CheckpointCandidate(
        String uri,
        Source source,
        List<String> outgoingLinks,
        List<String> unexploredAlternatives,
        List<String> exhaustedAlternatives,
        double validationScore,
        long recencyTimestamp,
        int graphDistance
    ) {
        enum Source { RDF, HISTORY, BOTH }
    }
    
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
        logger.fine("[Backtrack] Current resource: " + shortenUri(current));
        
        // STEP 1: Collect checkpoint candidates from RDF and history
        List<CheckpointCandidate> rdfCandidates = collectRdfCheckpoints(current, context);
        List<CheckpointCandidate> historyCandidates = collectHistoryCheckpoints(current, context);
        logger.fine(String.format("[Backtrack] Collected %d RDF candidates, %d history candidates",
            rdfCandidates.size(), historyCandidates.size()));
        
        // STEP 2: Merge candidates (same URI = same checkpoint, combine evidence)
        Map<String, CheckpointCandidate> mergedCandidates = mergeCandidates(rdfCandidates, historyCandidates);
        logger.finer(String.format("[Backtrack] Merged to %d unique checkpoints", mergedCandidates.size()));
        
        // STEP 3: Classify alternatives for each checkpoint
        for (String uri : mergedCandidates.keySet()) {
            CheckpointCandidate classified = classifyAlternatives(
                mergedCandidates.get(uri), current, context);
            mergedCandidates.put(uri, classified);
            logger.finer(String.format("[Backtrack]   %s: %d unexplored, %d exhausted",
                shortenUri(uri), classified.unexploredAlternatives().size(), 
                classified.exhaustedAlternatives().size()));
        }
        
        // STEP 4: Validate and filter checkpoints
        List<CheckpointCandidate> validatedCheckpoints = mergedCandidates.values().stream()
            .map(c -> validateCheckpoint(c, context))
            .filter(c -> c.validationScore() > 0)
            .filter(c -> !requireUnexploredAlternatives || 
                         c.unexploredAlternatives().size() >= minUnexploredAlternatives)
            .limit(maxCheckpointsToEvaluate)
            .toList();
        
        logger.fine(String.format("[Backtrack] %d checkpoints passed validation", validatedCheckpoints.size()));
        
        if (validatedCheckpoints.isEmpty()) {
            logger.warning("[Backtrack] No valid checkpoints with unexplored alternatives found");
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "No valid checkpoints with unexplored alternatives found");
        }
        
        // STEP 5: Calculate graph distances
        List<CheckpointCandidate> withDistances = validatedCheckpoints.stream()
            .map(c -> new CheckpointCandidate(
                c.uri(), c.source(), c.outgoingLinks(), c.unexploredAlternatives(),
                c.exhaustedAlternatives(), c.validationScore(), c.recencyTimestamp(),
                calculateGraphDistance(c.uri(), current, context)))
            .filter(c -> c.graphDistance() <= maxGraphDistance)
            .toList();
        
        logger.finer(String.format("[Backtrack] %d checkpoints within max distance %d",
            withDistances.size(), maxGraphDistance));
        
        if (withDistances.isEmpty()) {
            logger.warning("[Backtrack] All checkpoints exceed maximum graph distance");
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "All checkpoints exceed maximum graph distance");
        }
        
        // STEP 6: Rank checkpoints
        List<CheckpointCandidate> rankedCheckpoints = rankCheckpoints(withDistances);
        CheckpointCandidate bestCheckpoint = rankedCheckpoints.get(0);
        
        logger.info(String.format("[Backtrack] Selected checkpoint: %s (source=%s, unexplored=%d, distance=%d, score=%.2f)",
            shortenUri(bestCheckpoint.uri()), bestCheckpoint.source(),
            bestCheckpoint.unexploredAlternatives().size(), bestCheckpoint.graphDistance(),
            bestCheckpoint.validationScore()));
        
        // STEP 7: Compute backtrack path from current to checkpoint
        List<String> backtrackPath = computeBacktrackPath(current, bestCheckpoint.uri(), context);
        logger.fine(String.format("[Backtrack] Path length: %d steps", backtrackPath.size()));
        
        // STEP 8: Build result with rich metadata
        // Use checkpoint-specific attempt key to avoid different checkpoints sharing counters
        String attemptKey = "backtrack:" + bestCheckpoint.uri();
        int depth = situation.getAttemptCount(attemptKey) + 1;
        
        // Build parameter maps
        Map<String, List<String>> alternativesByCheckpoint = rankedCheckpoints.stream()
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                CheckpointCandidate::unexploredAlternatives));
        
        Map<String, List<String>> exhaustedByCheckpoint = rankedCheckpoints.stream()
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                CheckpointCandidate::exhaustedAlternatives));
        
        Map<String, Integer> graphDistances = rankedCheckpoints.stream()
            .collect(Collectors.toMap(
                CheckpointCandidate::uri,
                CheckpointCandidate::graphDistance));
        
        // B2: Generate OpportunisticResult mental notes
        // TODO: Correct the opportunistic result, following the standard structure.
        List<OpportunisticResult> opportunisticGuidance = generateOpportunisticNotes(
            current, bestCheckpoint, attemptKey);
        logger.info(String.format("[Backtrack] Generated %d opportunistic notes (%d unexplored options, %d dead ends)",
            opportunisticGuidance.size(), bestCheckpoint.unexploredAlternatives().size(),
            1 + bestCheckpoint.exhaustedAlternatives().size()));
        
        return StrategyResult.suggest(attemptKey, "navigate")
            .target(bestCheckpoint.uri())
            .param("reason", "backtrack_to_checkpoint")
            .param("fromResource", current)
            .param("backtrackPath", backtrackPath)
            .param("candidateCheckpoints", rankedCheckpoints.stream()
                .map(CheckpointCandidate::uri).toList())
            .param("alternativesByCheckpoint", alternativesByCheckpoint)
            .param("exhaustedByCheckpoint", exhaustedByCheckpoint)
            .param("graphDistances", graphDistances)
            .param("checkpointSource", bestCheckpoint.source().name())
            .param("unexploredCount", bestCheckpoint.unexploredAlternatives().size())
            .param("exhaustedCount", bestCheckpoint.exhaustedAlternatives().size())
            .param("graphDistance", bestCheckpoint.graphDistance())
            .param("validationScore", bestCheckpoint.validationScore())
            .param("depthFromCurrent", depth)
            .confidence(calculateConfidence(bestCheckpoint, depth))
            .cost(0.3)
            .rationale(buildRationale(current, bestCheckpoint, rankedCheckpoints.size(), depth))
            .opportunisticGuidance(opportunisticGuidance)
            .build();
    }
    
    /**
     * Collect checkpoint candidates from RDF graph.
     * A checkpoint is any resource that has a link TO the current resource.
     */
    private List<CheckpointCandidate> collectRdfCheckpoints(String currentResource, CcrsContext context) {
        List<RdfTriple> incomingLinks = context.query(null, null, currentResource);
        logger.finer(String.format("[Backtrack] Found %d incoming RDF links to %s",
            incomingLinks.size(), shortenUri(currentResource)));
        
        return incomingLinks.stream()
            .map(t -> t.subject)
            .filter(uri -> uri != null && !uri.isEmpty())
            .filter(uri -> uri.startsWith("http"))  // Valid URI
            .filter(uri -> !uri.equals(currentResource))  // Not self-loop
            .distinct()
            .map(uri -> createCandidate(uri, CheckpointCandidate.Source.RDF, context))
            .toList();
    }
    
    /**
     * Collect checkpoint candidates from interaction history.
     * Extract resources whose response contained links to current resource.
     */
    private List<CheckpointCandidate> collectHistoryCheckpoints(String currentResource, CcrsContext context) {
        List<Interaction> history = context.getRecentInteractions(50);
        logger.finer(String.format("[Backtrack] Scanning %d history interactions for checkpoints",
            history.size()));
        
        // Only consider successful interactions
        return history.stream()
            .filter(i -> i.outcome() == Interaction.Outcome.SUCCESS)
            .filter(i -> i.perceivedState() != null)
            .filter(i -> i.perceivedState().stream()
                .anyMatch(triple -> currentResource.equals(triple.object)))
            .map(i -> i.requestUri())
            .filter(uri -> uri != null && !uri.isEmpty())
            .filter(uri -> !uri.equals(currentResource))
            .distinct()
            .map(uri -> createCandidateWithRecency(uri, CheckpointCandidate.Source.HISTORY, 
                findMostRecentTimestamp(uri, history), context))
            .toList();
    }
    
    /**
     * Create initial checkpoint candidate.
     */
    private CheckpointCandidate createCandidate(String uri, CheckpointCandidate.Source source, 
                                                 CcrsContext context) {
        List<String> outgoing = queryOutgoingLinks(uri, context);
        return new CheckpointCandidate(uri, source, outgoing, 
            List.of(), List.of(), 0.0, 0L, Integer.MAX_VALUE);
    }
    
    private CheckpointCandidate createCandidateWithRecency(String uri, CheckpointCandidate.Source source,
                                                            long timestamp, CcrsContext context) {
        List<String> outgoing = queryOutgoingLinks(uri, context);
        return new CheckpointCandidate(uri, source, outgoing,
            List.of(), List.of(), 0.0, timestamp, Integer.MAX_VALUE);
    }
    
    /**
     * Merge RDF and history candidates (same URI = same checkpoint).
     */
    private Map<String, CheckpointCandidate> mergeCandidates(
            List<CheckpointCandidate> rdfCandidates, 
            List<CheckpointCandidate> historyCandidates) {
        
        Map<String, CheckpointCandidate> merged = new HashMap<>();
        
        // Add RDF candidates
        for (CheckpointCandidate c : rdfCandidates) {
            merged.put(c.uri(), c);
        }
        
        // Merge history candidates (prefer history recency, combine sources)
        for (CheckpointCandidate c : historyCandidates) {
            if (merged.containsKey(c.uri())) {
                CheckpointCandidate existing = merged.get(c.uri());
                merged.put(c.uri(), new CheckpointCandidate(
                    c.uri(),
                    CheckpointCandidate.Source.BOTH,  // Evidence from both sources
                    c.outgoingLinks().isEmpty() ? existing.outgoingLinks() : c.outgoingLinks(),
                    List.of(), List.of(),
                    0.0,
                    c.recencyTimestamp(),  // Use history timestamp
                    Integer.MAX_VALUE
                ));
            } else {
                merged.put(c.uri(), c);
            }
        }
        
        return merged;
    }
    
    /**
     * Classify alternatives as unexplored or exhausted based on interaction history.
     * 
     * Exhaustion rules:
     * - Alternative O is exhausted if interaction failed
     * - Alternative O is exhausted if succeeded but agent came back to checkpoint
     */
    private CheckpointCandidate classifyAlternatives(CheckpointCandidate candidate, 
                                                      String currentResource, 
                                                      CcrsContext context) {
        List<Interaction> history = context.getRecentInteractions(100);
        List<String> unexplored = new ArrayList<>();
        List<String> exhausted = new ArrayList<>();
        
        for (String alternative : candidate.outgoingLinks()) {
            if (isExhausted(alternative, candidate.uri(), history)) {
                exhausted.add(alternative);
            } else if (isUnexplored(alternative, history)) {
                unexplored.add(alternative);
            } else {
                // Visited and not exhausted - could still be viable
                unexplored.add(alternative);
            }
        }
        
        return new CheckpointCandidate(
            candidate.uri(), candidate.source(), candidate.outgoingLinks(),
            unexplored, exhausted, candidate.validationScore(), 
            candidate.recencyTimestamp(), candidate.graphDistance());
    }
    
    /**
     * Check if alternative is exhausted.
     * Exhausted if: failed OR (succeeded but later came back to checkpoint)
     */
    private boolean isExhausted(String alternative, String checkpointUri, 
                                List<Interaction> history) {
        for (int i = 0; i < history.size(); i++) {
            Interaction interaction = history.get(i);
            
            if (!alternative.equals(interaction.requestUri())) {
                continue;
            }
            
            // Check if it failed
            if (interaction.outcome() == Interaction.Outcome.CLIENT_FAILURE ||
                interaction.outcome() == Interaction.Outcome.SERVER_FAILURE) {
                return true;
            }
            
            // Check if succeeded but agent came back to checkpoint (or its predecessor)
            if (interaction.outcome() == Interaction.Outcome.SUCCESS) {
                // Look at later interactions (index < i since history is most recent first)
                for (int j = i - 1; j >= 0; j--) {
                    Interaction laterInteraction = history.get(j);
                    
                    // Came back to checkpoint itself
                    if (checkpointUri.equals(laterInteraction.requestUri())) {
                        return true;
                    }
                    
                    // Came back to a predecessor of checkpoint (has incoming link to checkpoint)
                    if (laterInteraction.perceivedState() != null) {
                        boolean isPredecessor = laterInteraction.perceivedState().stream()
                            .anyMatch(t -> checkpointUri.equals(t.object));
                        if (isPredecessor) {
                            return true;
                        }
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check if alternative is unexplored (never visited).
     */
    private boolean isUnexplored(String alternative, List<Interaction> history) {
        return history.stream()
            .noneMatch(i -> alternative.equals(i.requestUri()));
    }
    
    /**
     * Validate checkpoint and calculate validation score.
     * Score based on: has unexplored alternatives, has outgoing links, appears in history.
     */
    private CheckpointCandidate validateCheckpoint(CheckpointCandidate candidate, CcrsContext context) {
        double score = 0.0;
        
        // Penalize if all alternatives exhausted (checkpoint is dead end)
        if (candidate.exhaustedAlternatives().size() == candidate.outgoingLinks().size()) {
            return new CheckpointCandidate(candidate.uri(), candidate.source(), 
                candidate.outgoingLinks(), candidate.unexploredAlternatives(),
                candidate.exhaustedAlternatives(), 0.0, candidate.recencyTimestamp(), 
                candidate.graphDistance());
        }
        
        // Required: has outgoing links
        if (candidate.outgoingLinks().isEmpty()) {
            return new CheckpointCandidate(candidate.uri(), candidate.source(), 
                candidate.outgoingLinks(), candidate.unexploredAlternatives(),
                candidate.exhaustedAlternatives(), 0.0, candidate.recencyTimestamp(), 
                candidate.graphDistance());
        }
        
        // Has unexplored alternatives (+0.4)
        if (!candidate.unexploredAlternatives().isEmpty()) {
            score += 0.4;
        }
        
        // Appears in interaction history (+0.3)
        if (candidate.recencyTimestamp() > 0 || 
            candidate.source() == CheckpointCandidate.Source.HISTORY ||
            candidate.source() == CheckpointCandidate.Source.BOTH) {
            score += 0.3;
        }
        
        // Has multiple outgoing links (+0.3)
        if (candidate.outgoingLinks().size() > 1) {
            score += 0.3;
        }
        
        return new CheckpointCandidate(candidate.uri(), candidate.source(),
            candidate.outgoingLinks(), candidate.unexploredAlternatives(),
            candidate.exhaustedAlternatives(), score, candidate.recencyTimestamp(),
            candidate.graphDistance());
    }
    
    /**
     * Calculate temporal distance: number of interactions since last visit to checkpoint.
     */
    private int calculateGraphDistance(String checkpointUri, String currentResource, 
                                        CcrsContext context) {
        if (checkpointUri.equals(currentResource)) {
            return 0;
        }
        
        List<Interaction> history = context.getRecentInteractions(1000);
        
        // Count interactions from most recent until we find checkpoint
        int distance = 0;
        for (Interaction interaction : history) {
            if (interaction.requestUri().equals(checkpointUri)) {
                return distance;
            }
            distance++;
        }
        
        return Integer.MAX_VALUE; // Checkpoint not in history
    }
    
    /**
     * Compute backtrack path: sequence of resources from current back to checkpoint.
     * Returns list in forward order (first element = immediate previous, last = checkpoint).
     */
    private List<String> computeBacktrackPath(String current, String checkpoint, CcrsContext context) {
        if (current.equals(checkpoint)) {
            return List.of();
        }
        
        List<Interaction> history = context.getRecentInteractions(1000);
        List<String> path = new ArrayList<>();
        
        // Collect requestUri from most recent until checkpoint
        for (Interaction interaction : history) {
            String uri = interaction.requestUri();
            path.add(uri);
            if (uri.equals(checkpoint)) {
                break;
            }
        }
        
        // Reverse to get forward order (current → checkpoint)
        Collections.reverse(path);
        return path;
    }
    
    /**
     * Rank checkpoints by: unexplored alternatives DESC, distance ASC, recency DESC, score DESC.
     */
    private List<CheckpointCandidate> rankCheckpoints(List<CheckpointCandidate> candidates) {
        return candidates.stream()
            .sorted(Comparator
                .comparingInt((CheckpointCandidate c) -> c.unexploredAlternatives().size()).reversed()
                .thenComparingInt(CheckpointCandidate::graphDistance)
                .thenComparing(Comparator.comparingLong(CheckpointCandidate::recencyTimestamp).reversed())
                .thenComparing(Comparator.comparingDouble(CheckpointCandidate::validationScore).reversed()))
            .toList();
    }
    
    /**
     * Calculate confidence based on checkpoint quality and depth.
     */
    private double calculateConfidence(CheckpointCandidate checkpoint, int depth) {
        double base = 0.7;
        
        // Bonus for unexplored alternatives
        base += Math.min(0.15, checkpoint.unexploredAlternatives().size() * 0.05);
        
        // Bonus for close distance
        if (checkpoint.graphDistance() < 3) {
            base += 0.1;
        }
        
        // Penalty for depth
        base *= Math.pow(0.95, depth - 1);
        
        return Math.min(0.95, base);
    }
    
    /**
     * Build human-readable rationale with structured context.
     */
    private String buildRationale(String current, CheckpointCandidate checkpoint, 
                                   int totalCandidates, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("Blocked at ").append(shortenUri(current)).append(". ");
        sb.append("Backtracking to checkpoint ").append(shortenUri(checkpoint.uri()));
        sb.append(" with ").append(checkpoint.unexploredAlternatives().size())
          .append(" unexplored alternative(s)");
        
        if (checkpoint.graphDistance() < Integer.MAX_VALUE) {
            sb.append(" (distance: ").append(checkpoint.graphDistance()).append(" hops)");
        }
        
        if (totalCandidates > 1) {
            sb.append(" [selected from ").append(totalCandidates).append(" candidates]");
        }
        
        if (depth > 1) {
            sb.append(" [backtrack depth: ").append(depth).append("]");
        }
        
        return sb.toString();
    }
    
    /**
     * Generate opportunistic CCRS mental notes for unexplored options and dead ends.
     * 
     * These conform to ccrs(TargetURI, Type, Utility)[Annotations] format for
     * integration with existing prioritization mechanisms.
     */
    private List<OpportunisticResult> generateOpportunisticNotes(
            String currentResource,
            CheckpointCandidate checkpoint,
            String attemptKey) {
        
        List<OpportunisticResult> results = new ArrayList<>();
        
        // Metadata for all notes
        String checkpointUri = checkpoint.uri();
        
        // 1. Generate notes for unexplored options at checkpoint
        for (String unexploredOption : checkpoint.unexploredAlternatives()) {
            OpportunisticResult note = new OpportunisticResult(
                "unexplored_option",
                unexploredOption,
                attemptKey,
                0.2
            )
            .withMetadata("origin", "contingency-ccrs")
            .withMetadata("strategy", "backtrack")
            .withMetadata("checkpoint", checkpointUri);
            
            results.add(note);
        }
        
        // 2. Generate dead-end note for current resource
        OpportunisticResult deadEndNote = new OpportunisticResult(
            "dead_end",
            currentResource,
            attemptKey,
            -0.9
        )
        .withMetadata("origin", "contingency-ccrs")
        .withMetadata("strategy", "backtrack")
        .withMetadata("checkpoint", checkpointUri);
        
        results.add(deadEndNote);
        
        // 3. Optionally generate dead-end notes for exhausted alternatives
        for (String exhausted : checkpoint.exhaustedAlternatives()) {
            OpportunisticResult exhaustedNote = new OpportunisticResult(
                "dead_end",
                exhausted,
                attemptKey,
                -0.9
            )
            .withMetadata("origin", "contingency-ccrs")
            .withMetadata("strategy", "backtrack")
            .withMetadata("checkpoint", checkpointUri);
            
            results.add(exhaustedNote);
        }
        
        return results;
    }
    
    // ========== Utility Methods ==========
    
    private List<String> queryOutgoingLinks(String uri, CcrsContext context) {
        return context.query(uri, null, null).stream()
            .map(t -> t.object)
            .filter(o -> o != null && !o.isEmpty())
            .distinct()
            .toList();
    }
    
    private long findMostRecentTimestamp(String uri, List<Interaction> history) {
        return history.stream()
            .filter(i -> uri.equals(i.requestUri()))
            .mapToLong(Interaction::requestTimestamp)
            .max()
            .orElse(0L);
    }
    
    private String shortenUri(String uri) {
        if (uri == null) return "null";
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < uri.length() - 1) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }
    
    // ========== Configuration ==========
    
    public BacktrackStrategy maxDepth(int depth) {
        this.maxBacktrackDepth = depth;
        return this;
    }
    
    public BacktrackStrategy requireUnexploredAlternatives(boolean require) {
        this.requireUnexploredAlternatives = require;
        return this;
    }
    
    public BacktrackStrategy minUnexploredAlternatives(int min) {
        this.minUnexploredAlternatives = min;
        return this;
    }
    
    public BacktrackStrategy maxCheckpointsToEvaluate(int max) {
        this.maxCheckpointsToEvaluate = max;
        return this;
    }
    
    public BacktrackStrategy maxGraphDistance(int max) {
        this.maxGraphDistance = max;
        return this;
    }
}
