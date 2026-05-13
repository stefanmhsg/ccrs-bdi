package ccrs.capabilities.a2a;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

final class A2aDotenvConfigFallback {

    private static final Logger logger =
        Logger.getLogger(A2aDotenvConfigFallback.class.getName());
    private static boolean enabled;

    private A2aDotenvConfigFallback() {
    }

    static synchronized void enableIfAvailable() {
        if (enabled) {
            return;
        }

        try {
            Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

            A2aConfigResolver.enableDotenvFallback(() ->
                dotenv.entries().stream()
                    .collect(Collectors.toMap(
                        DotenvEntry::getKey,
                        DotenvEntry::getValue
                    ))
            );
            enabled = true;
        } catch (Exception e) {
            logger.log(Level.FINE, "A2A dotenv fallback unavailable: " + e.getMessage(), e);
        }
    }
}
