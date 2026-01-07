package ccrs.jason.contingency;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;
import jason.JasonException;

import java.util.logging.Logger;

import ccrs.core.contingency.dto.CcrsTrace;

/**
 * Internal action for reporting the outcome of a CCRS suggestion.
 * This enables learning and trace analysis.
 * 
 * Usage:
 *   ccrs.contingency.report_outcome(Outcome)
 *   ccrs.contingency.report_outcome(Outcome, Details)
 * 
 * Arguments:
 * - Outcome: "success", "partial", "failed"
 * - Details: Optional string with additional information
 * 
 * Example:
 *   +!execute_suggestion(Suggestion) : true
 *     <- // ... execute the suggestion ...
 *        ccrs.contingency.report_outcome("success");
 *   .
 *   
 *   -!execute_suggestion(Suggestion) : true
 *     <- ccrs.contingency.report_outcome("failed", "Plan execution failed");
 *   .
 */
public class report_outcome extends DefaultInternalAction {
    
    private static final Logger logger = Logger.getLogger(report_outcome.class.getName());
    
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        if (args.length < 1) {
            throw new JasonException(
                "ccrs.contingency.report_outcome requires at least 1 argument: (Outcome) or (Outcome, Details)");
        }
        
        String outcomeStr = termToString(args[0]).toUpperCase();
        final String details = args.length >= 2 ? termToString(args[1]) : null;
        
        CcrsTrace.Outcome parsedOutcome;
        try {
            parsedOutcome = CcrsTrace.Outcome.valueOf(outcomeStr);
        } catch (IllegalArgumentException e) {
            parsedOutcome = CcrsTrace.Outcome.UNKNOWN;
        }
        final CcrsTrace.Outcome outcome = parsedOutcome;
        
        // Get context and update last trace
        JasonCcrsContext context = track.getContext(ts.getAgArch().getAgName());
        if (context != null) {
            context.getLastCcrsInvocation().ifPresent(trace -> {
                trace.reportOutcome(outcome, details);
                logger.info("[CCRS-Outcome] Reported: " + outcome + 
                    (details != null ? " (" + details + ")" : ""));
            });
        } else {
            logger.warning("[CCRS-Outcome] No context found for agent, outcome not recorded");
        }
        
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
}
