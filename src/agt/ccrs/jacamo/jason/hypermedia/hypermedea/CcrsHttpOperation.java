package ccrs.jacamo.jason.hypermedia.hypermedea;

import org.hypermedea.op.Response;
import org.hypermedea.op.http.HttpOperation;
import jason.asSyntax.Literal;

import java.util.Collection;
import java.io.IOException;
import java.util.Map;

/**
 * An HttpOperation that logs lifecycle events to the CCRS sink.
 */
public class CcrsHttpOperation extends HttpOperation {

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
        if (sink != null) {
            sink.onRequest(this, createdTimestamp);
        }
        try {
            super.sendRequest();
        } catch (IOException e) {
            if (sink != null) {
                sink.onError(this, System.currentTimeMillis());
            }
            throw e;
        }
    }


    @Override
    protected void onResponse(Response r) {
        // Hook: When response comes back
        if (sink != null) {
            sink.onResponse(this, r, System.currentTimeMillis());
        }
        
        // Critical: pass data back to HypermedeaArtifact
        super.onResponse(r);
    }

    @Override
    protected void onError() {
        // Hook: On network failure
        if (sink != null) {
            sink.onError(this, System.currentTimeMillis());
        }
        
        super.onError();
    }
}
