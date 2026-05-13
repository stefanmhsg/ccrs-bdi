package ccrs.core.opportunistic;

import ccrs.core.rdf.RdfTriple;
import java.util.*;

/**
 * Lightweight Basic Graph Pattern (BGP) Matcher.
 * Executes pre-compiled patterns against RDF triples using pure Java collections.
 * Avoids the overhead of creating Jena Models in the hot path.
 */
public class StructuralPatternMatcher {

    /**
     * A compiled BGP pattern: a list of triple constraints.
     */
    public static class CompiledPattern {
        public final List<TripleConstraint> constraints;

        public CompiledPattern(List<TripleConstraint> constraints) {
            this.constraints = Collections.unmodifiableList(constraints);
        }
    }

    /**
     * A single constraint line (e.g., ?s ?p ?o).
     */
    public static class TripleConstraint {
        public final String s;
        public final String p;
        public final String o;

        public TripleConstraint(String s, String p, String o) {
            this.s = s; this.p = p; this.o = o;
        }

        public boolean hasVariable() {
            return isVar(s) || isVar(p) || isVar(o);
        }

        private boolean isVar(String term) {
            return term.startsWith("?");
        }
    }

    /**
     * Matches the pattern against a collection of triples.
     * @return A list of variable bindings (Maps), one for each successful match.
     */
    public static List<Map<String, String>> match(CompiledPattern pattern, Collection<RdfTriple> inputTriples) {
        if (inputTriples.isEmpty()) return Collections.emptyList();

        // 1. Indexing: Build Subject Index O(N)
        Map<String, List<RdfTriple>> sIndex = new HashMap<>();
        for (RdfTriple t : inputTriples) {
            sIndex.computeIfAbsent(t.subject, k -> new ArrayList<>()).add(t);
        }

        // 2. Matching: Recursive Backtracking
        List<Map<String, String>> results = new ArrayList<>();
        matchRecursive(pattern.constraints, 0, new HashMap<>(), sIndex, results);
        
        return results;
    }

    private static void matchRecursive(
            List<TripleConstraint> constraints, 
            int index, 
            Map<String, String> bindings,
            Map<String, List<RdfTriple>> sIndex,
            List<Map<String, String>> results) {

        // Base Case: All constraints satisfied
        if (index >= constraints.size()) {
            results.add(new HashMap<>(bindings));
            return;
        }

        TripleConstraint constraint = constraints.get(index);

        // Resolve terms based on current bindings
        String reqS = resolve(constraint.s, bindings);
        String reqP = resolve(constraint.p, bindings);
        String reqO = resolve(constraint.o, bindings);

        // Find Candidates
        // Optimization: If Subject is concrete, use index. Otherwise scan all.
        Collection<RdfTriple> candidates = reqS.startsWith("?") 
                ? sIndex.values().stream().flatMap(List::stream).toList()
                : sIndex.getOrDefault(reqS, Collections.emptyList());

        for (RdfTriple triple : candidates) {
            if (matches(reqP, triple.predicate) && matches(reqO, triple.object) && matches(reqS, triple.subject)) {
                
                // Tentatively bind new variables
                Map<String, String> newBindings = new HashMap<>(bindings);
                boolean possible = true;

                if (!tryBind(constraint.s, triple.subject, newBindings)) possible = false;
                if (possible && !tryBind(constraint.p, triple.predicate, newBindings)) possible = false;
                if (possible && !tryBind(constraint.o, triple.object, newBindings)) possible = false;

                if (possible) {
                    matchRecursive(constraints, index + 1, newBindings, sIndex, results);
                }
            }
        }
    }

    private static String resolve(String term, Map<String, String> bindings) {
        return bindings.getOrDefault(term, term);
    }

    private static boolean matches(String patternTerm, String inputTerm) {
        return patternTerm.startsWith("?") || patternTerm.equals(inputTerm);
    }

    private static boolean tryBind(String term, String value, Map<String, String> bindings) {
        if (term.startsWith("?")) {
            if (bindings.containsKey(term)) {
                return bindings.get(term).equals(value); // Must match existing binding
            }
            bindings.put(term, value); // New binding
        }
        return true;
    }
}