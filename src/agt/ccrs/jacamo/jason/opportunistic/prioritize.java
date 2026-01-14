package ccrs.jacamo.jason.opportunistic;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;
import jason.JasonException;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * Internal action for prioritizing hypermedia options based on CCRS utilities.
 * 
 * Usage: ccrs.prioritize(DefaultList, PlainCcrsList, DetailedCcrsList)
 * 
 * Arguments:
 * - DefaultList: Input list of URIs (original order, unmodified)
 * - PlainCcrsList: Re-ordered URIs based on CCRS utilities (no annotations)
 * - DetailedCcrsList: Re-ordered URIs with CCRS annotations (origin, type, utility, etc.)
 * 
 * Algorithm:
 * 1. Finds all beliefs matching ccrs(Target, PatternType, Utility)
 * 2. Matches each URI with corresponding CCRS targets
 * 3. Re-orders list:  highest utility first, unmatched in middle (original order),
 *    negative utility last
 * 4. Returns both plain (backward compatible) and detailed (annotated) versions
 * 
 * Example:
 * +! select_action(Options) :  true
 *   <- ccrs.prioritize(Options, PlainList, DetailedList);
 *      .print("Plain prioritized: ", PlainList);
 *      .print("Detailed: ", DetailedList);
 *      !execute(.nth(0, PlainList, BestOption)).
 * </p>
 */
public class prioritize extends DefaultInternalAction {

    protected transient Logger logger = Logger.getLogger(prioritize.class.getName());
    
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        try {
            // 1. Validate arguments
            if (args.length != 3) {
                throw new JasonException("ccrs.prioritize expects exactly 3 arguments:  " +
                        "prioritize(DefaultList, PlainCcrsList, DetailedCcrsList)");
            }
            
            // 2. Get the input list of URIs
            Term inputListTerm = args[0];
            if (!inputListTerm.isList()) {
                throw new JasonException("First argument must be a list of URIs");
            }
            
            ListTerm inputList = (ListTerm) inputListTerm;

            logger.log(Level.INFO, "[CCRS-Prioritize] Input options: " + inputList);

            // Return early if input list is empty. Even for 1 option it can be useful to know associated utility e.g. to avoid certain options.
            if (inputList.isEmpty()) {
                logger.log(Level.INFO, "[CCRS-Prioritize] Input list has 0 elements, no prioritization needed.");
                // Return same list for both outputs
                return un.unifies(inputList, args[1]) && un.unifies(inputList, args[2]);
            }
            
            // 3. Extract CCRS beliefs from the belief base
            Map<String, Literal> ccrsBeliefs = extractCcrsBeliefs(ts);
            
            // 4. Categorize and prioritize the options
            List<PrioritizedOption> prioritizedOptions = categorizeOptions(inputList, ccrsBeliefs);

            logger.log(Level.INFO, "[CCRS-Prioritize] Categorized options: " + prioritizedOptions.toString());
            
            // 5. Build the plain result list (backward compatible)
            ListTerm plainList = buildPlainList(prioritizedOptions);
            
            // 6. Build the detailed result list (with annotations)
            ListTerm detailedList = buildDetailedList(prioritizedOptions);

            logger.log(Level.FINE, "[CCRS-Prioritize] Plain list (output): " + plainList);
            logger.log(Level.FINE, "[CCRS-Prioritize] Detailed list (output): " + detailedList);
            
            // 7. Unify with the output parameters
            return un.unifies(plainList, args[1]) && un.unifies(detailedList, args[2]);
            
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new JasonException("ccrs.prioritize requires 3 arguments");
        } catch (ClassCastException e) {
            throw new JasonException("Invalid argument types for ccrs.prioritize");
        } catch (Exception e) {
            throw new JasonException("Error in ccrs.prioritize: " + e.getMessage());
        }
    }
    
    /**
     * Extracts all ccrs/3 beliefs from the agent's belief base and builds a map
     * of Target -> Literal (complete belief with annotations).
     * If multiple beliefs exist for the same target, keeps the one with highest utility.
     */
    private Map<String, Literal> extractCcrsBeliefs(TransitionSystem ts) {
        Map<String, Literal> beliefMap = new HashMap<>();
        
        try {
            // Create a PredicateIndicator for ccrs/3
            PredicateIndicator ccrsPI = new PredicateIndicator("ccrs", 3);
            
            // Get all candidate beliefs matching ccrs/3
            Iterator<Literal> beliefs = ts.getAg().getBB().getCandidateBeliefs(ccrsPI);
            
            if (beliefs == null) {
                return beliefMap;
            }
            
            while (beliefs.hasNext()) {
                Literal belief = beliefs.next();
                
                try {
                    // Extract the three terms:  Target, PatternType, Utility
                    // ccrs(Target, PatternType, Utility)[annotations...]
                    if (belief.getArity() == 3) {
                        Term targetTerm = belief.getTerm(0);
                        Term utilityTerm = belief.getTerm(2);
                        
                        // Get the target as a string (URI)
                        String target = extractUri(targetTerm);
                        
                        // Validate utility is numeric
                        if (utilityTerm.isNumeric()) {
                            double utility = ((NumberTerm) utilityTerm).solve();
                            
                            // If multiple beliefs exist for the same target, keep the highest utility
                            // TODO: Smarter policy for handling duplicates? if conflicting utilities (positive vs negative) are present. E.g. favour most recent?
                            Literal existing = beliefMap.get(target);
                            if (existing == null) {
                                beliefMap.put(target, belief);
                            } else {
                                // Compare utilities and keep the higher one
                                double existingUtility = ((NumberTerm) existing.getTerm(2)).solve();
                                if (utility > existingUtility) {
                                    beliefMap.put(target, belief);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed beliefs
                    continue;
                }
            }
        } catch (Exception e) {
            // If there's any error accessing the belief base, return empty map
            return beliefMap;
        }
        
        return beliefMap;
    }

    
    /**
     * Extracts a URI string from a Term, handling different representations.
     */
    private String extractUri(Term term) {
        if (term.isString()) {
            return ((StringTerm) term).getString();
        } else if (term.isAtom()) {
            return ((Atom) term).getFunctor();
        } else {
            return term.toString();
        }
    }
    
    /**
     * Categorizes options into three groups based on utility values and sorts them.
     * Now preserves the complete belief literal for access to annotations and all terms.
     */
    private List<PrioritizedOption> categorizeOptions(ListTerm inputList, 
                                                       Map<String, Literal> ccrsBeliefs) {
        List<PrioritizedOption> positive = new ArrayList<>();
        List<PrioritizedOption> unmatched = new ArrayList<>();
        List<PrioritizedOption> negative = new ArrayList<>();
        
        int originalIndex = 0;
        
        // Iterate through input options
        for (Term option : inputList) {
            String uri = extractUri(option);
            Literal belief = ccrsBeliefs.get(uri);
            
            Double utility = null;
            if (belief != null) {
                try {
                    // Extract utility from the belief (3rd term)
                    Term utilityTerm = belief.getTerm(2);
                    if (utilityTerm.isNumeric()) {
                        utility = ((NumberTerm) utilityTerm).solve();
                    }
                } catch (Exception e) {
                    // If we can't extract utility, treat as unmatched
                    utility = null;
                }
            }
            
            PrioritizedOption po = new PrioritizedOption(option, uri, utility, originalIndex++, belief);
            
            if (utility == null) {
                unmatched.add(po);
            } else if (utility >= 0) {
                positive.add(po);
            } else {
                negative.add(po);
            }
        }
        
        // Sort positive utilities in descending order (highest first)
        positive.sort((a, b) -> Double.compare(b.utility, a.utility));
        
        // Keep unmatched in original order (already in order by originalIndex)
        
        // Sort negative utilities in ascending order (least negative first, most negative last)
        negative.sort((a, b) -> Double.compare(b.utility, a.utility));
        
        // Combine all three lists
        List<PrioritizedOption> result = new ArrayList<>();
        result.addAll(positive);
        result.addAll(unmatched);
        result.addAll(negative);
        
        return result;
    }
    
    /**
     * Builds a plain Jason ListTerm from the prioritized options (no annotations).
     * Backward compatible with previous behavior.
     */
    private ListTerm buildPlainList(List<PrioritizedOption> options) {
        ListTerm result = new ListTermImpl();
        
        for (PrioritizedOption po : options) {
            result.add(po.term);
        }
        
        return result;
    }
    
    /**
     * Builds a detailed Jason ListTerm from the prioritized options.
     * Enriches each term with annotations from ccrs/3 beliefs:
     * - origin(), type(), pattern_id(), utility(), source(), original_index(), strategy()
     * Unmatched options get fallback values: utility=0, others="null"
     */
    private ListTerm buildDetailedList(List<PrioritizedOption> options) {
        ListTerm result = new ListTermImpl();
        
        for (PrioritizedOption po : options) {
            Term enriched = po.term;
            
            // Enrich matched options with CCRS annotations
            if (po.ccrsBelief != null) {
                try {
                    enriched = enrichWithAnnotations(po);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to enrich option: " + po.uri, e);
                    // Fallback: use original term
                    enriched = po.term;
                }
            } else {
                // Enrich unmatched options with fallback values
                try {
                    enriched = enrichWithFallbackValues(po);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to enrich unmatched option: " + po.uri, e);
                    // Fallback: use original term
                    enriched = po.term;
                }
            }
            
            result.add(enriched);
        }
        
        return result;
    }
    
    /**
     * Enriches a term with annotations from its ccrs belief.
     * Wraps the URI in a uri() structure to preserve the URI value while adding annotations.
     * Always adds: origin, type, pattern_id, utility, source, original_index
     * Conditionally adds: strategy (only for contingency-ccrs)
     */
    private Term enrichWithAnnotations(PrioritizedOption po) throws Exception {
        // Wrap URI in a structure so annotations don't corrupt the URI value
        // uri("http://...")[annotations] instead of "http://..."[annotations]
        Literal enriched = ASSyntax.createLiteral("uri", po.term);
        
        // Always add original_index
        enriched.addAnnot(ASSyntax.createStructure("original_index", 
            ASSyntax.createNumber(po.originalIndex)));
        
        // Extract and add annotations from ccrs belief
        Literal belief = po.ccrsBelief;
        
        // type() - from belief term(1)
        Term typeTerm = belief.getTerm(1);
        if (typeTerm != null) {
            enriched.addAnnot(ASSyntax.createStructure("type", typeTerm));
        }
        
        // utility() - from belief term(2)
        Term utilityTerm = belief.getTerm(2);
        if (utilityTerm != null && utilityTerm.isNumeric()) {
            enriched.addAnnot(ASSyntax.createStructure("utility", utilityTerm));
        }
        
        // origin() - always present according to user
        copyAnnotation(belief, enriched, "origin");
        
        // pattern_id() - always present
        copyAnnotation(belief, enriched, "pattern_id");
        
        // source() - always present
        copyAnnotation(belief, enriched, "source");
        
        // strategy() - add with null placeholder if not present (for stable result list)
        Term strategyAnnot = belief.getAnnot("strategy");
        if (strategyAnnot != null) {
            enriched.addAnnot(strategyAnnot);
        } else {
            // Add null placeholder for opportunistic-ccrs beliefs
            enriched.addAnnot(ASSyntax.createStructure("strategy", new Atom("null")));
        }
        
        return enriched;
    }
    
    /**
     * Safely copies an annotation from source belief to target term.
     */
    private void copyAnnotation(Literal source, Literal target, String annotKey) {
        Term annot = source.getAnnot(annotKey);
        if (annot != null) {
            target.addAnnot(annot);
        }
    }
    
    /**
     * Enriches an unmatched term with fallback annotation values.
     * Sets utility=0 and uses "null" placeholders for all other fields.
     */
    private Term enrichWithFallbackValues(PrioritizedOption po) throws Exception {
        // Wrap URI in a structure so annotations don't corrupt the URI value
        Literal enriched = ASSyntax.createLiteral("uri", po.term);
        
        // Always add original_index
        enriched.addAnnot(ASSyntax.createStructure("original_index", 
            ASSyntax.createNumber(po.originalIndex)));
        
        // Add fallback values for unmatched options
        enriched.addAnnot(ASSyntax.createStructure("utility", 
            ASSyntax.createNumber(0)));
        
        enriched.addAnnot(ASSyntax.createStructure("origin", 
            new Atom("null")));
        
        enriched.addAnnot(ASSyntax.createStructure("type", 
            new Atom("null")));
        
        enriched.addAnnot(ASSyntax.createStructure("pattern_id", 
            new Atom("null")));
        
        enriched.addAnnot(ASSyntax.createStructure("source", 
            new Atom("null")));
        
        enriched.addAnnot(ASSyntax.createStructure("strategy", 
            new Atom("null")));
        
        return enriched;
    }
    
    /**
     * Helper class to hold option data during prioritization.
     * Now includes the complete belief literal for access to annotations and all terms.
     */
    private static class PrioritizedOption {
        Term term;
        String uri;
        Double utility;  // null if no match
        int originalIndex;
        Literal ccrsBelief;  // Complete belief with annotations (null if no match)
        
        PrioritizedOption(Term term, String uri, Double utility, int originalIndex, Literal ccrsBelief) {
            this.term = term;
            this.uri = uri;
            this.utility = utility;
            this.originalIndex = originalIndex;
            this.ccrsBelief = ccrsBelief;
        }

        @Override
        public String toString() {
            if (ccrsBelief == null) {
                return "PrioritizedOption{" +
                        "term=" + term +
                        ", uri='" + uri + '\'' +
                        ", utility=null" +
                        ", originalIndex=" + originalIndex +
                        ", ccrsBelief=null (unmatched)" +
                        '}';
            }
            return "PrioritizedOption{" +
                    "term=" + term +
                    ", uri='" + uri + '\'' +
                    ", utility=" + utility +
                    ", originalIndex=" + originalIndex +
                    ", ccrsBelief=" + ccrsBelief +
                    '}';
        }
    }
}