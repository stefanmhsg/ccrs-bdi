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
}
