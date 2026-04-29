package ccrs.jacamo.jason.hypermedia.hypermedea;

import org.hypermedea.op.Operation;
import org.hypermedea.op.ProtocolBinding;
import org.hypermedea.op.http.HttpBinding;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * A custom ProtocolBinding that wraps the default HTTP binding when CCRS logging is active.
 */
public class CcrsHttpBinding implements ProtocolBinding {

    // We delegate to the original binding for standard properties
    private final HttpBinding delegate = new HttpBinding();

    @Override
    public String getProtocol() {
        return "CCRS-HTTP";
    }

    @Override
    public Collection<String> getSupportedSchemes() {
        // Claim the same schemes as the default HttpBinding to ensure we are considered
        return Arrays.asList("http", "https");
    }

    @Override
    public Operation bind(String targetURI, Map<String, Object> formFields) {
        InteractionLogSink sink = CcrsGlobalRegistry.getSink();

        if (sink != null) {
            // Active CCRS context: return instrumented operation that captures request/response.
            return new CcrsHttpOperation(targetURI, formFields, sink);
        } else {
            // Fallback: If no sink is installed, behave exactly like standard Hypermedea
            return delegate.bind(targetURI, formFields);
        }
    }

    @Override
    public Operation bind(String targetURITemplate, Map<String, Object> formFields, Map<String, Object> uriVariableMappings) {
        // URI-templated variants are not instrumented currently; delegate to default behaviour.
        return delegate.bind(targetURITemplate, formFields, uriVariableMappings);
    }
}
