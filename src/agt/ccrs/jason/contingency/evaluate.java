package ccrs.jason.contingency;

import ccrs.core.contingency.ContingencyCcrs;
import ccrs.core.contingency.CcrsTrace;
import ccrs.core.contingency.Situation;
import ccrs.core.contingency.StrategyResult;
import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;
import jason.JasonException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal action for evaluating contingency CCRS strategies.
 * 
 * Usage:
 *   ccrs.contingency.evaluate(SituationType, Trigger, ResultList)
 *   ccrs.contingency.evaluate(SituationType, Trigger, CurrentResource, TargetResource, ResultList)
 *   ccrs.contingency.evaluate(SituationType, Trigger, CurrentResource, TargetResource, FailedAction, ErrorInfo, AttemptedList, ResultList)
 * 
 * Arguments:
 * - SituationType: "failure", "stuck", "uncertainty", or "proactive"
 * - Trigger: Description of what caused the situation (string)
 * - CurrentResource: URI of current location (optional)
 * - TargetResource: URI that was being accessed (optional)
 * - FailedAction: The action that failed, e.g., "get", "post" (optional)
 * - ErrorInfo: List of key-value pairs like [httpStatus("503"), message("timeout")] (optional)
 * - AttemptedList: List of already-tried strategies like ["retry:1", "backtrack:1"] (optional)
 * - ResultList: Output - List of strategy suggestions
 * 
 * Result format (list of structures):
 *   [suggestion(StrategyId, ActionType, Target, Confidence, Cost, Rationale, Params), ...]
 * 
 * Example:
 *   -!navigate(Target) : true
 *     <- ccrs.contingency.evaluate("failure", "http_error", CurrentCell, Target, "get",
 *            [httpStatus("503")], [], Suggestions);
 *        !try_recovery(Suggestions).
 */
public class evaluate extends DefaultInternalAction {
    
    private static final Logger logger = Logger.getLogger(evaluate.class.getName());
    
    // Shared ContingencyCcrs instance (initialized lazily)
    private static ContingencyCcrs contingencyCcrs;
    
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        try {
            // Initialize CCRS if needed
            ContingencyCcrs ccrs = getContingencyCcrs();
            
            // Get or create context for this agent
            JasonCcrsContext context = getOrCreateContext(ts);
            
            // Parse arguments and build Situation
            Situation situation = parseSituation(args);
            
            logger.log(Level.INFO, "[CCRS-Contingency] Evaluating situation: " + situation);
            
            // Evaluate strategies
            CcrsTrace trace = ccrs.evaluateWithTrace(situation, context);
            
            // Record trace in context for history
            context.recordCcrsInvocation(trace);
            
            logger.log(Level.INFO, "[CCRS-Contingency] Evaluation complete: " + 
                trace.getSelectedResults().size() + " suggestions");
            
            // Convert results to Jason list 
            ListTerm resultList = buildResultList(trace.getSelectedResults());
            
            // Get the output argument (last one)
            Term outputArg = args[args.length - 1];
            
            return un.unifies(resultList, outputArg);
            
        } catch (JasonException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[CCRS-Contingency] Error: " + e.getMessage(), e);
            throw new JasonException("Error in ccrs.contingency.evaluate: " + e.getMessage());
        }
    }
    
    private Situation parseSituation(Term[] args) throws JasonException {
        if (args.length < 3) {
            throw new JasonException(
                "ccrs.contingency.evaluate requires at least 3 arguments: " +
                "(SituationType, Trigger, ResultList) or more detailed form");
        }
        
        // Parse situation type
        String typeStr = termToString(args[0]).toLowerCase();
        Situation.Type type = switch (typeStr) {
            case "failure" -> Situation.Type.FAILURE;
            case "stuck" -> Situation.Type.STUCK;
            case "uncertainty" -> Situation.Type.UNCERTAINTY;
            case "proactive" -> Situation.Type.PROACTIVE;
            default -> throw new JasonException("Unknown situation type: " + typeStr);
        };
        
        // Parse trigger
        String trigger = termToString(args[1]);
        
        Situation.Builder builder = Situation.builder(type).trigger(trigger);
        
        // Parse optional arguments based on arity
        if (args.length >= 5) {
            // Form: (Type, Trigger, Current, Target, Result)
            String current = termToStringOrNull(args[2]);
            String target = termToStringOrNull(args[3]);
            if (current != null) builder.currentResource(current);
            if (target != null) builder.targetResource(target);
        }
        
        if (args.length >= 6) {
            // Form: (Type, Trigger, Current, Target, FailedAction, Result)
            String action = termToStringOrNull(args[4]);
            if (action != null) builder.failedAction(action);
        }
        
        if (args.length >= 7) {
            // Form: (Type, Trigger, Current, Target, FailedAction, ErrorInfo, Result)
            parseErrorInfo(args[5], builder);
        }
        
        if (args.length >= 8) {
            // Form: (Type, Trigger, Current, Target, FailedAction, ErrorInfo, Attempted, Result)
            parseAttemptedStrategies(args[6], builder);
        }
        
        return builder.build();
    }
    
    private void parseErrorInfo(Term term, Situation.Builder builder) {
        if (!term.isList()) return;
        
        ListTerm list = (ListTerm) term;
        for (Term item : list) {
            if (item.isStructure()) {
                Structure s = (Structure) item;
                String key = s.getFunctor();
                if (s.getArity() > 0) {
                    String value = termToString(s.getTerm(0));
                    builder.errorInfo(key, value);
                }
            }
        }
    }
    
    private void parseAttemptedStrategies(Term term, Situation.Builder builder) {
        if (!term.isList()) return;
        
        ListTerm list = (ListTerm) term;
        for (Term item : list) {
            builder.attemptedStrategy(termToString(item));
        }
    }
    
    private ListTerm buildResultList(List<StrategyResult> results) {
        ListTerm list = new ListTermImpl();
        ListTerm tail = list;
        
        for (StrategyResult result : results) {
            if (result.isSuggestion()) {
                StrategyResult.Suggestion s = result.asSuggestion();
                
                // Build: suggestion(StrategyId, ActionType, Target, Confidence, Cost, Rationale, Params)
                Structure suggestion = ASSyntax.createStructure("suggestion",
                    ASSyntax.createString(s.getStrategyId()),
                    ASSyntax.createString(s.getActionType()),
                    s.getActionTarget() != null ? 
                        ASSyntax.createString(s.getActionTarget()) : 
                        ASSyntax.createAtom("null"),
                    ASSyntax.createNumber(s.getConfidence()),
                    ASSyntax.createNumber(s.getEstimatedCost()),
                    ASSyntax.createString(s.getRationale() != null ? s.getRationale() : ""),
                    buildParamsMap(s.getActionParams())
                );
                
                tail = tail.append(suggestion);
            }
        }
        
        return list;
    }
    
    private Term buildParamsMap(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new ListTermImpl();
        }
        
        ListTerm list = new ListTermImpl();
        ListTerm tail = list;
        
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Structure pair = ASSyntax.createStructure(entry.getKey(),
                objectToTerm(entry.getValue()));
            tail = tail.append(pair);
        }
        
        return list;
    }
    
    private Term objectToTerm(Object value) {
        if (value == null) {
            return ASSyntax.createAtom("null");
        }
        if (value instanceof Number) {
            return ASSyntax.createNumber(((Number) value).doubleValue());
        }
        if (value instanceof Boolean) {
            return ASSyntax.createAtom(value.toString());
        }
        if (value instanceof List) {
            ListTerm list = new ListTermImpl();
            ListTerm tail = list;
            for (Object item : (List<?>) value) {
                tail = tail.append(objectToTerm(item));
            }
            return list;
        }
        return ASSyntax.createString(value.toString());
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
    
    private String termToStringOrNull(Term term) {
        if (term.isAtom() && "null".equals(((Atom) term).getFunctor())) {
            return null;
        }
        String s = termToString(term);
        return s.isEmpty() ? null : s;
    }
    
    // ========== CCRS Instance Management ==========
    
    private synchronized ContingencyCcrs getContingencyCcrs() {
        if (contingencyCcrs == null) {
            contingencyCcrs = ContingencyCcrs.withDefaults();
            logger.info("[CCRS-Contingency] Initialized with default strategies: " +
                contingencyCcrs.getRegistry().getAll().size());
        }
        return contingencyCcrs;
    }
    
    /**
     * Get or create the JasonCcrsContext for an agent.
     * Contexts are stored in the agent's custom data.
     */
    private JasonCcrsContext getOrCreateContext(TransitionSystem ts) {
        // Try to get existing context from agent's custom properties
        Object existing = ts.getAg().getTS().getSettings().getUserParameter("ccrs_context");
        if (existing instanceof JasonCcrsContext) {
            return (JasonCcrsContext) existing;
        }
        
        // Create new context
        JasonCcrsContext context = new JasonCcrsContext(ts.getAg());
        
        // Note: In a full implementation, we'd store this properly.
        // For POC, we create fresh each time (history won't persist between calls)
        // TODO: Implement proper context storage per agent
        
        return context;
    }
    
    /**
     * Allow external configuration of the ContingencyCcrs instance.
     */
    public static void setContingencyCcrs(ContingencyCcrs ccrs) {
        contingencyCcrs = ccrs;
    }
    
    /**
     * Get the shared ContingencyCcrs instance (for testing/configuration).
     */
    public static ContingencyCcrs getSharedInstance() {
        if (contingencyCcrs == null) {
            contingencyCcrs = ContingencyCcrs.withDefaults();
        }
        return contingencyCcrs;
    }
}
