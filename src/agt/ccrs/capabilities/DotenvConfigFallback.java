package ccrs.capabilities;

import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

/**
 * Optional .env bridge for capability modules.
 *
 * <p>JaCaMo integration must not depend on dotenv. Capability providers that
 * read environment-style configuration can opt into this fallback when the
 * dotenv dependency is present in their module.</p>
 */
public final class DotenvConfigFallback {

    private static final Logger logger = Logger.getLogger(DotenvConfigFallback.class.getName());
    private static boolean enabled;

    private DotenvConfigFallback() {
    }

    public static synchronized void enableIfAvailable() {
        if (enabled) {
            return;
        }

        try {
            Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

            ConfigResolver.enableDotenvFallback(() ->
                dotenv.entries().stream()
                    .collect(Collectors.toMap(
                        DotenvEntry::getKey,
                        DotenvEntry::getValue
                    ))
            );
            enabled = true;
        } catch (Exception e) {
            logger.log(Level.FINE, "Dotenv fallback unavailable: " + e.getMessage(), e);
        }
    }
}
