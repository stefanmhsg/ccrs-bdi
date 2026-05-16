package ccrs.hypermedea;

import org.hypermedea.op.Response;
import org.hypermedea.op.http.HttpOperation;
import jason.asSyntax.Literal;

import java.util.Collection;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An HttpOperation that logs lifecycle events to the CCRS sink.
 */
public class CcrsHttpOperation extends HttpOperation {

    private static final Logger logger = Logger.getLogger(CcrsHttpOperation.class.getName());

    private final InteractionLogSink sink;
    private final long createdTimestamp;

    public CcrsHttpOperation(String targetURI, Map<String, Object> formFields, InteractionLogSink sink) {
        // Calling super starts the underlying HTTP client
        super(targetURI, formFields);
        this.sink = sink;
        this.createdTimestamp = System.currentTimeMillis();
    }

    @Override
    public void setPayload(Collection<Literal> payload) {
        // Preserve payload in operation state before delegating.
        // Without this, HttpOperation serialization works but getPayload() stays empty,
        // which causes InteractionBuilder to miss request bodies in interaction history.
        this.payload.clear();
        if (payload != null) {
            this.payload.addAll(payload);
        }

        super.setPayload(payload);
    }

    @Override
    public void sendRequest() throws IOException {
        notifyRequest(createdTimestamp);
        try {
            super.sendRequest();
        } catch (IOException e) {
            notifyError(System.currentTimeMillis());
            throw e;
        }
    }


    @Override
    protected void onResponse(Response r) {
        try {
            // Critical: pass data back to HypermedeaArtifact first.
            super.onResponse(r);
        } finally {
            notifyResponse(r, System.currentTimeMillis());
        }
    }

    @Override
    protected void onError() {
        try {
            super.onError();
        } finally {
            notifyError(System.currentTimeMillis());
        }
    }

    private void notifyRequest(long timestamp) {
        if (sink == null) {
            return;
        }
        try {
            sink.onRequest(this, timestamp);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "CCRS interaction logging failed during HTTP request", e);
        }
    }

    private void notifyResponse(Response response, long timestamp) {
        if (sink == null) {
            return;
        }
        try {
            sink.onResponse(this, response, timestamp);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "CCRS interaction logging failed during HTTP response", e);
        }
    }

    private void notifyError(long timestamp) {
        if (sink == null) {
            return;
        }
        try {
            sink.onError(this, timestamp);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "CCRS interaction logging failed during HTTP error", e);
        }
    }
}
