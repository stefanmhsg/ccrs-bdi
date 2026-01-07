package ccrs.jason.contingency;

import ccrs.core.contingency.ActionRecord;
import ccrs.core.contingency.CcrsTrace;
import ccrs.core.contingency.StateSnapshot;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;
import ccrs.jason.JasonRdfAdapter;
import jason.asSemantics.Agent;
import jason.asSemantics.Unifier;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Literal;
import jason.asSyntax.Term;
import jason.bb.BeliefBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Jason-specific implementation of CcrsContext.
 * Provides access to the Jason belief base for CCRS validation queries
 * and maintains history for contingency strategies.
 */
public class JasonCcrsContext implements CcrsContext {
    
    private static final String RDF_FUNCTOR = "rdf";
    private static final int DEFAULT_HISTORY_SIZE = 50;
    
    private final Agent agent;
    private final String agentId;
    
    // History tracking
    private final LinkedList<ActionRecord> actionHistory = new LinkedList<>();
    private final LinkedList<StateSnapshot> stateHistory = new LinkedList<>();
    private final LinkedList<CcrsTrace> ccrsHistory = new LinkedList<>();
    private int maxHistorySize = DEFAULT_HISTORY_SIZE;
    
    // Current state
    private String currentResource;
    
    public JasonCcrsContext(Agent agent) {
        this.agent = agent;
        this.agentId = agent.getTS() != null ? 
            agent.getTS().getAgArch().getAgName() : "unknown";
    }
    
    // ========== RDF Query Implementation ==========
    
    @Override
    public List<RdfTriple> query(String subject, String predicate, String object) {
        List<RdfTriple> results = new ArrayList<>();
        
        try {
            Term s = subject != null ? 
                ASSyntax.createString(subject) : 
                ASSyntax.createVar("S");
            Term p = predicate != null ? 
                ASSyntax.createString(predicate) : 
                ASSyntax.createVar("P");
            Term o = object != null ? 
                ASSyntax.createString(object) : 
                ASSyntax.createVar("O");
            
            Literal query = ASSyntax.createLiteral(RDF_FUNCTOR, s, p, o);
            
            Iterator<Literal> solutions = agent.getBB().getCandidateBeliefs(query, new Unifier());
            if (solutions != null) {
                while (solutions.hasNext()) {
                    Literal match = solutions.next();
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
                ASSyntax.createString(triple.predicate),
                ASSyntax.createString(triple.object)
            );
            return agent.believes(lit, new Unifier());
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public List<RdfTriple> queryAll() {
        return query(null, null, null);
    }
    
    // ========== History Implementation ==========
    
    @Override
    public List<ActionRecord> getRecentActions(int maxCount) {
        synchronized (actionHistory) {
            int count = Math.min(maxCount, actionHistory.size());
            return new ArrayList<>(actionHistory.subList(0, count));
        }
    }
    
    @Override
    public List<StateSnapshot> getRecentStates(int maxCount) {
        synchronized (stateHistory) {
            int count = Math.min(maxCount, stateHistory.size());
            return new ArrayList<>(stateHistory.subList(0, count));
        }
    }
    
    @Override
    public Optional<CcrsTrace> getLastCcrsInvocation() {
        synchronized (ccrsHistory) {
            return ccrsHistory.isEmpty() ? Optional.empty() : Optional.of(ccrsHistory.getFirst());
        }
    }
    
    @Override
    public List<CcrsTrace> getCcrsHistory(int maxCount) {
        synchronized (ccrsHistory) {
            int count = Math.min(maxCount, ccrsHistory.size());
            return new ArrayList<>(ccrsHistory.subList(0, count));
        }
    }
    
    @Override
    public boolean hasHistory() {
        return true;
    }
    
    // ========== History Recording ==========
    
    /**
     * Record an action taken by the agent.
     */
    public void recordAction(ActionRecord action) {
        synchronized (actionHistory) {
            actionHistory.addFirst(action);
            trimHistory(actionHistory);
        }
    }
    
    /**
     * Record a state snapshot.
     */
    public void recordState(StateSnapshot state) {
        synchronized (stateHistory) {
            stateHistory.addFirst(state);
            trimHistory(stateHistory);
        }
        // Update current resource
        if (state.getResource() != null) {
            this.currentResource = state.getResource();
        }
    }
    
    /**
     * Record a CCRS invocation trace.
     */
    public void recordCcrsInvocation(CcrsTrace trace) {
        synchronized (ccrsHistory) {
            ccrsHistory.addFirst(trace);
            trimHistory(ccrsHistory);
        }
    }
    
    private <T> void trimHistory(LinkedList<T> history) {
        while (history.size() > maxHistorySize) {
            history.removeLast();
        }
    }
    
    // ========== Agent State ==========
    
    @Override
    public Optional<String> getCurrentResource() {
        // First check if we have it cached
        if (currentResource != null) {
            return Optional.of(currentResource);
        }
        
        // Try to find from belief base (common patterns)
        String[] currentPredicates = {"current", "at", "location", "position"};
        for (String pred : currentPredicates) {
            try {
                Literal query = ASSyntax.createLiteral(pred, ASSyntax.createVar("X"));
                Iterator<Literal> solutions = agent.getBB().getCandidateBeliefs(query, new Unifier());
                if (solutions != null && solutions.hasNext()) {
                    Literal match = solutions.next();
                    if (match.getArity() > 0) {
                        Term t = match.getTerm(0);
                        if (t.isString()) {
                            return Optional.of(((jason.asSyntax.StringTerm) t).getString());
                        } else {
                            return Optional.of(t.toString());
                        }
                    }
                }
            } catch (Exception e) {
                // Try next predicate
            }
        }
        
        return Optional.empty();
    }
    
    @Override
    public String getAgentId() {
        return agentId;
    }
    
    /**
     * Set the current resource manually.
     */
    public void setCurrentResource(String resource) {
        this.currentResource = resource;
    }
    
    // ========== Configuration ==========
    
    public void setMaxHistorySize(int size) {
        this.maxHistorySize = size;
    }
    
    /**
     * Get direct access to the Jason agent (for advanced use).
     */
    public Agent getAgent() {
        return agent;
    }
    
    /**
     * Get direct access to the belief base.
     */
    public BeliefBase getBeliefBase() {
        return agent.getBB();
    }
}