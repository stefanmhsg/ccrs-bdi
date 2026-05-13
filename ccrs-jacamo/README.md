# CCRS JaCaMo Adapter Boundary

This package is the JaCaMo-facing adapter for CCRS. It intentionally contains
the pieces that must work together for JaCaMo agents:

- `ccrs.jacamo.jason.opportunistic.CcrsAgent`: Jason BRF integration.
- `ccrs.jacamo.jaca.CcrsAgentArch`: CArtAgO observable batching before the next reasoning cycle.
- `ccrs.jacamo.jason.contingency.evaluate`: AgentSpeak-facing contingency evaluation.
- `ccrs.jacamo.jason.opportunistic.prioritize`: AgentSpeak-facing option prioritization.
- `ccrs.jacamo.jason.contingency.JasonCcrsContext`: Jason belief-base backed `CcrsContext`.

`CcrsAgent` and `CcrsAgentArch` are complementary, not duplicate integration
points. Artifacts such as Hypermedea may synchronize directly with the belief
base, while structural opportunistic CCRS matching needs cycle-level batching
from CArtAgO observables.

## Optional Artifact History

The JaCaMo adapter does not depend on a concrete HTTP artifact implementation.
Interaction history is supplied through
[CcrsJacamoRuntime.java](src/main/java/ccrs/jacamo/CcrsJacamoRuntime.java):

```java
ccrs.jacamo.CcrsJacamoRuntime.setInteractionHistoryProvider(...)
```

The default provider is empty. The separate
[Hypermedea adapter README.md](../ccrs-hypermedea/README.md) describes how
[JasonInteractionLog.java](../ccrs-hypermedea/src/main/java/ccrs/hypermedea/JasonInteractionLog.java)
is installed as the provider when the Hypermedea registry is loaded.

This keeps Hypermedea replaceable: another HTTP artifact can implement
`InteractionHistoryProvider` and install itself without changing the JaCaMo
adapter.

## Optional Strategy Capabilities

The contingency internal action creates its `ContingencyCcrs` through
`CcrsJacamoRuntime`. The default supplier uses `ServiceLoader` for
`ccrs.core.contingency.CcrsStrategyProvider`.

This is the plugin point for optional strategy capabilities. The JaCaMo adapter
must not import concrete capability implementations such as LangChain4j or A2A,
because those dependencies should be optional. Instead, capability modules
announce themselves to Java's built-in `ServiceLoader` mechanism.

The runtime flow is:

```text
AgentSpeak calls ccrs.jacamo.jason.contingency.evaluate(...)
  -> evaluate asks CcrsJacamoRuntime for a ContingencyCcrs instance
  -> CcrsJacamoRuntime uses ContingencyCcrsFactory
  -> ContingencyCcrsFactory registers built-in core strategies
  -> ServiceLoader discovers CcrsStrategyProvider implementations on the classpath
  -> each provider registers its optional strategies
```

`META-INF/services` is the standard Java location where a jar lists service
implementations. The file name must be the fully qualified interface name:

```text
META-INF/services/ccrs.core.contingency.CcrsStrategyProvider
```

The file content is one provider implementation class per line:

```text
ccrs.capabilities.llm.langchain4j.Langchain4jPredictionStrategyProvider
ccrs.capabilities.a2a.A2aConsultationStrategyProvider
```

When that file is packaged into a jar and the jar is on the JaCaMo application's
classpath, `ServiceLoader` can instantiate those provider classes. Each provider
then decides whether its capability is configured and available before it
registers a strategy. For example, the LangChain4j provider can skip
registration when no LLM API key is configured.

In this repository, each optional capability module carries its own service
file under its own `src/main/resources`. For example,
[ccrs-langchain4j](../ccrs-langchain4j/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider)
lists only `Langchain4jPredictionStrategyProvider`, and
[ccrs-a2a](../ccrs-a2a/src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider)
lists only `A2aConsultationStrategyProvider`.

This keeps `evaluate` independent from concrete LLM, A2A, or other capability
implementations.

Applications that do not want classpath-based discovery can override the
factory completely:

```java
CcrsJacamoRuntime.setContingencyCcrsSupplier(() -> {
    ContingencyCcrs ccrs = ContingencyCcrs.withDefaults();
    ccrs.getRegistry().register(new MyCustomStrategy());
    return ccrs;
});
```

Use this explicit supplier when a deployment wants full control over which
strategies are available.
