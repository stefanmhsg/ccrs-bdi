# AGENTS.md

## Scope

- Applies to the whole `ccrs-bdi` repository.
- Treat this file as repository guidance for Codex sessions. Prefer more local
  `AGENTS.md` files if they are added later in subdirectories.

## CCRS First

- CCRS is the central research concept in this repository. Do not assume prior
  model knowledge of CCRS, opportunistic CCRS, or contingency CCRS.
- At the start of any CCRS-related task, build local context from the repository
  documentation before changing code.
- Read these files first when the task touches CCRS concepts, module boundaries,
  or architecture:
  - `README.md`
  - `ccrs-core/src/main/java/ccrs/core/opportunistic/README.md`
  - `ccrs-core/src/main/java/ccrs/core/contingency/README.md`
  - `ccrs-core/src/main/java/ccrs/core/rdf/README.md`
- Read these module docs when the task touches their integration area:
  - `ccrs-jacamo/README.md`
  - `ccrs-hypermedea/README.md`
  - `ccrs-langchain4j/README.md`
  - `ccrs-a2a/README.md`
  - `ccrs-core/src/main/java/ccrs/core/contingency/selection/README.md`
  - `ccrs-core/src/main/java/ccrs/core/contingency/strategies/social/README.md`

## Repository Shape

- This repository has two roles:
  - It is the working source tree for reusable CCRS library modules:
    `ccrs-core`, `ccrs-jacamo`, `ccrs-hypermedea`, `ccrs-langchain4j`, and
    `ccrs-a2a`.
  - It is also a concrete JaCaMo/Jason user project with `.jcm` files,
    AgentSpeak agents, local experiments, and Gradle project dependencies on
    those modules.
- The reusable libraries are the `ccrs-*` modules.
- The root project, `.jcm` files, `.asl` agents, logs, local environment files,
  and experiments are application code and are not intended to be published as
  libraries.

## CCRS Concepts

- Opportunistic CCRS interprets RDF perceptions as opportunities. It scans RDF
  triples and batches, discovers or loads CCRS vocabulary, matches simple and
  structural patterns, scores matches, and returns `OpportunisticResult` values
  that adapters can expose as agent beliefs such as `ccrs/3`.
- Contingency CCRS handles failures, stuck states, uncertainty, and proactive
  problem solving. It builds a `Situation`, evaluates recovery strategies such
  as retry, backtrack, consultation, LLM prediction, and stop, records
  `CcrsTrace` history, and returns selected `StrategyResult` suggestions.
- Keep these two concepts distinct. Opportunistic CCRS is perception and
  option-prioritization oriented; contingency CCRS is recovery and strategy
  evaluation oriented. They may cooperate, but they are not the same mechanism.

## Module Boundaries

- `ccrs-core` is agent-agnostic. It must not depend on JaCaMo, Jason, CArtAgO,
  Hypermedea, LangChain4j, A2A, dotenv, or application-specific agents.
- `ccrs-jacamo` depends on `ccrs-core` and provides the JaCaMo/Jason adapter.
  Keep `CcrsAgent` and `CcrsAgentArch` together as complementary integration
  points.
- `ccrs-hypermedea` depends on `ccrs-core`, `ccrs-jacamo`, and Hypermedea. It is
  a replaceable HTTP artifact/history provider.
- `ccrs-langchain4j` depends on `ccrs-core` and provides LangChain4j/OpenAI
  provider wiring only. Provider-agnostic prompt builders, response parsers, and
  LLM strategy policy belong outside this module.
- `ccrs-a2a` depends on `ccrs-core` and provides A2A-backed consultation wiring
  only. Generic social contingency policy belongs in core unless a local doc
  says otherwise.
- Optional capability modules should contribute strategies through
  `ccrs.core.contingency.CcrsStrategyProvider`, `ContingencyCcrsFactory`, and
  Java `ServiceLoader`. Do not wire LangChain4j or A2A directly into the JaCaMo
  `evaluate` action.

## Working Agreements

- Before broad refactors, read `CCRS_LIBRARY.md` and preserve its dependency
  direction:
  `core <- jacamo <- hypermedea`, `core <- langchain4j`, `core <- a2a`, and
  the root app depending on selected modules.
- Prefer existing CCRS abstractions and extension points over adding parallel
  mechanisms.
- Keep documentation and examples in sync with structural moves, package
  changes, internal action signatures, ServiceLoader files, and `.jcm` snippets.
- Do not move application-owned `.jcm`, `.asl`, `.env.example`, logs, or
  experiments into reusable library modules unless the user explicitly asks for
  that migration.
- Do not add secrets, API keys, local-only credentials, or private endpoints to
  tracked docs or source.

## Build And Verification

- Compile the app and CCRS modules with:
  `./gradlew classes`
- Run focused module tests when available with Gradle task paths such as:
  `./gradlew :ccrs-core:test`
- Publish library modules locally with:
  `./gradlew publishToMavenLocal`
- Run the local JaCaMo app with:
  `gradle run`
- Do not try to run jacamo agents in the .jcm files.
- Be aware that `./gradlew test` is documented in `CCRS_LIBRARY.md` as not fully
  repaired because the JaCaMo test suite currently has known issues. Report this
  context when test verification is blocked by that suite.

## Execution Plans

- For complex features, migrations, or multi-session refactors, check for
  relevant `PLAN_*.md` files before editing.
- Keep durable architecture notes in repository docs such as `CCRS_LIBRARY.md`;
  keep task-specific sequencing in the plan file.
