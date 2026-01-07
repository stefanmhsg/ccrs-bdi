package ccrs.core.contingency.strategies.internal;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.Situation;
import ccrs.core.contingency.StrategyResult;
import ccrs.core.rdf.CcrsContext;

/**
 * L0: Stop Strategy (Last Resort)
 * 
 * Gracefully fails when no recovery is possible.
 * This is the fallback when all other strategies have been exhausted.
 */
public class StopStrategy implements CcrsStrategy {
    
    public static final String ID = "stop";
    
    // Configuration
    private boolean requireExhaustion = true;
    private int exhaustionThreshold = 2;  // Min strategies attempted before suggesting stop
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Stop (Last Resort)";
    }
    
    @Override
    public Category getCategory() {
        return Category.INTERNAL;
    }
    
    @Override
    public int getEscalationLevel() {
        return 0;  // Special level - evaluated last
    }
    
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        // Stop is always applicable as last resort
        // But we might want to require other strategies tried first
        
        if (requireExhaustion) {
            int attemptedCount = situation.getAttemptedStrategies().size();
            if (attemptedCount < exhaustionThreshold) {
                return Applicability.NOT_APPLICABLE;
            }
        }
        
        return Applicability.APPLICABLE;
    }
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        int attemptedCount = situation.getAttemptedStrategies().size();
        
        // Check threshold if required
        if (requireExhaustion && attemptedCount < exhaustionThreshold) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.NOT_APPLICABLE,
                String.format("Only %d strategies attempted, threshold is %d", 
                    attemptedCount, exhaustionThreshold));
        }
        
        String finalError = buildFinalError(situation);
        
        return StrategyResult.suggest(ID, "stop")
            .target(null)  // No target - we're stopping
            .param("reason", determineReason(situation, attemptedCount))
            .param("attemptedCount", attemptedCount)
            .param("attemptedStrategies", situation.getAttemptedStrategies())
            .param("finalError", finalError)
            .param("situationType", situation.getType().name())
            .confidence(1.0)  // Certain this is appropriate given exhaustion
            .cost(1.0)        // Maximum cost - goal failure
            .rationale(buildRationale(situation, attemptedCount, finalError))
            .build();
    }
    
    private String determineReason(Situation situation, int attemptedCount) {
        if (attemptedCount >= exhaustionThreshold) {
            return "exhausted";
        }
        
        String httpStatus = situation.getErrorInfoString("httpStatus");
        if ("410".equals(httpStatus)) {
            return "resource_gone";
        }
        if ("401".equals(httpStatus) || "403".equals(httpStatus)) {
            return "access_denied";
        }
        
        return "unrecoverable";
    }
    
    private String buildFinalError(Situation situation) {
        StringBuilder sb = new StringBuilder();
        
        if (situation.getFailedAction() != null) {
            sb.append("Failed action: ").append(situation.getFailedAction());
            if (situation.getTargetResource() != null) {
                sb.append(" on ").append(situation.getTargetResource());
            }
            sb.append(". ");
        }
        
        String httpStatus = situation.getErrorInfoString("httpStatus");
        String message = situation.getErrorInfoString("message");
        
        if (httpStatus != null) {
            sb.append("HTTP ").append(httpStatus);
            if (message != null) {
                sb.append(": ").append(message);
            }
        } else if (message != null) {
            sb.append("Error: ").append(message);
        } else if (situation.getTrigger() != null) {
            sb.append("Trigger: ").append(situation.getTrigger());
        }
        
        return sb.length() > 0 ? sb.toString() : "Unknown error";
    }
    
    private String buildRationale(Situation situation, int attemptedCount, String finalError) {
        StringBuilder sb = new StringBuilder();
        
        if (attemptedCount > 0) {
            sb.append("All ").append(attemptedCount)
              .append(" recovery strategies exhausted. ");
        } else {
            sb.append("No recovery options available. ");
        }
        
        sb.append(finalError);
        sb.append(" Recommend graceful failure.");
        
        return sb.toString();
    }
    
    @Override
    public String getDescription() {
        return "Last resort - graceful goal abandonment when recovery is impossible";
    }
    
    // Configuration
    
    public StopStrategy requireExhaustion(boolean require) {
        this.requireExhaustion = require;
        return this;
    }
    
    public StopStrategy exhaustionThreshold(int threshold) {
        this.exhaustionThreshold = threshold;
        return this;
    }
    
    /**
     * Create a stop strategy that applies immediately (no exhaustion required).
     */
    public static StopStrategy immediate() {
        return new StopStrategy().requireExhaustion(false);
    }
}
