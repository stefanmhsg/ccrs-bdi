# CCRS LLM Integration

This module provides LLM integration for CCRS contingency strategies through three main abstractions:
- **LlmClient**: Interface for executing LLM completions
- **PromptBuilder**: Interface for constructing prompts
- **LlmResponseParser**: Interface for parsing LLM responses

## Architecture

```
┌─────────────────────────────────────┐
│   Contingency Strategies            │
│   - PredictionLlmStrategy           │
│   - ConsultationStrategy            │
└─────┬────────────────┬──────────────┘
      │ uses           │ uses
      ▼                ▼
┌──────────────┐ ┌────────────────────┐
│PromptBuilder │ │LlmResponseParser   │
│ (interface)  │ │(interface)         │
└──────┬───────┘ └────────┬───────────┘
       │                  │
       │                  │
       ▼                  ▼
┌──────────────┐ ┌────────────────────┐
│Template      │ │JsonActionParser    │
│PromptBuilder │ │(standard)          │
│(standard)    │ │                    │
└──────────────┘ └────────────────────┘
```

## Usage Examples

### Recommended Setup

```java
import ccrs.core.contingency.*;
import ccrs.capabilities.llm.*;
import ccrs.capabilities.llm.langchain4j.*;

// Create LLM client
LlmClient llmClient = Langchain4jLlmClient.fromEnvironment();

// Use standard implementations (recommended)
PromptBuilder promptBuilder = TemplatePromptBuilder.create();
LlmResponseParser parser = JsonActionParser.create();

// Create strategy with all components
PredictionLlmStrategy strategy = new PredictionLlmStrategy(llmClient, promptBuilder, parser);
```

### Custom Templates

```java
// Customize templates for your experiment
TemplatePromptBuilder promptBuilder = TemplatePromptBuilder.create()
    .withPredictionTemplate("""
        Custom template with {currentResource} and {failedAction}
        ...
        """)
    .withConsultationTemplate("""
        Custom consultation template...
        """);

PredictionLlmStrategy strategy = new PredictionLlmStrategy(llmClient, promptBuilder);
```

### Consultation Strategy

```java
// Create consultation channel with LLM backend
LlmResponseParser parser = JsonActionParser.create();
ConsultationStrategy.ConsultationChannel channel = 
    ConsultationStrategy.llmChannel(llmClient, parser);

// With custom prompts
PromptBuilder customPrompts = TemplatePromptBuilder.create()
    .withConsultationTemplate("...");
ConsultationStrategy.ConsultationChannel channel = 
    ConsultationStrategy.llmChannel(llmClient, customPrompts, parser);

ConsultationStrategy strategy = new ConsultationStrategy(channel);
```
Components

### Custom Prompt Builders

Implement the `PromptBuilder` interface for experiment-specific or domain-specific prompts:

```java
public class ExperimentPromptBuilder implements PromptBuilder {
    
    @Override
    public String buildPredictionPrompt(Map<String, Object> contextMap) {
        // Custom logic for your experiment
        return "...";
    }
    
    @Override
    public String buildConsultationPrompt(String question, Map<String, Object> contextMap) {
        // Custom consultation prompts
        return "...";
    }
    
    @Override
    public String getDescription() {
        return "ExperimentPromptBuilder[v1]";
    }
}
```

### Custom Response Parsers

Implement the `LlmResponseParser` interface for specialized parsing logic:

```java
public class CustomParser implements LlmResponseParser {
    
    @Override
    public LlmActionResponse parse(String rawResponse) {
        // Custom parsing logic
        // - Handle your specific response format
        // - Extract action, target, explanation
        // - Return LlmActionResponse.valid() or .invalid()
        return LlmActionResponse.valid(action, target, explanation)
            .withConfidence(0.8)
            .withMetadata("parseMethod", "custom");
    }
    
    @Override
    public String getDescription() {
        return "CustomParser[v1]";
    }
}
```

## Benefits

1. **Separation of Concerns**: Strategy logic separate from prompt engineering and parsing
2. **Centralized Parsing**: Single source of truth for response schemas
3. **Testability**: Easy to mock prompts and responses for testing
4. **Experimentation**: Swap components without changing strategy code
5. **Error Handling**: Robust parsing with clear error reporting
6. **Experimentation**: Swap prompt builders without changing strategy code
4. **Backend Agnostic**: Works with any LLM provider
5. **Versioning**: Track prompt versions independently from code

## Implementations

### TemplatePromptBuilder (Recommended)
**Location**: `ccrs.capabilities.llm.TemplatePromptBuilder`
- Standard reference implementation
- Backend agnostic - works with any LLM client
- Structured formatting with clear sections
- Smart formatting for complex data types
- Explicit JSON output format
- Use this for production and most experiments

### DefaultPromptBuilder (Minimal)
**Location**: `ccrs.core.contingency.DefaultPromptBuilder`
- Simple string replacement only
- Minimal formatting
- Fallback when nothing else specified
- Use for quick prototyping or when you need absolute minimal dependencies

## Architectural Principles

**PromptBuilder = Pure Formatter**
- Accepts only pre-prepared context maps
- NO dependencies on Situation, CcrsContext, or CCRS internals
- Backend agnostic - no LLM client dependencies
- Simple, stable, and easy to test

**Strategies Prepare Context**
- Strategies extract and format bounded data
- Use `getNeighborhood()`, not `queryAll()`
- Prepare context map before calling prompt builder

## Future Extensions

- Few-shot learning examples
- Dynamic prompt selection based on situation type
- Prompt caching and reuse
- Integration with platform-specific prompt template systems

