package ccrs.core.contingency.strategies.internal;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StateSnapshot;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;

/**
 * L2: Backtrack Strategy
 * 
 * Returns to a previous decision point when current path is blocked.
 * Uses a simple hypermedia heuristic: the "parent" of a resource is any
 * resource that links TO it (i.e., where the current resource appears as
 * the object in an RDF triple). This follows the linked data principle
 * that resources discover each other through links.
 * 
 * Example: if we have triple (cell_A, hasConnection, cell_B) and we're
 * stuck at cell_B, then cell_A is a candidate parent to backtrack to.
 */
public class BacktrackStrategy implements CcrsStrategy {
    
    public static final String ID = "backtrack";
    
    // Configuration
    private int maxBacktrackDepth = 5;
    private boolean requireAlternatives = false;  // If true, only suggest if parent has options
    
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
        // Applies to STUCK or certain FAILURE situations
        if (situation.getType() != Situation.Type.STUCK && 
            situation.getType() != Situation.Type.FAILURE) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Need to know current location
        String currentResource = situation.getCurrentResource();
        if (currentResource == null) {
            currentResource = context.getCurrentResource().orElse(null);
        }
        if (currentResource == null) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Check if we've already backtracked too many times
        int backtrackCount = situation.getAttemptCount(ID);
        if (backtrackCount >= maxBacktrackDepth) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Quick check: is there history or parent relationship?
        if (context.hasHistory() && !context.getRecentStates(2).isEmpty()) {
            return Applicability.APPLICABLE;
        }
        
        // Check RDF for parent relationship
        Optional<String> parent = findParent(currentResource, context);
        if (parent.isPresent()) {
            return Applicability.APPLICABLE;
        }
        
        return Applicability.NOT_APPLICABLE;
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
        
        // Try to find parent via RDF first
        Optional<String> parentOpt = findParent(currentResource, context);
        
        // Fall back to history if RDF doesn't have parent
        if (!parentOpt.isPresent() && context.hasHistory()) {
            parentOpt = findParentFromHistory(currentResource, context);
        }
        
        if (!parentOpt.isPresent()) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.PRECONDITION_MISSING,
                "No parent/previous resource found for: " + currentResource);
        }
        
        String parent = parentOpt.get();
        
        // Check for alternatives at parent (optional)
        List<String> alternatives = findAlternatives(parent, context);
        
        if (requireAlternatives && alternatives.isEmpty()) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.INSUFFICIENT_CONTEXT,
                "Parent resource has no unexplored alternatives");
        }
        
        int depth = situation.getAttemptCount(ID) + 1;
        
        return StrategyResult.suggest(ID, "navigate")
            .target(parent)
            .param("reason", "backtrack")
            .param("fromResource", currentResource)
            .param("alternativesAtTarget", alternatives)
            .param("depthFromCurrent", depth)
            .confidence(calculateConfidence(alternatives.size(), depth))
            .cost(0.3)  // Moderate cost - loses progress
            .rationale(buildRationale(currentResource, parent, alternatives, depth))
            .build();
    }
    
    /**
     * Find a parent resource using the hypermedia heuristic:
     * A parent is any resource that has a link TO the current resource.
     * We query for triples where the current resource is the OBJECT.
     * 
     * @param resource the current resource URI
     * @param context the CCRS context containing the RDF graph
     * @return the first resource that links to the current one, if any
     */
    private Optional<String> findParent(String resource, CcrsContext context) {
        // Simple hypermedia heuristic: find any triple where resource is the object
        // e.g., (parent_cell, hasConnection, current_cell) -> parent_cell is a candidate
        List<RdfTriple> incomingLinks = context.query(null, null, resource);
        
        if (!incomingLinks.isEmpty()) {
            // Return the subject of the first incoming link as the parent
            // Could be enhanced to prefer certain predicates or use history ordering
            return Optional.of(incomingLinks.get(0).subject);
        }
        
        return Optional.empty();
    }
    
    private Optional<String> findParentFromHistory(String currentResource, CcrsContext context) {
        List<StateSnapshot> history = context.getRecentStates(10);
        
        // Find the state before we arrived at current
        boolean foundCurrent = false;
        for (StateSnapshot state : history) {
            if (foundCurrent) {
                // This is the previous state
                return Optional.ofNullable(state.getResource());
            }
            if (currentResource.equals(state.getResource())) {
                foundCurrent = true;
            }
        }
        
        // If current not in history but we have history, return most recent
        if (!history.isEmpty() && !foundCurrent) {
            return Optional.ofNullable(history.get(0).getResource());
        }
        
        return Optional.empty();
    }
    
    /**
     * Find alternative outgoing links from a resource.
     * In hypermedia terms: what resources does the parent link TO?
     * These are potential alternative paths.
     * 
     * @param parent the parent resource URI
     * @param context the CCRS context containing the RDF graph
     * @return list of resources the parent links to (outgoing connections)
     */
    private List<String> findAlternatives(String parent, CcrsContext context) {
        // Find all outgoing links from parent (where parent is the subject)
        List<RdfTriple> outgoingLinks = context.query(parent, null, null);
        
        return outgoingLinks.stream()
            .map(t -> t.object)
            .distinct()
            .collect(Collectors.toList());
    }
    
    private double calculateConfidence(int alternativeCount, int depth) {
        // Higher confidence if alternatives exist
        double base = alternativeCount > 0 ? 0.85 : 0.6;
        // Slight decrease with depth
        return base * Math.pow(0.95, depth - 1);
    }
    
    private String buildRationale(String current, String parent, 
                                   List<String> alternatives, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dead end at ").append(shortenUri(current)).append(". ");
        sb.append("Backtracking to ").append(shortenUri(parent));
        
        if (!alternatives.isEmpty()) {
            sb.append(" which has ").append(alternatives.size())
              .append(" unexplored alternative(s)");
        }
        sb.append(".");
        
        if (depth > 1) {
            sb.append(" (backtrack depth: ").append(depth).append(")");
        }
        
        return sb.toString();
    }
    
    private String shortenUri(String uri) {
        if (uri == null) return "null";
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < uri.length() - 1) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }
    
    // Configuration
    
    public BacktrackStrategy maxDepth(int depth) {
        this.maxBacktrackDepth = depth;
        return this;
    }
    
    public BacktrackStrategy requireAlternatives(boolean require) {
        this.requireAlternatives = require;
        return this;
    }
}
