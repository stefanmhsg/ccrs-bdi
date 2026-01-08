package ccrs.jason.contingency;

import ccrs.core.contingency.dto.ActionRecord;
import ccrs.core.contingency.dto.StateSnapshot;
import ccrs.jason.JasonRdfAdapter;
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
        
        String trackType = JasonRdfAdapter.termToString(args[0]);
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
        
        String actionType = JasonRdfAdapter.termToString(args[1]);
        String target = JasonRdfAdapter.termToString(args[2]);
        String outcomeStr = JasonRdfAdapter.termToString(args[3]);
        
        try {
            // Create action/3 or action/4 belief directly
            Literal actionBel;
            if (args.length >= 5 && args[4].isList()) {
                // action(Type, Target, Outcome, Details)
                actionBel = ASSyntax.createLiteral("action",
                    ASSyntax.createString(actionType),
                    ASSyntax.createString(target),
                    ASSyntax.createString(outcomeStr),
                    args[4]
                );
            } else {
                // action(Type, Target, Outcome)
                actionBel = ASSyntax.createLiteral("action",
                    ASSyntax.createString(actionType),
                    ASSyntax.createString(target),
                    ASSyntax.createString(outcomeStr)
                );
            }
            
            actionBel.addAnnot(jason.bb.BeliefBase.TSelf);
            context.getBeliefBase().add(actionBel);
            logger.fine("[CCRS-Track] Action: " + actionType + " " + target + " -> " + outcomeStr);
            
        } catch (Exception e) {
            throw new JasonException("Failed to track action: " + e.getMessage());
        }
        
        return true;
    }
    
    private boolean trackState(JasonCcrsContext context, Term[] args) throws JasonException {
        if (args.length < 2) {
            throw new JasonException(
                "track(state, ...) requires: track(state, Resource) or " +
                "track(state, Resource, Summary)");
        }
        
        String resource = JasonRdfAdapter.termToString(args[1]);
        
        try {
            // Create state/1 or state/2 belief directly
            Literal stateBel;
            if (args.length >= 3 && args[2].isList()) {
                // state(Resource, Summary)
                stateBel = ASSyntax.createLiteral("state",
                    ASSyntax.createString(resource),
                    args[2]
                );
            } else {
                // state(Resource)
                stateBel = ASSyntax.createLiteral("state",
                    ASSyntax.createString(resource)
                );
            }
            
            stateBel.addAnnot(jason.bb.BeliefBase.TSelf);
            context.getBeliefBase().add(stateBel);
            context.setCurrentResource(resource);
            logger.fine("[CCRS-Track] State: " + resource);
            
        } catch (Exception e) {
            throw new JasonException("Failed to track state: " + e.getMessage());
        }
        
        return true;
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
