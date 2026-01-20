package ccrs.jacamo.jaca;

import cartago.ArtifactId;
import cartago.ArtifactObsProperty;
import ccrs.capabilities.ConfigResolver;
import ccrs.core.opportunistic.*;
import ccrs.core.rdf.*;
import ccrs.jacamo.jason.JasonRdfAdapter;
import ccrs.jacamo.jason.contingency.JasonCcrsContext;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import jaca.CAgentArch;
import jason.JasonException;
import jason.asSemantics.Intention;
import jason.asSyntax.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * CArtAgO architecture extension with opportunistic CCRS for artifact observables.
 * Intercepts observable properties and scans them for CCRS patterns.
 * Supports batch processing for structural patterns.
 */
public class CcrsAgentArch extends CAgentArch {
    
    private static final Logger logger = Logger.getLogger(CcrsAgentArch.class.getName());

    static {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        ConfigResolver.enableDotenvFallback(() ->
            dotenv.entries().stream()
                .collect(Collectors.toMap(
                    DotenvEntry::getKey,
                    DotenvEntry::getValue
                ))
        );
    }

    private OpportunisticCcrs ccrsScanner;
    private CcrsVocabulary vocabulary;
    
    // Buffers RDF triples arriving in the current perception cycle
    // Key: "ArtifactName:SourceURI" (or just ArtifactName)
    private final Map<String, List<RdfTriple>> perceptionBatch = new ConcurrentHashMap<>();
    private final Map<String, ArtifactId> sourceMap = new ConcurrentHashMap<>();

    // Flag to ensure we only register the flush callback once per cycle
    private boolean flushScheduled = false;
    
    public CcrsAgentArch() {
        super();
        this.vocabulary = CcrsVocabularyLoader.loadDefault();
        this.ccrsScanner = new VocabularyMatcher.Factory().createScanner(vocabulary);
    }
    
    /**
     * Initialize the CCRS Context immediately upon Agent startup.
     * This ensures the context is available before any artifact interaction occurs.
     */
    @Override
    public void init() throws Exception {
        super.init();

        // Initialize the Context. 
        // Note: The context automatically connects to the CcrsGlobalRegistry.SHARED_LOG
        JasonCcrsContext context = new JasonCcrsContext(getTS().getAg());
        
        // Store it in Agent Settings so 'evaluate' can find it later
        getTS().getSettings().addOption("ccrs_context", context);
        
        logger.info("[CcrsAgentArch] Initialized CCRS Context linked to Shared Interaction Log.");
    }

    /**
     * Set custom vocabulary.
     * 
     * @param vocabulary The vocabulary to use
     */
    public void setVocabulary(CcrsVocabulary vocabulary) {
        this.vocabulary = vocabulary;
        this.ccrsScanner = new VocabularyMatcher.Factory().createScanner(vocabulary);
    }
    
    /**
     * Set custom scanner. 
     * 
     * @param scanner The scanner to use
     */
    public void setCcrsScanner(OpportunisticCcrs scanner) {
        this.ccrsScanner = scanner;
    }
    
    /**
     * Override to intercept observable properties from artifacts.
     * We let super.addObsPropertiesBel handle the actual addition to the Belief Base 
     * to avoid visibility issues with private methods in CAgentArch.
     * CCRS operates on extracted RDF data flowing through.
     */
    @Override
    public void addObsPropertiesBel(ArtifactId source, ArtifactObsProperty prop, Atom nsp) {

        logger.log(Level.FINE, "Received observable from artifact " + source.getName() + ": " + prop);

        // 1. Standard CArtAgO behavior (adding belief, handling focus events, etc.)
        super.addObsPropertiesBel(source, prop, nsp);

        // 2. Perform Opportunistic-CCRS Scanning
        try {
            // Convert to a temporary Literal to inspect structure
            // We cannot use obsPropToLiteral because JaCaLiteral is package-private
            Literal proxyLiteral = convertToProxyLiteral(prop, nsp);

            // CCRS scanning:  Check if this is an RDF observable
            if (isRdfObservable(proxyLiteral)) {
                bufferForCcrs(proxyLiteral, source);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Error scanning observable property for Opportunistic-CCRS:  " + prop, ex);
        }
    }

    /**
     * Helper to verify if an observable is an RDF triple suitable for scanning.
     * This mimics the private logic in CAgentArch but is simplified for our read-only needs.
     */
    private Literal convertToProxyLiteral(ArtifactObsProperty prop, Atom nsp) {
        try {
            // Reconstruct the literal structure roughly as CArtAgO does it
            // We don't need the annotations or source tracking here, just the S-P-O structure
            LiteralImpl l = new LiteralImpl(nsp, Literal.LPos, prop.getName());
            for (Object obj : prop.getValues()) {
                l.addTerm(getJavaLib().objectToTerm(obj)); // getJavaLib() is protected in CAgentArch
            }
            
            // We specifically look for the 'source' annotation which carries the semantic origin
            if (prop.getAnnots() != null) {
                for (Object o : prop.getAnnots()) {
                    Term annot = getJavaLib().objectToTerm(o);
                    l.addAnnot(annot);
                }
            }
            
            return l;
        } catch (Exception e) {
            return null; // Conversion failed, ignore this property
        }
    }

    /**
     * Check if observable is in RDF format (rdf/3 functor).
     */
    private boolean isRdfObservable(Literal lit) {
        return lit != null && "rdf".equals(lit.getFunctor()) && lit.getArity() == 3;
    }
    
    /**
     * Buffer RDF observable for batch CCRS scanning and schedules a flush at the start of the next agent cycle.
     * @throws JasonException 
     */
    private void bufferForCcrs(Literal rdfLiteral, ArtifactId sourceArtifact) throws JasonException {
        RdfTriple triple = JasonRdfAdapter.toRdfTriple(rdfLiteral);
        if (triple == null) return;
        
        // Determine the "Logical Source" (e.g. the URL of the resource, not just the artifact name)
        String logicalSource = extractLogicalSource(rdfLiteral, sourceArtifact);

        synchronized (perceptionBatch) {
            pendingBatches.computeIfAbsent(logicalSource, k -> new ArrayList<>()).add(triple);
            sourceMap.put(logicalSource, sourceArtifact);
            
            if (!flushScheduled) {
                scheduleBatchFlush();
                flushScheduled = true;
            }
        }
        
    }
    
    /**
     * Uses Jason's infrastructure to run this logic *after* all percepts 
     * for the current cycle have been received but *before* the agent reasons.
     * @throws JasonException 
     */
    private void scheduleBatchFlush() throws JasonException {
        try {
            getTS().runAtBeginOfNextCycle(() -> {
                try {
                    flushBatches();
                } catch (JasonException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            // Fallback for safety
            flushBatches();
        }
    }

    private void flushBatches() throws JasonException {
        Map<String, List<RdfTriple>> batchesToProcess;
        
        synchronized (perceptionBatch) {
            // Swap buffers to minimize locking time
            batchesToProcess = new HashMap<>(perceptionBatch);
            perceptionBatch.clear();
            flushScheduled = false;
        }

        logger.fine("Abolishing previous opportunistic CCRS beliefs before processing new batches.");

        // Remove ONLY ccrs/3 beliefs WITH origin(opportunistic-ccrs) annotation (from opportunistic scanning)
        // Preserve ccrs/3 beliefs WITHOUT origin or with origin(contingency) (from contingency-CCRS strategies)
        Iterator<Literal> it = getTS().getAg().getBB().getCandidateBeliefs(
            new PredicateIndicator("ccrs", 3));
        
        if (it != null) {
            List<Literal> toRemove = new ArrayList<>();
            while (it.hasNext()) {
                Literal ccrs = it.next();
                // Check if it has origin(opportunistic-ccrs) annotation
                Term originAnnot = ccrs.getAnnot("origin");
                if (originAnnot != null && originAnnot.isStructure()) {
                    Structure s = (Structure) originAnnot;
                    if (s.getArity() > 0) {
                        String originValue = s.getTerm(0).toString().replace("\"", "");
                        if ("opportunistic-ccrs".equals(originValue)) {
                            toRemove.add(ccrs);
                        }
                    }
                }
            }
            
            for (Literal l : toRemove) {
                getTS().getAg().getBB().remove(l);
            }
        }
        
        logger.fine("Processing new batch of properties.");
        
        if (batchesToProcess.isEmpty()) return;

        // Process each batch (Grouped by logical source)
        for (Map.Entry<String, List<RdfTriple>> entry : batchesToProcess.entrySet()) {
            String sourceKey = entry.getKey();
            List<RdfTriple> triples = entry.getValue();
            ArtifactId artifactId = sourceMap.get(sourceKey);
            
            processSingleBatch(triples, sourceKey, artifactId);
        }
        
        // Clean up source map
        sourceMap.keySet().retainAll(perceptionBatch.keySet());
    }

    /**
    * Process a single batch of RDF triples from the same logical source.
     */
    private void processSingleBatch(List<RdfTriple> triples, String sourceKey, ArtifactId artifactId) throws JasonException {
        if (ccrsScanner == null) return;

        // context gets passed to the scanner and results in OpportunisticResult metadata map. Metadata here gets converted to Belief annotations 1:1.
        Map<String, Object> context = new HashMap<>();
        context.put("source", sourceKey);
        context.put("artifact_name", artifactId.getName());
        context.put("workspace", artifactId.getWorkspaceId().getName());
        context.put("origin", "opportunistic-ccrs");

        List<OpportunisticResult> results = ccrsScanner.scanAll(triples, context);

        for (OpportunisticResult r : results) {
            // Create the CCRS belief: ccrs(Target, PatternType, Utility)[source(Source), metadata(Key, Value), ...]
            // All annotations come from the context map via JasonRdfAdapter
            Literal ccrsBelief = JasonRdfAdapter.createCcrsBelief(
               r, sourceKey
            );

            // Inject into Belief Base
            if (getTS().getAg().getBB().add(ccrsBelief)) {
                // Generate event so plans can trigger: +ccrs(...)
                Trigger te = new Trigger(Trigger.TEOperator.add, Trigger.TEType.belief, ccrsBelief.copy());
                getTS().updateEvents(new jason.asSemantics.Event(te, Intention.EmptyInt));
                
                logger.fine("✓ CCRS detected: " + r.type + " in " + sourceKey);
            }
        }
    }
    
    /**
     * Extracts the logical source identifier.
     * 1. Tries to find [source("http://...")] annotation (Standard Semantic Web convention)
     * 2. Tries to find [source(ArtifactName)] annotation (Standard Jason convention)
     * 3. Falls back to Artifact Name.
     */
    private String extractLogicalSource(Literal lit, ArtifactId artifact) {
        // Look for explicit source annotation first
        Term sourceAnnot = lit.getAnnot("source");
        if (sourceAnnot != null && sourceAnnot.isStructure()) {
            Structure s = (Structure) sourceAnnot;
            if (s.getArity() > 0) {
                // If source("http://...") return the URL
                // If source(percept) ignore it, look deeper or fallback
                String val = s.getTerm(0).toString().replace("\"", "");
                if (!"percept".equals(val) && !"self".equals(val)) {
                    return val;
                }
            }
        }
        return artifact.getName();
    }
    
    // Re-declare pendingBatches purely for the class scope (used in synchronized blocks)
    // Note: In the logic above I used 'perceptionBatch', renaming locally to match field definition
    private final Map<String, List<RdfTriple>> pendingBatches = perceptionBatch; 
}