package ccrs.core.contingency;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Record of an action taken by the agent.
 * Used for history tracking in CcrsContext.
 */
public class ActionRecord {
    
    /**
     * Outcome of an action.
     */
    public enum Outcome {
        SUCCESS,
        FAILED,
        PENDING,
        UNKNOWN
    }
    
    private final Instant timestamp;
    private final String actionType;
    private final String target;
    private final Outcome outcome;
    private final Map<String, Object> details;
    
    public ActionRecord(String actionType, String target, Outcome outcome) {
        this(Instant.now(), actionType, target, outcome, Collections.emptyMap());
    }
    
    public ActionRecord(Instant timestamp, String actionType, String target, 
                        Outcome outcome, Map<String, Object> details) {
        this.timestamp = timestamp;
        this.actionType = actionType;
        this.target = target;
        this.outcome = outcome;
        this.details = Collections.unmodifiableMap(new HashMap<>(details));
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public String getActionType() {
        return actionType;
    }
    
    public String getTarget() {
        return target;
    }
    
    public Outcome getOutcome() {
        return outcome;
    }
    
    public Map<String, Object> getDetails() {
        return details;
    }
    
    public Object getDetail(String key) {
        return details.get(key);
    }
    
    @Override
    public String toString() {
        return String.format("Action{%s %s -> %s at %s}", 
            actionType, target, outcome, timestamp);
    }
    
    // Builder for convenience
    
    public static Builder builder(String actionType, String target) {
        return new Builder(actionType, target);
    }
    
    public static class Builder {
        private Instant timestamp = Instant.now();
        private final String actionType;
        private final String target;
        private Outcome outcome = Outcome.UNKNOWN;
        private Map<String, Object> details = new HashMap<>();
        
        private Builder(String actionType, String target) {
            this.actionType = actionType;
            this.target = target;
        }
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder outcome(Outcome outcome) {
            this.outcome = outcome;
            return this;
        }
        
        public Builder success() {
            this.outcome = Outcome.SUCCESS;
            return this;
        }
        
        public Builder failed() {
            this.outcome = Outcome.FAILED;
            return this;
        }
        
        public Builder detail(String key, Object value) {
            this.details.put(key, value);
            return this;
        }
        
        public ActionRecord build() {
            return new ActionRecord(timestamp, actionType, target, outcome, details);
        }
    }
}
