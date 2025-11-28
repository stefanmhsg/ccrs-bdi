package ccrs;

import jason.RevisionFailedException;
import jason.asSemantics.*;
import jason.asSyntax.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.Iterator;

public class Ccrs extends Agent {

    @Override
    public List<Literal>[] brf(Literal add, Literal del, Intention i)
            throws RevisionFailedException {
        return brf(add, del, i, false);
    }


    /**
     * This function should revise the belief base with the given literal to
     * add, to remove, and the current intention that triggered the operation.
     *
     * <p>In its return, List[0] has the list of actual additions to
     * the belief base, and List[1] has the list of actual deletions;
     * this is used to generate the appropriate internal events. If
     * nothing change, returns null.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Literal>[] brf(Literal beliefToAdd, Literal beliefToDel,  Intention i, boolean addEnd) throws RevisionFailedException {
        // This class does not implement belief revision! It
        // is supposed that a subclass will do it.
        // It simply adds/dels the belief.

        int position = 0; // add in the begining
        if (addEnd)
            position = 1;

        List<Literal>[] result = null;
        bb.getLock().lock();
        try {
            try {
                if (beliefToAdd != null) {
                    if (logger.isLoggable(Level.FINE)) logger.fine("Doing (add) brf for " + beliefToAdd);

                    if (getBB().add(position, beliefToAdd)) {
                        result = new List[2];
                        result[0] = Collections.singletonList(beliefToAdd);
                        result[1] = Collections.emptyList();
                        if (logger.isLoggable(Level.FINE)) logger.fine("brf added " + beliefToAdd);
                    }
                }

                if (beliefToDel != null) {
                    Unifier u;
                    try {
                        u = i.peek().getUnif(); // get from current intention
                    } catch (Exception e) {
                        u = new Unifier();
                    }

                    if (logger.isLoggable(Level.FINE)) logger.fine("Doing (del) brf for " + beliefToDel + " in BB=" + believes(beliefToDel, u));

                    boolean removed = getBB().remove(beliefToDel);
                    if (!removed && !beliefToDel.isGround()) { // then try to unify the parameter with a belief in BB
                        Iterator<Literal> il = getBB().getCandidateBeliefs(beliefToDel.getPredicateIndicator());
                        if (il != null) {
                            while (il.hasNext()) {
                                Literal linBB = il.next();
                                if (u.unifies(beliefToDel, linBB)) {
                                    beliefToDel = (Literal)beliefToDel.capply(u);
                                    linBB.delAnnots(beliefToDel.getAnnots());
                                    if (!linBB.hasSource()) {
                                        il.remove();
                                        removed = true;
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    if (removed) {
                        if (logger.isLoggable(Level.FINE)) logger.fine("Removed:" + beliefToDel);
                        if (result == null) {
                            result = new List[2];
                            result[0] = Collections.emptyList();
                        }
                        result[1] = Collections.singletonList(beliefToDel);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error at BRF.",e);
            }
        } finally {
            bb.getLock().unlock();
        }
        return result;
    }

}