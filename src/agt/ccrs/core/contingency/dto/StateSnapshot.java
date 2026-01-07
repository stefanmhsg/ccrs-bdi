package ccrs.core.contingency.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight snapshot of agent state at a point in time.
 * Used for history tracking and backtracking support.
 */
public class StateSnapshot {
    
    private final Instant timestamp;
    private final String resource;
    private final Map<String, Object> summary;
    
    public StateSnapshot(String resource) {
        this(Instant.now(), resource, Collections.emptyMap());
    }
    
    public StateSnapshot(Instant timestamp, String resource, Map<String, Object> summary) {
        this.timestamp = timestamp;
        this.resource = resource;
        this.summary = Collections.unmodifiableMap(new HashMap<>(summary));
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public String getResource() {
        return resource;
    }
    
    public Map<String, Object> getSummary() {
        return summary;
    }
    
    public Object getSummary(String key) {
        return summary.get(key);
    }
    
    @Override
    public String toString() {
        return String.format("State{resource=%s, at=%s}", resource, timestamp);
    }
    
    // Builder
    
    public static Builder builder(String resource) {
        return new Builder(resource);
    }
    
    public static class Builder {
        private Instant timestamp = Instant.now();
        private final String resource;
        private Map<String, Object> summary = new HashMap<>();
        
        private Builder(String resource) {
            this.resource = resource;
        }
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder summary(String key, Object value) {
            this.summary.put(key, value);
            return this;
        }
        
        public Builder summary(Map<String, Object> summary) {
            this.summary.putAll(summary);
            return this;
        }
        
        public StateSnapshot build() {
            return new StateSnapshot(timestamp, resource, summary);
        }
    }
}
