package ccrs.core.rdf;

import ccrs.core.contingency.dto.CcrsTrace;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Reusable in-memory store for per-context CCRS trace history.
 *
 * The owning context defines the lifecycle and scope of an instance.
 * In the Jason integration this means one history per agent context.
 */
public class InMemoryCcrsTraceHistory {

    private final LinkedList<CcrsTrace> traces = new LinkedList<>();
    private final int maxSize;

    public InMemoryCcrsTraceHistory(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    public synchronized Optional<CcrsTrace> getLast() {
        return traces.isEmpty() ? Optional.empty() : Optional.of(traces.getFirst());
    }

    public synchronized List<CcrsTrace> getRecent(int maxCount) {
        int count = Math.max(0, Math.min(maxCount, traces.size()));
        return new ArrayList<>(traces.subList(0, count));
    }

    public synchronized void record(CcrsTrace trace) {
        if (trace == null) {
            return;
        }

        traces.addFirst(trace);
        while (traces.size() > maxSize) {
            traces.removeLast();
        }
    }
}
