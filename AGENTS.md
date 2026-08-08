# AGENTS.md

## Scope

- Applies to the entire `ccrs-bdi` repository.
- This repository owns only the JaCaMo/BDI application, its configurations,
  AgentSpeak programs, environment code, tests, logs, and experiments.
- Reusable CCRS libraries and their plans belong in
  `https://github.com/stefanmhsg/ccrs`.

## Package Boundary

- Consume CCRS exclusively through published
  `io.github.stefanmhsg.ccrs` Maven coordinates.
- Do not add sibling-source includes, Gradle composite substitution, Maven
  Local, flat-directory, or repository-relative artifact fallbacks.
- Keep core and `ccrs-jacamo` as compile/runtime dependencies. Keep optional
  Hypermedea, LangChain4j, and A2A integrations runtime-only unless application
  Java source directly adopts their API.
- Use `-PccrsCapabilities` to select optional runtime modules and keep the
  documented profiles valid.

## Application Ownership

- Keep `.jcm`, `.asl`, `.env.example`, logging, experiments, and
  application-specific Java/XML content in this repository.
- Keep `src/agt/examples/contingency/examples.asl` application-owned.
- Do not duplicate CCRS library source, service descriptors, publication logic,
  or library architecture plans here.
- Never commit credentials, API keys, tokens, local endpoints, or `.env`.

## Build And Verification

- Use Java 21 and `--release 21`.
- Compile without starting agents using `./gradlew test classes`.
- Verify capability selection with `./gradlew
  -PccrsCapabilities=<profile> test classes printCcrsCapabilities`.
- `testJaCaMo` is an explicit interactive/agent-level task; do not run JaCaMo
  agents or `.jcm` applications automatically.
- Keep the package-consumer workflow green for `none`, each individual optional
  capability, and `all`.

## Documentation

- Update [README.md](README.md), `.jcm` snippets, AgentSpeak examples, and
  capability profiles together when application integration changes.
- Keep research-artifact architecture and library API documentation in the
  `ccrs` repository rather than copying it here.
