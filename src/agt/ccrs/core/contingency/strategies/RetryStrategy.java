package ccrs.core.contingency.strategies;

import java.util.HashSet;
import java.util.Set;

import ccrs.core.contingency.CcrsStrategy;
import ccrs.core.contingency.Situation;
import ccrs.core.contingency.StrategyResult;
import ccrs.core.rdf.CcrsContext;

/**
 * L1: Retry Strategy
 * 
 * Handles transient failures by repeating the same action with delay.
 * Applies to HTTP 5xx errors, timeouts, and connection issues.
 */
public class RetryStrategy implements CcrsStrategy {
    
    public static final String ID = "retry";
    
    // Configuration
    private int maxAttempts = 3;
    private long initialDelayMs = 1000;
    private double backoffMultiplier = 2.0;
    private Set<String> retriableCodes = new HashSet<>();
    
    public RetryStrategy() {
        // Default retriable error codes
        retriableCodes.add("500");
        retriableCodes.add("502");
        retriableCodes.add("503");
        retriableCodes.add("504");
        retriableCodes.add("timeout");
        retriableCodes.add("connection_reset");
        retriableCodes.add("connection_refused");
    }
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Retry";
    }
    
    @Override
    public Category getCategory() {
        return Category.INTERNAL;
    }
    
    @Override
    public int getEscalationLevel() {
        return 1;
    }
    
    @Override
    public Applicability appliesTo(Situation situation, CcrsContext context) {
        // Must be a failure situation
        if (situation.getType() != Situation.Type.FAILURE) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Must have a failed action to retry
        if (situation.getFailedAction() == null || situation.getTargetResource() == null) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Check if error is retriable
        String httpStatus = situation.getErrorInfoString("httpStatus");
        String errorType = situation.getErrorInfoString("errorType");
        
        boolean isRetriable = 
            (httpStatus != null && retriableCodes.contains(httpStatus)) ||
            (errorType != null && retriableCodes.contains(errorType));
        
        if (!isRetriable) {
            return Applicability.NOT_APPLICABLE;
        }
        
        // Check if we haven't exceeded max attempts
        int attemptCount = situation.getAttemptCount(ID);
        if (attemptCount >= maxAttempts) {
            return Applicability.NOT_APPLICABLE;
        }
        
        return Applicability.APPLICABLE;
    }
    
    @Override
    public StrategyResult evaluate(Situation situation, CcrsContext context) {
        int attemptCount = situation.getAttemptCount(ID);
        
        // Check max attempts again (defensive)
        if (attemptCount >= maxAttempts) {
            return StrategyResult.noHelp(ID,
                StrategyResult.NoHelpReason.ALREADY_ATTEMPTED,
                String.format("Max retry attempts (%d) exceeded", maxAttempts));
        }
        
        // Calculate delay with exponential backoff
        long delayMs = (long) (initialDelayMs * Math.pow(backoffMultiplier, attemptCount));
        int nextAttempt = attemptCount + 1;
        
        String httpStatus = situation.getErrorInfoString("httpStatus");
        String errorMsg = situation.getErrorInfoString("message");
        
        // Build suggestion
        return StrategyResult.suggest(ID, "retry")
            .target(situation.getTargetResource())
            .param("originalAction", situation.getFailedAction())
            .param("delayMs", delayMs)
            .param("attemptNumber", nextAttempt)
            .param("maxAttempts", maxAttempts)
            .confidence(calculateConfidence(attemptCount, httpStatus))
            .cost(0.1)  // Low cost - just time
            .rationale(buildRationale(httpStatus, errorMsg, nextAttempt, delayMs))
            .build();
    }
    
    private double calculateConfidence(int attemptCount, String httpStatus) {
        // Base confidence depends on error type
        double base = 0.7;
        if ("503".equals(httpStatus)) {
            base = 0.8;  // Service unavailable is very likely transient
        } else if ("500".equals(httpStatus)) {
            base = 0.5;  // Internal error might not be transient
        }
        
        // Decrease confidence with each retry
        return base * Math.pow(0.8, attemptCount);
    }
    
    private String buildRationale(String httpStatus, String errorMsg, int attempt, long delay) {
        StringBuilder sb = new StringBuilder();
        
        if (httpStatus != null) {
            sb.append("HTTP ").append(httpStatus);
            if (errorMsg != null) {
                sb.append(" (").append(errorMsg).append(")");
            }
            sb.append(" is typically transient. ");
        } else {
            sb.append("Transient error detected. ");
        }
        
        sb.append(String.format("Retry attempt %d after %dms delay.", attempt, delay));
        
        return sb.toString();
    }
    
    // Configuration setters
    
    public RetryStrategy maxAttempts(int max) {
        this.maxAttempts = max;
        return this;
    }
    
    public RetryStrategy initialDelay(long delayMs) {
        this.initialDelayMs = delayMs;
        return this;
    }
    
    public RetryStrategy backoffMultiplier(double multiplier) {
        this.backoffMultiplier = multiplier;
        return this;
    }
    
    public RetryStrategy addRetriableCode(String code) {
        this.retriableCodes.add(code);
        return this;
    }
}
