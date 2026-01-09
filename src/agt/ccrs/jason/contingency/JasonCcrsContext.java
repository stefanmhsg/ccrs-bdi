package ccrs.jason.contingency;

import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Interaction;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;
import ccrs.jason.JasonRdfAdapter;
import ccrs.jason.hypermedia.hypermedea.JasonInteractionLog;
import jason.asSemantics.Agent;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;
import jason.bb.BeliefBase;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Jason-specific implementation of CcrsContext.
 * Queries belief base for RDF triples and history using add_time annotations.
 * Expects TimestampedBeliefBase to be configured.
 */
public class JasonCcrsContext implements CcrsContext {
    
    private static final String RDF_FUNCTOR = "rdf";
    private static final String ADD_TIME_ANNOT = "add_time";
    
    private final Agent agent;
    private final String agentId;

    private final JasonInteractionLog interactionLog;
    
    // Only track CCRS invocations (not in BB)
    private final LinkedList<CcrsTrace> ccrsHistory = new LinkedList<>();
    private static final int MAX_CCRS_HISTORY = 50;
    
    // Current state cache
    private String currentResource;
    
    public JasonCcrsContext(Agent agent, JasonInteractionLog interactionLog) {
        this.agent = agent;
        this.agentId = agent.getTS() != null ? 
            agent.getTS().getAgArch().getAgName() : "unknown";
        this.interactionLog = interactionLog;
    }
    
    // ========== RDF Query Implementation ==========
    
    // TODO: Annotations? not in rdf?
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

    
    // ========== History Implementation (Query Hypermedia-Artifact InteractionLog) ==========
    
    @Override
    public List<Interaction> getRecentInteractions(int maxCount) {
        if (interactionLog == null) {
            return Collections.emptyList();
        }
        return interactionLog.getRecentInteractions(maxCount);
    }

    @Override
    public Optional<Interaction> getLastInteraction() {
        if (interactionLog == null) {
            return Optional.empty();
        }
        return interactionLog.getLastInteraction();
    }

    @Override
    public List<Interaction> getInteractionsFor(String logicalSource) {
        if (interactionLog == null) {
            return Collections.emptyList();
        }
        return interactionLog.getInteractionsFor(logicalSource);
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
        return interactionLog != null && !interactionLog.getRecentInteractions(1).isEmpty();
    }
    
    /**
     * Record a CCRS invocation trace.
     */
    public void recordCcrsInvocation(CcrsTrace trace) {
        synchronized (ccrsHistory) {
            ccrsHistory.addFirst(trace);
            while (ccrsHistory.size() > MAX_CCRS_HISTORY) {
                ccrsHistory.removeLast();
            }
        }
    }
    
    // ========== Utility Methods ==========
    
    /**
     * Extract add_time annotation from belief. Returns 0 if not found.
     */
    private long getTimestamp(Literal bel) {
        for (Term annot : bel.getAnnots()) {
            if (annot.isStructure()) {
                Structure s = (Structure) annot;
                if (ADD_TIME_ANNOT.equals(s.getFunctor()) && s.getArity() > 0) {
                    Term timeTerm = s.getTerm(0);
                    if (timeTerm.isNumeric()) {
                        try {
                            return (long) ((NumberTerm) timeTerm).solve();
                        } catch (Exception e) {
                            return 0;
                        }
                    }
                }
            }
        }
        return 0;
    }
    
    /**
     * Convert milliseconds-since-start to Instant approximation.
     */
    private Instant millisToInstant(long millis) {
        // Use current time minus relative offset
        return Instant.now().minusMillis(System.currentTimeMillis() % 1000000 - millis);
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