# CCRS Library Extraction Notes

This document captures the intended library split and the current direction of
the CCRS extraction work. It is meant as a durable working note for future
development sessions so the module boundaries and unfinished tasks stay
consistent.

## Target State

The current repository is still a JaCaMo application that contains CCRS code.
The intended library shape is a set of small modules with one-directional
dependencies:

```text
ccrs-core
  no JaCaMo, Jason, CArtAgO, Hypermedea, LangChain4j, A2A, or dotenv

ccrs-jacamo
  depends on ccrs-core
  provides the JaCaMo/Jason adapter

ccrs-hypermedea
  depends on ccrs-core + ccrs-jacamo + Hypermedea
  provides one replaceable HTTP artifact/history implementation

ccrs-langchain4j
  depends on ccrs-core
  provides LangChain4j/OpenAI-backed LLM client/provider wiring

ccrs-a2a
  depends on ccrs-core
  provides A2A-backed consultation strategy wiring

this user repository / app module
  depends on whichever library modules are needed
  remains the concrete CCRS BDI application
  contains .jcm, .asl, .env.example, logs, experiments, and app-specific docs
```

Dependency rule:

```text
core <- jacamo <- hypermedea
core <- langchain4j
core <- a2a
user app -> all selected modules
```

`ccrs-jacamo` must not import Hypermedea, LangChain4j, A2A, or dotenv classes.
Those are optional modules.

## Current Important Decisions

### JaCaMo Is Not Just Jason

`CcrsAgent` and `CcrsAgentArch` are complementary and should stay together in
the JaCaMo adapter:

- `CcrsAgent` integrates with Jason belief revision.
- `CcrsAgentArch` integrates with CArtAgO observables and batches RDF triples
  before the next reasoning cycle.

This is required because artifacts such as Hypermedea may synchronize directly
with the belief base, while structural CCRS matching needs cycle-level batches.
These are not duplicate paths.

### Hypermedea Must Be Replaceable

Hypermedea is one concrete HTTP artifact/instrumentation implementation for
JaCaMo agents. It should be separated into `ccrs-hypermedea`.

The intended boundary:

- `ccrs-jacamo` exposes `InteractionHistoryProvider`.
- `ccrs-hypermedea` implements/installs a provider through
  `CcrsJacamoRuntime.setInteractionHistoryProvider(...)`.
- Another HTTP artifact can replace Hypermedea by implementing the same
  provider contract.

Current relevant files:

- `ccrs-jacamo/src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java`
- `ccrs-jacamo/src/main/java/ccrs/jacamo/jason/contingency/InteractionHistoryProvider.java`
- `ccrs-jacamo/src/main/java/ccrs/jacamo/jason/contingency/JasonCcrsContext.java`
- `ccrs-hypermedea/src/main/java/ccrs/hypermedea/*`

### Optional Capabilities Use Strategy Providers

JaCaMo `evaluate` should not know concrete capability implementations. It should
create `ContingencyCcrs` through `CcrsJacamoRuntime`.

Optional modules contribute strategies through:

- `ccrs.core.contingency.CcrsStrategyProvider`
- `ccrs.core.contingency.ContingencyCcrsFactory`
- Java `ServiceLoader`

Current service files:

```text
ccrs-langchain4j/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
ccrs-a2a/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
```

Each capability module owns only the provider it contributes:

```text
ccrs-langchain4j/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
ccrs-a2a/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
```

## Current Physical Module Split

### ccrs-core

Move:

```text
ccrs-core/src/main/java/ccrs/core/**
ccrs-core/src/main/resources/ccrs-vocabulary.ttl
```

Keep dependencies small:

- Java
- Apache Jena, unless RDF/vocabulary handling is split further later

No dependencies on:

- JaCaMo/Jason/CArtAgO
- Hypermedea
- LangChain4j/OpenAI
- A2A SDK
- dotenv

### ccrs-jacamo

Move:

```text
ccrs-jacamo/src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jaca/CcrsAgentArch.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/JasonRdfAdapter.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/TimestampedBeliefBase.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/opportunistic/CcrsAgent.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/opportunistic/CcrsConfiguration.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/opportunistic/prioritize.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/contingency/JasonCcrsContext.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/contingency/InteractionHistoryProvider.java
ccrs-jacamo/src/main/java/ccrs/jacamo/jason/contingency/evaluate.java
ccrs-jacamo/src/main/resources/ccrs/jacamo/jason/contingency/examples.asl
```

Question for later: `examples.asl` may belong in the example app instead of
the library module.

Must not move into this module:

- Hypermedea instrumentation
- LangChain4j clients
- A2A clients
- dotenv fallback

### ccrs-hypermedea

Move:

```text
ccrs-hypermedea/src/main/java/ccrs/hypermedea/**
ccrs-hypermedea/src/main/resources/META-INF/services/org.hypermedea.op.ProtocolBinding
```

Current Java package after the JaCaMo adapter cleanup:

```java
ccrs.hypermedea
```

The application `.jcm` files and the Hypermedea SPI service file must point to
this package. If published users already depend on the previous package name,
add deprecated compatibility wrappers in the old package before releasing a
binary library.

### ccrs-langchain4j

Move:

```text
ccrs-langchain4j/src/main/java/ccrs/capabilities/ConfigResolver.java
ccrs-langchain4j/src/main/java/ccrs/capabilities/DotenvConfigFallback.java
ccrs-langchain4j/src/main/java/ccrs/capabilities/llm/langchain4j/**
ccrs-langchain4j/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
```

The LangChain4j module should stay a provider adapter. It may create a
LangChain4j-backed `LlmClient`, read provider configuration, and register an
LLM-backed strategy through `ServiceLoader`, but it should not own strategy
prompts or parsing policy.

Provider-agnostic LLM strategy support belongs in `ccrs-core`:

- `ccrs.core.contingency.PromptBuilder`
- `ccrs.core.contingency.LlmResponseParser`
- `ccrs.core.contingency.strategies.internal.prediction.PredictionLlmStrategy`
- `ccrs.core.contingency.strategies.internal.prediction.DefaultPredictionPromptBuilder`
- `ccrs.core.contingency.strategies.internal.prediction.JsonActionParser`

This keeps the prompt with the consultation/prediction strategy behavior rather
than with one concrete LLM provider. Applications can then plug in a different
`LlmClient` capability without changing the core prompt contract.

The default provider path should instantiate the core strategy with the
provider client only, for example `new PredictionLlmStrategy(llmClient)`, so
the core strategy selects its own standard prompt builder and response parser.

### ccrs-a2a

Move:

```text
ccrs-a2a/src/main/java/ccrs/capabilities/a2a/**
ccrs-a2a/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
```

`A2aConsultationStrategyProvider` should remain here. It is the adapter that
registers `ConsultationStrategy` when A2A is available.

The A2A module has its own small config resolver and dotenv bridge, so it does
not depend on `ccrs-langchain4j` for optional `.env` support.

The A2A module should stay a consultation-channel adapter. It may resolve agent
cards, invoke the A2A SDK, extract returned payload text and metadata, and
register an A2A-backed `ConsultationChannel` through `ServiceLoader`. It should
not own generic social escalation policy. The current proof-of-concept still
leaves some A2A-shaped target discovery and RDF projection behavior inside
`ConsultationStrategy`; that is tracked under the ConsultationStrategy cleanup
below.

### User Application Repository

This repository should remain the user/application repository, not become a
generic library example project. The extracted CCRS modules should be usable by
this application, but the application's MAS configuration, agents, experiments,
and local setup files stay app-owned.

Keep outside library modules:

```text
*.jcm
src/agt/*.asl
src/agt/agt_archive/**
.env.example
LLM_SETUP.md, or rewrite as app/capability docs
README.md, app-level usage
log/** (runtime output, not published library content)
```

If a separate sample project is useful later, create it intentionally as a new
example. Do not silently turn this repository into that example.

## Strategy Library-Readiness Review

The strategy implementations should be reviewed before publishing a stable
library. The main issue is not whether they compile, but whether they expose
good extension points and avoid app/domain assumptions.

### RetryStrategy

Current status: mostly library-ready.

Concerns:

- HTTP-oriented error keys: `httpStatus`, `errorType`.
- Hardcoded retriable status/error codes.
- Confidence and rationale are private methods, so subclassing is not very
  useful.

Todos:

- Introduce a configurable retry classifier or predicate.
- Consider a `RetryStrategyConfig` value object instead of fluent mutability
  only.
- Decide whether the class should be `final` with configuration, or expose
  protected hooks intentionally.
- Add unit tests for retry classification, attempt counting, confidence, and
  trace lookback behavior.

### StopStrategy

Current status: mostly library-ready.

Concerns:

- Some HTTP-specific rationale and confidence logic.
- Stop semantics should be clearly documented as L0 fallback.

Todos:

- Review whether HTTP status handling belongs in core or a classifier.
- Add tests for exhaustion-required vs immediate stop.
- Document how L0 is treated by `ContingencyCcrs`.

### BacktrackStrategy

Current status: useful but too monolithic for a library.

Concerns:

- Large private implementation with many embedded concepts.
- URI policy is hardcoded: HTTP(S), no fragments, excludes strings containing
  `agent`.
- Checkpoint extraction, graph building, ranking, confidence, and suggestion
  building are all inside one class.
- Opportunistic guidance has a TODO about standard structure.

Todos:

- Extract `InteractionGraphBuilder`.
- Extract `CheckpointSelector` or `CheckpointRankingPolicy`.
- Extract `UriPolicy`.
- Extract `BacktrackSuggestionBuilder`.
- Fix/standardize generated `OpportunisticResult` metadata.
- Add focused tests for graph building, checkpoint ranking, path computation,
  URI filtering, and opportunistic guidance generation.

### PredictionLlmStrategy

Current status: provider-level decoupled from LangChain4j. The standard prompt
builder and JSON action parser now live in core beside the LLM strategy
contract, while `ccrs-langchain4j` supplies only the concrete LLM client and
provider registration.

Concerns:

- It lives under `core/contingency/strategies/internal`, but functionally it is
  an LLM capability.
- Prompt-context formatting is embedded in the strategy.
- UI namespace filtering is hardcoded by default.

Todos:

- Decide module ownership:
  - keep interfaces in `ccrs-core`;
  - move the strategy to a generic `ccrs-llm` module, or keep it in core only
    if core is allowed to contain optional strategy types.
- Consider extracting prompt context preparation from the strategy.
- Keep `DefaultPredictionPromptBuilder` and `JsonActionParser` outside LangChain4j while
  they remain provider-agnostic.
- Add tests with fake `LlmClient`, fake parser, and deterministic context.

### ConsultationStrategy

Current status: highest-priority cleanup before library release.

Concerns:

- It exposes a generic `ConsultationChannel`, but contains domain and A2A
  discovery assumptions:
  - maze `contains` predicate,
  - A2A agent card predicates,
  - candidate discovery from recent interactions,
  - Turtle artifact projection into a POST body.
- Jena/Turtle projection logic is embedded in the core strategy.
- The README currently documents the current A2A proof of concept more than a
  reusable strategy contract.

Todos:

- Extract `ConsultationTargetResolver`.
- Extract `ConsultationRequestBuilder`.
- Extract `ConsultationResponseProjector`.
- Move A2A-specific target discovery/projection into `ccrs-a2a`, unless it is
  intentionally generic RDF consultation behavior.
- Make fallback confidence configurable.
- Add tests for no-channel, no-history, candidate discovery, response mapping,
  projection, and failed consultation.

## Extension Policy

For library users, the primary extension point should be:

```java
public final class MyStrategy implements CcrsStrategy {
    ...
}
```

Strategies should be registered either explicitly:

```java
ContingencyCcrs ccrs = ContingencyCcrs.withDefaults();
ccrs.getRegistry().register(new MyStrategy());
```

or through a provider:

```java
public final class MyStrategyProvider implements CcrsStrategyProvider {
    @Override
    public void registerStrategies(StrategyRegistry registry) {
        registry.register(new MyStrategy());
    }
}
```

and a service file:

```text
META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
```

containing:

```text
com.example.MyStrategyProvider
```

Subclassing built-in strategies should not be the main promise until the built
ins have explicit protected hooks. Either make a built-in strategy final and
configurable, or intentionally expose protected methods as stable extension
points. Avoid accidental subclassing contracts.

## Build Split Todos

Completed:

```text
:ccrs-core
:ccrs-jacamo
:ccrs-hypermedea
:ccrs-langchain4j
:ccrs-a2a
root project as the user application
```

- `settings.gradle` includes the CCRS modules.
- CCRS Java sources now live in module-local `src/main/java`.
- CCRS resources and ServiceLoader files now live in module-local
  `src/main/resources`.
- The root project remains the concrete JaCaMo application and depends on the
  selected CCRS modules.
- `ccrs-*` modules are configured as Maven publications in
  [build.gradle](build.gradle) under the group
  `io.github.stefanmhsg.ccrs` with version `0.1.0-SNAPSHOT`.
- The root JaCaMo application is not configured as a Maven publication.
- Local library publishing works through:

```text
./gradlew :ccrs-core:publishToMavenLocal \
  :ccrs-jacamo:publishToMavenLocal \
  :ccrs-hypermedea:publishToMavenLocal \
  :ccrs-langchain4j:publishToMavenLocal \
  :ccrs-a2a:publishToMavenLocal
```

- A clean file-based local Maven repository for smoke tests is available at
  `build/local-maven-repo` through each module's
  `publishMavenJavaPublicationToCcrsLocalRepository` task.

Remaining:

1. Keep the remaining package names stable during the first Gradle split unless
   there is a concrete adapter boundary reason to change them. The Hypermedea
   implementation has already moved to `ccrs.hypermedea`; future compatibility
   concerns should be handled with wrappers, not by re-nesting it under
   `ccrs.jacamo`.

2. Keep `.jcm` and `.asl` files owned by the user application. If the app is
   moved into a dedicated Gradle subproject later, move them there explicitly.
   Otherwise configure the existing root project as the application that depends
   on the extracted CCRS modules.

3. Add module-level tests:

- `ccrs-core`: RDF vocabulary, opportunistic matching, strategy registry,
  selection policy, built-in strategies.
- `ccrs-jacamo`: `JasonRdfAdapter`, `prioritize`, fallback `JasonCcrsContext`,
  provider-independent `evaluate`.
- `ccrs-hypermedea`: SPI file packaging, `CcrsHttpBinding`, interaction log,
  provider installation.
- `ccrs-langchain4j`: provider registration with and without config, fake LLM
  where possible.
- `ccrs-a2a`: target resolution and consultation response mapping with fake
  A2A/http client.

4. Repair the actual JaCaMo test suite. The Gradle task now runs as a
   `JavaExec` task, but `./gradlew test` still fails inside the JaCaMo test
   manager. One visible issue is that
   `src/test/agt/test-sample.asl` includes `sample_agent.asl`, which is not
   present in the repository.

## Documentation And Examples Todos

Every structural move must update documentation and examples in the same
change. This is part of the extraction work, not a cleanup to postpone.

When a documentation-specific skill or project documentation guidelines are
available in a future session, use them to guide the rewrite. If no such skill
is available, follow this checklist:

- Update package names, module names, and filesystem paths in all READMEs.
- Update `.jcm` snippets when `ag-class`, `ag-arch`, artifact class names, or
  internal action package names change.
- Update AgentSpeak examples when action signatures, result terms, annotations,
  or belief formats change.
- Update service loader documentation whenever files under `META-INF/services`
  move to module-local resources.
- Update capability setup docs so LLM/A2A configuration is described as
  optional module wiring, not as behavior baked into the JaCaMo adapter.
- Update Hypermedea docs so Hypermedea is described as a replaceable HTTP
  artifact/history provider.
- Update comments in moved Java classes if they mention old package locations,
  root source sets, or direct dependencies that no longer exist.
- Remove or rewrite stale docs rather than preserving contradictory guidance.

Current docs that likely need attention during the split:

```text
README.md
LLM_SETUP.md
ccrs-jacamo/README.md
ccrs-core/src/main/java/ccrs/core/contingency/README.md
ccrs-core/src/main/java/ccrs/core/opportunistic/README.md
ccrs-core/src/main/java/ccrs/core/contingency/strategies/social/README.md
ccrs-langchain4j/README.md
ccrs-a2a/README.md
ccrs-hypermedea/README.md
ccrs-jacamo/src/main/resources/ccrs/jacamo/jason/contingency/examples.asl
```

## Current Intermediate State

Implemented toward the split:

- `CcrsStrategyProvider`
- `ContingencyCcrsFactory`
- `CcrsJacamoRuntime`
- `InteractionHistoryProvider`
- provider classes for LangChain4j prediction and A2A consultation
- module-local service files for LangChain4j prediction and A2A consultation
- provider-agnostic LLM prompt and JSON parser implementations moved into
  `ccrs-core`
- `JasonCcrsContext` no longer directly imports Hypermedea classes
- `evaluate` no longer directly imports LangChain4j or A2A classes
- `CcrsAgentArch` no longer loads `.env`; dotenv fallback is now capability-side
- Hypermedea code moved out of the JaCaMo package path into
  `ccrs-hypermedea/src/main/java/ccrs/hypermedea` with package
  `ccrs.hypermedea`
- Gradle subprojects exist for `ccrs-core`, `ccrs-jacamo`,
  `ccrs-hypermedea`, `ccrs-langchain4j`, and `ccrs-a2a`

Known remaining coupling:

- The root application currently depends on all CCRS modules. Later app
  profiles could depend only on the selected capabilities.
- Some strategy implementations still contain app/domain assumptions.
- The root `testJaCaMo` task launches, but the AgentSpeak test suite still
  needs repair.

## Do Not Regress

- Do not make `ccrs-jacamo` depend on Hypermedea.
- Do not make `evaluate` import LangChain4j or A2A classes directly again.
- Do not put dotenv loading back into `CcrsAgentArch`.
- Do not put provider-agnostic prompt builders, prompt templates, response
  parsers, or LLM strategy policy into `ccrs-langchain4j`; it is only the
  LangChain4j/OpenAI provider adapter.
- Do not split `CcrsAgent` away from `CcrsAgentArch` as if one replaces the
  other. They are complementary in the JaCaMo integration.
- Keep the Hypermedea implementation outside `ccrs.jacamo`; if compatibility
  with the previous class name is needed, add wrappers instead of moving the
  implementation back.
