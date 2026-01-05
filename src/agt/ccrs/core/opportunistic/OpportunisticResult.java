package ccrs.core.opportunistic;

import java.util.*;

/**
 * Result from opportunistic CCRS scanning. 
 * 
 */
public class OpportunisticResult {
    
    public final String type;      // High-level type (e.g. "signifier", "stigmergy")
    public final String target;    // The actionable target URI or value
    public final String patternId; // The specific pattern definition URI or ID
    public final double utility;   // Calculated utility (0.0 to 1.0+)
    public final Map<String, String> metadata;
    
    /**
     * Create an opportunistic result. 
     * 
     * @param type The CCRS type
     * @param target The target of the opportunity
     * @param patternId The specific pattern ID
     * @param utility The calculated utility
     */
    public OpportunisticResult(String type, String target, String patternId, double utility) {
        this.type = type;
        this.target = target;
        this.patternId = patternId;
        this.utility = utility;
        this.metadata = new HashMap<>();
    }
    
    /**
     * Add metadata to this result.
     * 
     * @param key Metadata key
     * @param value Metadata value
     * @return This result for chaining
     */
    public OpportunisticResult withMetadata(String key, String value) {
        if (key != null && value != null) {
            this.metadata.put(key, value);
        }
        return this;
    }
    
    /**
     * Get metadata value. 
     * 
     * @param key Metadata key
     * @return Value or null if not present
     */
    public Optional<String> getMetadata(String key) {
        return Optional.ofNullable(metadata.get(key));
    }

    /**
     * Get a copy of the metadata map.
     * 
     * @return Copy of metadata map
     */
    public Map<String, String> getMetadataMap() {
        return new HashMap<>(metadata);
    }
    
    @Override
    public String toString() {
        return String.format("OpportunisticResult[target=%s, type=%s, utility=%.2f]", 
            target, type, utility);
    }

}