package ccrs.core.contingency;

import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;

/**
 * Base interface for all contingency CCRS strategies.
 * 
 * Strategies encapsulate specific recovery approaches that can be
 * applied when an agent encounters problems. Each strategy:
 * - Has a unique identifier and category
 * - Has an escalation level (lower = less invasive/costly)
 * - Can check if it applies to a given situation
 * - Can produce a recovery suggestion
 * 
 * Strategies should be stateless - all state comes from the
 * Situation and CcrsContext parameters.
 */
public interface CcrsStrategy {
    
    /**
     * Strategy categories based on orientation.
     */
    enum Category {
        /** Uses agent's own internal resources */
        INTERNAL,
        /** Leverages environmental cues */
        ENVIRONMENT,
        /** Involves other agents */
        SOCIAL,
        /** Based on norms and regulations */
        NORM
    }
    
    /**
     * Result of applicability check.
     */
    enum Applicability {
        /** Strategy can definitely help */
        APPLICABLE,
        /** Strategy cannot help with this situation */
        NOT_APPLICABLE,
        /** Cannot determine without full evaluation */
        UNKNOWN
    }
    
    /**
     * Unique identifier for this strategy.
     * Used in logs, traces, and attemptedStrategies tracking.
     */
    String getId();
    
    /**
     * Human-readable name for display.
     */
    String getName();
    
    /**
     * Category of this strategy.
     */
    Category getCategory();
    
    /**
     * Escalation level (0-4).
     * - L1: Low cost (e.g., retry)
     * - L2: Moderate (e.g., backtrack, prediction)
     * - L3: High (e.g., planning)
     * - L4: Social (e.g., consultation)
     * - L0: Last resort (e.g., stop)
     */
    int getEscalationLevel();
    
    /**
     * Quick check if this strategy might apply.
     * Should be fast - avoid heavy computation.
     * 
     * @param situation The current situation
     * @param context Access to agent's knowledge base
     * @return Applicability status
     */
    Applicability appliesTo(Situation situation, CcrsContext context);
    
    /**
     * Evaluate the situation and produce a recovery suggestion.
     * Called only if appliesTo() returned APPLICABLE or UNKNOWN.
     * 
     * @param situation The current situation
     * @param context Access to agent's knowledge base
     * @return A Suggestion or NoHelp result
     */
    StrategyResult evaluate(Situation situation, CcrsContext context);
    
    /**
     * Whether this strategy is currently enabled.
     * Default implementation returns true.
     */
    default boolean isEnabled() {
        return true;
    }
    
    /**
     * Brief description of what this strategy does.
     */
    default String getDescription() {
        return getName() + " (L" + getEscalationLevel() + ")";
    }
}
