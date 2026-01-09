package ccrs.jason.contingency;

import ccrs.core.contingency.ContingencyCcrs;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.jason.JasonRdfAdapter;
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
 *   ccrs.contingency.evaluate(Type, Trigger, Suggestions)
 *   ccrs.contingency.evaluate(Type, Trigger, FocusURI, Suggestions)
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

        logger.log(Level.INFO,
            "[CCRS] Evaluating situation: " + situation);

        List<StrategyResult> results =
            ccrs.evaluate(situation, context);

        ListTerm resultList = buildResultList(results);

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
    // CCRS wiring
    // ------------------------------------------------------------------

    private synchronized ContingencyCcrs getCcrs() {
        if (contingencyCcrs == null) {
            contingencyCcrs = ContingencyCcrs.withDefaults();
            logger.info("[CCRS] Contingency CCRS initialized");
        }
        return contingencyCcrs;
    }

    private CcrsContext getContext(TransitionSystem ts) {
        Object ctx =
            ts.getAg()
              .getTS()
              .getSettings()
              .getUserParameter("ccrs_context");

        if (ctx instanceof CcrsContext) {
            return (CcrsContext) ctx;
        }
        
        throw new IllegalStateException(
            "CCRS context not found for agent: " + ts.getAgArch().getAgName()
        );
    }
}
