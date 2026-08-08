# CCRS BDI Application

This repository is the JaCaMo/Jason demonstrator and evaluation application for
[Course Check and Revision Strategies (CCRS)](https://github.com/stefanmhsg/ccrs).
It owns the application `.jcm` configurations, AgentSpeak programs,
environments, tests, logging, and experiment material. Reusable CCRS source is
not stored here; the application consumes published Maven packages.

## Requirements

- Java 21
- GitHub Packages credentials for local package downloads
- External services required by the selected JaCaMo scenario

Put GitHub credentials only in the user-level Gradle file
`%USERPROFILE%\.gradle\gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_GITHUB_PAT_WITH_READ_PACKAGES
```

Never add credentials to this repository. GitHub Actions uses its runtime
`GITHUB_TOKEN` with `packages: read`.

## CCRS Package Selection

The application always consumes the CCRS core and JaCaMo adapter:

```text
io.github.stefanmhsg.ccrs:ccrs-core:0.1.0-SNAPSHOT
io.github.stefanmhsg.ccrs:ccrs-jacamo:0.1.0-SNAPSHOT
```

Optional capabilities are runtime-only modules selected with
`-PccrsCapabilities`. The default is `all` to preserve the complete application
behavior.

| Value | Runtime capability modules |
| --- | --- |
| `none` | Core and JaCaMo only |
| `hypermedea` | Hypermedea artifact and interaction history |
| `langchain4j` | LangChain4j-backed prediction provider |
| `a2a` | A2A-backed consultation provider |
| `hypermedea,a2a` | An explicit comma-separated combination |
| `all` | Hypermedea, LangChain4j, and A2A |

The packages resolve from:

```text
https://maven.pkg.github.com/stefanmhsg/ccrs
```

There is no Maven Local, sibling-source, composite-build, or filesystem
fallback.

## Build And Test

Compile the default application without starting agents:

```powershell
.\gradlew.bat --refresh-dependencies clean test classes
```

Verify a minimal package profile:

```powershell
.\gradlew.bat -PccrsCapabilities=none clean test classes printCcrsCapabilities
```

The normal `test` task verifies the application-owned JaCaMo fixture set but
does not start a multi-agent system. The interactive JaCaMo test manager remains
available as the explicit `testJaCaMo` task.

The
[BDI consumer workflow](.github/workflows/verify-bdi-consumer.yml) builds five
capability profiles with separate fresh Gradle user homes. This verifies that a
clean checkout resolves only the published packages from the separate `ccrs`
repository.

## Run The JaCaMo Application

The default configuration is [ccrs_bdi.jcm](ccrs_bdi.jcm):

```powershell
.\gradlew.bat run
```

Run a specific application configuration with `-Pjcm`.

### DFS baseline

[dfs_baseline.asl](src/agt/dfs_baseline.asl) implements depth-first search
without CCRS prioritization:

```powershell
.\gradlew.bat run "-Pjcm=dfs_baseline.jcm"
```

### Opportunistic CCRS

[dfs_opportunistic_ccrs.asl](src/agt/agt_archive/dfs_opportunistic_ccrs.asl)
prioritizes available options using opportunistic CCRS:

```powershell
.\gradlew.bat run "-Pjcm=dfs_opportunistic_ccrs.jcm"
```

### Opportunistic and contingency CCRS

[dfs_ccrs.asl](src/agt/dfs_ccrs.asl) combines opportunity prioritization with
contingency strategy evaluation:

```powershell
.\gradlew.bat run "-Pjcm=dfs_ccrs.jcm"
```

The DFS configurations use the Hypermedea artifact and therefore require a
profile containing `hypermedea`; the default `all` profile satisfies this.

## Repository Layout

| Path | Responsibility |
| --- | --- |
| [src/agt](src/agt) | AgentSpeak application programs and examples |
| [src/env](src/env) | Application CArtAgO environment artifacts |
| [src/org](src/org) | Application organization specification |
| [src/test](src/test) | Application-owned JaCaMo test fixtures |
| [experiments](experiments) | MASE experiment inputs, scripts, runs, and reports |
| Root `*.jcm` files | Runnable JaCaMo application configurations |

CCRS library architecture, source, publications, and release planning belong
in the separate [ccrs repository](https://github.com/stefanmhsg/ccrs).

## Related Documentation

- [JaCaMo documentation](https://jacamo-lang.github.io/doc)
- [Jason documentation](https://jason-lang.github.io/)
- [Hypermedea API](https://hypermedea.github.io/javadoc/hypermedea/latest/)
