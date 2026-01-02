package ccrs.core.rdf;

import java.util. Objects;

/**
 * Lightweight RDF triple representation.
 * Subject, predicate, and object are stored as strings (URIs or lexical values).
 */
public class RdfTriple {
    
    public final String subject;
    public final String predicate;
    public final String object;
    
    /**
     * Create an RDF triple.
     * 
     * @param subject Subject URI or blank node
     * @param predicate Predicate URI
     * @param object Object URI, blank node, or literal value
     */
    public RdfTriple(String subject, String predicate, String object) {
        this.subject = Objects.requireNonNull(subject, "Subject cannot be null");
        this.predicate = Objects.requireNonNull(predicate, "Predicate cannot be null");
        this.object = Objects.requireNonNull(object, "Object cannot be null");
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RdfTriple)) return false;
        RdfTriple other = (RdfTriple) obj;
        return subject.equals(other.subject) 
            && predicate.equals(other. predicate) 
            && object.equals(other.object);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(subject, predicate, object);
    }
    
    @Override
    public String toString() {
        return String.format("<%s> <%s> <%s>", subject, predicate, object);
    }
}