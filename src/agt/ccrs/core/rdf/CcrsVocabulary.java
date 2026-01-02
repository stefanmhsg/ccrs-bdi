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
    private static final Property EXTRACT_VARIABLE = ResourceFactory.createProperty(CCRS_NS + "extractVariable");

    // Runtime Structures
    private final Map<String, Set<String>> simplePatternIndex = new ConcurrentHashMap<>();
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

    private void compileSimplePatterns() {
        ResIterator iter = model.listSubjectsWithProperty(RDF.type, SIMPLE_PATTERN);
        while (iter.hasNext()) {
            Resource r = iter.nextResource();
            if (!r.isURIResource()) continue;

            String type = getString(r, PATTERN_TYPE);
            String pos = getString(r, MATCHES_POSITION);
            
            if (type != null && pos != null) {
                discoveredTypes.add(type);
                simplePatternIndex.computeIfAbsent(type + ":" + pos, k -> ConcurrentHashMap.newKeySet())
                                  .add(r.getURI());
            }
        }
    }

    private void compileStructuralPatterns() {
        ResIterator iter = model.listSubjectsWithProperty(RDF.type, STRUCTURAL_PATTERN);
        while (iter.hasNext()) {
            Resource r = iter.nextResource();

            String id = getPatternId(r);
            String type = getString(r, PATTERN_TYPE);
            int priority = getInt(r, PRIORITY, 0);
            String rawVar = getString(r, EXTRACT_VARIABLE);
            String sparql = getString(r, SPARQL_PATTERN);

            if (type == null || sparql == null) {
                logger.warning("Skipping invalid structural pattern: " + id);
                continue;
            }

            discoveredTypes.add(type);
            final String var = (rawVar != null) ? rawVar : "subject"; // Default extraction var

            // Hybrid Compilation Strategy
            StructuralPatternDefinition def = tryCompileFastPath(id, type, priority, var, sparql)
                    .orElseGet(() -> compileSlowPath(id, type, priority, var, sparql));

            structuralPatterns.add(def);
        }
        
        // Ensure high priority patterns are matched first
        structuralPatterns.sort((a, b) -> Integer.compare(b.priority, a.priority));
    }

    /**
     * Attempts to compile SPARQL into a lightweight Java object.
     */
    private Optional<StructuralPatternDefinition> tryCompileFastPath(String id, String type, int prio, String var, String sparql) {
        try {
            Query query = QueryFactory.create(sparql);
            
            // Fast Path constraints: SELECT queries only, no aggregations
            if (!query.isSelectType() || query.hasGroupBy() || query.hasAggregators()) return Optional.empty();

            List<StructuralPatternMatcher.TripleConstraint> constraints = new ArrayList<>();
            if (extractConstraints(query.getQueryPattern(), constraints)) {
                
                StructuralPatternMatcher.CompiledPattern fastPattern = 
                    new StructuralPatternMatcher.CompiledPattern(constraints);
                
                logger.fine("Compiled to FAST PATH: " + id);
                return Optional.of(new StructuralPatternDefinition(id, type, prio, var, null, fastPattern));
            }
        } catch (Exception e) {
            logger.warning("SPARQL parse error for " + id + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Fallback to Jena Query object.
     */
    private StructuralPatternDefinition compileSlowPath(String id, String type, int prio, String var, String sparql) {
        Query query = QueryFactory.create(sparql);
        logger.fine("Compiled to SLOW PATH (Jena): " + id);
        return new StructuralPatternDefinition(id, type, prio, var, query, null);
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

    // Accessors
    public boolean matchesSimple(String uri, String type, String pos) {
        Set<String> s = simplePatternIndex.get(type + ":" + pos);
        return s != null && s.contains(uri);
    }

    public List<StructuralPatternDefinition> getAllStructuralPatterns() {
        return Collections.unmodifiableList(structuralPatterns);
    }

    public Set<String> getDiscoveredTypes() {
        return Collections.unmodifiableSet(discoveredTypes);
    }

    /**
     * Definition wrapper holding either a Fast Path pattern or a Slow Path query.
     */
    public static class StructuralPatternDefinition {
        public final String id;
        public final String type;
        public final int priority;
        public final String extractVar;
        
        public final Query slowQuery; 
        public final StructuralPatternMatcher.CompiledPattern fastPattern;

        public StructuralPatternDefinition(String id, String type, int priority, String extractVar, 
                                           Query slowQuery, StructuralPatternMatcher.CompiledPattern fastPattern) {
            this.id = id; this.type = type; this.priority = priority; this.extractVar = extractVar;
            this.slowQuery = slowQuery; this.fastPattern = fastPattern;
        }

        public boolean isFastPath() { return fastPattern != null; }
    }
}