package ccrs.core.opportunistic;

/**
 * Strategy for calculating utility scores from CCRS pattern matches.
 * Separates "what is scored" (pattern priorities, relevance variables) 
 * from "how it is scored" (normalization, utility formulas).
 */
public interface ScoringStrategy {
    
    /**
     * Calculate utility score for a matched pattern.
     * 
     * @param priority Pattern priority from vocabulary (in range [-1, 1])
     * @param context Relevance context describing pattern type and runtime values
     * @return Calculated utility score
     */
    double calculateUtility(double priority, RelevanceContext context);
    
    /**
     * Context for relevance calculation capturing pattern metadata.
     * 
     * @param hasRelevanceVariable True if pattern declares a relevance variable by design
     * @param rawValue Raw extracted value (null if missing, even when variable declared)
     * @param isSimplePattern True for simple patterns (predicate/subject/object match)
     */
    record RelevanceContext(
        boolean hasRelevanceVariable,
        Object rawValue,
        boolean isSimplePattern
    ) {
        /**
         * Create context for simple pattern (always relevance = 1.0).
         */
        public static RelevanceContext forSimplePattern() {
            return new RelevanceContext(false, null, true);
        }
        
        /**
         * Create context for structural pattern without relevance variable.
         */
        public static RelevanceContext forStructuralWithoutVariable() {
            return new RelevanceContext(false, null, false);
        }
        
        /**
         * Create context for structural pattern with relevance variable.
         * 
         * @param rawValue Extracted value (may be null if absent at runtime)
         */
        public static RelevanceContext forStructuralWithVariable(Object rawValue) {
            return new RelevanceContext(true, rawValue, false);
        }
    }
}
