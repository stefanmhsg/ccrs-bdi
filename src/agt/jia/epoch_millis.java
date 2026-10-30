package jia;

import jason.JasonException;
import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.Term;

/** Records a UTC epoch observation without affecting navigation decisions. */
public class epoch_millis extends DefaultInternalAction {
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        if (args.length != 1) {
            throw new JasonException("jia.epoch_millis expects one output argument.");
        }
        return un.unifies(args[0], new NumberTermImpl(System.currentTimeMillis()));
    }
}
