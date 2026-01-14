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
 * Usage:
 *   ccrs.jacamo.jason.contingency.evaluate(Type, Trigger, Suggestions)
 *   ccrs.jacamo.jason.contingency.evaluate(Type, Trigger, FocusURI, Suggestions)
 * 
 *  - Type: "failure", "stuck", "uncertainty", "proactive"
 *  - Trigger: Best effort term for reason why
 *  - Focus: Current URI
 */
public class evaluate extends DefaultInternalAction {

    private static final Logger logger = Logger.getLogger(evaluate.class.getName());

    private static ContingencyCcrs contingencyCcrs;

    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {

        if (args.length < 3) {
            throw new JasonException(
                "ccrs.contingency.evaluate/3 or /4 expected: " +
                "(Type, Trigger, [Focus], Result)"
            );
        }

        ContingencyCcrs ccrs = getCcrs();
        CcrsContext context = getContext(ts);

        Situation situation = parseSituation(args);
        
        // Track current resource explicitly when Focus parameter is provided
        if (args.length == 4) {
            String focus = JasonRdfAdapter.termToString(args[2]);
            if (!focus.isEmpty() && context instanceof ccrs.jacamo.jason.contingency.JasonCcrsContext) {
                ((ccrs.jacamo.jason.contingency.JasonCcrsContext) context).setCurrentResource(focus);
                logger.log(Level.FINE, "[ContingencyCcrs] Set current resource to: " + focus);
            }
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

        Situation.Builder b =
            Situation.builder(type)
                     .trigger(trigger);

        if (args.length == 4) {
            String focus = JasonRdfAdapter.termToString(args[2]);
            if (!focus.isEmpty()) {
                b.currentResource(focus);
            }
        }

        return b.build();
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
