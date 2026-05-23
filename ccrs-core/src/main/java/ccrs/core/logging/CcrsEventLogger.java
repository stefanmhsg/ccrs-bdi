package ccrs.core.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Emits stable key-value CCRS events for diagnostics and downstream analysis.
 *
 * <p>The event vocabulary is intentionally generic. Applications can decide
 * which events count as experiment metrics without coupling the CCRS libraries
 * to one local experiment setup.</p>
 */
public final class CcrsEventLogger {

    public static final String PREFIX = "[CCRS-EVENT]";

    private CcrsEventLogger() {
    }

    public static void info(Logger logger, String eventName, Map<String, ?> fields) {
        log(logger, Level.INFO, eventName, fields);
    }

    public static void log(Logger logger, Level level, String eventName, Map<String, ?> fields) {
        if (logger == null || eventName == null || eventName.isBlank()) {
            return;
        }

        logger.log(level != null ? level : Level.INFO, format(eventName, fields));
    }

    public static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (keyValues == null) {
            return fields;
        }

        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key != null) {
                fields.put(String.valueOf(key), keyValues[i + 1]);
            }
        }
        return fields;
    }

    public static String format(String eventName, Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder(PREFIX)
            .append(' ')
            .append("event=")
            .append(formatValue(eventName));

        if (fields != null) {
            for (Map.Entry<String, ?> entry : fields.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                sb.append(' ')
                    .append(entry.getKey())
                    .append('=')
                    .append(formatValue(entry.getValue()));
            }
        }

        return sb.toString();
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }

        String text = String.valueOf(value);
        if (isBareValue(text)) {
            return text;
        }

        return "\"" + escape(text) + "\"";
    }

    private static boolean isBareValue(String text) {
        return !text.isEmpty() && text.matches("[A-Za-z0-9_.:/#@+-]+");
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
