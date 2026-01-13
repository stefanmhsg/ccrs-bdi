package ccrs.core.rdf;

import ccrs.core.opportunistic.StructuralPatternMatcher;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Semantic CCRS Vocabulary.
 * Performs Hybrid Compilation:
 * 1. Simple Patterns -> HashSet Index.
 * 2. Structural Patterns -> Parsed from SPARQL strings.
 *    - Simple structures -> Compiled to Java objects (Fast Path).
 *    - Complex structures -> Kept as Jena Query objects (Slow Path).
 */
public class CcrsVocabulary {

    private static final Logger logger = Logger.getLogger(CcrsVocabulary.class.getName());
    public static final String CCRS_NS = "https://example.org/ccrs#";

    // RDF Constants
    private static final Resource SIMPLE_PATTERN = ResourceFactory.createResource(CCRS_NS + "SimplePattern");
    private static final Resource STRUCTURAL_PATTERN = ResourceFactory.createResource(CCRS_NS + "StructuralPattern");
    private static final Property PATTERN_TYPE = ResourceFactory.createProperty(CCRS_NS + "patternType");
    private static final Property MATCHES_POSITION = ResourceFactory.createProperty(CCRS_NS + "matchesPosition");
    private static final Property PRIORITY = ResourceFactory.createProperty(CCRS_NS + "priority");
    private static final Property SPARQL_PATTERN = ResourceFactory.createProperty(CCRS_NS + "sparqlPattern");
    private static final Property EXTRACT_TARGET_VARIABLE = ResourceFactory.createProperty(CCRS_NS + "extractTargetVariable");
    private static final Property EXTRACT_RELEVANCE_VARIABLE = ResourceFactory.createProperty(CCRS_NS + "extractedRelevanceVariable");

    // Runtime Structures
    private final Map<String, Set<SimplePatternDefinition>> simplePatternIndex = new ConcurrentHashMap<>();
    private final List<StructuralPatternDefinition> structuralPatterns = new ArrayList<>();
    private final Set<String> discoveredTypes = ConcurrentHashMap.newKeySet();
    private final Model model;

    public CcrsVocabulary(Model model) {
        this.model = model;
        compile();
    }

    private void compile() {
        compileSimplePatterns();
        compileStructuralPatterns();
    }

    /**
     * Compiles simple patterns into a hash-based index for O(1) lookups.
     */
    private void compileSimplePatterns() {
        ResIterator iter = model.listSubjectsWithProperty(RDF.type, SIMPLE_PATTERN);
        while (iter.hasNext()) {
            Resource r = iter.nextResource();
            if (!r.isURIResource()) continue;

            String type = getString(r, PATTERN_TYPE);
            String pos = getString(r, MATCHES_POSITION);
            double priority = getDouble(r, PRIORITY, 0.0);
            validatePriority(priority, r.getURI());
            
            if (type != null && pos != null) {
                discoveredTypes.add(type);
                SimplePatternDefinition def = new SimplePatternDefinition(r.getURI(), type, priority, pos);
                simplePatternIndex.computeIfAbsent(type + ":" + pos, k -> ConcurrentHashMap.newKeySet())
                                  .add(def);
            }
        }
    }

    /**
     * Compiles structural patterns from SPARQL definitions.
     * Uses a hybrid strategy to optimize structural patterns either to Java objects or Jena queries.
     */
    private void compileStructuralPatterns() {
        ResIterator iter = model.listSubjectsWithProperty(RDF.type, STRUCTURAL_PATTERN);
        while (iter.hasNext()) {
            Resource r = iter.nextResource();

            String id = getPatternId(r);
            String type = getString(r, PATTERN_TYPE);
            double priority = getDouble(r, PRIORITY, 0.0);
            validatePriority(priority, id);
            String targetVar = getString(r, EXTRACT_TARGET_VARIABLE);            
            String sparql = getString(r, SPARQL_PATTERN);
            String relVar = getString(r, EXTRACT_RELEVANCE_VARIABLE);

            if (type == null || sparql == null) {
                logger.warning("Skipping invalid structural pattern: " + id);
                continue;
            }
            final String target = (targetVar != null) ? targetVar : "subject"; // Default target var


            discoveredTypes.add(type);

            // Hybrid Compilation Strategy
            StructuralPatternDefinition def = tryCompileFastPath(id, type, priority, target, relVar, sparql)
                    .orElseGet(() -> compileSlowPath(id, type, priority, target, relVar, sparql));

            structuralPatterns.add(def);
        }
        
        // Ensure high priority patterns are matched first
        structuralPatterns.sort((a, b) -> Double.compare(b.priority, a.priority));
    }

    /**
     * Attempts to compile SPARQL into a lightweight Java object.
     */
    private Optional<StructuralPatternDefinition> tryCompileFastPath(String id, String type, double prio, 
            String targetVar, String relVar, String sparql) {
        try {
            Query query = QueryFactory.create(sparql);
            
            // Fast Path constraints: SELECT queries only, no aggregations
            if (!query.isSelectType() || query.hasGroupBy() || query.hasAggregators()) return Optional.empty();

            List<StructuralPatternMatcher.TripleConstraint> constraints = new ArrayList<>();
            if (extractConstraints(query.getQueryPattern(), constraints)) {
                
                StructuralPatternMatcher.CompiledPattern fastPattern = 
                    new StructuralPatternMatcher.CompiledPattern(constraints);
                
                logger.fine("Compiled to FAST PATH: " + id);
                return Optional.of(new StructuralPatternDefinition(id, type, prio, targetVar, relVar, null, fastPattern));
            }
        } catch (Exception e) {
            logger.warning("SPARQL parse error for " + id + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Fallback to Jena Query object.
     */
    private StructuralPatternDefinition compileSlowPath(String id, String type, double prio, 
            String targetVar, String relVar, String sparql) {
        Query query = QueryFactory.create(sparql);
        logger.fine("Compiled to SLOW PATH (Jena): " + id);
        return new StructuralPatternDefinition(id, type, prio, targetVar, relVar, query, null);
    }

    private boolean extractConstraints(Element el, List<StructuralPatternMatcher.TripleConstraint> list) {
        if (el instanceof ElementGroup) {
            for (Element child : ((ElementGroup) el).getElements()) {
                if (!extractConstraints(child, list)) return false;
            }
            return true;
        } else if (el instanceof ElementPathBlock) {
            for (TriplePath tp : ((ElementPathBlock) el).getPattern().getList()) {
                if (!tp.isTriple()) return false; // Complex paths not supported
                org.apache.jena.graph.Triple t = tp.asTriple();
                list.add(new StructuralPatternMatcher.TripleConstraint(
                        nodeToStr(t.getSubject()), nodeToStr(t.getPredicate()), nodeToStr(t.getObject())
                ));
            }
            return true;
        }
        return false; // OPTIONAL, FILTER, UNION not supported in fast path
    }

    // Helpers
    private String getPatternId(Resource r) {
        if (r.isURIResource()) return r.getURI();
        // Use label or hash for anon nodes
        String label = getString(r, RDFS.label);
        return label != null ? label.replaceAll("\\s+", "_") : "genid_" + r.getId().toString();
    }

    private String nodeToStr(Node n) {
        if (n.isVariable()) return "?" + n.getName();
        if (n.isURI()) return n.getURI();
        if (n.isLiteral()) return n.getLiteralLexicalForm();
        return n.toString();
    }

    private String getString(Resource r, Property p) {
        Statement s = r.getProperty(p); return s == null ? null : s.getString();
    }

    private int getInt(Resource r, Property p, int def) {
        Statement s = r.getProperty(p); return s == null ? def : s.getInt();
    }
    
    private double getDouble(Resource r, Property p, double def) {
        Statement s = r.getProperty(p);
        if (s == null) return def;
        try {
            return s.getDouble();
        } catch (Exception e) {
            // Try int as fallback
            try {
                return (double) s.getInt();
            } catch (Exception e2) {
                logger.warning("Invalid priority value for " + r + ": " + e.getMessage());
                return def;
            }
        }
    }
    
    /**
     * Validates that priority is in the valid range [-1, 1].
     * @throws IllegalArgumentException if priority is out of range
     */
    private void validatePriority(double priority, String patternId) {
        if (priority < -1.0 || priority > 1.0) {
            throw new IllegalArgumentException(
                "Priority must be in range [-1, 1], got " + priority + " for pattern: " + patternId);
        }
    }

    // Accessors
    public boolean matchesSimple(String uri, String type, String pos) {
        return getSimpleDefinition(uri, type, pos).isPresent();
    }

    public Optional<SimplePatternDefinition> getSimpleDefinition(String uri, String type, String pos) {
        Set<SimplePatternDefinition> s = simplePatternIndex.get(type + ":" + pos);
        if (s == null) return Optional.empty();
        for (SimplePatternDefinition def : s) {
            if (def.id.equals(uri)) return Optional.of(def);
        }
        return Optional.empty();
    }

    public List<StructuralPatternDefinition> getAllStructuralPatterns() {
        return Collections.unmodifiableList(structuralPatterns);
    }

    public Set<String> getDiscoveredTypes() {
        return Collections.unmodifiableSet(discoveredTypes);
    }


    /**
     * Definition wrapper for simple patterns.
     */
    public static class SimplePatternDefinition {
        public final String id;
        public final String type;
        public final double priority;
        public final String position;

        public SimplePatternDefinition(String id, String type, double priority, String position) {
            this.id = id;
            this.type = type;
            this.priority = priority;
            this.position = position;
        }
    }


    /**
     * Definition wrapper holding either a Fast Path pattern or a Slow Path query.
     */
    public static class StructuralPatternDefinition {
        public final String id;
        public final String type;
        public final double priority;
        public final String extractTargetVar;
        public final String extractRelevanceVar;

        public final Query slowQuery; 
        public final StructuralPatternMatcher.CompiledPattern fastPattern;

        public StructuralPatternDefinition(String id, String type, double priority, 
                String extractTargetVar, String extractRelevanceVar,
                Query slowQuery, StructuralPatternMatcher.CompiledPattern fastPattern) {
            this.id = id; this.type = type; this.priority = priority; 
            this.extractTargetVar = extractTargetVar;
            this.extractRelevanceVar = extractRelevanceVar;
            this.slowQuery = slowQuery; this.fastPattern = fastPattern;
        }

        public boolean isFastPath() { return fastPattern != null; }
        
        /**
         * @return true if this pattern declares a relevance variable by design
         */
        public boolean hasRelevanceVariable() { return extractRelevanceVar != null; }
    }
}