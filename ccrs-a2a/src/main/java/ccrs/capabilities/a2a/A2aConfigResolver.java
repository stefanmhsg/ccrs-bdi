package ccrs.capabilities.a2a;

import java.util.Map;
import java.util.function.Supplier;

final class A2aConfigResolver {

    private static Supplier<Map<String, String>> dotenvSupplier = null;

    private A2aConfigResolver() {
    }

    static void enableDotenvFallback(Supplier<Map<String, String>> supplier) {
        dotenvSupplier = supplier;
    }

    static String resolve(String key) {
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

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
