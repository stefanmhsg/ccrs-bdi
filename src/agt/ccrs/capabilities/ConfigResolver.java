package ccrs.capabilities;

import java.util.Map;
import java.util.function.Supplier;

public final class ConfigResolver {

    private static Supplier<Map<String, String>> dotenvSupplier = null;

    private ConfigResolver() {}

    public static void enableDotenvFallback(Supplier<Map<String, String>> supplier) {
        dotenvSupplier = supplier;
    }

    public static String resolve(String key) {
        String value = System.getenv(key);
        if (isSet(value)) return value;

        value = System.getProperty(key);
        if (isSet(value)) return value;

        if (dotenvSupplier != null) {
            value = dotenvSupplier.get().get(key);
            if (isSet(value)) return value;
        }

        return null;
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }
}
