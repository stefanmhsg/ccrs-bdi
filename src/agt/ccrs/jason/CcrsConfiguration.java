package ccrs.jason;

import ccrs.core.opportunistic.*;
import ccrs.core.rdf.*;
import jason. asSemantics. Agent;

/**
 * Configuration helper for CCRS agents.
 * Provides convenient methods for configuring CCRS functionality.
 */
public class CcrsConfiguration {
    
    /**
     * Configure agent with vocabulary from multiple sources.
     * 
     * @param agent The agent to configure (must be CcrsAgent)
     * @param vocabularySources Source identifiers for vocabularies
     */
    public static void configure(Agent agent, String... vocabularySources) {
        if (!(agent instanceof CcrsAgent)) {
            throw new IllegalArgumentException("Agent must be a CcrsAgent instance");
        }
        
        CcrsAgent ccrsAgent = (CcrsAgent) agent;
        CcrsVocabulary vocabulary = CcrsVocabularyLoader.load(vocabularySources);
        ccrsAgent.setVocabulary(vocabulary);
    }
    
    /**
     * Configure agent with custom scanner factory.
     * 
     * @param agent The agent to configure (must be CcrsAgent)
     * @param factory The scanner factory to use
     */
    public static void configure(Agent agent, CcrsScannerFactory factory) {
        if (!(agent instanceof CcrsAgent)) {
            throw new IllegalArgumentException("Agent must be a CcrsAgent instance");
        }
        
        CcrsAgent ccrsAgent = (CcrsAgent) agent;
        ccrsAgent.setScannerFactory(factory);
    }
    
    /**
     * Configure agent with custom scanner implementation.
     * 
     * @param agent The agent to configure (must be CcrsAgent)
     * @param scanner The scanner to use
     */
    public static void configure(Agent agent, OpportunisticCcrs scanner) {
        if (!(agent instanceof CcrsAgent)) {
            throw new IllegalArgumentException("Agent must be a CcrsAgent instance");
        }
        
        CcrsAgent ccrsAgent = (CcrsAgent) agent;
        ccrsAgent.setCcrsScanner(scanner);
    }
}