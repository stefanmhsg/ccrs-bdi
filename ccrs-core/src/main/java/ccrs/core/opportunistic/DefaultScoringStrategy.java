package ccrs.core.opportunistic;

import org.apache.jena.rdf.model.RDFNode;
import java.util.logging.Logger;

/**
 * Default CCRS scoring strategy implementing the standard behavior:
 * <ul>
 *   <li>Simple patterns: relevance = 1.0 (match is signal)</li>
 *   <li>Structural without relevance variable: relevance = 1.0 (match is signal)</li>
 *   <li>Structural with relevance variable present: relevance = normalize(value)</li>
 *   <li>Structural with relevance variable missing: relevance = 0.0 (signal absent)</li>
 * </ul>
 * 
 * Utility formula: utility = priority × relevance
 * Normalization: saturating normalization x/(x+k) with k=1.0
 */
public class DefaultScoringStrategy implements ScoringStrategy {
    
    private static final Logger logger = Logger.getLogger(DefaultScoringStrategy.class.getName());
    private static final double SATURATION_CONSTANT = 1.0;
    
    @Override
    public double calculateUtility(double priority, RelevanceContext context) {
        double relevance = calculateRelevance(context);
        return priority * relevance;
    }
    
    /**
     * Calculate relevance based on pattern type and runtime values.
     */
    private double calculateRelevance(RelevanceContext context) {
        // Simple patterns: match itself is the signal
        if (context.isSimplePattern()) {
            return 1.0;
        }
        
        // Structural patterns without relevance variable: match itself is the signal
        if (!context.hasRelevanceVariable()) {
            return 1.0;
        }
        
        // Structural patterns with relevance variable:
        // - Value present: normalize it
        // - Value missing: signal is absent
        if (context.rawValue() == null) {
            return 0.0;  // Intended signal is absent
        }
        
        return normalizeValue(context.rawValue());
    }
    
    /**
     * Normalize raw value to [0, 1] range using saturating normalization.
     * Handles String, RDFNode, and numeric types.
     */
    private double normalizeValue(Object rawValue) {
        if (rawValue == null) {
            return 0.0;
        }
        
        // Try RDFNode first (SPARQL results)
        if (rawValue instanceof RDFNode) {
            return normalizeRdfNode((RDFNode) rawValue);
        }
        
        // Try String (fast path bindings)
        if (rawValue instanceof String) {
            return normalizeString((String) rawValue);
        }
        
        // Try direct numeric types
        if (rawValue instanceof Number) {
            return normalizeNumeric(((Number) rawValue).doubleValue());
        }
        
        // Fallback: try string conversion
        logger.warning("Unexpected relevance value type: " + rawValue.getClass().getName() + 
                      ", attempting string conversion");
        return normalizeString(rawValue.toString());
    }
    
    private double normalizeRdfNode(RDFNode node) {
        if (node.isLiteral()) {
            try {
                return normalizeNumeric(node.asLiteral().getDouble());
            } catch (Exception e) {
                // Non-numeric literal - treat as presence indicator
                return 1.0;
            }
        }
        // Resource node - treat as presence indicator
        return 1.0;
    }
    
    private double normalizeString(String value) {
        try {
            return normalizeNumeric(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            // Non-numeric string - treat as presence indicator
            return 1.0;
        }
    }
    
    /**
     * Saturating normalization: relevance = x / (x + k)
     * Maps [0, ∞) → [0, 1) with gradual saturation.
     * 
     * @param val Raw numeric value
     * @return Normalized relevance in [0, 1]
     */
    private double normalizeNumeric(double val) {
        if (val <= 0.0) {
            return 0.0;
        }
        
        return val / (val + SATURATION_CONSTANT);
    }
}
