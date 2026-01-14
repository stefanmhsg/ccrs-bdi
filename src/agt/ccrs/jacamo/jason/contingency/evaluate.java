package ccrs.jacamo.jason.contingency;

import ccrs.core.contingency.ContingencyCcrs;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.jacamo.jason.JasonRdfAdapter;
import jason.JasonException;
import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal action bridging AgentSpeak and Contingency-CCRS.
 *
 * Supports multiple signatures for different situation complexity:
 *
 * 1. Basic (3 args): evaluate(Type, Trigger, Result)
 *    - Minimal context for simple situations
 *
 * 2. With focus (4 args): evaluate(Type, Trigger, Focus, Result)
 *    - Adds current location tracking
 *
 * 3. Failure details (8 args): evaluate(Type, Trigger, Current, Target, Action, Error, Attempted, Result)
 *    - Full context for FAILURE situations (RetryStrategy needs this)
 *
 * 4. Map-based (4 args): evaluate(Type, Trigger, ContextMap, Result)
 *    - Flexible field composition using map(key1(val1), key2(val2), ...)
 *    - Supported keys: current, target, action, error, attempted
 *
 * Type: "failure", "stuck", "uncertainty", "proactive"
 * Trigger: Reason/description string
 * Focus/Current: Current resource URI
 * Target: Target resource URI (for failures)
 * Action: Failed action name (for failures)
 * Error: Error code/message (for failures)
 * Attempted: List of already-tried strategies
 *
 * See contingency/README.md for usage examples.
 */
public class evaluate extends DefaultInternalAction {

    private static final Logger logger = Logger.getLogger(evaluate.class.getName());

    private static ContingencyCcrs contingencyCcrs;

    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {

        if (args.length < 3) {
            throw new JasonException(
                "ccrs.contingency.evaluate requires at least 3 args: (Type, Trigger, ..., Result)"
            );
        }

        ContingencyCcrs ccrs = getCcrs();
        CcrsContext context = getContext(ts);

        Situation situation = parseSituation(args);
        
        // Track current resource in context when available
        String currentResource = situation.getCurrentResource();
        if (currentResource != null && !currentResource.isEmpty() 
            && context instanceof ccrs.jacamo.jason.contingency.JasonCcrsContext) {
            ((ccrs.jacamo.jason.contingency.JasonCcrsContext) context).setCurrentResource(currentResource);
            logger.log(Level.FINE, "[ContingencyCcrs] Set current resource to: " + currentResource);
        }

        logger.log(Level.INFO,
            "[ContingencyCcrs] Evaluating situation: " + situation + " with context: " + context.toString());

        List<StrategyResult> results =
            ccrs.evaluate(situation, context);

        logger.log(Level.INFO, "[ContingencyCcrs] Evaluation produced " + results.size() + " results.");
        
        // Inject OpportunisticResult mental notes as ccrs/3 beliefs (B2)
        injectOpportunisticNotes(ts, results);

        ListTerm resultList = buildResultList(results);
        logger.log(Level.INFO, "[ContingencyCcrs] Result list: " + resultList);
        Term out = args[args.length - 1];
        return un.unifies(out, resultList);
    }

    // ------------------------------------------------------------------
    // Situation parsing
    // ------------------------------------------------------------------

    private Situation parseSituation(Term[] args) throws JasonException {
        Situation.Type type = parseType(args[0]);
        String trigger = JasonRdfAdapter.termToString(args[1]);
        Situation.Builder builder = Situation.builder(type).trigger(trigger);

        // Result is always last arg, so content args are [0..length-2]
        int contentArgs = args.length - 1;

        switch (contentArgs) {
            case 2: // evaluate(Type, Trigger, Result)
                // Minimal signature
                break;

            case 3: // evaluate(Type, Trigger, X, Result)
                // Could be Focus string OR ContextMap
                Term arg2 = args[2];
                if (isMap(arg2)) {
                    parseMapIntoBuilder(arg2, builder);
                } else {
                    // Legacy: treat as Focus/Current
                    String focus = JasonRdfAdapter.termToString(arg2);
                    if (!focus.isEmpty()) {
                        builder.currentResource(focus);
                    }
                }
                break;

            case 7: // evaluate(Type, Trigger, Current, Target, Action, Error, Attempted, Result)
                // Full FAILURE signature
                builder.currentResource(parseOptionalString(args[2]));
                builder.targetResource(parseOptionalString(args[3]));
                builder.failedAction(parseOptionalString(args[4]));
                parseErrorInfo(args[5], builder);
                parseAttemptedStrategies(args[6], builder);
                break;

            default:
                throw new JasonException(
                    "Unsupported argument count: " + args.length + 
                    ". Expected 3, 4, or 8 args (Type, Trigger, ..., Result)"
                );
        }

        return builder.build();
    }

    private Situation.Type parseType(Term t) throws JasonException {
        String s = JasonRdfAdapter.termToString(t).toLowerCase();
        return switch (s) {
            case "failure" -> Situation.Type.FAILURE;
            case "stuck" -> Situation.Type.STUCK;
            case "uncertainty" -> Situation.Type.UNCERTAINTY;
            case "proactive" -> Situation.Type.PROACTIVE;
            default -> throw new JasonException("Unknown situation type: " + s);
        };
    }

    private boolean isMap(Term t) {
        // Map structure: map(key1(val1), key2(val2), ...)
        return t.isStructure() && ((Structure) t).getFunctor().equals("map");
    }

    private String parseOptionalString(Term t) {
        if (t == null) return null;
        String s = JasonRdfAdapter.termToString(t);
        return (s.isEmpty() || s.equals("null")) ? null : s;
    }

    private void parseMapIntoBuilder(Term mapTerm, Situation.Builder builder) throws JasonException {
        if (!mapTerm.isStructure()) return;
        Structure map = (Structure) mapTerm;
        
        for (Term entry : map.getTerms()) {
            if (!entry.isStructure()) continue;
            Structure kv = (Structure) entry;
            String key = kv.getFunctor();
            String value = kv.getArity() > 0 ? JasonRdfAdapter.termToString(kv.getTerm(0)) : null;
            
            switch (key) {
                case "current" -> builder.currentResource(value);
                case "target" -> builder.targetResource(value);
                case "action" -> builder.failedAction(value);
                case "error" -> {
                    if (value != null && !value.isEmpty()) {
                        builder.errorInfo("message", value);
                    }
                }
                case "attempted" -> {
                    if (entry.isStructure() && kv.getArity() > 0) {
                        parseAttemptedStrategies(kv.getTerm(0), builder);
                    }
                }
            }
        }
    }

    private void parseErrorInfo(Term t, Situation.Builder builder) {
        if (t == null) return;
        String error = JasonRdfAdapter.termToString(t);
        if (error.isEmpty() || error.equals("null")) return;
        
        // Try to parse as HTTP status code
        if (error.matches("\\d{3}")) {
            builder.httpError(Integer.parseInt(error), "HTTP " + error);
        } else {
            builder.errorInfo("message", error);
        }
    }

    private void parseAttemptedStrategies(Term t, Situation.Builder builder) {
        if (t == null || !t.isList()) return;
        ListTerm list = (ListTerm) t;
        for (Term item : list) {
            String strategy = JasonRdfAdapter.termToString(item);
            if (!strategy.isEmpty()) {
                builder.attemptedStrategy(strategy);
            }
        }
    }

    // ------------------------------------------------------------------
    // Result conversion
    // ------------------------------------------------------------------

    private ListTerm buildResultList(List<StrategyResult> results) {

        ListTerm list = new ListTermImpl();
        ListTerm tail = list;

        for (StrategyResult r : results) {
            if (!r.isSuggestion()) continue;

            StrategyResult.Suggestion s = r.asSuggestion();

            Structure sug = ASSyntax.createStructure(
                "suggestion",
                ASSyntax.createString(s.getStrategyId()),
                ASSyntax.createString(s.getActionType()),
                s.getActionTarget() != null
                    ? ASSyntax.createString(s.getActionTarget())
                    : ASSyntax.createAtom("null"),
                ASSyntax.createNumber(s.getConfidence()),
                ASSyntax.createNumber(s.getEstimatedCost()),
                ASSyntax.createString(
                    s.getRationale() != null ? s.getRationale() : ""
                ),
                buildParams(s.getActionParams())
            );

            tail = tail.append(sug);
        }

        return list;
    }

    private Term buildParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new ListTermImpl();
        }

        ListTerm list = new ListTermImpl();
        ListTerm tail = list;

        for (Map.Entry<String, Object> e : params.entrySet()) {
            Structure pair = ASSyntax.createStructure(
                e.getKey(),
                ASSyntax.createString(String.valueOf(e.getValue()))
            );
            tail = tail.append(pair);
        }

        return list;
    }

    // ------------------------------------------------------------------
    // OpportunisticResult injection (B2)
    // ------------------------------------------------------------------
    
    /**
     * Inject OpportunisticResult mental notes from contingency strategies as ccrs/3 beliefs.
     * These beliefs do NOT have artifact_name annotation, so they persist across perception cycles.
     */
    private void injectOpportunisticNotes(TransitionSystem ts, List<StrategyResult> results) {
        for (StrategyResult r : results) {
            if (!r.isSuggestion()) continue;
            
            StrategyResult.Suggestion s = r.asSuggestion();
            if (!s.hasOpportunisticGuidance()) continue;
            
            for (ccrs.core.opportunistic.OpportunisticResult opp : s.getOpportunisticGuidance()) {
                try {
                    // Use JasonRdfAdapter to create consistent ccrs/3 belief structure
                    // Source "contingency" marks as contingency-generated (no artifact_name = persists)
                    Literal ccrsBelief = JasonRdfAdapter.createCcrsBelief(opp, "contingency");
                    
                    // Add to belief base (no artifact_name annotation = persists)
                    if (ts.getAg().getBB().add(ccrsBelief)) {
                        // Generate +ccrs(...) event for agent plans
                        Trigger te = new Trigger(Trigger.TEOperator.add, 
                            Trigger.TEType.belief, ccrsBelief.copy());
                        ts.getC().addEvent(new jason.asSemantics.Event(te));
                        
                        logger.fine("[ContingencyCcrs] Injected contingency mental note: " + ccrsBelief);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to inject opportunistic note: " + opp, e);
                }
            }
        }
    }
    
    // ------------------------------------------------------------------
    // CCRS wiring
    // ------------------------------------------------------------------

    private synchronized ContingencyCcrs getCcrs() {
        if (contingencyCcrs == null) {
            contingencyCcrs = ContingencyCcrs.withDefaults();
            logger.info("[ContingencyCcrs] Contingency CCRS initialized");
        }
        return contingencyCcrs;
    }

    private CcrsContext getContext(TransitionSystem ts) {
        Object ctx =
            ts.getAg()
              .getTS()
              .getSettings()
              .getUserParameters()
              .get("ccrs_context");

        if (ctx instanceof CcrsContext) {
            return (CcrsContext) ctx;
        }

        throw new IllegalStateException(
            "CCRS context not found for agent: " + ts.getAgArch().getAgName()
        );
    }
}
