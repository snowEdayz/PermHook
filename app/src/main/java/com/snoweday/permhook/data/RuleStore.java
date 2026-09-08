package com.snoweday.permhook.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;

import io.github.libxposed.service.XposedService;

/** Shared configuration bridge between the settings app and system_server. */
public final class RuleStore {
    public static final String PREF_GROUP = "permhook_rules";
    public static final String RULES_KEY = "rules_json";

    private static final String LOCAL_PREFS = PREF_GROUP + "_local";
    private static final String TAG = "PermHookRuleStore";

    private RuleStore() {
    }

    public static List<LaunchRule> load(Context context, @Nullable XposedService service) {
        String encoded = null;
        if (hasRemoteAccess(service)) {
            try {
                SharedPreferences remote = service.getRemotePreferences(PREF_GROUP);
                encoded = remote.getString(RULES_KEY, null);
            } catch (Throwable error) {
                Log.w(TAG, "Unable to read remote rules; using local copy", error);
            }
        }
        if (encoded == null) {
            encoded = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                    .getString(RULES_KEY, "[]");
        }
        return LaunchRule.decode(encoded);
    }

    /**
     * Always stores a local copy, and mirrors it to the framework when its
     * remote-preferences capability is available.
     *
     * @return true when the framework copy was accepted for synchronization.
     */
    public static boolean save(Context context, @Nullable XposedService service,
            List<LaunchRule> rules) {
        String encoded = LaunchRule.encode(rules);
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(RULES_KEY, encoded)
                .apply();

        if (!hasRemoteAccess(service)) {
            return false;
        }
        try {
            service.getRemotePreferences(PREF_GROUP)
                    .edit()
                    .putString(RULES_KEY, encoded)
                    .apply();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to write remote rules; local copy retained", error);
            return false;
        }
    }

    public static boolean hasRemoteAccess(@Nullable XposedService service) {
        if (service == null) {
            return false;
        }
        try {
            return (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) != 0;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to query Xposed service capabilities", error);
            return false;
        }
    }
}
