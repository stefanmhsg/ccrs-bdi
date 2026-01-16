package ccrs.core.contingency.dto;

import java.util.List;
import java.util.Map;
import ccrs.core.rdf.RdfTriple;

public record Interaction(

    // Intent
    String method,                // get, post, put, delete, watch
    String requestUri,
    Map<String, String> requestHeaders,
    Object requestBody,

    // Outcome
    Outcome outcome,              // SUCCESS, CLIENT_FAILURE, SERVER_FAILURE, UNKNOWN
    List<RdfTriple> perceivedState,

    // Temporal
    long requestTimestamp,
    long responseTimestamp,

    // Provenance
    String logicalSource

) {

    public enum Outcome {
        SUCCESS,
        CLIENT_FAILURE,
        SERVER_FAILURE,
        UNKNOWN
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Interaction {\n");
        sb.append("  request:\n");
        sb.append("    method: ").append(method).append('\n');
        sb.append("    uri: ").append(requestUri).append('\n');
        sb.append("    headers: ").append(requestHeaders).append('\n');
        sb.append("    body: ").append(requestBody).append('\n');
        sb.append("  response:\n");
        sb.append("    status: ").append(outcome).append('\n');
        sb.append("    perceivedStateSize: ")
          .append(perceivedState == null ? 0 : perceivedState.size()).append(" triples").append('\n');
        sb.append("  details:\n");
        sb.append("    requestTs: ").append(requestTimestamp).append('\n');
        sb.append("    responseTs: ").append(responseTimestamp).append('\n');
        sb.append("    durationMs: ")
          .append(responseTimestamp - requestTimestamp).append('\n');
        sb.append("    source: ").append(logicalSource).append('\n');
        sb.append("}");
        return sb.toString();
    }


}
