package ccrs.jacamo.jason.hypermedia.hypermedea;

/**
 * A thread-local registry to pass the InteractionLogSink from the 
 * CArtAgO Artifact (Application Layer) to the HttpBinding (Network Layer).
 * 
 * Usage of ThreadLocal ensures that the sink is scoped to the specific 
 * agent thread currently executing the operation, avoiding race conditions
 * when multiple agents use the same artifact instance concurrently.
 */
public class CcrsGlobalRegistry {

    // 1. The Shared Log Instance (Singleton)
    // All artifacts write here. All agents read from here.
    private static final JasonInteractionLog SHARED_LOG = new JasonInteractionLog();

    // 2. The ThreadLocal Context
    // Used to pass the specific logging hook to the low-level HTTP client
    private static final ThreadLocal<InteractionLogSink> sink = new ThreadLocal<>();

    public static JasonInteractionLog getSharedLog() {
        return SHARED_LOG;
    }

    /**
     * Registers the sink for the current thread.
     * Should be called at the start of an artifact operation.
     */
    public static void setSink(InteractionLogSink s) {
        sink.set(s);
    }

    /**
     * Retrieves the sink for the current thread.
     * Called by the CcrsHttpBinding (SPI) to instrument the operation.
     */
    public static InteractionLogSink getSink() {
        return sink.get();
    }
    
    /**
     * Removes the sink for the current thread.
     * Must be called in a finally block after the operation setup to prevent memory leaks.
     */
    public static void clear() {
        sink.remove();
    }
}