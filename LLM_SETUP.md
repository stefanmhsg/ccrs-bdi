# LLM Provisioning Implementation Summary

## Overview

LLM capability integration for Contingency-CCRS strategies, enabling PredictionLlmStrategy (L2) and ConsultationStrategy (L4) with automatic environment-based configuration.

## Architecture

```
┌─────────────────────────────────────────────────┐
│ .env file (project root)                        │
│ - OPENAI_API_KEY=sk-...                         │
│ - OPENAI_MODEL=gpt-4o-mini (optional)           │
└─────────────────┬───────────────────────────────┘
                  │ loaded by
                  ▼
┌─────────────────────────────────────────────────┐
│ CcrsAgentArch.java (static initializer)         │
│ - Loads .env using Dotenv library               │
│ - Injects into System.setProperty()             │
│ - Runs before class initialization              │
└─────────────────┬───────────────────────────────┘
                  │ environment ready
                  ▼
┌─────────────────────────────────────────────────┐
│ evaluate.java getCcrs()                         │
│ - Langchain4jLlmClient.fromEnvironment()        │
│ - Auto-registers PredictionLlmStrategy (L2)     │
│ - Auto-registers ConsultationStrategy (L4)      │
│ - Graceful degradation if API key missing       │
└─────────────────────────────────────────────────┘
```

## Implementation Details

### 1. Dependency Management ([build.gradle](s:\dev\ma\ccrs-bdi\build.gradle))

Added dotenv-java for environment variable loading:

```gradle
dependencies {
    // ... existing dependencies ...
    implementation('io.github.cdimascio:dotenv-java:3.0.0')
}
```

### 2. Environment Loading ([CcrsAgentArch.java](s:\dev\ma\ccrs-bdi\src\agt\ccrs\jacamo\jaca\CcrsAgentArch.java))

Static initializer loads `.env` before JaCaMo initialization:

```java
static {
    try {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();
        
        dotenv.entries().forEach(entry -> 
            System.setProperty(entry.getKey(), entry.getValue())
        );
    } catch (Exception e) {
        logger.log(Level.WARNING, "[CcrsAgentArch] Failed to load .env: " + e.getMessage());
    }
}
```

**Why here?**
- Runs before any JaCaMo agents start
- Project-wide environment availability
- Clean separation: initialization ≠ strategy registration

### 3. Strategy Registration ([evaluate.java](s:\dev\ma\ccrs-bdi\src\agt\ccrs\jacamo\jason\contingency\evaluate.java))

Platform adapter wires LLM capability into contingency core:

```java
private synchronized ContingencyCcrs getCcrs() {
    if (contingencyCcrs == null) {
        contingencyCcrs = ContingencyCcrs.withDefaults();
        
        try {
            LlmClient llmClient = Langchain4jLlmClient.fromEnvironment();
            
            if (llmClient.isAvailable()) {
                // PredictionLlmStrategy (L2) - autonomous prediction
                PredictionLlmStrategy predictionStrategy = new PredictionLlmStrategy(
                    llmClient,
                    TemplatePromptBuilder.create(),
                    JsonActionParser.create()
                );
                contingencyCcrs.getRegistry().register(predictionStrategy);
                
                // ConsultationStrategy (L4) - human-in-the-loop
                ConsultationStrategy.ConsultationChannel channel = 
                    ConsultationStrategy.llmChannel(
                        llmClient,
                        TemplatePromptBuilder.create(),
                        JsonActionParser.create()
                    );
                ConsultationStrategy consultationStrategy = new ConsultationStrategy(channel);
                contingencyCcrs.getRegistry().register(consultationStrategy);
                
                logger.info("[ContingencyCcrs] LLM strategies enabled (PredictionLlm, Consultation)");
            } else {
                logger.warning("[ContingencyCcrs] LLM client not available");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[ContingencyCcrs] LLM initialization failed: " + e.getMessage());
        }
        
        logger.info("[ContingencyCcrs] Contingency CCRS initialized");
    }
    return contingencyCcrs;
}
```

**Architectural principles respected:**
- **Core is pure**: PredictionLlmStrategy depends only on LlmClient interface
- **Capability is encapsulated**: Langchain4jLlmClient wraps library-specific code
- **Adapter is the assembler**: evaluate.java knows which implementations to wire

### 4. Configuration Files

#### [.env](s:\dev\ma\ccrs-bdi\.env) (gitignored, user-specific)
```properties
OPENAI_API_KEY=sk-proj-your-api-key-here
# OPENAI_MODEL=gpt-4o-mini
# OPENAI_BASE_URL=https://api.openai.com/v1
```

#### [.env.example](s:\dev\ma\ccrs-bdi\.env.example) (committed, template)
Complete documentation with all supported variables, usage notes, and examples.

#### [.gitignore](s:\dev\ma\ccrs-bdi\.gitignore)
Added `.env` to prevent accidental API key commits.

## Usage

### 1. Setup

```bash
# Copy template
cp .env.example .env

# Add your API key
# Edit .env and replace sk-proj-your-api-key-here
```

### 2. Run

```bash
gradle run
```

**Expected logs:**
```
[CcrsAgentArch] Interaction Log Sink installed for Hypermedea.
[ContingencyCcrs] LLM strategies enabled (PredictionLlm, Consultation)
[ContingencyCcrs] Contingency CCRS initialized
```

### 3. Verify Strategy Availability

When agent calls `evaluate`, both LLM strategies will be available:

```asl
// In agent code
ccrs.jacamo.jason.contingency.evaluate("stuck", "low_progress", Location, Suggestions)
```

**Available strategies** (if API key configured):
- RetryStrategy (L1) - built-in
- BacktrackStrategy (L2) - built-in
- PredictionLlmStrategy (L2) - LLM-powered prediction
- ConsultationStrategy (L4) - LLM-assisted human consultation
- StopStrategy (L0) - built-in fallback

## Graceful Degradation

If API key is not configured:
- No errors or crashes
- Log warning: `[ContingencyCcrs] LLM client not available`
- Built-in strategies (Retry, Backtrack, Stop) still work
- Agent continues with reduced capability set

## Environment Variables Reference

### Required
- `OPENAI_API_KEY` (or `LLM_API_KEY` fallback)

### Optional
- `OPENAI_MODEL` (default: `gpt-4o-mini`)
- `OPENAI_BASE_URL` (default: OpenAI API)
- `OPENAI_ORGANIZATION_ID` (for org keys)

See [.env.example](s:\dev\ma\ccrs-bdi\.env.example) for full documentation.

## Testing Without API Key

To test graceful degradation:

```bash
# Remove or comment out OPENAI_API_KEY in .env
# OPENAI_API_KEY=sk-...

gradle run
```

Expected: System runs with built-in strategies only.

## Next Steps

1. **Add valid API key** to `.env`
2. **Test agent behavior** with LLM strategies enabled
3. **Monitor logs** for strategy selection decisions
4. **Adjust model** in `.env` if needed (e.g., `OPENAI_MODEL=gpt-4o` for higher quality)
5. **Review strategy outputs** in contingency scenarios

## Related Documentation

- [contingency/README.md](s:\dev\ma\ccrs-bdi\src\agt\ccrs\jacamo\jason\contingency\README.md) - evaluate() usage guide
- [contingency/examples.asl](s:\dev\ma\ccrs-bdi\src\agt\ccrs\jacamo\jason\contingency\examples.asl) - AgentSpeak patterns
- [capabilities/llm/README.md](s:\dev\ma\ccrs-bdi\src\agt\ccrs\capabilities\llm\README.md) - LLM integration architecture
