package ccrs.core. rdf;

import org.apache.jena.rdf.model.*;

import java.io. InputStream;
import java.util. logging.Logger;

/**
 * Loads CCRS vocabulary from RDF files.
 * Supports multiple sources:  classpath resources, file paths, HTTP(S) URLs.
 */
public class CcrsVocabularyLoader {
    
    private static final Logger logger = Logger.getLogger(CcrsVocabularyLoader.class.getName());
    
    /**
     * Load vocabulary from multiple sources.
     * Sources are processed in order; all are merged into a single model.
     * 
     * @param sources Source identifiers (URLs, file paths, or classpath:  prefixed resources)
     * @return Loaded vocabulary
     */
    public static CcrsVocabulary load(String... sources) {
        Model model = ModelFactory.createDefaultModel();
        
        for (String source : sources) {
            try {
                if (source.startsWith("http://") || source.startsWith("https://")) {
                    logger.info("Loading CCRS vocabulary from URL: " + source);
                    model.read(source);
                } else if (source.startsWith("classpath:")) {
                    String resourcePath = source.substring("classpath:".length());
                    InputStream in = CcrsVocabularyLoader. class
                        .getClassLoader()
                        .getResourceAsStream(resourcePath);
                    if (in != null) {
                        logger.info("Loading CCRS vocabulary from classpath: " + resourcePath);
                        model. read(in, null, "TURTLE");
                    } else {
                        logger.warning("Classpath resource not found: " + resourcePath);
                    }
                } else {
                    logger.info("Loading CCRS vocabulary from file: " + source);
                    model.read(source);
                }
            } catch (Exception e) {
                logger.warning("Failed to load vocabulary from " + source + ": " + e.getMessage());
            }
        }
        
        return new CcrsVocabulary(model);
    }
    
    /**
     * Load default built-in vocabulary.
     * 
     * @return Default vocabulary
     */
    public static CcrsVocabulary loadDefault() {
        return load("classpath:ccrs-vocabulary.ttl");
    }
}