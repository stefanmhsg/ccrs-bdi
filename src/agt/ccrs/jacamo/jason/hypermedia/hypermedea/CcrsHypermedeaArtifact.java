package ccrs.jacamo.jason.hypermedia.hypermedea;

import cartago.OPERATION;
import org.hypermedea.HypermedeaArtifact;
import org.hypermedea.op.Operation;
import org.hypermedea.op.Response;

public class CcrsHypermedeaArtifact extends HypermedeaArtifact {

    @Override
    public void init() {
        super.init();
    }

    // Helper: Wraps execution with a Proxy Sink bound to the current agent
    private void executeWithLog(Runnable opAction) {
        final String currentAgent = this.getCurrentOpAgentId().getAgentName();
        final JasonInteractionLog sharedLog = CcrsGlobalRegistry.getSharedLog();

        // Create a temporary proxy that injects the agent name
        InteractionLogSink proxy = new InteractionLogSink() {
            @Override
            public void onRequest(Operation op, long ts) {
                // Forward to shared log WITH agent name
                sharedLog.onRequest(op, ts, currentAgent);
            }

            @Override
            public void onResponse(Operation op, Response r, long ts) {
                sharedLog.onResponse(op, r, ts);
            }

            @Override
            public void onError(Operation op, long ts) {
                sharedLog.onError(op, ts);
            }
        };

        try {
            CcrsGlobalRegistry.setSink(proxy);
            opAction.run();
        } finally {
            CcrsGlobalRegistry.clear();
        }
    }

    // === OVERRIDES ===

    @Override @OPERATION public void get(String uri) { executeWithLog(() -> super.get(uri)); }
    @Override @OPERATION public void get(String uri, Object[] form) { executeWithLog(() -> super.get(uri, form)); }
    
    @Override @OPERATION public void post(String uri) { executeWithLog(() -> super.post(uri)); }
    @Override @OPERATION public void post(String uri, Object[] part) { executeWithLog(() -> super.post(uri, part)); }
    @Override @OPERATION public void post(String uri, Object[] part, Object[] form) { executeWithLog(() -> super.post(uri, part, form)); }

    @Override @OPERATION public void put(String uri, Object[] rep) { executeWithLog(() -> super.put(uri, rep)); }
    @Override @OPERATION public void put(String uri, Object[] rep, Object[] form) { executeWithLog(() -> super.put(uri, rep, form)); }

    @Override @OPERATION public void patch(String uri, Object[] diff) { executeWithLog(() -> super.patch(uri, diff)); }
    @Override @OPERATION public void patch(String uri, Object[] diff, Object[] form) { executeWithLog(() -> super.patch(uri, diff, form)); }

    @Override @OPERATION public void delete(String uri) { executeWithLog(() -> super.delete(uri)); }
    @Override @OPERATION public void delete(String uri, Object[] form) { executeWithLog(() -> super.delete(uri, form)); }

    @Override @OPERATION public void watch(String uri) { executeWithLog(() -> super.watch(uri)); }
    @Override @OPERATION public void watch(String uri, Object[] form) { executeWithLog(() -> super.watch(uri, form)); }
    
    @Override @OPERATION public void forget(String uri) { executeWithLog(() -> super.forget(uri)); }
    @Override @OPERATION public void forget(String uri, Object[] form) { executeWithLog(() -> super.forget(uri, form)); }
}