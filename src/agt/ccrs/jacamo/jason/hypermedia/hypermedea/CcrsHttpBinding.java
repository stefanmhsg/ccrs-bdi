package ccrs.jacamo.jason.hypermedia.hypermedea;

import org.hypermedea.op.Operation;
import org.hypermedea.op.ProtocolBinding;
import org.hypermedea.op.http.HttpBinding;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * A custom ProtocolBinding that overrides the default HTTP binding.
 * It checks the registry for a sink. If found, it creates a logging operation.
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
            // Return our instrumented operation
            return new CcrsHttpOperation(targetURI, formFields, sink);
        } else {
            // Fallback: If no sink is installed, behave exactly like standard Hypermedea
            return delegate.bind(targetURI, formFields);
        }
    }

    @Override
    public Operation bind(String targetURITemplate, Map<String, Object> formFields, Map<String, Object> uriVariableMappings) {
        // For simplicity, we delegate this one, or you can implement similar logic for templates
        return delegate.bind(targetURITemplate, formFields, uriVariableMappings);
    }
}