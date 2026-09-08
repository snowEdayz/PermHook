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
    private static final String LOCAL_DIRTY_KEY = "rules_dirty";
    private static final String LAST_SYNCED_KEY = "rules_last_synced_json";
    private static final String EMPTY_RULES = "[]";
    private static final String TAG = "PermHookRuleStore";
    private static final Object LOCK = new Object();

    private RuleStore() {
    }

    public static List<LaunchRule> load(Context context, @Nullable XposedService service) {
        synchronized (LOCK) {
            SharedPreferences local = localPreferences(context);
            if (hasRemoteAccess(service)) {
                try {
                    SharedPreferences remote = service.getRemotePreferences(PREF_GROUP);
                    return LaunchRule.decode(reconcile(local, remote));
                } catch (Throwable error) {
                    Log.w(TAG, "Unable to reconcile remote rules; using local copy", error);
                }
            }
            return LaunchRule.decode(readLocalEncoded(local));
        }
    }

    /**
     * Reconciles an offline local save as soon as the Xposed service becomes available.
     * Local changes marked dirty are authoritative; otherwise a valid remote value is
     * mirrored locally. The legacy empty-remote case preserves pre-sync local rules.
     */
    public static boolean synchronize(Context context, @Nullable XposedService service) {
        if (!hasRemoteAccess(service)) {
            return false;
        }
        synchronized (LOCK) {
            try {
                SharedPreferences local = localPreferences(context);
                SharedPreferences remote = service.getRemotePreferences(PREF_GROUP);
                reconcile(local, remote);
                return true;
            } catch (Throwable error) {
                Log.w(TAG, "Unable to synchronize local rules", error);
                return false;
            }
        }
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
        synchronized (LOCK) {
            SharedPreferences local = localPreferences(context);
            try {
                // Write the payload before the dirty bit so a later bind can retry safely.
                local.edit()
                        .putString(RULES_KEY, encoded)
                        .putBoolean(LOCAL_DIRTY_KEY, true)
                        .apply();
            } catch (Throwable error) {
                Log.w(TAG, "Unable to write local rules", error);
                return false;
            }

            if (!hasRemoteAccess(service)) {
                return false;
            }
            try {
                SharedPreferences remote = service.getRemotePreferences(PREF_GROUP);
                remote.edit()
                        .putString(RULES_KEY, encoded)
                        .apply();
                markSynchronized(local, encoded);
                return true;
            } catch (Throwable error) {
                Log.w(TAG, "Unable to write remote rules; local copy retained", error);
                return false;
            }
        }
    }

    public static boolean hasPendingLocalChanges(Context context) {
        synchronized (LOCK) {
            SharedPreferences local = localPreferences(context);
            String lastSynced = readOptionalString(local, LAST_SYNCED_KEY);
            return readBoolean(local, LOCAL_DIRTY_KEY, false)
                    || (lastSynced != null && !lastSynced.equals(readLocalEncoded(local)));
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

    private static SharedPreferences localPreferences(Context context) {
        return context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    private static String reconcile(SharedPreferences local, SharedPreferences remote) {
        String localEncoded = readLocalEncoded(local);
        boolean localValid = LaunchRule.isValidEncoding(localEncoded);
        if (!localValid) {
            Log.w(TAG, "Local rules are malformed; repairing from a valid remote copy when possible");
            localEncoded = EMPTY_RULES;
        }

        String remoteEncoded = readOptionalString(remote, RULES_KEY);
        if (remoteEncoded != null && !LaunchRule.isValidEncoding(remoteEncoded)) {
            Log.w(TAG, "Remote rules are malformed; replacing them with the local copy");
            remoteEncoded = null;
        }

        // A valid remote copy is safer than a corrupted local payload, even if the
        // latter left a stale dirty flag behind.
        if (!localValid && remoteEncoded != null) {
            local.edit()
                    .putString(RULES_KEY, remoteEncoded)
                    .putBoolean(LOCAL_DIRTY_KEY, false)
                    .putString(LAST_SYNCED_KEY, remoteEncoded)
                    .apply();
            return remoteEncoded;
        }

        boolean dirty = readBoolean(local, LOCAL_DIRTY_KEY, false);
        String lastSynced = readOptionalString(local, LAST_SYNCED_KEY);
        boolean localChanged = dirty
                || (lastSynced != null && !lastSynced.equals(localEncoded));
        boolean legacyLocalRules = lastSynced == null
                && !dirty
                && EMPTY_RULES.equals(remoteEncoded)
                && !LaunchRule.decode(localEncoded).isEmpty();

        if (localChanged || remoteEncoded == null || legacyLocalRules) {
            remote.edit()
                    .putString(RULES_KEY, localEncoded)
                    .apply();
            markSynchronized(local, localEncoded);
            return localEncoded;
        }

        // No pending local change: the framework copy is the shared source of truth.
        local.edit()
                .putString(RULES_KEY, remoteEncoded)
                .putBoolean(LOCAL_DIRTY_KEY, false)
                .putString(LAST_SYNCED_KEY, remoteEncoded)
                .apply();
        return remoteEncoded;
    }

    private static void markSynchronized(SharedPreferences local, String encoded) {
        local.edit()
                .putBoolean(LOCAL_DIRTY_KEY, false)
                .putString(LAST_SYNCED_KEY, encoded)
                .apply();
    }

    private static String readLocalEncoded(SharedPreferences local) {
        String encoded = readOptionalString(local, RULES_KEY);
        return encoded == null ? EMPTY_RULES : encoded;
    }

    @Nullable
    private static String readOptionalString(SharedPreferences preferences, String key) {
        try {
            return preferences.getString(key, null);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read preference " + key, error);
            return null;
        }
    }

    private static boolean readBoolean(
            SharedPreferences preferences, String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read preference " + key, error);
            return fallback;
        }
    }
}
