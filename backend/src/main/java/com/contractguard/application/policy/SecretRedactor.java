package com.contractguard.application.policy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes likely credentials from text before it reaches logs, artifacts or
 * the model (§17). Patterns favour recall over precision: redacting a
 * harmless string costs nothing, leaking a real token is unacceptable.
 */
public final class SecretRedactor {

    private static final String MASK = "[REDACTED]";

    private static final List<Pattern> PATTERNS = List.of(
            // key=value style assignments for sensitive keys (properties, yaml, env)
            Pattern.compile(
                    "(?i)((?:password|passwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret)"
                            + "\\s*[=:]\\s*)(\\S+)"),
            // Authorization headers
            Pattern.compile("(?i)(authorization\\s*[=:]?\\s*(?:bearer|basic)\\s+)(\\S+)"),
            // Well-known token shapes
            Pattern.compile("\\b(sk-[A-Za-z0-9_-]{16,})\\b"),
            Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{20,})\\b"),
            Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"),
            Pattern.compile("\\b(eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,})\\b"),
            // PEM blocks
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(match ->
                    match.groupCount() >= 2
                            ? java.util.regex.Matcher.quoteReplacement(match.group(1) + MASK)
                            : java.util.regex.Matcher.quoteReplacement(MASK));
        }
        return result;
    }
}
