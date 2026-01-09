package ccrs.jason.hypermedia.hypermedea;

import org.hypermedea.op.Response;
import org.hypermedea.op.http.HttpOperation;

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