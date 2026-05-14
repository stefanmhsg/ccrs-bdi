# CCRS LangChain4j LLM Provider

This Gradle module (`ccrs-langchain4j`) contains the optional LangChain4j/OpenAI
adapter for the CCRS core [`LlmClient`](../ccrs-core/src/main/java/ccrs/core/contingency/LlmClient.java)
interface. It depends on `ccrs-core`, contributes
[`Langchain4jPredictionStrategyProvider.java`](src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jPredictionStrategyProvider.java)
through Java `ServiceLoader`, and can be omitted from applications that do not
need LangChain4j-backed LLM access.

The module is intentionally provider-focused. Prompt templates, prompt-building
contracts, response parsing, and LLM strategy behavior belong to `ccrs-core`:

- [`PromptBuilder.java`](../ccrs-core/src/main/java/ccrs/core/contingency/PromptBuilder.java)
- [`LlmResponseParser.java`](../ccrs-core/src/main/java/ccrs/core/contingency/LlmResponseParser.java)
- [`PredictionLlmStrategy.java`](../ccrs-core/src/main/java/ccrs/core/contingency/strategies/internal/prediction/PredictionLlmStrategy.java)
- [`DefaultPredictionPromptBuilder.java`](../ccrs-core/src/main/java/ccrs/core/contingency/strategies/internal/prediction/DefaultPredictionPromptBuilder.java)
- [`JsonActionParser.java`](../ccrs-core/src/main/java/ccrs/core/contingency/strategies/internal/prediction/JsonActionParser.java)

Keeping these pieces in core means the same strategy prompt and parser can be
used with any `LlmClient` implementation, not only LangChain4j.

## Architecture Overview

```text
+--------------------------------------------------------------+
| Application / JaCaMo adapter                                 |
| - depends on selected CCRS modules                           |
| - calls ContingencyCcrsFactory / strategy registry           |
+-------------------------------+------------------------------+
                                |
                                v
+--------------------------------------------------------------+
| ccrs-core                                                    |
|                                                              |
|  PredictionLlmStrategy                                       |
|    -> owns prediction behavior and prompt defaults           |
|    -> uses PromptBuilder + LlmResponseParser contracts       |
|                                                              |
|  DefaultPredictionPromptBuilder                              |
|    -> builds the standard prediction prompt                  |
|                                                              |
|  JsonActionParser                                            |
|    -> maps model output into LlmActionResponse               |
|                                                              |
|  LlmClient                                                   |
|    -> small provider-neutral completion interface            |
+-------------------------------+------------------------------+
                                |
                                | implemented by
                                v
+--------------------------------------------------------------+
| ccrs-langchain4j                                             |
|                                                              |
|  Langchain4jLlmClient                                        |
|    -> adapts LangChain4j ChatModel to LlmClient              |
|                                                              |
|  Langchain4jConfig + DotenvConfigFallback                    |
|    -> provider configuration only                            |
|                                                              |
|  Langchain4jPredictionStrategyProvider                       |
|    -> ServiceLoader provider; supplies only the LLM client   |
+--------------------------------------------------------------+
```

The important dependency direction is:

```text
application -> ccrs-langchain4j -> ccrs-core
application -> ccrs-core

ccrs-core does not depend on LangChain4j.
```

## Provided Classes

- [`Langchain4jLlmClient.java`](src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jLlmClient.java)
  adapts a LangChain4j `ChatModel` to the core `LlmClient` interface.
- [`Langchain4jConfig.java`](src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jConfig.java)
  reads OpenAI-compatible model settings from explicit configuration or the
  environment.
- [`Langchain4jPredictionStrategyProvider.java`](src/main/java/ccrs/capabilities/llm/langchain4j/Langchain4jPredictionStrategyProvider.java)
  registers `PredictionLlmStrategy` when the provider can create an available
  LangChain4j-backed client.

## Usage

```java
import ccrs.capabilities.llm.langchain4j.Langchain4jLlmClient;
import ccrs.core.contingency.LlmClient;
import ccrs.core.contingency.strategies.internal.prediction.PredictionLlmStrategy;

LlmClient llmClient = Langchain4jLlmClient.fromEnvironment();

PredictionLlmStrategy strategy = new PredictionLlmStrategy(llmClient);
```

Applications that use `ContingencyCcrsFactory` can rely on the service file in
[`META-INF/services/ccrs.core.contingency.CcrsStrategyProvider`](src/main/resources/META-INF/services/ccrs.core.contingency.CcrsStrategyProvider)
instead of wiring the strategy manually.

## Boundary Rule

Do not add prompt templates, prompt builders, response parsers, or strategy
logic to this module. Add provider-specific code here only when it depends on
LangChain4j, OpenAI-compatible model configuration, or the optional dotenv
bridge used to load provider configuration.
