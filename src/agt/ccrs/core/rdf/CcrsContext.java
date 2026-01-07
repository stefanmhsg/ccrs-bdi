package ccrs.core.rdf;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import ccrs.core.contingency.ActionRecord;
import ccrs.core.contingency.CcrsTrace;
import ccrs.core.contingency.StateSnapshot;

/**
 * Context interface for CCRS operations.
 * Provides access to the agent's knowledge base and history.
 * 
 * Core interface is RDF-focused for agent-agnostic design.
 * Platform-specific adapters (Jason, LangGraph) extend this
 * with full access to their respective agent internals.
 */
public interface CcrsContext {
    
    // ========== Defaults ==========
    
    /** Default limit for outgoing links in neighborhood queries */
    int DEFAULT_MAX_OUTGOING = 30;
    
    /** Default limit for incoming links in neighborhood queries */
    int DEFAULT_MAX_INCOMING = 20;
    
    // ========== RDF Query (required) ==========
    
    /**
     * Query for RDF triples matching a pattern.
     * 
     * @param subject Subject URI or null for wildcard
     * @param predicate Predicate URI or null for wildcard
     * @param object Object URI/literal or null for wildcard
     * @return List of matching triples
     */
    List<RdfTriple> query(String subject, String predicate, String object);
    
    /**
     * Check if a specific triple exists in the belief base.
     * 
     * @param triple The triple to check
     * @return True if the triple exists
     */
    boolean contains(RdfTriple triple);
    
    /**
     * Get all triples in the context.
     * WARNING: May be expensive on large graphs. Prefer getNeighborhood() for bounded queries.
     * 
     * @return All triples, or empty list if not supported
     */
    default List<RdfTriple> queryAll() {
        return query(null, null, null);
    }
    
    // ========== Neighborhood Query (optional) ==========
    
    /**
     * Result of a neighborhood query around a resource.
     * Contains bounded outgoing and incoming links.
     */
    record Neighborhood(
        String resource,
        List<RdfTriple> outgoing,
        List<RdfTriple> incoming
    ) {
        public int size() {
            return outgoing.size() + incoming.size();
        }
    }
    
    /**
     * Get the bounded neighborhood around a resource.
     * Returns outgoing links (where resource is subject) and
     * incoming links (where resource is object), capped at limits.
     * 
     * This is the preferred method for LLM/consultation contexts
     * as it avoids exploding on large graphs.
     * 
     * @param resource The resource URI to get neighborhood for
     * @param maxOutgoing Maximum outgoing links to return
     * @param maxIncoming Maximum incoming links to return
     * @return Neighborhood containing bounded link sets
     */
    default Neighborhood getNeighborhood(String resource, int maxOutgoing, int maxIncoming) {
        if (resource == null) {
            return new Neighborhood(null, Collections.emptyList(), Collections.emptyList());
        }
        
        List<RdfTriple> outgoing = query(resource, null, null);
        if (outgoing.size() > maxOutgoing) {
            outgoing = outgoing.subList(0, maxOutgoing);
        }
        
        List<RdfTriple> incoming = query(null, null, resource);
        if (incoming.size() > maxIncoming) {
            incoming = incoming.subList(0, maxIncoming);
        }
        
        return new Neighborhood(resource, outgoing, incoming);
    }
    
    /**
     * Get neighborhood with default limits.
     * 
     * @param resource The resource URI
     * @return Neighborhood with DEFAULT_MAX_OUTGOING and DEFAULT_MAX_INCOMING limits
     */
    default Neighborhood getNeighborhood(String resource) {
        return getNeighborhood(resource, DEFAULT_MAX_OUTGOING, DEFAULT_MAX_INCOMING);
    }
    
    // ========== History (optional) ==========
    
    /**
     * Get recent actions taken by the agent.
     * 
     * @param maxCount Maximum number of actions to return
     * @return Recent actions, most recent first
     */
    default List<ActionRecord> getRecentActions(int maxCount) {
        return Collections.emptyList();
    }
    
    /**
     * Get recent state snapshots.
     * 
     * @param maxCount Maximum number of snapshots to return
     * @return Recent states, most recent first
     */
    default List<StateSnapshot> getRecentStates(int maxCount) {
        return Collections.emptyList();
    }
    
    /**
     * Get the last CCRS invocation trace.
     * 
     * @return Last trace, or empty if none
     */
    default Optional<CcrsTrace> getLastCcrsInvocation() {
        return Optional.empty();
    }
    
    /**
     * Get recent CCRS invocation history.
     * 
     * @param maxCount Maximum number of traces to return
     * @return Recent traces, most recent first
     */
    default List<CcrsTrace> getCcrsHistory(int maxCount) {
        return Collections.emptyList();
    }
    
    // ========== Agent State (optional) ==========
    
    /**
     * Get the agent's current resource/location.
     * 
     * @return Current resource URI, or empty if unknown
     */
    default Optional<String> getCurrentResource() {
        return Optional.empty();
    }
    
    /**
     * Get the agent's identifier.
     * 
     * @return Agent ID
     */
    default String getAgentId() {
        return "unknown";
    }
    
    // ========== Capabilities ==========
    
    /**
     * Check if history tracking is available.
     */
    default boolean hasHistory() {
        return false;
    }
    
    /**
     * Check if LLM access is configured.
     */
    default boolean hasLlmAccess() {
        return false;
    }
    
    /**
     * Check if consultation channels are available.
     */
    default boolean hasConsultationChannel() {
        return false;
    }
}