package com.contractguard.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source transformations for the mechanical change categories
 * (endpoint rename, property rename, enum value removal) — exact text
 * surgery, not a model guess. Transformations are idempotent, so re-applying
 * them (a scripted mock-mode "rewrite", a real repair pass re-reading
 * already-patched content, or both in the same run) is always safe.
 *
 * <p>Used two ways: {@code adapter.llm.ScriptedLlmGateway} builds mock mode's
 * entire response from it, and {@code application.service.ExecutionService}
 * applies it as a pre-transform ahead of every real LLM call (ADR-0015) —
 * the mechanical part of a plan is solved for free regardless of which
 * gateway is configured.
 */
public final class DeterministicRemediation {

    /** The change facts a transformation needs; a projection of {@link ApiChange}. */
    public record ChangeSpec(String type, String oldValue, String newValue) {
    }

    private DeterministicRemediation() {
    }

    /** Projects the facts {@link #transform} needs from real {@link ApiChange}s (ADR-0015). */
    public static List<ChangeSpec> specsFor(List<ApiChange> changes) {
        return changes.stream()
                .map(change -> new ChangeSpec(change.type().name(), change.oldValue(), change.newValue()))
                .toList();
    }

    /**
     * Applies every deterministic transform this class knows to each file, keeping only the
     * files whose content actually changed — the shape both {@link ScriptedLlmGateway} and
     * {@link ExecutionService}'s pre-transform need (ADR-0015).
     */
    public static Map<String, String> applyToChangedFiles(Map<String, String> currentFiles,
            List<ApiChange> changes) {
        List<ChangeSpec> specs = specsFor(changes);
        Map<String, String> changed = new LinkedHashMap<>();
        currentFiles.forEach((path, content) -> {
            String transformed = transform(content, specs);
            if (!transformed.equals(content)) {
                changed.put(path, transformed);
            }
        });
        return changed;
    }

    public static String transform(String content, List<ChangeSpec> changes) {
        String result = content;
        for (ChangeSpec change : changes) {
            result = switch (change.type()) {
                case "ENDPOINT_RENAMED" -> replaceEndpointPath(result, change.oldValue(), change.newValue());
                case "PROPERTY_RENAMED" -> renameProperty(result, change.oldValue(), change.newValue());
                case "ENUM_VALUE_REMOVED" -> removeEnumValue(result, change.oldValue());
                default -> result;
            };
        }
        return result;
    }

    /**
     * Replaces the endpoint path prefix (template segments stripped) so both
     * the path constant and concrete URLs built in tests are updated. When the
     * new prefix contains the old one (the /v2 case) a negative lookbehind
     * keeps the replacement idempotent.
     */
    static String replaceEndpointPath(String content, String oldPath, String newPath) {
        String oldPrefix = templateFreePrefix(oldPath);
        String newPrefix = templateFreePrefix(newPath);
        if (oldPrefix.isEmpty() || oldPrefix.equals(newPrefix)) {
            return content.replace(oldPath, newPath);
        }
        if (newPrefix.endsWith(oldPrefix)) {
            String added = newPrefix.substring(0, newPrefix.length() - oldPrefix.length());
            Pattern pattern = Pattern.compile("(?<!" + Pattern.quote(added) + ")" + Pattern.quote(oldPrefix));
            return pattern.matcher(content).replaceAll(Matcher.quoteReplacement(newPrefix));
        }
        return content.replace(oldPath, newPath).replace(oldPrefix, newPrefix);
    }

    private static String templateFreePrefix(String path) {
        int brace = path.indexOf('{');
        return brace < 0 ? path : path.substring(0, brace);
    }

    /** Renames the property token and its capitalised accessor form (getX/setX). */
    static String renameProperty(String content, String oldName, String newName) {
        String result = content.replaceAll("\\b" + Pattern.quote(oldName) + "\\b",
                Matcher.quoteReplacement(newName));
        // No leading \b: the capitalised fragment sits inside identifiers like getFullName.
        return result.replaceAll(Pattern.quote(capitalise(oldName)) + "\\b",
                Matcher.quoteReplacement(capitalise(newName)));
    }

    private static String capitalise(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Removes handling of a deleted enum value: test methods referencing it,
     * {@code case VALUE} branches, and the enum constant declaration itself.
     */
    static String removeEnumValue(String content, String value) {
        String result = removeTestMethodsReferencing(content, value);
        return removeEnumValueLines(result, value);
    }

    static String removeTestMethodsReferencing(String content, String value) {
        StringBuilder result = new StringBuilder(content);
        int searchFrom = 0;
        while (true) {
            int annotation = result.indexOf("@Test", searchFrom);
            if (annotation < 0) {
                return result.toString();
            }
            int open = result.indexOf("{", annotation);
            if (open < 0) {
                return result.toString();
            }
            int close = matchBrace(result, open);
            if (close < 0) {
                return result.toString();
            }
            String body = result.substring(open, close + 1);
            if (body.contains(value)) {
                int start = lineStart(result, annotation);
                int end = close + 1;
                while (end < result.length() && (result.charAt(end) == '\n' || result.charAt(end) == '\r')) {
                    end++;
                }
                // Also swallow one preceding blank line to keep spacing tidy.
                int trimmedStart = precedingBlankLineStart(result, start);
                result.delete(trimmedStart, end);
                searchFrom = trimmedStart;
            } else {
                searchFrom = close;
            }
        }
    }

    private static int matchBrace(CharSequence text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lineStart(CharSequence text, int index) {
        int i = index;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    private static int precedingBlankLineStart(CharSequence text, int lineStartIndex) {
        int i = lineStartIndex;
        while (i > 0 && (text.charAt(i - 1) == '\n' || text.charAt(i - 1) == '\r')) {
            i--;
        }
        int previousLineStart = lineStart(text, Math.max(0, i - 1));
        return text.subSequence(previousLineStart, i).toString().isBlank() && previousLineStart < i
                ? i : lineStartIndex;
    }

    static String removeEnumValueLines(String content, String value) {
        List<String> lines = new java.util.ArrayList<>(content.lines().toList());
        boolean removedLastConstant = false;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String trimmed = lines.get(i).strip();
            boolean caseBranch = trimmed.startsWith("case " + value)
                    && (trimmed.contains("->") || trimmed.endsWith(":"));
            boolean constant = trimmed.equals(value) || trimmed.equals(value + ",");
            if (caseBranch || constant) {
                if (trimmed.equals(value)) {
                    removedLastConstant = true;
                }
                lines.remove(i);
            }
        }
        if (removedLastConstant) {
            stripTrailingCommaOfLastConstant(lines);
        }
        String joined = String.join("\n", lines);
        return content.endsWith("\n") && !joined.endsWith("\n") ? joined + "\n" : joined;
    }

    /** After removing the last enum constant, its former predecessor's trailing comma is now dangling. */
    private static void stripTrailingCommaOfLastConstant(List<String> lines) {
        int i = lastMeaningfulLineIndex(lines);
        if (i < 0) {
            return;
        }
        String trimmed = lines.get(i).strip();
        if (trimmed.endsWith(",")) {
            lines.set(i, lines.get(i).substring(0, lines.get(i).lastIndexOf(',')));
        }
    }

    /** @return the index of the last line that isn't blank or a bare closing brace, or -1 if none */
    private static int lastMeaningfulLineIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String trimmed = lines.get(i).strip();
            if (!trimmed.isEmpty() && !trimmed.equals("}")) {
                return i;
            }
        }
        return -1;
    }
}
