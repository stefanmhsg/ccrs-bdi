package ccrs.jacamo.jason.hypermedia.hypermedea;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.hypermedea.op.Operation;
import org.hypermedea.op.Response;

import ccrs.core.contingency.dto.Interaction;

/**
 * A centralized interaction log that partitions history by Agent Name.
 * 
 * It bridges the gap between CArtAgO's synchronous execution model (Requests)
 * and Hypermedea's asynchronous I/O (Responses) by preserving the agent identity
 * in an inflight context.
 */
public class JasonInteractionLog implements InteractionLogSink {

    private static final Logger logger = Logger.getLogger(JasonInteractionLog.class.getName());

    /**
     * Preserves the agent identity between the Request (Agent Thread) 
     * and the Response (Network Thread).
     */
    private record InflightContext(InteractionBuilder builder, String agentName) {}

    // Maps the specific operation instance to its context (Builder + Agent Name)
    private final Map<Operation, InflightContext> inflight = new ConcurrentHashMap<>();

    // Partitioned history: Agent Name -> Their Interaction History
    private final Map<String, Deque<Interaction>> agentHistories = new ConcurrentHashMap<>();

    private final int maxSize = 100;

    public JasonInteractionLog() {
    }
    // === WRITE API (Called by Artifacts) ===

    /**
     * Called when we explicitly know the agent name (from Artifact)
     */
    public void onRequest(Operation op, long ts, String agentName) {
        logger.fine("[JasonInteractionLog] onRequest from agent: '" + agentName + "'");
        inflight.put(op, new InflightContext(InteractionBuilder.fromRequest(op, ts), agentName));
    }

    // Fallback for interface compliance (defaults to "unknown")
    @Override
    public void onRequest(Operation op, long ts) {
        logger.fine("[JasonInteractionLog] onRequest from unknown agent");
        onRequest(op, ts, "unknown");
    }

    @Override
    public void onResponse(Operation op, Response res, long ts) {
        InflightContext context = inflight.remove(op);
        if (context == null) return;

        logger.fine("[JasonInteractionLog] onResponse for agent: '" + context.agentName() + "'");
        Interaction interaction = context.builder().withResponse(res, ts).build();
        append(context.agentName(), interaction);
    }

    @Override
    public void onError(Operation op, long ts) {
        InflightContext context = inflight.remove(op);
        if (context == null) return;

        logger.fine("[JasonInteractionLog] onError for agent: '" + context.agentName() + "'");
        Interaction interaction = context.builder().withError(ts).build();
        append(context.agentName(), interaction);
    }

    private synchronized void append(String agentName, Interaction interaction) {
        agentHistories.putIfAbsent(agentName, new ArrayDeque<>());
        Deque<Interaction> history = agentHistories.get(agentName);
        history.addFirst(interaction);
        while (history.size() > maxSize) history.removeLast();
        
        // Debug logging to track what agent names are being used
        logger.fine("[JasonInteractionLog] Appended interaction for agent: '" + agentName + "' (size=" + history.size() + ")");
    }

    // === READ API (Called by Agents/Context) ===

    public List<Interaction> getRecentInteractions(String agentName, int n) {
        Deque<Interaction> history = agentHistories.get(agentName);
        if (history == null) return List.of();
        logger.fine("[JasonInteractionLog] getRecentInteractions for agent: '" + agentName + "' (requested=" + n + ", available=" + history.size() + ")");
        return new ArrayList<>(history).stream().limit(n).toList();
    }

    public Optional<Interaction> getLastInteraction(String agentName) {
        Deque<Interaction> history = agentHistories.get(agentName);
        if (history == null || history.isEmpty()) return Optional.empty();
        logger.fine("[JasonInteractionLog] getLastInteraction for agent: '" + agentName + "'");
        return Optional.of(history.getFirst());
    }

    public List<Interaction> getInteractionsFor(String agentName, String logicalSource) {
        Deque<Interaction> history = agentHistories.get(agentName);
        if (history == null) return List.of();
        logger.fine("[JasonInteractionLog] getInteractionsFor for agent: '" + agentName + "' and logicalSource: '" + logicalSource + "'");
        return history.stream().filter(i -> logicalSource.equals(i.logicalSource())).toList();
    }

    public String formatAgentHistory(String agentName) {
        Deque<Interaction> history = agentHistories.get(agentName);
        if (history == null || history.isEmpty()) {
            // Show what agent names ARE tracked for debugging
            String tracked = agentHistories.isEmpty() ? "none" : String.join(", ", agentHistories.keySet());
            return "[JasonInteractionLog] {requested='" + agentName + "', found=none, tracked=[" + tracked + "]}";
        }
        return "[JasonInteractionLog] {history[0]=" + history.getFirst()
            + ", size=" + history.size() + "}";
    }

}