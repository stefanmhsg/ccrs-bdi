package ccrs.core.opportunistic;

import ccrs.core.rdf.CcrsVocabulary;
import ccrs.core.rdf.CcrsVocabularyLoader;
import ccrs.core.rdf.RdfTriple;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;

import java.util.*;
import java.util.logging.Logger;

/**
 * Vocabulary-based Opportunistic CCRS Scanner.
 * Implements the Hybrid Matching Strategy:
 * 1. Structural Patterns (Fast Path via Java, Slow Path via Jena).
 * 2. Simple Patterns (O(1) HashSet).
 */
public class VocabularyMatcher implements OpportunisticCcrs {

    private static final Logger logger = Logger.getLogger(VocabularyMatcher.class.getName());
    private final CcrsVocabulary vocabulary;
    private final ScoringStrategy scoringStrategy;

    public VocabularyMatcher(CcrsVocabulary vocabulary) {
        this(vocabulary, new DefaultScoringStrategy());
    }
    
    public VocabularyMatcher(CcrsVocabulary vocabulary, ScoringStrategy scoringStrategy) {
        this.vocabulary = vocabulary;
        this.scoringStrategy = scoringStrategy;
    }

    @Override
    public Optional<OpportunisticResult> scan(RdfTriple triple, Map<String, Object> context) {
        return scanSimple(triple, context);
    }

    @Override
    public List<OpportunisticResult> scanAll(Collection<RdfTriple> triples, Map<String, Object> context) {
        List<OpportunisticResult> results = new ArrayList<>();

        // 1. Structural Patterns (Higher Priority)
        for (CcrsVocabulary.StructuralPatternDefinition def : vocabulary.getAllStructuralPatterns()) {
            if (def.isFastPath()) {
                matchFast(def, triples, context, results);
            } else {
                matchSlow(def, triples, context, results);
            }
        }

        // 2. Simple Patterns (Lower Priority)
        for (RdfTriple t : triples) {
            scanSimple(t, context).ifPresent(results::add);
        }

        return results;
    }

    /**
     * FAST PATH: Pure Java BGP Matching.
     */
    private void matchFast(CcrsVocabulary.StructuralPatternDefinition def, Collection<RdfTriple> triples, 
                           Map<String, Object> context, List<OpportunisticResult> results) {
        
        List<Map<String, String>> matches = StructuralPatternMatcher.match(def.fastPattern, triples);
        
        for (Map<String, String> binding : matches) {
            String target = binding.get("?" + def.extractTargetVar); // Matcher variables have '?'
            
            if (target != null) {
                // Extract relevance value if pattern declares variable
                Object relevanceValue = null;
                if (def.hasRelevanceVariable()) {
                    relevanceValue = binding.get("?" + def.extractRelevanceVar);
                }
                
                // Build relevance context
                ScoringStrategy.RelevanceContext relContext = def.hasRelevanceVariable()
                    ? ScoringStrategy.RelevanceContext.forStructuralWithVariable(relevanceValue)
                    : ScoringStrategy.RelevanceContext.forStructuralWithoutVariable();
                
                // Calculate utility using strategy
                double utility = scoringStrategy.calculateUtility(def.priority, relContext);
                
                OpportunisticResult res = new OpportunisticResult(def.type, target, def.id, utility);
                binding.forEach((k, v) -> res.withMetadata("var_" + k.replace("?", ""), v));
                addContext(res, context);
                results.add(res);
                logger.fine("CCRS Fast Match: " + def.id);
            }
        }
    }

    /**
     * SLOW PATH: Jena SPARQL Engine.
     * Only used for complex patterns with FILTER, OPTIONAL, etc.
     */
    private void matchSlow(CcrsVocabulary.StructuralPatternDefinition def, Collection<RdfTriple> triples, 
                           Map<String, Object> context, List<OpportunisticResult> results) {
        
        // High overhead: Model creation per scan
        Model model = ModelFactory.createDefaultModel();
        for (RdfTriple t : triples) {
            Resource s = model.createResource(t.subject);
            Property p = model.createProperty(t.predicate);
            RDFNode o = t.object.startsWith("http") ? model.createResource(t.object) : model.createLiteral(t.object);
            model.add(s, p, o);
        }

        try (QueryExecution qexec = QueryExecutionFactory.create(def.slowQuery, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.nextSolution();
                RDFNode node = sol.get(def.extractTargetVar);
                
                if (node != null) {
                    String target = node.isResource() ? node.asResource().getURI() : node.toString();
                    
                    // Extract relevance value if pattern declares variable
                    Object relevanceValue = null;
                    if (def.hasRelevanceVariable()) {
                        RDFNode relNode = sol.get(def.extractRelevanceVar);
                        relevanceValue = relNode; // Pass RDFNode directly
                    }
                    
                    // Build relevance context
                    ScoringStrategy.RelevanceContext relContext = def.hasRelevanceVariable()
                        ? ScoringStrategy.RelevanceContext.forStructuralWithVariable(relevanceValue)
                        : ScoringStrategy.RelevanceContext.forStructuralWithoutVariable();
                    
                    // Calculate utility using strategy
                    double utility = scoringStrategy.calculateUtility(def.priority, relContext);

                    OpportunisticResult res = new OpportunisticResult(def.type, target, def.id, utility);
                    
                    sol.varNames().forEachRemaining(v -> {
                        RDFNode val = sol.get(v);
                        if (val != null) res.withMetadata("var_" + v, val.toString());
                    });
                    
                    addContext(res, context);
                    results.add(res);
                    logger.fine("CCRS Slow Match: " + def.id);
                }
            }
        }
    }

    /**
     * SIMPLE PATH: O(1) Index Lookup.
     */
    private Optional<OpportunisticResult> scanSimple(RdfTriple t, Map<String, Object> ctx) {
        for (String type : vocabulary.getDiscoveredTypes()) {
            Optional<CcrsVocabulary.SimplePatternDefinition> def;

            def = vocabulary.getSimpleDefinition(t.predicate, type, "predicate");
            if (def.isPresent()) return Optional.of(createSimpleRes(def.get(), t.object, ctx));
            
            def = vocabulary.getSimpleDefinition(t.subject, type, "subject");
            if (def.isPresent()) return Optional.of(createSimpleRes(def.get(), t.subject, ctx));
            
            def = vocabulary.getSimpleDefinition(t.object, type, "object");
            if (def.isPresent()) return Optional.of(createSimpleRes(def.get(), t.object, ctx));
        }
        return Optional.empty();
    }

    private OpportunisticResult createSimpleRes(CcrsVocabulary.SimplePatternDefinition def, String target, Map<String, Object> ctx) {
        // Simple patterns: match itself is the signal
        ScoringStrategy.RelevanceContext relContext = ScoringStrategy.RelevanceContext.forSimplePattern();
        double utility = scoringStrategy.calculateUtility(def.priority, relContext);
        
        OpportunisticResult res = new OpportunisticResult(def.type, target, def.id, utility);
        res.withMetadata("match_position", def.position);
        addContext(res, ctx);
        return res;
    }
    
    private void addContext(OpportunisticResult res, Map<String, Object> ctx) {
        if (ctx != null) ctx.forEach((k, v) -> res.withMetadata("ctx_" + k, v.toString()));
    }


    public static class Factory implements CcrsScannerFactory {
        private final ScoringStrategy scoringStrategy;
        
        /**
         * Create factory with default scoring strategy.
         */
        public Factory() {
            this(new DefaultScoringStrategy());
        }
        
        /**
         * Create factory with custom scoring strategy.
         * 
         * @param scoringStrategy Custom scoring strategy for utility calculation
         */
        public Factory(ScoringStrategy scoringStrategy) {
            this.scoringStrategy = scoringStrategy;
        }
        
        @Override
        public OpportunisticCcrs createScanner(CcrsVocabulary vocabulary) {
            // Uses the loader if no vocab is provided, matching CcrsAgent usage
            if (vocabulary == null) {
                vocabulary = CcrsVocabularyLoader.loadDefault();
            }
            return new VocabularyMatcher(vocabulary, scoringStrategy);
        }
    }
}