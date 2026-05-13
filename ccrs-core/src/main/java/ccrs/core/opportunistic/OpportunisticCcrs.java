package ccrs.core.opportunistic;

import ccrs.core.rdf.RdfTriple;

import java.util.*;

/**
 * Core interface for opportunistic CCRS scanning. 
 * Implementations detect opportunities and threats in RDF percepts.
 */
public interface OpportunisticCcrs {
    
    /**
     * Scan a single RDF triple for CCRS opportunities.
     * 
     * @param triple The RDF triple to scan
     * @param context Optional context information (e.g., source, timestamp)
     * @return Result if an opportunity was found, empty otherwise
     */
    Optional<OpportunisticResult> scan(RdfTriple triple, Map<String, Object> context);
    
    /**
     * Scan a single RDF triple without context.
     * 
     * @param triple The RDF triple to scan
     * @return Result if an opportunity was found, empty otherwise
     */
    default Optional<OpportunisticResult> scan(RdfTriple triple) {
        return scan(triple, Collections.emptyMap());
    }
    
    /**
     * Scan a collection of RDF triples together.
     * Enables detection of structural patterns across multiple triples.
     * 
     * @param triples Collection of triples to scan
     * @param context Optional context information
     * @return List of all detected opportunities
     */
    default List<OpportunisticResult> scanAll(Collection<RdfTriple> triples, Map<String, Object> context) {
        List<OpportunisticResult> results = new ArrayList<>();
        for (RdfTriple triple : triples) {
            scan(triple, context).ifPresent(results::add);
        }
        return results;
    }
}