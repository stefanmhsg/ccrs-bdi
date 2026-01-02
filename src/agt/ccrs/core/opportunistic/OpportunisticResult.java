package ccrs.core.opportunistic;

import java.util.*;

/**
 * Result from opportunistic CCRS scanning. 
 * 
 */
public class OpportunisticResult {
    
    public final String type;
    public final String subject;
    public final String value;
    public final Map<String, String> metadata;
    
    /**
     * Create an opportunistic result. 
     * 
     * @param type The CCRS type (discovered from vocabulary, e.g., "signifier", "stigmergy")
     * @param subject The subject of the opportunity
     * @param value The detected value
     */
    public OpportunisticResult(String type, String subject, String value) {
        this.type = type;
        this.subject = subject;
        this.value = value;
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
        return String.format("OpportunisticResult[type=%s, subject=%s, value=%s]",
            type, subject, value);
    }

}