package ccrs.jacamo.jason;

import jason.JasonException;
import jason.asSemantics.Agent;
import jason.asSyntax.Literal;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.Structure;
import jason.asSyntax.Term;
import jason.bb.DefaultBeliefBase;

/**
 * Custom BeliefBase that automatically adds timestamps to all beliefs.
 * Timestamp is milliseconds since MAS start, added as add_time/1 annotation.
 * 
 * This enables history tracking by querying the belief base directly
 * without maintaining shadow state.
 * 
 * Example belief: rdf(S,P,O)[source(H), add_time(1234)]
 * 
 * Based on Jason Programming book example (Section 6.2.2).
 */
public class TimestampedBeliefBase extends DefaultBeliefBase {
    
    private long start;
    
    @Override
    public void init(Agent ag, String[] args) {
        start = System.currentTimeMillis();
        super.init(ag, args);
    }
    
    @Override
    public boolean add(Literal bel) throws JasonException {
        if (!hasTimeAnnot(bel)) {
            Structure time = new Structure("add_time");
            long pass = System.currentTimeMillis() - start;
            time.addTerm(new NumberTermImpl(pass));
            bel.addAnnot(time);
        }
        return super.add(bel);
    }
    
    @Override
    public boolean add(int index, Literal bel) throws JasonException {
        if (!hasTimeAnnot(bel)) {
            Structure time = new Structure("add_time");
            long pass = System.currentTimeMillis() - start;
            time.addTerm(new NumberTermImpl(pass));
            bel.addAnnot(time);
        }
        return super.add(index, bel);
    }
    
    private boolean hasTimeAnnot(Literal bel) {
        Literal belInBB = contains(bel);
        if (belInBB != null) {
            for (Term a : belInBB.getAnnots()) {
                if (a.isStructure()) {
                    if (((Structure) a).getFunctor().equals("add_time")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Get start time in milliseconds.
     * Useful for converting relative timestamps back to absolute.
     */
    public long getStartTime() {
        return start;
    }
}
