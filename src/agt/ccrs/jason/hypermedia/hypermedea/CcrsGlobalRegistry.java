package ccrs.jason.hypermedia.hypermedea;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe global registry to hold the InteractionLogSink.
 * This acts as the bridge between (Ccrs)AgentArch and the SPI-instantiated Binding of the hypermedia-Artifact.
 */
public class CcrsGlobalRegistry {

    private static final AtomicReference<InteractionLogSink> sinkRef = new AtomicReference<>();

    public static void setSink(InteractionLogSink sink) {
        sinkRef.set(sink);
    }

    public static InteractionLogSink getSink() {
        return sinkRef.get();
    }
}