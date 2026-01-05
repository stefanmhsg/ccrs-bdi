package ccrs.jason;

import ccrs.core.opportunistic.OpportunisticResult;
import ccrs.core.rdf.RdfTriple;
import jason.asSyntax.*;

/**
 * Adapter between Jason literals and RDF triples.
 * Handles conversion between Jason's rdf/3 format and RdfTriple objects.
 */
public class JasonRdfAdapter {
    
    private static final String RDF_FUNCTOR = "rdf";
    
    /**
     * Convert Jason literal to RDF triple.
     * Expected format: rdf(Subject, Predicate, Object)
     * 
     * @param lit Jason literal
     * @return RdfTriple or null if not in rdf/3 format
     */
    public static RdfTriple toRdfTriple(Literal lit) {
        if (lit == null || !RDF_FUNCTOR.equals(lit.getFunctor()) || lit.getArity() != 3) {
            return null;
        }
        
        String subject = termToString(lit.getTerm(0));
        String predicate = termToString(lit.getTerm(1));
        String object = termToString(lit.getTerm(2));
        
        return new RdfTriple(subject, predicate, object);
    }
    
    /**
     * Create a CCRS belief in Jason format.
     * Format: ccrs(Target, PatternType, Utility)[source(Source), metadata(Key, Value), ...]
     * 
     * @param result The opportunistic result
     * @param sourceAnchor The source where it was detected
     * @return Jason literal representing the CCRS belief
     */
    public static Literal createCcrsBelief(OpportunisticResult result, String sourceAnchor) {
        try {
            Literal ccrs = ASSyntax.createLiteral("ccrs",
                ASSyntax.createString(result.target),
                ASSyntax.createString(result.type),
                ASSyntax.createNumber(result.utility)
            );
            
            ccrs.addAnnot(ASSyntax.createStructure("source", 
                ASSyntax.createString(sourceAnchor)));
            
            // Add pattern ID as metadata
            if (result.patternId != null) {
                 ccrs.addAnnot(ASSyntax.createStructure("pattern_id", 
                    ASSyntax.createString(result.patternId)));
            }
            
            // Add other metadata
            result.getMetadataMap().forEach((k, v) -> {
                ccrs.addAnnot(ASSyntax.createStructure(k, ASSyntax.createString(v)));
            });
            
            return ccrs;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create CCRS belief", e);
        }
    }
    
    /**
     * Convert Jason Term to string, handling quotes.
     * 
     * @param term Jason term
     * @return String value
     */
    private static String termToString(Term term) {
        if (term. isString()) {
            return ((StringTerm) term).getString();
        }
        String str = term.toString();
        if (str.startsWith("\"") && str.endsWith("\"") && str.length() > 1) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }
}