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
     * Useful for LLM-based strategies that need full context.
     * 
     * @return All triples, or empty list if not supported
     */
    default List<RdfTriple> queryAll() {
        return query(null, null, null);
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