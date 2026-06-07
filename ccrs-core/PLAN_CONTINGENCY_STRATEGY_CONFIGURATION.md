# PLAN_CONTINGENCY_STRATEGY_CONFIGURATION: Migrate contingency strategies to typed configuration

This ExecPlan is a living document. The sections `Rules`, `Progress`, `Surprises & Discoveries`, and `Decision Log` must be kept up to date as work proceeds. Work packages must be kept current with their local context, discussion, todos, concrete steps, validation, and outcomes.

This repository does not currently have a checked-in `PLANS.md` guide. This plan follows the local `PLAN_<SCOPE>.md` convention described in [../AGENTS.md](../AGENTS.md) and is scoped to the reusable `ccrs-core` package plus the adapters that must continue to consume it.

## Purpose / Big Picture

Agent designers who consume the CCRS libraries from Maven need one predictable way to configure contingency strategy behavior. After this migration, a Java application, the JaCaMo adapter, and the React JPype adapter should all be able to construct contingency CCRS with the same central `ContingencyConfiguration` object and have strategy-specific values such as prediction prompt history limits, retry thresholds, consultation context limits, and stop exhaustion limits flow into the strategies that use them.

The observable result is not only a cleaner API. A user should be able to set a non-default value such as `prediction_llm.maxHistoryActions = 20`, create CCRS through the default factory or ServiceLoader provider path, and observe that the registered `PredictionLlmStrategy` uses that value without hand-instantiating the strategy. Existing React and JaCaMo adapter paths must still compile and pass smoke checks before legacy per-strategy mutator APIs are removed or retired.

## Rules

- Rule: Keep `ccrs-core` provider-neutral and agent-agnostic.
  Reason: The core module must not depend on JaCaMo, Jason, CArtAgO, Hypermedea, LangChain4j, A2A, dotenv, or the React Python adapter.
  Added/Updated: 2026-06-07 / Codex.

- Rule: Put first-party strategy settings behind typed option objects exposed through `ContingencyConfiguration`.
  Reason: Flat configuration fields will become unmaintainable as strategies grow, while direct mutable strategy setters do not work for ServiceLoader-created strategies.
  Added/Updated: 2026-06-07 / Codex.

- Rule: Preserve ServiceLoader as the optional capability mechanism.
  Reason: `ccrs-langchain4j` and `ccrs-a2a` are optional provider modules, and `ccrs-jacamo` must not import either concrete capability.
  Added/Updated: 2026-06-07 / Codex.

- Rule: Smoke test both adapters before removing legacy configuration paths.
  Reason: The React adapter consumes Maven-local jars through JPype, while the JaCaMo adapter consumes `ContingencyCcrsFactory` through `CcrsJacamoRuntime`; both paths can break even when `ccrs-core` compiles.
  Added/Updated: 2026-06-07 / Codex.

- Rule: Do not run the local `.jcm` JaCaMo agents as the normal smoke test for this plan.
  Reason: [../AGENTS.md](../AGENTS.md) says not to try to run JaCaMo agents in the `.jcm` files. Use compile checks and focused Java adapter tests instead.
  Added/Updated: 2026-06-07 / Codex.

- Rule: Remove or deprecate legacy per-strategy mutators only after all first-party call sites and docs have moved to the central configuration path.
  Reason: The target is full migration and cleanup, but removal before adapter validation would hide behavioral regressions behind API churn.
  Added/Updated: 2026-06-07 / Codex.

## Now / Next / Later

Use this matrix as the visual overview of work package priority and timing. Work package details live in the `Work Packages` section.

| NOW | NEXT | LATER |
| --- | --- | --- |
| Highest priority | Medium-term goals | Nice-to-have ideas |
| Current sprint/month | Next 1-3 months | No time commitment |
| Fully defined | Roughly specified | Fuzzy / visionary |
| Already running or starting now | Waiting for capacity | May be discarded |
| WP1: Inventory current knobs and define target semantics (complete) |  | WP6: Add optional system-property overlay |
| WP2: Add typed options to `ccrs-core` (complete) |  |  |
| WP3: Propagate configuration through factories and ServiceLoader providers (complete) |  |  |
| WP4: Clean up legacy strategy mutators and docs (complete) |  |  |
| WP5: React and JaCaMo adapter compatibility smoke tests (complete) |  |  |

## Progress

- [x] (2026-06-07) Created this plan under `ccrs-core` for the contingency strategy configuration migration.
- [x] (2026-06-07) Read the contingency CCRS README, `ContingencyConfiguration`, default factory/provider path, strategy classes, `ccrs-jacamo` adapter docs, React CCRS adapter docs, and repository guidance.
- [x] (2026-06-07) Added the WP1 candidate knob inventory table with `Certain`, `Unsure`, and `Declined` classifications.
- [x] (2026-06-07) Added relative source links to every WP1 candidate knob row.
- [x] (2026-06-07) Explicitly deferred all WP1 `Unsure` rows and proceeded with only `Certain` strategy options.
- [x] (2026-06-07) Implemented WP2 typed option classes, central `ContingencyConfiguration` option accessors/builders, third-party option storage, and option-based built-in strategy constructors.
- [x] (2026-06-07) Implemented WP3 factory overloads, provider context propagation, first-party ServiceLoader option wiring, and the JaCaMo runtime configuration bridge.
- [x] (2026-06-07) Completed WP4 by removing legacy mutable strategy setters from first-party strategy classes, converting strategy settings to constructor-time option snapshots, updating core/provider/adapter docs, and updating the standalone Maven consumer example to use central typed configuration.
- [x] (2026-06-07) Published updated `0.1.0-SNAPSHOT` artifacts to Maven local and verified the standalone Maven consumer compiles against the new configuration API.
- [x] (2026-06-07) Completed WP5 by adding React Python-side contingency configuration mapping, documenting React and JaCaMo configuration usage, publishing updated Maven-local artifacts, and running Java, JaCaMo, and React adapter smokes.

## Surprises & Discoveries

- Observation: Before WP4, `PredictionLlmStrategy` had fluent setters such as `maxHistoryActions(...)`, but those setters only helped callers that manually created the strategy.
  Evidence: Initial WP1 inspection found the setter pattern in [src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java), while [../ccrs-langchain4j/src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jPredictionStrategyProvider.java](../ccrs-langchain4j/src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jPredictionStrategyProvider.java) registered `new PredictionLlmStrategy(llmClient)` through ServiceLoader with no configuration input. WP4 removed those legacy mutators after provider context propagation was in place.

- Observation: `ContingencyCcrsFactory` currently discovers providers with only a `StrategyRegistry` and `ClassLoader` boundary.
  Evidence: [src/main/java/ccrs/core/contingency/ContingencyCcrsFactory.java](src/main/java/ccrs/core/contingency/ContingencyCcrsFactory.java) calls `provider.registerStrategies(registry)`, and [src/main/java/ccrs/core/contingency/CcrsStrategyProvider.java](src/main/java/ccrs/core/contingency/CcrsStrategyProvider.java) has one abstract method that accepts only `StrategyRegistry`.

- Observation: The React adapter depends on Maven-local CCRS jars for normal Java-backed contingency smoke tests.
  Evidence: [../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py](../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py) constructs `CcrsJavaRuntime.from_maven_local(...)` and uses `ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(class_loader)` when provider discovery is requested.

- Observation: `ccrs-core` and `ccrs-jacamo` currently have no `src/test` tree.
  Evidence: Directory inspection under `ccrs-bdi/ccrs-core/src` and `ccrs-bdi/ccrs-jacamo/src` found `src/main` only. Focused tests may need to create test source roots and add a test framework or use compile/smoke helpers.

- Observation: `BacktrackStrategy` has no public configuration setter, but it reads up to `1000` recent interactions when building its interaction graph.
  Evidence: [src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java) calls `context.getRecentInteractions(1000)` inside `buildInteractionGraph(...)`.

- Observation: The standalone Maven consumer can fail against stale local SNAPSHOT artifacts even when the in-repo modules compile.
  Evidence: [../examples/ccrs-library-consumer/src/main/java/example/CcrsLibraryConsumer.java](../examples/ccrs-library-consumer/src/main/java/example/CcrsLibraryConsumer.java) initially failed to compile because Maven local still exposed the old `ContingencyConfiguration.Builder` and `ContingencyCcrsFactory` APIs. Running `.\gradlew.bat publishToMavenLocal` refreshed the artifacts and the consumer then compiled after correcting the example to use `RetryStrategyOptions.Builder.initialDelayMs(...)`.

- Observation: The React adapter had no way to pass non-default Java strategy options before WP5.
  Evidence: [../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py](../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py) only called `ContingencyCcrs.withDefaults()` or `ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(classLoader)`. WP5 added a Python mapping bridge that builds Java `ContingencyConfiguration` through JPype and passes it into the same Java factory overloads.

- Observation: JaCaMo runtime configuration can be smoke-tested without launching `.jcm` agents.
  Evidence: A Java 21 JShell smoke imported [../ccrs-jacamo/src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java](../ccrs-jacamo/src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java), installed a central `ContingencyConfiguration`, called `createContingencyCcrs()`, and printed configured retry/stop option values plus `true` for retry strategy registration.

## Decision Log

- Decision: Use `ContingencyConfiguration` as the only first-party public entry point for strategy settings.
  Rationale: It is already the public object used to configure strategy selection, tracing, learning thresholds, enabled strategies, and max suggestions. Extending it with typed nested option groups gives Maven consumers one place to look.
  Date/Author: 2026-06-07 / Codex.

- Decision: Use typed option objects for built-in strategies and a generic namespaced option map for third-party strategy providers.
  Rationale: Built-in strategies need discoverable Java APIs, while extension modules should not require `ccrs-core` to know their option classes in advance.
  Date/Author: 2026-06-07 / Codex.

- Decision: Pass configuration to ServiceLoader providers through a new provider context while keeping the old `registerStrategies(StrategyRegistry)` method as the compatibility bridge during migration.
  Rationale: Existing providers should keep compiling while first-party providers opt into the richer context. This makes the migration staged and easy to verify.
  Date/Author: 2026-06-07 / Codex.

- Decision: Strategies should receive immutable options at construction time instead of reading global configuration during `evaluate(...)`.
  Rationale: Construction-time options keep strategies deterministic, simple to test, and free of hidden runtime dependencies. Mutable global lookups would make repeated evaluations harder to reason about.
  Date/Author: 2026-06-07 / Codex.

- Decision: Adapter compatibility means behavior compatibility for the current React and JaCaMo entry points, not preserving every legacy fluent setter forever.
  Rationale: The user explicitly wants a full migration and legacy cleanup. Public setter removal is acceptable only after first-party adapters, examples, and docs use the new API and smoke tests prove the adapter paths still work.
  Date/Author: 2026-06-07 / Codex.

- Decision: Add `BacktrackStrategyOptions` to the first-pass option set, but only expose the history bound initially.
  Rationale: `maxRecentInteractions = 1000` is a resource-use and performance knob that agent designers may reasonably need to tune. Backtrack URI filtering, validation weights, and confidence coefficients are more algorithm-shaped and remain `Unsure` rows until a richer policy design is justified.
  Date/Author: 2026-06-07 / Codex.

- Decision: Defer every WP1 `Unsure` row and implement WP2 using only `Certain` rows.
  Rationale: The user chose to proceed with the certain first-pass option set now. Confidence formulas, parser calibration, prompt templates, and consultation discovery/projection policies remain outside WP2 until a later design pass promotes or declines them.
  Date/Author: 2026-06-07 / user, recorded by Codex.

- Decision: Remove first-party legacy strategy mutators instead of deprecating them for this snapshot migration.
  Rationale: The user explicitly requested full migration and legacy cleanup. Constructors remain for collaborators and typed option objects now cover first-party strategy settings, so retaining mutable setters would keep two public configuration models alive.
  Date/Author: 2026-06-07 / Codex.

- Decision: Add React Python mapping support in WP5 instead of creating a new configuration plan.
  Rationale: Adapter compatibility now includes demonstrating how React users provide the same central Java strategy options. The existing React adapter plan already covers this boundary, while broader string-based deployment overlays remain WP6.
  Date/Author: 2026-06-07 / Codex.

## Context and Orientation

The repository root for the Java libraries is `S:\dev\ma\ccrs-bdi`. The reusable core module is [ccrs-core](.), and its contingency package is [src/main/java/ccrs/core/contingency](src/main/java/ccrs/core/contingency). Contingency CCRS means the failure-recovery part of CCRS. It evaluates a `Situation`, consults registered strategies such as retry, backtrack, consultation, LLM prediction, and stop, records a `CcrsTrace`, and returns selected `StrategyResult` suggestions.

The current central configuration class is [src/main/java/ccrs/core/contingency/ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java). It controls orchestration behavior such as strategy enablement, escalation policy, max suggestions, tracing, trace-based learned strategy selection, and the thresholds that decide whether an expensive strategy should be evaluated after a cheaper strategy has already produced a suggestion.

The built-in strategies currently keep their own local fields and fluent setters. Important examples are [src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java), [src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java), [src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java), and [src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java). This local setter pattern is easy for manual Java construction but fails the ServiceLoader path, because optional providers construct strategies internally.

The default factory path is [src/main/java/ccrs/core/contingency/ContingencyCcrsFactory.java](src/main/java/ccrs/core/contingency/ContingencyCcrsFactory.java). It builds a core `ContingencyCcrs`, registers default built-in strategies, and discovers optional `CcrsStrategyProvider` implementations from Java ServiceLoader. ServiceLoader means Java's standard mechanism for finding implementations listed in `META-INF/services/<interface-name>` files inside jars.

The optional LLM capability lives in [../ccrs-langchain4j](../ccrs-langchain4j). It creates a provider-specific `LlmClient` and registers `PredictionLlmStrategy`. The optional A2A capability lives in [../ccrs-a2a](../ccrs-a2a). It creates an A2A `ConsultationChannel` and registers `ConsultationStrategy`. These modules must remain provider adapters only; generic strategy options belong in `ccrs-core`.

The JaCaMo adapter lives in [../ccrs-jacamo](../ccrs-jacamo). Its contingency internal action asks [../ccrs-jacamo/src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java](../ccrs-jacamo/src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java) to create a `ContingencyCcrs`. That runtime defaults to `ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders`. The adapter must keep this boundary and must not import LangChain4j or A2A directly.

The React adapter lives in [../../ccrs-react](../../ccrs-react). It uses JPype to load Maven-local CCRS jars and call Java from Python. Its contingency wrapper is [../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py](../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py). The wrapper currently calls either `ContingencyCcrs.withDefaults()` or `ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(class_loader)`. This plan must keep those defaults working and later add a way to pass the new Java configuration when React users need non-default strategy options.

## Work Packages

### WP1: Inventory current knobs and define target semantics

Status: Done

Purpose: Produce a complete list of strategy values that are truly user-facing configuration and decide which option object owns each value. A future implementer should be able to edit from this inventory without guessing whether a constant is policy, resource control, or internal scoring detail.

Local context: Inspect [src/main/java/ccrs/core/contingency](src/main/java/ccrs/core/contingency), especially `RetryStrategy`, `BacktrackStrategy`, `StopStrategy`, `ConsultationStrategy`, `PredictionLlmStrategy`, `JsonActionParser`, `DefaultPredictionPromptBuilder`, `TraceBasedStrategySelectionModel`, and `ContingencyConfiguration`. Search for private fields with literal defaults, public fluent setters, fixed limits, and hard-coded confidence values.

Discussion: Not every numeric constant should become user configuration. Values that control resource use, prompt size, retry behavior, context window size, strategy availability, or fallback confidence are good options. Purely local formula coefficients can stay private unless a strategy's external behavior is clearly policy-like and likely to vary by domain. The inventory must explicitly classify each discovered value so cleanup does not silently remove useful controls.

Candidate knob status meanings:

- `Certain`: include in the first central configuration migration or keep as an already-central `ContingencyConfiguration` setting.
- `Unsure`: do not implement until the row is resolved; these may need a separate policy object, a cleaner abstraction, or user feedback.
- `Declined`: do not expose as a first-party configuration knob in this migration; keep internal, keep as a dependency injection point, or rely on a custom implementation.

| Status | Candidate knob | Current source and default | Target owner / semantics | Notes |
| --- | --- | --- | --- | --- |
| Certain | Strategy enablement by id and category | [ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java): enabled strategies, disabled strategies, enabled categories | Keep in `ContingencyConfiguration` orchestration settings | Already central; do not duplicate in per-strategy option objects. |
| Certain | Escalation policy, max level, max suggestions, tracing, learned selection toggle | [ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java): `PARALLEL`, `maxEscalationLevel = 4`, `maxSuggestions = 7`, `traceEnabled = true`, `learnedSelectionEnabled = true` | Keep in `ContingencyConfiguration` orchestration settings | Already central and not a strategy-specific option. |
| Certain | Learned selection history and gate thresholds | [ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java): `learningHistoryLimit = 25`, `minimumLearningSamples = 2`, `minimumExpectedConfidenceGain = 0.10`, `highConfidenceEvaluationFloor = 0.80`, `cheapEvaluationTimeMs = 250` | Keep in `ContingencyConfiguration` strategy-selection settings | These configure the selector, not individual strategies. |
| Declined | Deprecated cost reference times | [ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java): L0 `50`, L1 `100`, L2 `1000`, L3 `2000`, L4 `3000`, deprecated | Do not move into new strategy options | Preserve only as deprecated compatibility until WP4 cleanup. |
| Certain | LLM prediction max action history | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `maxHistoryActions = 50` | `PredictionLlmStrategyOptions.maxHistoryActions` | Prompt-size and context-window control; migrated from a legacy per-strategy mutator. |
| Certain | LLM prediction max perceived triples per interaction | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `maxInteractionStateTriples = 100` | `PredictionLlmStrategyOptions.maxInteractionStateTriples` | Prompt-size and evidence-volume control; migrated from a legacy per-strategy mutator. |
| Certain | LLM prediction max previous CCRS traces | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `maxCcrsTraces = 10` | `PredictionLlmStrategyOptions.maxCcrsTraces` | Prompt-size and trace-history control; migrated from a legacy per-strategy mutator. |
| Certain | LLM prediction local neighborhood bounds | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `maxNeighborhoodOutgoing = CcrsContext.DEFAULT_MAX_OUTGOING`, `maxNeighborhoodIncoming = CcrsContext.DEFAULT_MAX_INCOMING`; defaults in [CcrsContext.java](src/main/java/ccrs/core/rdf/CcrsContext.java) | `PredictionLlmStrategyOptions.maxNeighborhoodOutgoing`, `maxNeighborhoodIncoming` | Prompt-size and RDF-neighborhood control; migrated from a legacy per-strategy mutator. |
| Certain | LLM prompt triple namespace filter | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `filteredTripleNamespaces = ["https://example.org/ui"]` | `PredictionLlmStrategyOptions.filteredTripleNamespaces` | Domain presentation filtering should be available through ServiceLoader-created strategies. |
| Certain | LLM fallback confidence when the model omits confidence | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `baseConfidence = 0.6` | `PredictionLlmStrategyOptions.baseConfidence` | Directly affects suggestion ranking; migrated from a legacy per-strategy mutator. |
| Certain | Default parser plain-text fallback enablement | [JsonActionParser.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/JsonActionParser.java): `enablePlainTextFallback = true` | `PredictionLlmStrategyOptions.plainTextFallbackEnabled` when using the default parser | ServiceLoader-created prediction strategies need a way to request strict JSON without custom strategy construction. |
| Unsure | Default parser fallback confidence | [JsonActionParser.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/JsonActionParser.java): assigns `0.3` to plain-text extracted actions | Possibly `PredictionLlmStrategyOptions.plainTextFallbackConfidence` or parser-specific options | This affects ranking but belongs to parser calibration; decide before WP2 whether to expose it now. |
| Unsure | Prediction prompt template text | [DefaultPredictionPromptBuilder.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/DefaultPredictionPromptBuilder.java): `withPredictionTemplate(...)`; default template embedded in builder | Possibly constructor dependency only, or `PredictionLlmStrategyOptions.predictionTemplate` for default builder | Prompt customization may be too large for a value option; provider-created strategies make it tempting, but a custom `PromptBuilder` is cleaner for advanced users. |
| Declined | LLM client, prompt builder, and response parser objects | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java): `withClient(...)`, `withPromptBuilder(...)`, `withResponseParser(...)` | Keep as constructor/provider dependencies, not typed value options | These are collaborators rather than scalar settings. WP4 should remove or deprecate mutable setters after constructors/provider context cover them. |
| Declined | Parser action keyword lists and URI extraction heuristics | [JsonActionParser.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/JsonActionParser.java): `tryPlainTextParse(...)` hard-coded keywords and URI detection | Keep internal or use a custom `LlmResponseParser` | Exposing these as maps would make the default parser look like a DSL; custom parser injection is the cleaner extension point. |
| Declined | Rationale truncation lengths in prediction/parser | [PredictionLlmStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java) and [JsonActionParser.java](src/main/java/ccrs/core/contingency/strategies/internal/prediction/JsonActionParser.java): truncate explanations around `200` chars | Keep internal formatting detail | This is diagnostic text shaping, not strategy behavior. |
| Certain | Retry max attempts | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `maxAttempts = 3` | `RetryStrategyOptions.maxAttempts` | Retry policy control; already has a public mutator. |
| Certain | Retry initial delay | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `initialDelayMs = 1000` | `RetryStrategyOptions.initialDelayMs` | Retry policy control; already has a public mutator. |
| Certain | Retry backoff multiplier | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `backoffMultiplier = 2.0` | `RetryStrategyOptions.backoffMultiplier` | Retry policy control; already has a public mutator. |
| Certain | Retriable error codes | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `retriableCodes = 500, 502, 503, 504, timeout, connection_reset, connection_refused` | `RetryStrategyOptions.retriableCodes` | Domain and protocol policy; already has append-only public mutator, but options should support full replacement. |
| Certain | Retry lookback limit | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `retryLookbackLimit = 25` | `RetryStrategyOptions.retryLookbackLimit` | Trace-history bound; migrated from a legacy per-strategy mutator. |
| Unsure | Retry confidence policy | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `calculateConfidence` default `0.7`, `503 -> 0.8`, `500 -> 0.5`, decay `0.8` per attempt | Possibly `RetryStrategyOptions.confidencePolicy` or scalar confidence fields | Directly affects ranking, but exposing every coefficient may be premature. Resolve before WP2 if confidence calibration is in scope. |
| Declined | Retry prior-situation matching predicate | [RetryStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/RetryStrategy.java): `situationMatchesForRetry(...)` matches type, failed action, and target resource | Keep internal for now | This is semantic identity logic rather than a normal setting. |
| Certain | Backtrack max recent interactions | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): `buildInteractionGraph(...)` reads `context.getRecentInteractions(1000)` | `BacktrackStrategyOptions.maxRecentInteractions` | Resource-use and graph-size control; this makes `BacktrackStrategyOptions` part of the first-pass option set. |
| Unsure | Backtrack URI eligibility policy | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): `isValidUri(...)` allows HTTP(S), excludes fragments, excludes values containing `agent` | Possibly `BacktrackStrategyOptions.allowedSchemes`, `excludedUriSubstrings`, or a pluggable URI predicate | The hard-coded `agent` exclusion is domain-shaped; decide whether to parameterize or replace with a cleaner policy object. |
| Unsure | Backtrack checkpoint validation weights | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): `validateCheckpoint(...)` unexplored `0.4`, history `0.3`, multiple outgoing links `0.3`, valid score `> 0` | Possibly `BacktrackStrategyOptions.validationWeights` | These tune algorithm behavior, but exposing them may overfit the public API to one implementation. |
| Unsure | Backtrack confidence formula | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): `calculateConfidence(...)` base `0.3`, option cap `0.25`, option factor `0.10`, distance cap `0.35`, distance factor `0.06`, clamp `0.1..0.9` | Possibly `BacktrackStrategyOptions.confidencePolicy` | Directly affects ranking; likely defer unless tests or experiments show agent designers need calibration. |
| Declined | Backtrack ranking order | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): distance asc, unexplored count desc, recency desc, validation score desc | Keep internal | This is the core algorithm, not a first-pass setting. |
| Declined | Backtrack opportunistic guidance metadata and note types | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): `backtrack_step`, `unexplored_option`, metadata keys such as `origin`, `strategy`, `checkpoint` | Keep internal contract | Adapter interoperability depends on stable note shapes. |
| Declined | Backtrack direct-jump fallback | [BacktrackStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/BacktrackStrategy.java): `computeBacktrackPath(...)` returns `List.of(checkpoint)` when no predecessor path exists | Keep internal | This is a recovery fallback inside the algorithm. |
| Certain | Consultation max recent interactions | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `maxRecentInteractions = 10` | `ConsultationStrategyOptions.maxRecentInteractions` | Context-window control; already configurable through constructor and public mutator. |
| Certain | Consultation max discovered agent candidates | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `MAX_AGENT_CANDIDATES = 5` | `ConsultationStrategyOptions.maxAgentCandidates` | Resource-use and request-context bound; currently hidden but user-facing in large social contexts. |
| Certain | Consultation fallback confidence | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `DEFAULT_CONFIDENCE = 0.5` | `ConsultationStrategyOptions.defaultConfidence` | Directly affects ranking when channel confidence is absent or invalid. |
| Certain | Consultation previous CCRS history limit | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `buildContext(...)` reads `context.getCcrsHistory(3)` | `ConsultationStrategyOptions.maxCcrsTraces` | Hidden context-window bound; should be consistent with the central options approach. |
| Unsure | Consultation peer-discovery RDF predicates | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `MAZE_CONTAINS`, `A2A_AGENT_CARD`, `A2A_PROVIDES_TYPE`, `A2A_PROVIDES_PROPERTY` constants | Possibly `ConsultationStrategyOptions.discoveryPredicates` or a pluggable target-discovery policy | The current strategy is partly A2A-shaped and partly maze-shaped; decide whether to parameterize predicates or extract a discovery collaborator. |
| Unsure | Consultation self-filtering rule | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `isConsultableAgent(...)` excludes exact self id and URI ending with `/selfAgentId` | Possibly discovery policy | This is deployment-specific enough to question, but not a simple scalar knob. |
| Unsure | Consultation RDF projection heuristic | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): first literal statement from `text/turtle` artifact becomes POST body on focus resource | Possibly projection policy, not scalar options | This is behavior-heavy and should not be exposed as a handful of loose booleans. |
| Declined | Consultation channel object | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `withChannel(...)` and constructors | Keep as constructor/provider dependency, not typed value option | The channel is a collaborator supplied by optional modules such as `ccrs-a2a`. |
| Declined | Consultation rationale truncation length | [ConsultationStrategy.java](src/main/java/ccrs/core/contingency/strategies/social/ConsultationStrategy.java): `buildRationale(...)` truncates advice around `150` chars | Keep internal formatting detail | Diagnostic formatting does not need central configuration. |
| Certain | Stop require exhaustion | [StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java): `requireExhaustion = true` | `StopStrategyOptions.requireExhaustion` | Stop policy control; already has a public mutator. |
| Certain | Stop exhaustion threshold | [StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java): `exhaustionThreshold = 2` | `StopStrategyOptions.exhaustionThreshold` | Stop policy control; already has a public mutator. |
| Certain | Stop lookback limit | [StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java): `stopLookbackLimit = 30` | `StopStrategyOptions.stopLookbackLimit` | Hidden trace-history bound; should be configurable with the rest of the stop policy. |
| Declined | Stop static `immediate()` convenience | [StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java): `immediate()` delegates to `requireExhaustion(false)` | Use `StopStrategyOptions.requireExhaustion(false)` | Not a separate knob; WP4 can remove or keep as deprecated convenience. |
| Declined | Stop confidence value | [StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java): stop suggestion uses confidence `1.0` | Keep internal semantic value | Stop is the last-resort fallback after exhaustion; changing confidence is more likely to obscure semantics than improve configuration. |
| Declined | Stop reason mapping for HTTP 410/401/403 | [StopStrategy.java](src/main/java/ccrs/core/contingency/strategies/internal/StopStrategy.java): `determineReason(...)` maps status to `resource_gone` and `access_denied` | Keep internal for now | This is explanation mapping, not first-pass strategy behavior. |
| Certain | Third-party strategy option escape hatch | Not present today; target owner is [ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java) | `ContingencyConfiguration.strategyOptions(String strategyId, Object options)` | Required so external providers can use the same central configuration channel without core knowing their option types. |
| Declined | System-property overlay | Not present today; planned for [ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java) in WP6 | Do not implement in WP2 | The primary API is typed Java configuration; string property overlay is later deployment convenience only. |

First-pass option object decision from this inventory:

- Create `PredictionLlmStrategyOptions`, including the default parser plain-text fallback toggle if the default parser is used.
- Create `RetryStrategyOptions`.
- Create `BacktrackStrategyOptions` with at least `maxRecentInteractions`; defer scoring-policy fields unless the `Unsure` rows are resolved before WP2.
- Create `ConsultationStrategyOptions`.
- Create `StopStrategyOptions`.
- Keep existing `ContingencyConfiguration` orchestration and learned-selection settings in the root configuration object, not in strategy option objects.
- Keep collaborator objects such as `LlmClient`, `PromptBuilder`, `LlmResponseParser`, and `ConsultationChannel` as constructor or provider dependencies, not value options.

Todos:

- [x] List existing public strategy mutators and mark whether each becomes a typed option, constructor dependency, or removed internal detail.
- [x] List hard-coded private strategy values that have no public mutator and decide whether to expose them.
- [x] Define first-pass option object names and default values for `Certain` rows.
- [x] Decide that `BacktrackStrategy` needs a first-pass options object for `maxRecentInteractions`, while URI filtering and scoring policy remain unresolved.
- [ ] Resolve or explicitly defer all `Unsure` rows before WP2 implementation starts.

Concrete steps: From `S:\dev\ma\ccrs-bdi`, run:

    rg -n "private .* =|public .*Strategy .*\\(|max[A-Z]|with[A-Z]|threshold|confidence|limit|DEFAULT|static final int|static final double" ccrs-core/src/main/java/ccrs/core/contingency

Then record the final inventory in this work package before implementing WP2.

Validation and acceptance: WP1 is accepted when this plan names every migrated option, every deliberately non-configurable constant, and every legacy setter targeted for removal or deprecation. No code behavior changes are required for WP1. WP1 can hand off to WP2 once each `Unsure` row is either promoted to `Certain`, moved to a later work package, or explicitly declined.

Outcome and notes: Candidate inventory added on 2026-06-07. The certain first-pass migration now includes a `BacktrackStrategyOptions` object for history bounds. On 2026-06-07, the user explicitly deferred every `Unsure` row so WP2 could proceed with only `Certain` rows. The remaining design risk is confidence and discovery policy: retry/backtrack confidence formulas and consultation target discovery may deserve richer policy abstractions rather than scalar fields.

### WP2: Add typed options to `ccrs-core`

Status: Done

Purpose: Give Java Maven consumers a stable, discoverable API for configuring built-in contingency strategies through `ContingencyConfiguration`.

Local context: The target files are [src/main/java/ccrs/core/contingency/ContingencyConfiguration.java](src/main/java/ccrs/core/contingency/ContingencyConfiguration.java) and new option classes under a package such as `ccrs.core.contingency.options`. Strategy constructors live beside the existing strategy classes.

Discussion: Use immutable option values. Java records with nested builders are a good fit if the project Java level supports records. If records are not available or the module targets older Java, use final classes with private final fields, getters, `defaults()`, `builder()`, and `toBuilder()`. Defaults must match current behavior exactly.

Todos:

- [x] Add `PredictionLlmStrategyOptions` with defaults for max history actions, max interaction state triples, max CCRS traces, max neighborhood outgoing/incoming, base confidence, and filtered triple namespaces.
- [x] Add `RetryStrategyOptions` with defaults for max attempts, initial delay, backoff multiplier, retriable codes, retry lookback limit, and any exposed confidence policy values selected in WP1.
- [x] Add `BacktrackStrategyOptions` with at least max recent interactions; include URI filtering or scoring-policy fields only if the WP1 `Unsure` rows are resolved before implementation.
- [x] Add `ConsultationStrategyOptions` with defaults for max recent interactions, max agent candidates if exposed, and fallback confidence.
- [x] Add `StopStrategyOptions` with defaults for require exhaustion, exhaustion threshold, and stop lookback limit.
- [x] Add `ContingencyConfiguration.Builder` methods for each option group.
- [x] Add a generic extension option map for third-party strategies.
- [x] Update strategies to use constructor-injected options while preserving existing behavior.

Concrete steps: Add option classes and builder methods first, then update strategy constructors. Example target API shape:

    ContingencyConfiguration config = ContingencyConfiguration.builder()
        .predictionLlm(options -> options
            .maxHistoryActions(20)
            .maxInteractionStateTriples(50))
        .retry(options -> options
            .maxAttempts(5)
            .initialDelayMs(500))
        .build();

    ContingencyCcrs ccrs =
        ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(config);

For extension modules, expose a namespaced option hook shaped like:

    ContingencyConfiguration config = ContingencyConfiguration.builder()
        .strategyOptions("my_strategy", MyStrategyOptions.builder().windowSize(25).build())
        .build();

The getter should be type-safe at the call site:

    Optional<MyStrategyOptions> options =
        config.getStrategyOptions("my_strategy", MyStrategyOptions.class);

Validation and acceptance: Core compile must pass from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava

If WP2 adds tests, run:

    .\gradlew.bat :ccrs-core:test

Expected behavior: default `ContingencyConfiguration.defaults()` produces strategies with the same defaults as before. A non-default option passed through a direct constructor changes only the intended strategy field.

Outcome and notes: Implemented on 2026-06-07. Added immutable option classes under `ccrs.core.contingency.options` for prediction LLM, retry, backtrack, consultation, and stop. `ContingencyConfiguration` now exposes typed option getters, builder customizers, and `strategyOptions(String strategyId, Object options)` plus typed lookup for third-party providers. Built-in strategies now have option-based constructors while existing constructors and fluent mutators remain for WP4 cleanup.

Validation run on 2026-06-07 from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava
    .\gradlew.bat :ccrs-core:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava
    .\gradlew.bat :ccrs-core:test

Results: core compile passed; core plus LangChain4j and A2A provider compile passed; `:ccrs-core:test` passed with `NO-SOURCE` for test compilation and execution because the module currently has no test source tree.

### WP3: Propagate configuration through factories and ServiceLoader providers

Status: Now

Purpose: Make central strategy options work for strategies that are created indirectly by default registration and optional provider discovery.

Local context: The main files are [src/main/java/ccrs/core/contingency/ContingencyCcrs.java](src/main/java/ccrs/core/contingency/ContingencyCcrs.java), [src/main/java/ccrs/core/contingency/ContingencyCcrsFactory.java](src/main/java/ccrs/core/contingency/ContingencyCcrsFactory.java), [src/main/java/ccrs/core/contingency/CcrsStrategyProvider.java](src/main/java/ccrs/core/contingency/CcrsStrategyProvider.java), [../ccrs-langchain4j/src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jPredictionStrategyProvider.java](../ccrs-langchain4j/src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jPredictionStrategyProvider.java), and [../ccrs-a2a/src/main/java/ccrs/capabilities/a2a/A2aConsultationStrategyProvider.java](../ccrs-a2a/src/main/java/ccrs/capabilities/a2a/A2aConsultationStrategyProvider.java).

Discussion: Keep `CcrsStrategyProvider` source-compatible by retaining the existing abstract method and adding a default overload. First-party providers should override the new overload. Third-party providers that only implement the old method should still work and receive default behavior.

Todos:

- [x] Add `CcrsStrategyProviderContext` in `ccrs.core.contingency`.
- [x] Add a default provider method `registerStrategies(StrategyRegistry registry, CcrsStrategyProviderContext context)` that delegates to the old method.
- [x] Add factory overloads that accept `ContingencyConfiguration`.
- [x] Add `ContingencyCcrs.withDefaults(ContingencyConfiguration config)` and `registerDefaultStrategies(StrategyRegistry registry, ContingencyConfiguration config)`.
- [x] Update core default strategy registration to pass typed options into retry, backtrack if applicable, and stop.
- [x] Update LangChain4j and A2A providers to read `PredictionLlmStrategyOptions` and `ConsultationStrategyOptions` from the context.
- [x] Update `CcrsJacamoRuntime` so callers can set a central configuration without replacing the whole supplier.

Concrete steps: Add APIs in this order to keep compilation failures local:

    public final class CcrsStrategyProviderContext {
        public ContingencyConfiguration configuration();
        public ClassLoader classLoader();
        public <T> Optional<T> getStrategyOptions(String strategyId, Class<T> type);
    }

    public interface CcrsStrategyProvider {
        void registerStrategies(StrategyRegistry registry);

        default void registerStrategies(
                StrategyRegistry registry,
                CcrsStrategyProviderContext context) {
            registerStrategies(registry);
        }
    }

Then add factory overloads:

    ContingencyCcrsFactory.withCoreDefaults(ContingencyConfiguration config)
    ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(ContingencyConfiguration config)
    ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(ClassLoader classLoader, ContingencyConfiguration config)
    ContingencyCcrsFactory.registerDiscoveredProviders(StrategyRegistry registry, ClassLoader classLoader, ContingencyConfiguration config)

Validation and acceptance: Compile the core and optional modules:

    .\gradlew.bat :ccrs-core:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava

Expected behavior: existing providers that only implement `registerStrategies(StrategyRegistry)` still compile. First-party providers can read configured options and instantiate strategies with those options. Existing no-argument factory calls keep current defaults.

Outcome and notes: Implemented on 2026-06-07. Added `CcrsStrategyProviderContext` and a context-aware `CcrsStrategyProvider.registerStrategies(...)` default overload while keeping the old single-argument method as the functional interface method. Added configuration-aware `ContingencyCcrs` and `ContingencyCcrsFactory` overloads. Core default registration now constructs retry, backtrack, and stop from central typed options. LangChain4j and A2A providers now read prediction and consultation options from the provider context. `CcrsJacamoRuntime` now stores a central `ContingencyConfiguration` and its default supplier passes it into `ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(...)`; custom suppliers remain available for full override.

Validation run on 2026-06-07 from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava :ccrs-jacamo:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava
    .\gradlew.bat :ccrs-core:compileJava :ccrs-jacamo:compileJava :ccrs-hypermedea:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava

Results: both compile commands passed. No Java smoke tests were added in WP3; React and JaCaMo adapter smoke execution remains WP5.

### WP4: Migrate call sites, remove legacy mutators, and update docs

Status: Done

Purpose: Finish the migration so active code uses the central configuration path and stale legacy setters do not invite users into the old model.

Local context: Update source docs in [src/main/java/ccrs/core/contingency/README.md](src/main/java/ccrs/core/contingency/README.md), module docs in [../ccrs-langchain4j/README.md](../ccrs-langchain4j/README.md), [../ccrs-a2a/README.md](../ccrs-a2a/README.md), [../ccrs-jacamo/README.md](../ccrs-jacamo/README.md), and examples in [../examples/ccrs-library-consumer](../examples/ccrs-library-consumer). Also update JavaDoc on strategy classes and factory methods.

Discussion: Legacy cleanup should be deliberate. For `0.1.0-SNAPSHOT`, removal may be acceptable if no stable external compatibility contract exists. If these APIs have already been consumed outside this workspace, leave deprecated delegating methods for one release and record the removal target. In either case, no first-party code or documentation should teach the legacy setter style after this work package.

Todos:

- [x] Replace first-party manual strategy construction examples with central configuration examples.
- [x] Remove or deprecate option mutators on built-in strategies after all call sites are migrated.
- [x] Replace mutable strategy fields with final option-derived fields where practical.
- [x] Update contingency README configuration examples to show typed strategy options and provider discovery.
- [x] Update `ccrs-jacamo` docs to show `CcrsJacamoRuntime.setContingencyConfiguration(...)` or the chosen equivalent.
- [x] Update React docs to state that the current Python wrapper uses default Java strategy options and that a Python-side configuration bridge is separate future work.
- [x] Update Maven consumer example to demonstrate a non-default strategy option.

Concrete steps: Search for legacy setter usage from `S:\dev\ma`:

    rg -n "maxHistoryActions|maxInteractionStateTriples|maxCcrsTraces|maxNeighborhood|filteredTripleNamespaces|filterTripleNamespace|withConfidence|retryLookbackLimit|maxAttempts|initialDelay|backoffMultiplier|addRetriableCode|withMaxRecentInteractions|requireExhaustion|exhaustionThreshold|withChannel|withClient|withPromptBuilder|withResponseParser" ccrs-bdi ccrs-react

After migration, this search should find only removed/deprecated definitions, release notes, or explicit migration documentation.

Validation and acceptance: Documentation examples compile or are demonstrably aligned with the final APIs. No first-party production code uses legacy strategy mutators. If deprecated methods remain, they are marked with `@Deprecated`, have JavaDoc pointing to the central configuration path, and are not used by examples.

Outcome and notes: Implemented on 2026-06-07. `PredictionLlmStrategy`, `RetryStrategy`, `BacktrackStrategy`, `StopStrategy`, and `ConsultationStrategy` now snapshot typed options at construction time. Removed the legacy fluent mutators from those strategy classes, including prediction collaborator setters, retry setters, consultation channel setters, stop exhaustion setters, and the `StopStrategy.immediate()` convenience. Constructor injection remains available for collaborators such as `LlmClient`, `PromptBuilder`, `LlmResponseParser`, and `ConsultationChannel`.

Documentation now teaches central typed configuration in [src/main/java/ccrs/core/contingency/README.md](src/main/java/ccrs/core/contingency/README.md), provider usage in [../ccrs-langchain4j/README.md](../ccrs-langchain4j/README.md) and [../ccrs-a2a/README.md](../ccrs-a2a/README.md), JaCaMo runtime configuration in [../ccrs-jacamo/README.md](../ccrs-jacamo/README.md), React wrapper boundaries in [../../ccrs-react/react_agent/ccrs/README.md](../../ccrs-react/react_agent/ccrs/README.md), and Maven consumer usage in [../examples/ccrs-library-consumer/README.md](../examples/ccrs-library-consumer/README.md). The durable architecture note [../CCRS_LIBRARY.md](../CCRS_LIBRARY.md) now describes provider-created prediction strategies receiving central options through configuration rather than relying on manual strategy construction.

Validation run on 2026-06-07 from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava :ccrs-jacamo:compileJava :ccrs-hypermedea:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava
    .\gradlew.bat publishToMavenLocal
    .\gradlew.bat -p examples\ccrs-library-consumer compileJava

Results: all commands passed. `publishToMavenLocal` emitted existing Javadoc warnings but completed successfully. The first standalone consumer compile attempt failed against stale Maven-local artifacts and then exposed one bad example method name, `initialDelay(...)`; after publishing the updated snapshot and correcting the example to `initialDelayMs(...)`, the consumer compile passed.

### WP5: Preserve React and JaCaMo adapter compatibility

Status: Done

Purpose: Prove that the migration did not break the two active adapter consumers named by the user: the Python React adapter and the JaCaMo adapter.

Local context: The React adapter is outside `ccrs-bdi` at [../../ccrs-react](../../ccrs-react). It loads Maven-local Java jars and calls Java with JPype. The JaCaMo adapter is [../ccrs-jacamo](../ccrs-jacamo) and creates contingency CCRS through `CcrsJacamoRuntime`.

Discussion: Smoke tests should avoid live LLM calls, A2A network calls, or running the `.jcm` agents. The important compatibility signal is that default construction still works, provider discovery still works when jars are present, Java contingency evaluation still returns a retry suggestion for a synthetic failure, and the graph builders still compile.

Todos:

- [x] Add or update a focused Java smoke test for `CcrsJacamoRuntime` configuration propagation without launching a `.jcm` file.
- [x] Publish updated Maven-local CCRS jars before React validation.
- [x] Run React compile and graph-builder smokes.
- [x] Run React Java-backed contingency retry smoke.
- [x] If React exposes non-default strategy configuration, add one React smoke that passes a non-default option and observes it through Java behavior or registered strategy diagnostics.
- [x] Record the exact smoke outputs in this plan after running them.

Concrete steps for Java and JaCaMo from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava :ccrs-jacamo:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava
    .\gradlew.bat classes
    .\gradlew.bat publishToMavenLocal

If Java tests are added, run the focused tasks:

    .\gradlew.bat :ccrs-core:test :ccrs-jacamo:test

Concrete steps for React from `S:\dev\ma\ccrs-react` after Maven-local publish:

    S:\anaconda\agent\python.exe -m compileall react_agent

    S:\anaconda\agent\python.exe -c "from react_agent.graph.graph import build_graph as b1; from react_agent.graph.graph_ccrs import build_graph as b2; print(type(b1()).__name__); print(type(b2()).__name__)"

    S:\anaconda\agent\python.exe -c "from react_agent.ccrs.contingency import ContingencyCcrs, Situation, SituationType, InMemoryCcrsContext; ccrs=ContingencyCcrs.from_maven_local(); ctx=InMemoryCcrsContext(agent_id='SmokeAgent'); situation=Situation(type=SituationType.FAILURE, trigger='http_error', target_resource='http://example.org/cells/1', failed_action='GET', error_info={'httpStatus':'503','message':'Service unavailable'}, metadata={'agent_name':'SmokeAgent'}); result=ccrs.evaluate(situation, ctx); print(result['top_suggestion']['strategy_id'] if result['top_suggestion'] else None); print(result['top_suggestion']['action_type'] if result['top_suggestion'] else None); print(len(result['evaluations']), len(result['suggestions']), len(result['no_help'])); print(len(ctx.ccrs_history.getCcrsHistory(25)))"

Validation and acceptance: Java compile and `classes` pass. Maven-local publish succeeds. React `compileall` succeeds. Both graph builders print `CompiledStateGraph`. The React contingency smoke prints `retry`, `retry`, non-zero evaluation counts, and a trace-history length of `1`. The JaCaMo adapter has a focused smoke proving `CcrsJacamoRuntime` still creates a configured `ContingencyCcrs` without importing optional capability classes directly.

Outcome and notes: Implemented on 2026-06-07. React now accepts `contingency_configuration` in [../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py](../../ccrs-react/react_agent/ccrs/contingency/contingency_ccrs.py) and [../../ccrs-react/react_agent/graph/graph_ccrs.py](../../ccrs-react/react_agent/graph/graph_ccrs.py). The mapping uses Python `snake_case` keys and is converted to Java `ContingencyConfiguration` through JPype. Existing defaults remain unchanged when no configuration is provided. Java objects are also accepted directly for advanced callers.

Adapter documentation now includes a React configuration example in [../../ccrs-react/react_agent/ccrs/README.md](../../ccrs-react/react_agent/ccrs/README.md) and a JaCaMo setup example in [../ccrs-jacamo/README.md](../ccrs-jacamo/README.md). No new plan file was created: the React-specific bridge fits the existing [../../ccrs-react/PLAN_CCRS_README.md](../../ccrs-react/PLAN_CCRS_README.md), and broader string/system-property overlays remain in WP6.

Validation run on 2026-06-07 from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava :ccrs-jacamo:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava
    .\gradlew.bat publishToMavenLocal
    .\gradlew.bat classes

Results: all commands passed. `publishToMavenLocal` emitted existing Javadoc warnings.

Focused JaCaMo runtime smoke from `S:\dev\ma\ccrs-bdi` used Java 21 JShell with compiled `ccrs-core` and `ccrs-jacamo` classes. It set retry max attempts to `5`, retry initial delay to `500`, stop exhaustion threshold to `1`, created `CcrsJacamoRuntime.createContingencyCcrs()`, and printed:

    5
    500
    1
    true

The first JShell attempt used a Java 11 executable and failed with `class file has wrong version 65.0, should be 55.0`; rerunning with `C:\Program Files\Java\jdk-21\bin\jshell.exe` passed.

Validation run on 2026-06-07 from `S:\dev\ma\ccrs-react`:

    S:\anaconda\agent\python.exe -m compileall react_agent
    S:\anaconda\agent\python.exe -m compileall react_agent\ccrs\contingency\contingency_ccrs.py react_agent\graph\graph_ccrs.py
    S:\anaconda\agent\python.exe -c "from react_agent.graph.graph import build_graph as b1; from react_agent.graph.graph_ccrs import build_graph as b2; print(type(b1()).__name__); print(type(b2()).__name__)"

Graph builder output:

    CompiledStateGraph
    CompiledStateGraph

The graph-builder smoke emitted a LangGraph `LangChainPendingDeprecationWarning` about future `allowed_objects` defaults; it did not block validation.

Default React Java-backed contingency retry smoke output:

    retry
    retry
    2 1 0
    1

Configured React Java-backed contingency retry smoke used `contingency_configuration={'retry': {'max_attempts': 5, 'initial_delay_ms': 500}}` and printed:

    retry
    retry
    5
    500
    1

Optional provider-discovery smoke used `ccrs-core`, `ccrs-langchain4j`, and `ccrs-a2a` modules with provider discovery enabled and configuration sections for `prediction_llm` and `consultation`. It printed:

    retry
    retry
    PARALLEL
    4 1 0

The optional-provider smoke registered `prediction_llm` and `consultation` through `ServiceLoader` and avoided live LLM/A2A calls because both optional strategies were not applicable to the synthetic retry-only situation.

### WP6: Add optional deployment configuration overlay

Status: Later

Purpose: Make configuration easier for deployments that cannot conveniently build Java objects, such as command-line JaCaMo launches or lightweight examples.

Local context: This package can safely read Java system properties from `System.getProperty(...)`. It should not read `.env` files in `ccrs-core`; dotenv belongs to optional capability modules or applications. React can set Java system properties before JPype constructs Java CCRS if this overlay is added.

Discussion: This is intentionally later. The primary API should be typed Java configuration, not stringly typed properties. A property overlay is useful only as a convenience layer over the same typed option objects.

Todos:

- [ ] Decide property key names, for example `ccrs.contingency.prediction_llm.maxHistoryActions`.
- [ ] Add a `ContingencyConfiguration.fromSystemProperties()` or builder overlay method.
- [ ] Document precedence between defaults, system properties, and explicit builder values.
- [ ] Keep the overlay optional and deterministic.

Concrete steps: Not ready. Do not start WP6 until WP2 through WP5 are complete and the remaining ergonomics problem is concrete.

Validation and acceptance: Not defined yet. At minimum, a small Java smoke should set one system property and observe the resulting typed option value.

Outcome and notes: Not started.

## Validation and Acceptance

The plan is complete when the following are true:

1. `ContingencyConfiguration` exposes typed option groups for all first-party strategy settings selected in WP1.
2. Built-in strategy construction through `ContingencyCcrs.withDefaults(...)` and `ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(...)` receives those options.
3. `CcrsStrategyProvider` has a configuration-aware provider context, and first-party ServiceLoader providers use it.
4. Existing default construction still behaves as before.
5. First-party docs and examples teach the new configuration API.
6. Legacy mutable strategy setters are removed or deprecated according to the final compatibility decision, and no first-party production code uses them.
7. Java compile, Maven-local publish, React adapter smoke, and JaCaMo adapter smoke pass.

Plan-wide Java validation from `S:\dev\ma\ccrs-bdi`:

    .\gradlew.bat :ccrs-core:compileJava :ccrs-jacamo:compileJava :ccrs-langchain4j:compileJava :ccrs-a2a:compileJava
    .\gradlew.bat classes
    .\gradlew.bat publishToMavenLocal

Plan-wide React validation from `S:\dev\ma\ccrs-react`:

    S:\anaconda\agent\python.exe -m compileall react_agent
    S:\anaconda\agent\python.exe -c "from react_agent.graph.graph import build_graph as b1; from react_agent.graph.graph_ccrs import build_graph as b2; print(type(b1()).__name__); print(type(b2()).__name__)"
    S:\anaconda\agent\python.exe -c "from react_agent.ccrs.contingency import ContingencyCcrs, Situation, SituationType, InMemoryCcrsContext; ccrs=ContingencyCcrs.from_maven_local(); ctx=InMemoryCcrsContext(agent_id='SmokeAgent'); situation=Situation(type=SituationType.FAILURE, trigger='http_error', target_resource='http://example.org/cells/1', failed_action='GET', error_info={'httpStatus':'503','message':'Service unavailable'}, metadata={'agent_name':'SmokeAgent'}); result=ccrs.evaluate(situation, ctx); print(result['top_suggestion']['strategy_id'] if result['top_suggestion'] else None); print(result['top_suggestion']['action_type'] if result['top_suggestion'] else None); print(len(result['evaluations']), len(result['suggestions']), len(result['no_help'])); print(len(ctx.ccrs_history.getCcrsHistory(25)))"

If tests are added in WP2 or WP5, run the focused tasks and record results here:

    .\gradlew.bat :ccrs-core:test :ccrs-jacamo:test

Do not use a full live maze or LLM run as the required acceptance gate. Those runs depend on external services, credentials, and local environment state.

## Idempotence and Recovery

All implementation steps should be additive until the adapter smokes pass. Add typed options, constructors, factory overloads, and provider context first. Migrate call sites second. Remove or deprecate legacy mutators last.

If a provider migration fails, keep the old `registerStrategies(StrategyRegistry)` method working and temporarily let the provider ignore non-default options. This preserves current default behavior while the configuration-aware overload is repaired.

If React smoke fails after Maven-local publish, verify that the JPype process is fresh. JPype cannot fully unload and restart the JVM inside one Python process, so use a new shell command for each smoke.

If JaCaMo compatibility fails, do not wire optional providers directly into `ccrs-jacamo`. Fix the core factory/provider context or `CcrsJacamoRuntime` configuration bridge instead.

Generated build output, Maven-local artifacts, and React `__pycache__` files may be regenerated. Do not modify archived experiment runs or application `.jcm` files for this plan unless a later user request explicitly expands scope.

## Artifacts and Notes

Target Java API sketch for a plain Maven consumer:

    ContingencyConfiguration config = ContingencyConfiguration.builder()
        .predictionLlm(options -> options.maxHistoryActions(20))
        .retry(options -> options.maxAttempts(5))
        .build();

    ContingencyCcrs ccrs =
        ContingencyCcrsFactory.withDefaultsAndDiscoveredProviders(config);

Target JaCaMo runtime sketch:

    CcrsJacamoRuntime.setContingencyConfiguration(
        ContingencyConfiguration.builder()
            .predictionLlm(options -> options.maxHistoryActions(20))
            .build()
    );

Target direct strategy construction sketch for tests and advanced users:

    PredictionLlmStrategy strategy = new PredictionLlmStrategy(
        llmClient,
        PredictionLlmStrategyOptions.defaults()
            .toBuilder()
            .maxHistoryActions(20)
            .build()
    );

Known current default values to preserve until WP1 revises them:

- `PredictionLlmStrategy`: `baseConfidence = 0.6`, `maxHistoryActions = 50`, `maxInteractionStateTriples = 100`, `maxCcrsTraces = 10`, neighborhood limits from `CcrsContext`, filtered namespace `https://example.org/ui`.
- `RetryStrategy`: `maxAttempts = 3`, `initialDelayMs = 1000`, `backoffMultiplier = 2.0`, retry lookback limit `25`, retriable codes `500`, `502`, `503`, `504`, `timeout`, `connection_reset`, and `connection_refused`.
- `ConsultationStrategy`: max recent interactions `10`, max agent candidates `5`, fallback confidence `0.5`.
- `BacktrackStrategy`: max recent interactions `1000`; URI filtering and scoring constants remain unresolved WP1 rows and should not be added to first-pass options without a decision.
- `StopStrategy`: require exhaustion `true`, exhaustion threshold `2`, stop lookback limit `30`.
- `ContingencyConfiguration`: keep existing orchestration defaults unless a separate selection-policy plan changes them.

## Interfaces and Dependencies

The new first-party option classes should live in `ccrs.core.contingency.options` unless implementation discovers a stronger local convention. Required types at the end of WP2:

    ccrs.core.contingency.options.PredictionLlmStrategyOptions
    ccrs.core.contingency.options.RetryStrategyOptions
    ccrs.core.contingency.options.BacktrackStrategyOptions
    ccrs.core.contingency.options.ConsultationStrategyOptions
    ccrs.core.contingency.options.StopStrategyOptions

`ContingencyConfiguration` should expose getters:

    getPredictionLlmStrategyOptions()
    getRetryStrategyOptions()
    getBacktrackStrategyOptions()
    getConsultationStrategyOptions()
    getStopStrategyOptions()
    <T> Optional<T> getStrategyOptions(String strategyId, Class<T> type)

`ContingencyConfiguration.Builder` should expose mutation methods that accept either fully built options or builder customizers. Prefer names that are readable at the consumer call site:

    predictionLlm(PredictionLlmStrategyOptions options)
    predictionLlm(Consumer<PredictionLlmStrategyOptions.Builder> customizer)
    retry(RetryStrategyOptions options)
    retry(Consumer<RetryStrategyOptions.Builder> customizer)
    backtrack(BacktrackStrategyOptions options)
    backtrack(Consumer<BacktrackStrategyOptions.Builder> customizer)
    consultation(ConsultationStrategyOptions options)
    consultation(Consumer<ConsultationStrategyOptions.Builder> customizer)
    stop(StopStrategyOptions options)
    stop(Consumer<StopStrategyOptions.Builder> customizer)
    strategyOptions(String strategyId, Object options)

`CcrsStrategyProviderContext` should expose at least:

    ContingencyConfiguration configuration()
    ClassLoader classLoader()
    <T> Optional<T> getStrategyOptions(String strategyId, Class<T> type)

`ContingencyCcrsFactory` and `ContingencyCcrs` should keep no-argument defaults and add configuration-aware overloads. `ccrs-jacamo` should depend only on `ccrs-core` for this configuration bridge. `ccrs-langchain4j` and `ccrs-a2a` should remain optional modules that read core option objects through the provider context and own only their provider-specific clients or channels.

Revision note 2026-06-07 / Codex: Created the initial migration plan after user approval of the central typed configuration approach. The plan records the target API shape, migration packages, legacy cleanup rule, and required React plus JaCaMo compatibility smoke tests.

Revision note 2026-06-07 / Codex: Completed the first WP1 inventory pass by adding a candidate-knob table with `Certain`, `Unsure`, and `Declined` classifications. The update promotes `BacktrackStrategyOptions` for the hidden interaction-history bound and leaves confidence/discovery policy rows unresolved for explicit follow-up before WP2.

Revision note 2026-06-07 / Codex: Added relative source links to every WP1 candidate knob row so each default or planned owner can be traced directly from the table.

Revision note 2026-06-07 / Codex: Recorded the user decision to defer all WP1 `Unsure` rows, implemented WP2 typed option objects and central configuration wiring for `Certain` rows, and captured compile/test validation results.

Revision note 2026-06-07 / Codex: Implemented WP3 propagation through core factory overloads, ServiceLoader provider context, LangChain4j/A2A provider option use, and the JaCaMo runtime configuration bridge; recorded compile validation results.

Revision note 2026-06-07 / Codex: Completed WP4 by removing legacy mutable strategy mutators, making first-party strategies consume constructor-time typed option snapshots, updating core/provider/JaCaMo/React/Maven-consumer documentation, publishing updated SNAPSHOT artifacts to Maven local, and recording compile validation for the Java modules plus standalone consumer.

Revision note 2026-06-07 / Codex: Completed WP5 by adding the React Python-to-Java configuration mapping bridge, updating React and JaCaMo configuration documentation, validating Maven-local publication, running React compile/graph/contingency/provider-discovery smokes, and running a Java 21 JShell JaCaMo runtime configuration smoke without launching `.jcm` agents.
