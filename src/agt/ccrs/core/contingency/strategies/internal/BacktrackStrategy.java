package ccrs.core.contingency.strategies.internal;

import java.util.*;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.Interaction;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.opportunistic.OpportunisticResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;

/**
 * L2: Checkpoint-Based Backtrack Strategy
 * 
 * Identifies checkpoints (decision points with unexplored alternatives) and
 * returns to the best one when the current path is blocked.
 * 
 * Uses hypermedia heuristics: a checkpoint is any resource that links TO 
 * the current resource and has outgoing alternatives not yet exhausted.
 * 
 * Exhaustion rules based on interaction history:
 * - Alternative is exhausted if it failed OR led to a dead-end (came back)
 * - Alternative is unexplored if never visited
 */
public class BacktrackStrategy implements CcrsStrategy {
    
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
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse(null);
        }
        
        if (currentResource == null) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.INSUFFICIENT_CONTEXT,
                "Cannot determine current resource location");
        }
        
        final String current = currentResource;  // Make effectively final for lambdas
        
        // STEP 1: Collect checkpoint candidates from RDF and history
        List<CheckpointCandidate> rdfCandidates = collectRdfCheckpoints(current, context);
        List<CheckpointCandidate> historyCandidates = collectHistoryCheckpoints(current, context);
        
        // STEP 2: Merge candidates (same URI = same checkpoint, combine evidence)
        Map<String, CheckpointCandidate> mergedCandidates = mergeCandidates(rdfCandidates, historyCandidates);
        
        // STEP 3: Classify alternatives for each checkpoint
        for (String uri : mergedCandidates.keySet()) {
            CheckpointCandidate classified = classifyAlternatives(
                mergedCandidates.get(uri), current, context);
            mergedCandidates.put(uri, classified);
        }
        
        // STEP 4: Validate and filter checkpoints
        List<CheckpointCandidate> validatedCheckpoints = mergedCandidates.values().stream()
            .map(c -> validateCheckpoint(c, context))
            .filter(c -> c.validationScore() > 0)
            .filter(c -> !requireUnexploredAlternatives || 
                         c.unexploredAlternatives().size() >= minUnexploredAlternatives)
            .limit(maxCheckpointsToEvaluate)
            .toList();
        
        if (validatedCheckpoints.isEmpty()) {
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
        
        if (withDistances.isEmpty()) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "All checkpoints exceed maximum graph distance");
        }
        
        // STEP 6: Rank checkpoints
        List<CheckpointCandidate> rankedCheckpoints = rankCheckpoints(withDistances);
        CheckpointCandidate bestCheckpoint = rankedCheckpoints.get(0);
        
        // STEP 7: Compute backtrack path from current to checkpoint
        List<String> backtrackPath = computeBacktrackPath(current, bestCheckpoint.uri(), context);
        
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
        List<OpportunisticResult> opportunisticGuidance = generateOpportunisticNotes(
            current, bestCheckpoint, attemptKey);
        
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
            .withMetadata("source", "contingency-ccrs")
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
        .withMetadata("source", "contingency-ccrs")
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
            .withMetadata("source", "contingency-ccrs")
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
