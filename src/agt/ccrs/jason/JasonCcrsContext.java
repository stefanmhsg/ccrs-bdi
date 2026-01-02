package ccrs.jason;

import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;
import jason.asSemantics.Agent;
import jason.asSemantics.Unifier;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Literal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Jason-specific implementation of CcrsContext.
 * Provides access to the Jason belief base for CCRS validation queries.
 * Not actively used in opportunistic scanning but provided for future extensions.
 */
public class JasonCcrsContext implements CcrsContext {
    
    private static final String RDF_FUNCTOR = "rdf";
    
    private final Agent agent;
    
    public JasonCcrsContext(Agent agent) {
        this.agent = agent;
    }
    
    @Override
    public List<RdfTriple> query(String subject, String predicate, String object) {
        List<RdfTriple> results = new ArrayList<>();
        
        try {
            jason.asSyntax.Term s = subject != null ? 
                ASSyntax.createString(subject) : 
                ASSyntax.createVar("S");
            jason.asSyntax.Term p = predicate != null ? 
                ASSyntax.createString(predicate) : 
                ASSyntax.createVar("P");
            jason.asSyntax.Term o = object != null ? 
                ASSyntax.createString(object) : 
                ASSyntax. createVar("O");
            
            Literal query = ASSyntax.createLiteral(RDF_FUNCTOR, s, p, o);
            
            Iterator<Literal> solutions = agent.getBB().getCandidateBeliefs(query, new Unifier());
            if (solutions != null) {
                while (solutions.hasNext()) {
                    Literal match = solutions.next();
                    // Check if the candidate belief actually unifies with our query pattern
                    Unifier u = new Unifier();
                    if (u.unifies(query, match)) {
                        RdfTriple triple = JasonRdfAdapter.toRdfTriple(match);
                        if (triple != null) {
                            results.add(triple);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Query failed, return empty results
        }
        
        return results;
    }
    
    @Override
    public boolean contains(RdfTriple triple) {
        try {
            Literal lit = ASSyntax.createLiteral(RDF_FUNCTOR,
                ASSyntax.createString(triple.subject),
                ASSyntax. createString(triple.predicate),
                ASSyntax.createString(triple.object)
            );
            return agent.believes(lit, new Unifier());
        } catch (Exception e) {
            return false;
        }
    }
}