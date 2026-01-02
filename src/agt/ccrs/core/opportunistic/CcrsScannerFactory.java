package ccrs.core.opportunistic;

import ccrs.core.rdf.CcrsVocabulary;

/**
 * Factory interface for creating CCRS scanners. 
 * Allows different scanner implementations to be instantiated.
 */
public interface CcrsScannerFactory {
    /**
     * Create a CCRS scanner instance.
     * 
     * @param vocabulary The vocabulary to use, or null for default
     * @return A configured scanner instance
     */
    OpportunisticCcrs createScanner(CcrsVocabulary vocabulary);
}
