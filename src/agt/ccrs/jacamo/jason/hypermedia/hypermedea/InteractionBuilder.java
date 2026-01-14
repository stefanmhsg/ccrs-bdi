package ccrs.jacamo.jason.hypermedia.hypermedea;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ccrs.core.contingency.dto.Interaction;
import ccrs.core.rdf.RdfTriple;
import ccrs.jacamo.jason.JasonRdfAdapter;

import org.hypermedea.op.Operation;
import org.hypermedea.op.Response;

/**
 * Mutable builder used to assemble a semantic Interaction
 * across request and response callbacks.
 *
 * This class is internal to the logging layer and never exposed
 * to CCRS strategies directly.
 */
final class InteractionBuilder {

    // ===== Request side =====

    private final String method;
    private final String requestUri;
    private final Map<String, String> requestHeaders;
    private final Object requestBody;
    private final long requestTimestamp;
    private final String logicalSource;

    // ===== Response side =====

    private Interaction.Outcome outcome = Interaction.Outcome.UNKNOWN;
    private List<RdfTriple> perceivedState = Collections.emptyList();
    private long responseTimestamp = -1L;

    private InteractionBuilder(
        String method,
        String requestUri,
        Map<String, String> requestHeaders,
        Object requestBody,
        long requestTimestamp,
        String logicalSource
    ) {
        this.method = method;
        this.requestUri = requestUri;
        this.requestHeaders = requestHeaders;
        this.requestBody = requestBody;
        this.requestTimestamp = requestTimestamp;
        this.logicalSource = logicalSource;
    }

    // =========================
    // Factory: request phase
    // =========================

    static InteractionBuilder fromRequest(Operation op, long timestamp) {

        String method = extractMethod(op);

        Map<String, String> headers = extractHeaders(op.getForm());

        Object body =
            op.getPayload() == null || op.getPayload().isEmpty()
                ? null
                : List.copyOf(op.getPayload());

        return new InteractionBuilder(
            method,
            op.getTargetURI(),
            headers,
            body,
            timestamp,
            op.getTargetURI()
        );
    }

    // =========================
    // Enrichment: response phase
    // =========================

    InteractionBuilder withResponse(Response response, long timestamp) {
        this.responseTimestamp = timestamp;
        this.outcome = mapOutcome(response.getStatus());
        this.perceivedState = extractTriples(response);
        return this;
    }

    InteractionBuilder withError(long timestamp) {
        this.responseTimestamp = timestamp;
        this.outcome = Interaction.Outcome.UNKNOWN;
        this.perceivedState = Collections.emptyList();
        return this;
    }

    // =========================
    // Finalization
    // =========================

    Interaction build() {
        return new Interaction(
            method,
            requestUri,
            requestHeaders,
            requestBody,
            outcome,
            perceivedState,
            requestTimestamp,
            responseTimestamp,
            logicalSource
        );
    }

    // =========================
    // Helpers
    // =========================

    /**
     * Extract HTTP method from Operation.
     * Tries multiple strategies:
     * 1. Explicit 'method' key in form
     * 2. URN-style keys like 'urn:hypermedea:http:mthd'
     * 3. Infer from payload: POST if has body, GET otherwise
     */
    private static String extractMethod(Operation op) {
        Map<String, Object> form = op.getForm();
        
        // Strategy 1: Check simple 'method' key
        if (form.containsKey("method")) {
            return String.valueOf(form.get("method")).toUpperCase();
        }
        
        // Strategy 2: Check URN-style method key
        for (Map.Entry<String, Object> e : form.entrySet()) {
            String key = e.getKey().toLowerCase();
            if (key.contains(":mthd") || key.contains(":method")) {
                return String.valueOf(e.getValue()).toUpperCase();
            }
        }
        
        // Strategy 3: Infer from payload
        if (op.getPayload() != null && !op.getPayload().isEmpty()) {
            return "POST";
        }
        
        // Default to GET for read operations
        return "GET";
    }

    private static Interaction.Outcome mapOutcome(Response.ResponseStatus status) {
        if (status == null) {
            return Interaction.Outcome.UNKNOWN;
        }
        return switch (status) {
            case OK -> Interaction.Outcome.SUCCESS;
            case CLIENT_ERROR -> Interaction.Outcome.CLIENT_FAILURE;
            case SERVER_ERROR -> Interaction.Outcome.SERVER_FAILURE;
            case UNKNOWN_ERROR -> Interaction.Outcome.UNKNOWN;
        };
    }

    private static Map<String, String> extractHeaders(Map<String, Object> form) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, Object> e : form.entrySet()) {
            if (e.getKey().startsWith("urn:hypermedea:http:")) {
                headers.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return headers;
    }

    private static List<RdfTriple> extractTriples(Response response) {
        if (response.getPayload() == null) {
            return Collections.emptyList();
        }

        return response.getPayload().stream()
            .map(JasonRdfAdapter::toRdfTriple)
            .filter(t -> t != null)
            .toList();
    }
}
