package ccrs.core.rdf;

import java.util. List;

/**
 * Context interface for CCRS validation queries.
 * Provides access to the agent's belief base for pattern validation.
 * Not actively used in stateless opportunistic scanning but provided
 * for future contingency-CCRS extensions.
 */
public interface CcrsContext {
    
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
}