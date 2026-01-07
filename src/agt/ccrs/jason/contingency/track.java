package ccrs.jason.contingency;

import ccrs.core.contingency.ActionRecord;
import ccrs.core.contingency.StateSnapshot;
import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;
import jason.JasonException;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Internal action for tracking agent actions and state for CCRS history.
 * 
 * Usage:
 *   ccrs.contingency.track(action, ActionType, Target, Outcome)
 *   ccrs.contingency.track(action, ActionType, Target, Outcome, Details)
 *   ccrs.contingency.track(state, Resource)
 *   ccrs.contingency.track(state, Resource, Summary)
 * 
 * Examples:
 *   ccrs.contingency.track(action, "get", "http://maze/cell/5", "success");
 *   ccrs.contingency.track(action, "post", "http://maze/cell/5", "failed", 
 *       [httpStatus("403"), message("Locked")]);
 *   ccrs.contingency.track(state, "http://maze/cell/5");
 */
public class track extends DefaultInternalAction {
    
    private static final Logger logger = Logger.getLogger(track.class.getName());
    
    // Store contexts per agent (simple map for POC)
    private static final Map<String, JasonCcrsContext> contexts = new HashMap<>();
    
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        if (args.length < 2) {
            throw new JasonException(
                "ccrs.contingency.track requires at least 2 arguments");
        }
        
        String trackType = termToString(args[0]);
        JasonCcrsContext context = getOrCreateContext(ts);
        
        switch (trackType.toLowerCase()) {
            case "action":
                return trackAction(context, args);
            case "state":
                return trackState(context, args);
            default:
                throw new JasonException("Unknown track type: " + trackType + 
                    ". Use 'action' or 'state'.");
        }
    }
    
    private boolean trackAction(JasonCcrsContext context, Term[] args) throws JasonException {
        if (args.length < 4) {
            throw new JasonException(
                "track(action, ...) requires: track(action, ActionType, Target, Outcome) or " +
                "track(action, ActionType, Target, Outcome, Details)");
        }
        
        String actionType = termToString(args[1]);
        String target = termToString(args[2]);
        String outcomeStr = termToString(args[3]).toUpperCase();
        
        ActionRecord.Outcome outcome;
        try {
            outcome = ActionRecord.Outcome.valueOf(outcomeStr);
        } catch (IllegalArgumentException e) {
            outcome = ActionRecord.Outcome.UNKNOWN;
        }
        
        ActionRecord.Builder builder = ActionRecord.builder(actionType, target)
            .outcome(outcome);
        
        // Parse optional details
        if (args.length >= 5 && args[4].isList()) {
            ListTerm details = (ListTerm) args[4];
            for (Term item : details) {
                if (item.isStructure()) {
                    Structure s = (Structure) item;
                    if (s.getArity() > 0) {
                        builder.detail(s.getFunctor(), termToString(s.getTerm(0)));
                    }
                }
            }
        }
        
        context.recordAction(builder.build());
        logger.fine("[CCRS-Track] Action: " + actionType + " " + target + " -> " + outcome);
        
        return true;
    }
    
    private boolean trackState(JasonCcrsContext context, Term[] args) throws JasonException {
        if (args.length < 2) {
            throw new JasonException(
                "track(state, ...) requires: track(state, Resource) or " +
                "track(state, Resource, Summary)");
        }
        
        String resource = termToString(args[1]);
        
        StateSnapshot.Builder builder = StateSnapshot.builder(resource);
        
        // Parse optional summary
        if (args.length >= 3 && args[2].isList()) {
            ListTerm summary = (ListTerm) args[2];
            for (Term item : summary) {
                if (item.isStructure()) {
                    Structure s = (Structure) item;
                    if (s.getArity() > 0) {
                        builder.summary(s.getFunctor(), termToString(s.getTerm(0)));
                    }
                }
            }
        }
        
        context.recordState(builder.build());
        context.setCurrentResource(resource);
        logger.fine("[CCRS-Track] State: " + resource);
        
        return true;
    }
    
    private String termToString(Term term) {
        if (term.isString()) {
            return ((StringTerm) term).getString();
        }
        if (term.isAtom()) {
            return ((Atom) term).getFunctor();
        }
        return term.toString();
    }
    
    private synchronized JasonCcrsContext getOrCreateContext(TransitionSystem ts) {
        String agentName = ts.getAgArch().getAgName();
        return contexts.computeIfAbsent(agentName, 
            k -> new JasonCcrsContext(ts.getAg()));
    }
    
    /**
     * Get the context for an agent (for use by other internal actions).
     */
    public static JasonCcrsContext getContext(String agentName) {
        return contexts.get(agentName);
    }
    
    /**
     * Set context for an agent (for testing).
     */
    public static void setContext(String agentName, JasonCcrsContext context) {
        contexts.put(agentName, context);
    }
}
