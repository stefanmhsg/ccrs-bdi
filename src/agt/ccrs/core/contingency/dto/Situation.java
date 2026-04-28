package ccrs.core.contingency.dto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a situation requiring contingency handling.
 * This is the primary input to contingency CCRS strategies.
 * 
 * Situations can represent failures, stuck states, uncertainty,
 * or proactive checks before taking risky actions.
 */
public class Situation {
    
    /**
     * Type of situation being handled.
     */
    public enum Type {
        /** An action failed with an error */
        FAILURE,
        /** Agent is stuck with no clear path forward */
        STUCK,
        /** Agent faces uncertainty about what to do */
        UNCERTAINTY,
        /** Proactive check before taking an action */
        PROACTIVE
    }
    
    // What kind of situation
    private final Type type;
    private final String trigger;
    
    // Location context
    private final String currentResource;
    private final String targetResource;
    
    // Failure details
    private final String failedAction;
    private final Map<String, Object> errorInfo;
    
    // Extensible metadata
    private final Map<String, Object> metadata;
    
    private Situation(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "Situation type is required");
        this.trigger = builder.trigger;
        this.currentResource = builder.currentResource;
        this.targetResource = builder.targetResource;
        this.failedAction = builder.failedAction;
        this.errorInfo = Collections.unmodifiableMap(new HashMap<>(builder.errorInfo));
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
    }
    
    // Getters
    
    public Type getType() {
        return type;
    }
    
    public String getTrigger() {
        return trigger;
    }
    
    public String getCurrentResource() {
        return currentResource;
    }
    
    public String getTargetResource() {
        return targetResource;
    }
    
    public String getFailedAction() {
        return failedAction;
    }
    
    public Map<String, Object> getErrorInfo() {
        return errorInfo;
    }
    
    public Object getErrorInfo(String key) {
        return errorInfo.get(key);
    }
    
    public String getErrorInfoString(String key) {
        Object value = errorInfo.get(key);
        return value != null ? value.toString() : null;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Situation{type=").append(type);
        if (trigger != null) sb.append(", trigger='").append(trigger).append("'");
        if (currentResource != null) sb.append(", current='").append(currentResource).append("'");
        if (targetResource != null) sb.append(", target='").append(targetResource).append("'");
        if (failedAction != null) sb.append(", action='").append(failedAction).append("'");
        if (!errorInfo.isEmpty()) sb.append(", error=").append(errorInfo);
        sb.append("}");
        return sb.toString();
    }
    
    // Builder
    
    public static Builder builder(Type type) {
        return new Builder(type);
    }
    
    public static Builder failure(String trigger) {
        return new Builder(Type.FAILURE).trigger(trigger);
    }
    
    public static Builder stuck(String trigger) {
        return new Builder(Type.STUCK).trigger(trigger);
    }
    
    public static class Builder {
        private final Type type;
        private String trigger;
        private String currentResource;
        private String targetResource;
        private String failedAction;
        private Map<String, Object> errorInfo = new HashMap<>();
        private Map<String, Object> metadata = new HashMap<>();
        
        private Builder(Type type) {
            this.type = type;
        }
        
        public Builder trigger(String trigger) {
            this.trigger = trigger;
            return this;
        }
        
        public Builder currentResource(String currentResource) {
            this.currentResource = currentResource;
            return this;
        }
        
        public Builder targetResource(String targetResource) {
            this.targetResource = targetResource;
            return this;
        }
        
        public Builder failedAction(String failedAction) {
            this.failedAction = failedAction;
            return this;
        }
        
        public Builder errorInfo(String key, Object value) {
            this.errorInfo.put(key, value);
            return this;
        }
        
        public Builder errorInfo(Map<String, Object> errorInfo) {
            this.errorInfo.putAll(errorInfo);
            return this;
        }
        
        public Builder httpError(int statusCode, String message) {
            this.errorInfo.put("httpStatus", String.valueOf(statusCode));
            this.errorInfo.put("message", message);
            return this;
        }
        
        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public Situation build() {
            return new Situation(this);
        }
    }
}
