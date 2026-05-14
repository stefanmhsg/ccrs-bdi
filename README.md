# CCRS BDI Repository

This repository currently has two roles:

- It is the working source tree for the reusable CCRS library modules:
  [ccrs-core](ccrs-core), [ccrs-jacamo](ccrs-jacamo),
  [ccrs-hypermedea](ccrs-hypermedea),
  [ccrs-langchain4j](ccrs-langchain4j), and [ccrs-a2a](ccrs-a2a).
- It is also a concrete JaCaMo/Jason user project with agents and `.jcm`
  configurations that use those modules directly through Gradle project
  dependencies in [build.gradle](build.gradle).

The reusable libraries are the `ccrs-*` modules. The root project, `.jcm`
files, `.asl` agents, logs, local environment files, and experiments are
application code and are not intended to be published as libraries.

---

## Repository Layout

| Path | Role |
| --- | --- |
| [ccrs-core](ccrs-core) | Agent-agnostic CCRS core, RDF context contracts, contingency strategies, and strategy extension points. |
| [ccrs-jacamo](ccrs-jacamo) | JaCaMo/Jason adapter for CCRS. |
| [ccrs-hypermedea](ccrs-hypermedea) | Optional Hypermedea integration and interaction history provider. |
| [ccrs-langchain4j](ccrs-langchain4j) | Optional LangChain4j/OpenAI-backed LLM capability provider. |
| [ccrs-a2a](ccrs-a2a) | Optional A2A-backed consultation capability provider. |
| [src/agt](src/agt) and `*.jcm` | This repository's JaCaMo/Jason application agents and launch configurations. |
| [examples/ccrs-library-consumer](examples/ccrs-library-consumer) | Standalone example project that consumes the published CCRS modules from Maven coordinates. |
| [CCRS_LIBRARY.md](CCRS_LIBRARY.md) | Library extraction notes, module boundaries, and remaining library-readiness tasks. |

## Working On The CCRS Libraries

The modules are included in [settings.gradle](settings.gradle), so local
development uses normal Gradle project dependencies. The root JaCaMo
application depends on the modules directly:

```gradle
implementation project(':ccrs-core')
implementation project(':ccrs-jacamo')
implementation project(':ccrs-hypermedea')
implementation project(':ccrs-langchain4j')
implementation project(':ccrs-a2a')
```

This means changes made inside a `ccrs-*` module are immediately used by the
agents in this repository when running the local JaCaMo application.

Compile the app and all CCRS modules:

```powershell
./gradlew classes
```

Publish only the `ccrs-*` libraries to your local Maven repository:

```powershell
./gradlew publishToMavenLocal
```

The published coordinates use:

```text
io.github.stefanmhsg.ccrs:<module-name>:0.1.0-SNAPSHOT
```

For example:

```gradle
implementation 'io.github.stefanmhsg.ccrs:ccrs-core:0.1.0-SNAPSHOT'
```

The root JaCaMo application is not configured as a Maven publication. Only the
`ccrs-*` modules are publishable.

You can also publish the libraries to a repository-local Maven directory:

```powershell
./gradlew :ccrs-core:publishMavenJavaPublicationToCcrsLocalRepository `
  :ccrs-jacamo:publishMavenJavaPublicationToCcrsLocalRepository `
  :ccrs-hypermedea:publishMavenJavaPublicationToCcrsLocalRepository `
  :ccrs-langchain4j:publishMavenJavaPublicationToCcrsLocalRepository `
  :ccrs-a2a:publishMavenJavaPublicationToCcrsLocalRepository
```

That writes artifacts under `build/local-maven-repo`, which is useful for
clean consumer smoke tests.

## Standalone Consumer Example

[examples/ccrs-library-consumer](examples/ccrs-library-consumer) is a tiny
separate Gradle project that demonstrates how another repository can import
the published CCRS libraries from local Maven instead of using Gradle project
dependencies.

Run it after publishing the libraries:

```powershell
./gradlew publishToMavenLocal
./gradlew -p examples/ccrs-library-consumer run
```

The example creates a minimal in-memory CCRS context, evaluates a retryable
failure situation, and prints the resulting strategy suggestion.

## Run The Local JaCaMo App

Defaults to [ccrs_bdi.jcm](ccrs_bdi.jcm):

```powershell
gradle run
```

To run a specific JaCaMo configuration file, use:

##### Depth-First Search (DFS) Baseline Agent:

* [dfs_baseline.asl](src/agt/dfs_baseline.asl) implements a Depth-First Search to navigate the maze. This is a possible solution without considering any CCRS features. Can handle 'unlock' actions.

```powershell
gradle run "-Pjcm=dfs_baseline.jcm"
```

##### DFS Baseline Agent extended with opportunistic CCRS:

* [dfs_opportunistic_ccrs.asl](src/agt/dfs_opportunistic_ccrs.asl) extends the DFS baseline agent with opportunistic CCRS features. It defaults to DFS but prioritizes options at every step based on opportunistic CCRS outcomes.

```powershell
gradle run "-Pjcm=dfs_opportunistic_ccrs.jcm"
```

##### DFS Baseline Agent extended with opportunistic and contingency CCRS:

* [dfs_ccrs.asl](src/agt/dfs_ccrs.asl) extends the DFS baseline agent with opportunistic CCRS features. It defaults to DFS but prioritizes options at every step based on opportunistic CCRS outcomes. Additionally, it incorporates contingency CCRS features that provide a set of strategies to guide the agent's actions in case of predefined situations.

```powershell
gradle run "-Pjcm=dfs_ccrs.jcm"
```

---

## Mindinspector URL

http://192.168.68.53:3272/

## Resources

* [JaCaMo Docs](https://jacamo-lang.github.io/doc)

* [Jason Docs](https://jason-lang.github.io/)
* [Jason API](https://jason-lang.github.io/api/jason/stdlib/package-summary.html#package.description)
* [Unification of Annotations](https://jason-lang.github.io/jason/tech/annotations.html)
* [Plan patterns](https://jason-lang.github.io/jason/tech/patterns.html)

* [Hypermedea Github](https://github.com/Hypermedea/hypermedea)
    * [Artifact](https://github.com/Hypermedea/hypermedea/blob/master/hypermedea-lib/src/main/java/org/hypermedea/HypermedeaArtifact.java)
* [Hypermedea API](https://hypermedea.github.io/javadoc/hypermedea/latest/)
    * [rdf](https://hypermedea.github.io/javadoc/hypermedea/latest/org/hypermedea/ct/rdf/package-summary.html)

* [LDFU](https://linked-data-fu.github.io/#faq)
