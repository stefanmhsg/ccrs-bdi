package ccrs.capabilities.llm.langchain4j;

import java.util.logging.Level;
import java.util.logging.Logger;

import ccrs.capabilities.DotenvConfigFallback;
import ccrs.core.contingency.CcrsStrategyProvider;
import ccrs.core.contingency.LlmClient;
import ccrs.core.contingency.StrategyRegistry;
import ccrs.core.contingency.strategies.internal.prediction.PredictionLlmStrategy;

/**
 * ServiceLoader provider for LangChain4j-backed LLM prediction.
 */
public class Langchain4jPredictionStrategyProvider implements CcrsStrategyProvider {

    private static final Logger logger =
        Logger.getLogger(Langchain4jPredictionStrategyProvider.class.getName());

    @Override
    public void registerStrategies(StrategyRegistry registry) {
        if (registry.getStrategy(PredictionLlmStrategy.ID).isPresent()) {
            logger.info("[Langchain4jProvider] Prediction strategy already registered");
            return;
        }

        try {
            DotenvConfigFallback.enableIfAvailable();
            LlmClient llmClient = Langchain4jLlmClient.fromEnvironment();
            if (!llmClient.isAvailable()) {
                logger.info("[Langchain4jProvider] LLM client not available");
                return;
            }

            registry.register(new PredictionLlmStrategy(llmClient));
            logger.info("[Langchain4jProvider] Registered PredictionLlmStrategy");
        } catch (Exception e) {
            logger.log(Level.WARNING,
                "[Langchain4jProvider] LLM prediction strategy not registered: " + e.getMessage());
        }
    }
}
