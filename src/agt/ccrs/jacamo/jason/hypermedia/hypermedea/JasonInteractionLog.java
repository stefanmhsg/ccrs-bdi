package ccrs.jacamo.jason.hypermedia.hypermedea;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hypermedea.op.Operation;
import org.hypermedea.op.Response;

import ccrs.core.contingency.dto.Interaction;

public class JasonInteractionLog implements InteractionLogSink {

    // correlation: operation → partial interaction
    private final Map<Operation, InteractionBuilder> inflight = new ConcurrentHashMap<>();

    // bounded history
    private final Deque<Interaction> history = new ArrayDeque<>();
    private final int maxSize = 100;

    @Override
    public void onRequest(Operation op, long ts) {
        inflight.put(op, InteractionBuilder.fromRequest(op, ts));
    }

    @Override
    public void onResponse(Operation op, Response res, long ts) {
        InteractionBuilder b = inflight.remove(op);
        if (b == null) return;

        Interaction interaction = b.withResponse(res, ts).build();
        append(interaction);
    }

    @Override
    public void onError(Operation op, long ts) {
        InteractionBuilder b = inflight.remove(op);
        if (b == null) return;

        Interaction interaction = b.withError(ts).build();
        append(interaction);
    }

    private synchronized void append(Interaction interaction) {
        history.addFirst(interaction);
        while (history.size() > maxSize) {
            history.removeLast();
        }
    }

    // === Query API used by CcrsContext ===

    /**
     * Get recent interaction history.
     * 
     * @param maxCount Maximum number of interactions to return
     * @return Recent interactions, most recent first
     */    
    public List<Interaction> getRecentInteractions(int n) {
        return history.stream().limit(n).toList();
    }

    /**
     * Get the most recent interaction.
     *
     * @return Last interaction, or empty if none available
     */
    public java.util.Optional<Interaction> getLastInteraction() {
        return history.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(history.getFirst());
    }

    /**
     * Get interactions for a specific logical source.
     * 
     * @param logicalSource The logical source identifier
     * @return List of interactions from that source
     */
    public List<Interaction> getInteractionsFor(String logicalSource) {
        return history.stream()
            .filter(i -> logicalSource.equals(i.logicalSource()))
            .toList();
    }

    @Override
    public String toString() {
        return "JasonInteractionLog{history[0]=" + (history.isEmpty() ? "none" : history.getFirst()) + ", size=" + history.size() + "}";
    }
}

