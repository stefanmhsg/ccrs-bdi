package jia;

import jason.*;
import jason.asSemantics.*;
import jason.asSyntax.*;

import java.util.List;
import java.util.Random;

// Lowercase class name to match Jason internal action naming convention
public class pick extends DefaultInternalAction {

    private final Random rng = new Random();

    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {

        // Expect 2 arguments: a list and a variable
        if (args.length != 2) {
            throw new JasonException("rnd.pick expects 2 arguments: List and Var.");
        }

        // Argument 1 must be a list
        if (!(args[0] instanceof ListTerm)) {
            throw new JasonException("rnd.pick first argument must be a list.");
        }

        ListTerm list = (ListTerm) args[0];
        List<Term> elems = list.getAsList();

        if (elems.isEmpty()) {
            return false;   // no choice possible
        }

        // pick index
        int idx = rng.nextInt(elems.size());
        Term chosen = elems.get(idx);

        // unify with the output variable
        return un.unifies(chosen, args[1]);

        // Alternative: return the index of the chosen element
        //return un.unifies(new NumberTermImpl(idx), args[1]);

    }
}
