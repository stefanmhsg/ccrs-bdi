package ccrs.hypermedea;

import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private static final Logger logger = Logger.getLogger(InteractionBuilder.class.getName());
    private static final String FALLBACK_RAW_RESPONSE =
        "https://example.org/ccrs#unparsedResponse";
    private static final String FALLBACK_PARSE_ERROR =
        "https://example.org/ccrs#responseParseError";

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
        Map<String, Object> form = op.getForm();
        String method = extractMethod(op);

        Map<String, String> headers = extractHeaders(form);
        // Use op.getPayload() as single source of truth for request body.
        // Hypermedea's transport-layer serializers can send data, but we need an
        // in-memory payload here so the interaction history includes the POST body.
        Object body = op.getPayload() == null || op.getPayload().isEmpty() ? null : List.copyOf(op.getPayload());

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

        logger.fine("Extracting HTTP method from Operation form: " + form);
        
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
        
        logger.fine("No method found in Operation; defaulting to GET.");

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
        final List<jason.asSyntax.Literal> payload;
        try {
            Collection<jason.asSyntax.Literal> responsePayload = response.getPayload();
            if (responsePayload == null) {
                return Collections.emptyList();
            }
            payload = List.copyOf(responsePayload);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Falling back to a generic RDF triple for an unparseable response payload", e);
            return fallbackTriples(response, e);
        }

        if (payload.isEmpty()) {
            return Collections.emptyList();
        }

        List<RdfTriple> triples = new ArrayList<>();
        String fallbackSubject = responseSubject(response);
        for (jason.asSyntax.Literal literal : payload) {
            try {
                RdfTriple triple = JasonRdfAdapter.toRdfTriple(literal);
                if (triple == null) {
                    triples.add(new RdfTriple(fallbackSubject, FALLBACK_RAW_RESPONSE, literal.toString()));
                } else {
                    triples.add(triple);
                }
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Wrapping response payload literal that could not be converted to an RDF triple: " + literal, e);
                triples.add(new RdfTriple(fallbackSubject, FALLBACK_RAW_RESPONSE, literal.toString()));
                triples.add(new RdfTriple(fallbackSubject, FALLBACK_PARSE_ERROR, e.toString()));
            }
        }
        return triples;
    }

    private static List<RdfTriple> fallbackTriples(Response response, RuntimeException parseError) {
        String subject = responseSubject(response);
        String rawBody = rawResponseBody(response)
            .orElseGet(() -> parseError.getMessage() == null ? parseError.toString() : parseError.getMessage());

        return List.of(
            new RdfTriple(subject, FALLBACK_RAW_RESPONSE, rawBody),
            new RdfTriple(subject, FALLBACK_PARSE_ERROR, parseError.toString())
        );
    }

    private static String responseSubject(Response response) {
        try {
            if (response.getOperation() != null && response.getOperation().getTargetURI() != null) {
                return response.getOperation().getTargetURI();
            }
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Could not extract response operation target URI for fallback triple", e);
        }
        return "urn:ccrs:hypermedea:unknown-response";
    }

    private static Optional<String> rawResponseBody(Response response) {
        try {
            Field rawResponseField = response.getClass().getDeclaredField("response");
            rawResponseField.setAccessible(true);
            Object rawResponse = rawResponseField.get(response);
            if (rawResponse == null) {
                return Optional.empty();
            }

            Method getBodyText = rawResponse.getClass().getMethod("getBodyText");
            Object bodyText = getBodyText.invoke(rawResponse);
            return bodyText == null ? Optional.empty() : Optional.of(bodyText.toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.FINE, "Could not extract raw HTTP response body for fallback triple", e);
            return Optional.empty();
        }
    }
}
