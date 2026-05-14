package example;

import ccrs.core.contingency.ContingencyCcrs;
import ccrs.core.contingency.ContingencyCcrsFactory;
import ccrs.core.contingency.dto.CcrsTrace;
import ccrs.core.contingency.dto.Situation;
import ccrs.core.contingency.dto.StrategyResult;
import ccrs.core.rdf.CcrsContext;
import ccrs.core.rdf.RdfTriple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CcrsLibraryConsumer {

    private CcrsLibraryConsumer() {
    }

    public static void main(String[] args) {
        reduceLibraryLogging();

        CcrsContext context = new InMemoryContext(List.of(
            new RdfTriple("https://example.org/agent", "https://example.org/isAt", "https://example.org/room-a"),
            new RdfTriple("https://example.org/room-a", "https://example.org/connectsTo", "https://example.org/room-b")
        ));

        Situation situation = Situation.failure("HTTP request failed")
            .failedAction("GET")
            .targetResource("https://example.org/api/orders")
            .httpError(503, "Service unavailable")
            .build();

        ContingencyCcrs ccrs = ContingencyCcrsFactory.withCoreDefaults();
        List<StrategyResult> results = ccrs.evaluate(situation, context);

        System.out.println("CCRS suggestions");
        if (results.isEmpty()) {
            System.out.println("- No strategy suggested an action.");
            return;
        }

        for (StrategyResult result : results) {
            if (result.isSuggestion()) {
                StrategyResult.Suggestion suggestion = result.asSuggestion();
                System.out.printf(
                    "- %s suggests %s target=%s confidence=%.2f params=%s%n",
                    suggestion.getStrategyId(),
                    suggestion.getActionType(),
                    suggestion.getActionTarget(),
                    suggestion.getConfidence(),
                    suggestion.getActionParams()
                );
                System.out.println("  rationale: " + suggestion.getRationale());
            } else {
                StrategyResult.NoHelp noHelp = result.asNoHelp();
                System.out.printf(
                    "- %s cannot help: %s (%s)%n",
                    noHelp.getStrategyId(),
                    noHelp.getReason(),
                    noHelp.getExplanation()
                );
            }
        }
    }

    private static void reduceLibraryLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.OFF);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(Level.OFF);
        }
    }

    private static final class InMemoryContext implements CcrsContext {
        private final List<RdfTriple> triples;
        private final List<CcrsTrace> traces = new ArrayList<>();

        private InMemoryContext(List<RdfTriple> triples) {
            this.triples = List.copyOf(triples);
        }

        @Override
        public List<RdfTriple> query(String subject, String predicate, String object) {
            return triples.stream()
                .filter(triple -> subject == null || subject.equals(triple.subject))
                .filter(triple -> predicate == null || predicate.equals(triple.predicate))
                .filter(triple -> object == null || object.equals(triple.object))
                .toList();
        }

        @Override
        public boolean contains(RdfTriple triple) {
            return triples.contains(triple);
        }

        @Override
        public Optional<CcrsTrace> getLastCcrsInvocation() {
            return traces.isEmpty() ? Optional.empty() : Optional.of(traces.get(0));
        }

        @Override
        public List<CcrsTrace> getCcrsHistory(int maxCount) {
            if (maxCount <= 0 || traces.isEmpty()) {
                return Collections.emptyList();
            }
            return traces.subList(0, Math.min(maxCount, traces.size()));
        }

        @Override
        public void recordCcrsInvocation(CcrsTrace trace) {
            traces.add(0, trace);
        }

        @Override
        public Optional<String> getCurrentResource() {
            return Optional.of("https://example.org/room-a");
        }

        @Override
        public String getAgentId() {
            return "example-agent";
        }

        @Override
        public boolean hasHistory() {
            return true;
        }
    }
}
