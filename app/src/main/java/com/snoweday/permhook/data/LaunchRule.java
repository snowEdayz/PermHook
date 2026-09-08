package com.snoweday.permhook.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A user-authored caller/target activity-start rule and its selected operation. */
public final class LaunchRule {
    public static final String ANY = "*";

    private static final String KEY_ID = "id";
    private static final String KEY_CALLER = "callerPackage";
    private static final String KEY_TARGET = "targetPackage";
    private static final String KEY_OPERATION = "operation";
    private static final String OPERATION_CONFIRM = "confirm";
    private static final String OPERATION_BYPASS = "bypass";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LABEL = "label";

    private final String id;
    private final String callerPackage;
    private final String targetPackage;
    private final boolean requiresConfirmationActivity;
    private final boolean enabled;
    private final String label;

    public LaunchRule(
            String id,
            String callerPackage,
            String targetPackage,
            boolean requiresConfirmationActivity,
            boolean enabled,
            String label) {
        this.id = nonBlankOr(id, UUID.randomUUID().toString());
        this.callerPackage = normalizePackage(callerPackage);
        this.targetPackage = normalizePackage(targetPackage);
        this.requiresConfirmationActivity = requiresConfirmationActivity;
        this.enabled = enabled;
        this.label = normalizeOptional(label);
    }

    public static LaunchRule create(
            String callerPackage,
            String targetPackage,
            boolean requiresConfirmationActivity,
            boolean enabled,
            String label) {
        return new LaunchRule(
                UUID.randomUUID().toString(),
                callerPackage,
                targetPackage,
                requiresConfirmationActivity,
                enabled,
                label);
    }

    public String getId() {
        return id;
    }

    public String getCallerPackage() {
        return callerPackage;
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    public boolean requiresConfirmationActivity() {
        return requiresConfirmationActivity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getLabel() {
        return label;
    }

    public LaunchRule withEnabled(boolean value) {
        return new LaunchRule(id, callerPackage, targetPackage,
                requiresConfirmationActivity, value, label);
    }

    public LaunchRule withValues(
            String newCaller,
            String newTarget,
            boolean newRequiresConfirmationActivity,
            boolean newEnabled,
            String newLabel) {
        return new LaunchRule(
                id,
                newCaller,
                newTarget,
                newRequiresConfirmationActivity,
                newEnabled,
                newLabel);
    }

    /**
     * Matches a resolved start request. A rule only applies to a cross-package
     * start; same-package starts are intentionally left to the platform.
     */
    public boolean matches(String actualCaller, String actualTarget) {
        if (!enabled || isBlank(actualCaller) || isBlank(actualTarget)
                || actualCaller.equals(actualTarget)) {
            return false;
        }
        if (!globMatches(callerPackage, actualCaller)
                || !globMatches(targetPackage, actualTarget)) {
            return false;
        }
        return true;
    }

    public String summary() {
        StringBuilder builder = new StringBuilder(callerPackage)
                .append("  →  ")
                .append(targetPackage);
        builder.append(" · ")
                .append(requiresConfirmationActivity
                        ? "经过 Activity 确认"
                        : "不经过 Activity 确认");
        return builder.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put(KEY_ID, id);
        object.put(KEY_CALLER, callerPackage);
        object.put(KEY_TARGET, targetPackage);
        object.put(KEY_OPERATION,
                requiresConfirmationActivity ? OPERATION_CONFIRM : OPERATION_BYPASS);
        object.put(KEY_ENABLED, enabled);
        object.put(KEY_LABEL, label);
        return object;
    }

    public static LaunchRule fromJson(JSONObject object) {
        if (object == null) {
            throw new IllegalArgumentException("rule object is null");
        }
        String caller = object.optString(KEY_CALLER, "").trim();
        String target = object.optString(KEY_TARGET, "").trim();
        if (caller.isEmpty() || target.isEmpty()) {
            throw new IllegalArgumentException("callerPackage and targetPackage are required");
        }
        String operation = object.optString(KEY_OPERATION, OPERATION_CONFIRM).trim();
        boolean requiresConfirmationActivity = !OPERATION_BYPASS.equalsIgnoreCase(operation);
        return new LaunchRule(
                object.optString(KEY_ID, ""),
                caller,
                target,
                requiresConfirmationActivity,
                object.optBoolean(KEY_ENABLED, true),
                object.optString(KEY_LABEL, ""));
    }

    public static String encode(List<LaunchRule> rules) {
        JSONArray array = new JSONArray();
        if (rules != null) {
            for (LaunchRule rule : rules) {
                if (rule == null) {
                    continue;
                }
                try {
                    array.put(rule.toJson());
                } catch (JSONException ignored) {
                    // All fields are primitive values; keep the remaining rules if this changes.
                }
            }
        }
        return array.toString();
    }

    public static List<LaunchRule> decode(String encoded) {
        List<LaunchRule> rules = new ArrayList<>();
        if (isBlank(encoded)) {
            return rules;
        }
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof JSONObject) {
                    try {
                        rules.add(fromJson((JSONObject) value));
                    } catch (RuntimeException ignored) {
                        // Ignore one malformed rule instead of losing all configuration.
                    }
                }
            }
        } catch (JSONException ignored) {
            // Treat invalid or old data as an empty rule set.
        }
        return rules;
    }

    private static String normalizePackage(String value) {
        return nonBlankOr(value, "").trim();
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlankOr(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Simple case-sensitive glob matching with '*' as the only wildcard. */
    private static boolean globMatches(String pattern, String value) {
        if (isBlank(pattern)) {
            return false;
        }
        if (ANY.equals(pattern)) {
            return true;
        }
        if (value == null) {
            return false;
        }

        int patternIndex = 0;
        int valueIndex = 0;
        int lastStar = -1;
        int valueAfterStar = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                lastStar = patternIndex++;
                valueAfterStar = valueIndex;
            } else if (lastStar >= 0) {
                patternIndex = lastStar + 1;
                valueIndex = ++valueAfterStar;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
