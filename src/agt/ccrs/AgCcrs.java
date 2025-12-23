package ccrs;

import jason.RevisionFailedException;
import jason.asSemantics.*;
import jason.asSyntax.*;
import jason.asSyntax.Trigger.TEOperator;
import jason.asSyntax.Trigger.TEType;
import jason.bb.BeliefBase;
import jason.bb.StructureWrapperForLiteral;
import jason.functions.log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.Iterator;

public class AgCcrs extends Agent {


    /** Belief Update Function: adds/removes percepts into belief base.
     *
     *  @return the number of changes (add + dels)
     */
    @Override
    public int buf(Collection<Literal> percepts) {
        /*
        // complexity: 2n + n*m (n = number of percepts; m = number of beliefs)

        HashSet percepts = clone from the list of current environment percepts // 1n

        for b in BBPercept (the set of perceptions already in BB) // 1n * m
            if b not in percepts // constant time test
                remove b in BBPercept // constant time
                remove b in percept   // linear time

        for p still in percepts // 1n
            add p in BBPercepts
        */

        if (percepts == null) {
            return 0;
        }

        logger.info("BUF called with percepts: " + percepts);

        // stat
        int adds = 0;
        int dels = 0;
        //long startTime = qProfiling == null ? 0 : System.nanoTime();

        // to copy percepts allows the use of contains below
        Set<StructureWrapperForLiteral> perW = new HashSet<>();
        Iterator<Literal> iper = percepts.iterator();
        while (iper.hasNext()) {
            Literal l = iper.next();
            if (l != null)
                perW.add(new StructureWrapperForLiteral(l));
        }


        // deleting percepts in the BB that are not perceived anymore
        Iterator<Literal> perceptsInBB = getBB().getPercepts();
        while (perceptsInBB.hasNext()) {
            Literal l = perceptsInBB.next();
            if (l.subjectToBUF() && ! perW.remove(new StructureWrapperForLiteral(l))) { // l is not perceived anymore
            
                /*  // Currently no need to pass deletion to BRF
                // Reconstruct literal
                Literal ld = ASSyntax.createLiteral(l.getFunctor(), l.getTermsArray());
                // Preserve external event semantics: Percepts are not caused by intentions.
                ld.addAnnot(BeliefBase.TPercept);

                // Delegate to BRF
                try {
                    List<Literal>[] result = brf(null, ld, Intention.EmptyInt, false);            
            
                    if (result != null && result[1] != null && !result[1].isEmpty()) {
                        dels++;
                        logger.fine("BUF removed percept " + ld);
                        Trigger te = new Trigger(TEOperator.del, TEType.belief, ld);
                        if (ts.getC().hasListener() || pl.hasCandidatePlan(te)) {
                          ts.getC().addEvent(new Event(te));
                          logger.fine("BUF generated event for removed percept " + ld);
                        }
                    }
                } catch (RevisionFailedException e) {
                    logger.log(Level.WARNING, "BRF failed while removing percept " + ld, e);
                }
            */
                
                dels++;
                perceptsInBB.remove(); // remove l as perception from BB

                // new version (it is certain that l is in BB, only clone l when the event is relevant)
                Trigger te = new Trigger(TEOperator.del, TEType.belief, l);
                if (ts.getC().hasListener() || pl.hasCandidatePlan(te)) {
                    l = ASSyntax.createLiteral(l.getFunctor(), l.getTermsArray());
                    l.addAnnot(BeliefBase.TPercept);
                    te.setLiteral(l);
                    ts.getC().addEvent(new Event(te));
                }
                
            }
        }

        // adding new percepts into the BB
        for (StructureWrapperForLiteral lw: perW) {
            try {
                Literal lp = lw.getLiteral().copy().forceFullLiteralImpl();
                lp.addAnnot(BeliefBase.TPercept);

                try {
                    // Delegate to BRF
                    List<Literal>[] result = brf(lp, null, Intention.EmptyInt, false);

                    if (result != null && result[0] != null && !result[0].isEmpty()) {
                        adds++;
                        logger.fine("BUF added percept " + lp);
                        Trigger te = new Trigger(TEOperator.add, TEType.belief, lp);
                        ts.updateEvents(new Event(te));
                        logger.fine("BUF generated event for added percept " + lp);
                    }
                } catch (RevisionFailedException e) {
                    logger.log(Level.WARNING, "BRF failed while adding percept " + lp, e);
                }

                /* 
                if (getBB().add(lp)) {
                    adds++;
                    ts.updateEvents(new Event(new Trigger(TEOperator.add, TEType.belief, lp)));
                }
                */
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error adding percetion " + lw.getLiteral(), e);
            }
        }

        return adds + dels;
    }


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
                if (beliefToAdd != null && beliefToAdd.hasAnnot(BeliefBase.TPercept)) {
                    // Opportunistic-CCRS
                    if (logger.isLoggable(Level.FINE)) logger.fine("Doing (add-percept) brf for " + beliefToAdd);
                }
                // Default brf behavior
                else if (beliefToAdd != null) {
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