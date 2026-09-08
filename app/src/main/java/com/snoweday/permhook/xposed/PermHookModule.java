package com.snoweday.permhook.xposed;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import com.snoweday.permhook.data.LaunchRule;
import com.snoweday.permhook.data.RuleStore;

/**
 * Modern API entry point. It runs only in system_server and augments the
 * platform's own app-start confirmation decision with user-authored rules.
 * Each matching rule can either force the confirmation Activity or bypass it.
 */
public final class PermHookModule extends XposedModule {
    private static final String TAG = "PermHook";
    private static final String MANAGER_CLASS =
            "com.android.server.wm.OplusAppStartConfirmManager";
    private static final String ACTIVITY_RECORD_CLASS = "com.android.server.wm.ActivityRecord";
    private static final String METHOD_NAME = "checkStartActivityForConfirm";

    private static final String CONFIRM_ACTION = "oplus.app.action.CHECK_ALLOW_START_ACTIVITY";
    private static final String CONFIRM_PACKAGE = "com.oplus.securitypermission";
    private static final String CONFIRM_ACTIVITY =
            "com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity";
    private static final String EXTRA_CALLER_PACKAGE = "caller_package";
    private static final String EXTRA_CALLEE_PACKAGE = "callee_package";
    private static final String EXTRA_USER_ID = "extra_userid";
    private static final String EXTRA_CALLER_UID = "caller_uid";
    private static final String EXTRA_CALLEE_UID = "callee_uid";
    private static final String EXTRA_REQUEST_CODE = "request_code";
    private static final String EXTRA_START_TYPE = "start_type";
    private static final String EXTRA_CONFIRM_VERSION = "activity_start_confirm_version";
    private static final String INTERNAL_TOKEN_EXTRA =
            "com.snoweday.permhook.extra.FORCE_CONFIRM_TOKEN";

    private static final int CONFIRM_VERSION = 2;
    private static final int START_TYPE_NORMAL = 0;
    private static final int FIRST_APPLICATION_UID = 10000;
    private static final int UID_PER_USER_RANGE = 100000;
    private static final long INTERNAL_TOKEN_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_PENDING_INTERNAL_LAUNCHES = 256;

    private static final Map<String, PendingInternalLaunch> PENDING_INTERNAL_LAUNCHES =
            new ConcurrentHashMap<>();

    private volatile List<LaunchRule> rules = Collections.emptyList();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Modern API module loaded; api=" + getApiVersion()
                + ", framework=" + getFrameworkName());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        final ClassLoader classLoader = param.getClassLoader();
        final SharedPreferences preferences = loadPreferences();
        if (preferences != null) {
            rules = readRules(preferences);
            preferences.registerOnSharedPreferenceChangeListener(
                    (changedPreferences, key) -> {
                        if (RuleStore.RULES_KEY.equals(key)) {
                            rules = readRules(changedPreferences);
                        }
                    });
        }

        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS, false, classLoader);
            Class<?> activityRecordClass = Class.forName(ACTIVITY_RECORD_CLASS, false, classLoader);
            Class<?> profilerInfoClass = Class.forName(
                    "android.app.ProfilerInfo", false, classLoader);
            Method method = managerClass.getDeclaredMethod(
                    METHOD_NAME,
                    activityRecordClass,
                    ActivityInfo.class,
                    Intent.class,
                    int.class,
                    int.class,
                    String.class,
                    android.app.ActivityOptions.class,
                    profilerInfoClass,
                    boolean.class);

            hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("permhook.force-confirm")
                    .intercept(chain -> interceptStart(chain));

            log(Log.INFO, TAG, "Hooked " + MANAGER_CLASS + "." + METHOD_NAME);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Unable to hook Oplus app-start confirmation", error);
        }
    }

    private Object interceptStart(XposedInterface.Chain chain) throws Throwable {
        try {
            if (isSamePackageStart(chain)) {
                // Same-package starts are always allowed without the confirmation Activity.
                return null;
            }
            // The OEM method skips system targets in isSystemAppOrSameApp before it
            // reaches its own permission check. Evaluate user rules first so that
            // dst_pkg rules are not lost on system applications.
            RuleMatch match = findMatchingRule(chain);
            if (match != null) {
                if (!match.rule.requiresConfirmationActivity()) {
                    // A null result tells the platform that no confirmation Activity is needed.
                    return null;
                }
                Object confirmationResult = buildConfirmationResult(
                        chain.getThisObject(),
                        chain.getArg(0),
                        match.callerPackage,
                        match.targetPackage,
                        match.activityInfo,
                        match.sourceIntent,
                        match.requestCode,
                        match.callerUid,
                        chain.getArg(7));
                if (confirmationResult != null) {
                    return confirmationResult;
                }
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Rule evaluation failed; continuing with platform result", error);
        }
        return chain.proceed();
    }

    private static boolean isSamePackageStart(XposedInterface.Chain chain) {
        Object activityInfoObject = chain.getArg(1);
        Object callerPackageObject = chain.getArg(5);
        if (!(activityInfoObject instanceof ActivityInfo)
                || !(callerPackageObject instanceof String)) {
            return false;
        }

        ActivityInfo activityInfo = (ActivityInfo) activityInfoObject;
        ApplicationInfo applicationInfo = activityInfo.applicationInfo;
        String targetPackage = applicationInfo == null ? null : applicationInfo.packageName;
        String callerPackage = (String) callerPackageObject;
        return targetPackage != null && callerPackage.equals(targetPackage);
    }

    private RuleMatch findMatchingRule(XposedInterface.Chain chain) {
        Object activityInfoObject = chain.getArg(1);
        Object sourceIntentObject = chain.getArg(2);
        Object requestCodeObject = chain.getArg(3);
        Object callingUidObject = chain.getArg(4);
        Object callerPackageObject = chain.getArg(5);
        if (!(activityInfoObject instanceof ActivityInfo)
                || !(sourceIntentObject instanceof Intent)
                || !(requestCodeObject instanceof Integer)
                || !(callingUidObject instanceof Integer)
                || !(callerPackageObject instanceof String)) {
            return null;
        }

        ActivityInfo activityInfo = (ActivityInfo) activityInfoObject;
        Intent sourceIntent = (Intent) sourceIntentObject;
        String callerPackage = (String) callerPackageObject;
        ApplicationInfo targetApplicationInfo = activityInfo.applicationInfo;
        String targetPackage = targetApplicationInfo == null
                ? null : targetApplicationInfo.packageName;
        if (targetPackage == null) {
            return null;
        }
        int callerUid = (Integer) callingUidObject;
        if (consumeInternalToken(sourceIntent, targetPackage, targetApplicationInfo.uid)) {
            // The confirmation Activity is now returning the original Intent. The
            // token is consumed atomically, so replaying or forging it cannot bypass rules.
            return null;
        }
        if (CONFIRM_PACKAGE.equals(callerPackage)
                || CONFIRM_PACKAGE.equals(targetPackage)) {
            return null;
        }

        boolean needsCallerUserApp = false;
        boolean needsTargetUserApp = false;
        for (LaunchRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            needsCallerUserApp |= LaunchRule.ANY_USER.equals(rule.getCallerPackage());
            needsTargetUserApp |= LaunchRule.ANY_USER.equals(rule.getTargetPackage());
            if (needsCallerUserApp && needsTargetUserApp) {
                break;
            }
        }
        Context context = needsCallerUserApp ? findContext(chain.getThisObject()) : null;
        boolean callerIsUserApp = needsCallerUserApp
                && isUserApp(context, callerPackage, (Integer) callingUidObject);
        boolean targetIsUserApp = needsTargetUserApp
                && isUserApp(activityInfo.applicationInfo);
        for (LaunchRule rule : rules) {
            if (rule.matches(callerPackage, targetPackage,
                    callerIsUserApp, targetIsUserApp)) {
                return new RuleMatch(
                        rule,
                        callerPackage,
                        targetPackage,
                        activityInfo,
                        sourceIntent,
                        (Integer) requestCodeObject,
                        callerUid);
            }
        }
        return null;
    }

    private Object buildConfirmationResult(
            Object manager,
            Object sourceRecord,
            String callerPackage,
            String targetPackage,
            ActivityInfo activityInfo,
            Intent sourceIntent,
            int requestCode,
            int callerUid,
            Object profilerInfo) {
        Context context = findContext(manager);

        int calleeUid = activityInfo.applicationInfo.uid;
        Intent confirmIntent = new Intent(CONFIRM_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .setPackage(CONFIRM_PACKAGE)
                .setClassName(CONFIRM_PACKAGE, CONFIRM_ACTIVITY)
                .putExtra(EXTRA_CALLER_PACKAGE, callerPackage)
                .putExtra(EXTRA_CALLEE_PACKAGE, targetPackage)
                .putExtra(EXTRA_USER_ID, userIdForUid(calleeUid))
                .putExtra(EXTRA_CALLER_UID, callerUid)
                .putExtra(EXTRA_CALLEE_UID, calleeUid)
                .putExtra(EXTRA_REQUEST_CODE, requestCode)
                .putExtra(EXTRA_START_TYPE, START_TYPE_NORMAL)
                .putExtra(EXTRA_CONFIRM_VERSION, CONFIRM_VERSION);

        Intent originalIntent = new Intent(sourceIntent);
        if ((originalIntent.getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0) {
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        }
        if (sourceRecord != null && requestCode >= 0) {
            originalIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        }
        confirmIntent.putExtra(Intent.EXTRA_INTENT, originalIntent);

        ActivityInfo confirmationInfo = resolveConfirmationActivity(
                manager, confirmIntent, profilerInfo, callerUid);
        if (confirmationInfo == null && context != null) {
            try {
                PackageManager packageManager = context.getPackageManager();
                int callerUserId = userIdForUid(callerUid);
                ResolveInfo resolveInfo = resolveActivityForUser(
                        packageManager, confirmIntent, callerUserId);
                confirmationInfo = resolveInfo == null ? null : resolveInfo.activityInfo;
            } catch (Throwable error) {
                log(Log.WARN, TAG, "Unable to resolve Oplus confirmation activity", error);
            }
        }
        if (confirmationInfo == null) {
            log(Log.WARN, TAG, "Oplus confirmation activity is not installed");
            return null;
        }

        String internalToken = registerInternalToken(targetPackage, calleeUid);
        if (internalToken == null) {
            log(Log.WARN, TAG, "Too many pending confirmation Intents; using platform flow");
            return null;
        }
        originalIntent.putExtra(INTERNAL_TOKEN_EXTRA, internalToken);
        confirmIntent.putExtra(Intent.EXTRA_INTENT, originalIntent);

        Pair<Intent, ActivityInfo> resolvedActivity =
                new Pair<>(confirmIntent, confirmationInfo);
        // The second value is the OEM abort flag: false means launch the
        // resolved confirmation Activity, while true means hard-intercept.
        return new Pair<>(resolvedActivity, Boolean.FALSE);
    }

    /**
     * Resolve the confirmation Activity through the same system_server resolver
     * used by OplusAppStartConfirmManager. PackageManager-only resolution can
     * fail for the security-protected Activity or the target user's profile,
     * causing the OEM method to fall back to a hard intercept.
     */
    private ActivityInfo resolveConfirmationActivity(
            Object manager, Intent intent, Object profilerInfo, int callerUid) {
        try {
            Object atms = readField(manager, "mAtms");
            Object taskSupervisor = readField(atms, "mTaskSupervisor");
            if (taskSupervisor == null) {
                return null;
            }

            Class<?> profilerInfoClass = profilerInfo == null
                    ? Class.forName("android.app.ProfilerInfo", false,
                    taskSupervisor.getClass().getClassLoader())
                    : profilerInfo.getClass();
            Method resolveActivity = findResolveActivityMethod(
                    taskSupervisor.getClass(), profilerInfoClass);
            if (resolveActivity == null) {
                log(Log.WARN, TAG, "Oplus ActivityTaskSupervisor resolver is unavailable");
                return null;
            }
            resolveActivity.setAccessible(true);
            Object resolved = resolveActivity.invoke(
                    taskSupervisor,
                    intent,
                    null,
                    0,
                    profilerInfo,
                    userIdForUid(callerUid),
                    callerUid,
                    Binder.getCallingUid());
            return resolved instanceof ActivityInfo ? (ActivityInfo) resolved : null;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to resolve Oplus confirmation Activity via system_server", error);
            return null;
        }
    }

    private static Method findResolveActivityMethod(
            Class<?> type, Class<?> profilerInfoClass) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!"resolveActivity".equals(method.getName())
                        || parameterTypes.length != 7
                        || parameterTypes[0] != Intent.class
                        || parameterTypes[1] != String.class
                        || parameterTypes[2] != int.class
                        || parameterTypes[4] != int.class
                        || parameterTypes[5] != int.class
                        || parameterTypes[6] != int.class
                        || !parameterTypes[3].isAssignableFrom(profilerInfoClass)) {
                    continue;
                }
                return method;
            }
        }
        return null;
    }

    private static ResolveInfo resolveActivityForUser(
            PackageManager packageManager, Intent intent, int userId) {
        if (userId < 0) {
            return packageManager.resolveActivity(intent, 0);
        }
        try {
            // resolveActivityAsUser is hidden from some app SDK stubs but is available
            // on the system_server PackageManager implementation.
            Method method = PackageManager.class.getDeclaredMethod(
                    "resolveActivityAsUser", Intent.class, int.class, int.class);
            method.setAccessible(true);
            Object resolved = method.invoke(packageManager, intent, 0, userId);
            return resolved instanceof ResolveInfo ? (ResolveInfo) resolved : null;
        } catch (Throwable error) {
            Log.w(TAG, "User-scoped Activity resolution is unavailable", error);
            // Do not resolve against the default user: that can select a different
            // profile's confirmation Activity. The caller will continue platform flow.
            return null;
        }
    }

    private SharedPreferences loadPreferences() {
        try {
            return getRemotePreferences(RuleStore.PREF_GROUP);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Remote preferences are unavailable; rules disabled", error);
            return null;
        }
    }

    private static List<LaunchRule> readRules(SharedPreferences preferences) {
        try {
            return LaunchRule.decode(preferences.getString(RuleStore.RULES_KEY, "[]"));
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read remote rules", error);
            return Collections.emptyList();
        }
    }

    private static String registerInternalToken(String targetPackage, int calleeUid) {
        long now = SystemClock.uptimeMillis();
        pruneInternalTokens(now);
        if (PENDING_INTERNAL_LAUNCHES.size() >= MAX_PENDING_INTERNAL_LAUNCHES) {
            return null;
        }

        PendingInternalLaunch pending = new PendingInternalLaunch(targetPackage, calleeUid, now);
        for (int attempt = 0; attempt < 4; attempt++) {
            String token = UUID.randomUUID().toString();
            if (PENDING_INTERNAL_LAUNCHES.putIfAbsent(token, pending) == null) {
                return token;
            }
        }
        return null;
    }

    private static boolean consumeInternalToken(
            Intent sourceIntent, String targetPackage, int calleeUid) {
        String token;
        try {
            token = sourceIntent.getStringExtra(INTERNAL_TOKEN_EXTRA);
        } catch (Throwable ignored) {
            return false;
        }
        if (token == null || token.isEmpty()) {
            return false;
        }

        PendingInternalLaunch pending = PENDING_INTERNAL_LAUNCHES.get(token);
        if (pending == null) {
            return false;
        }
        long age = SystemClock.uptimeMillis() - pending.createdAt;
        if (age >= INTERNAL_TOKEN_TTL_MILLIS) {
            PENDING_INTERNAL_LAUNCHES.remove(token, pending);
            return false;
        }
        if (!pending.matches(targetPackage, calleeUid)) {
            return false;
        }
        boolean consumed = PENDING_INTERNAL_LAUNCHES.remove(token, pending);
        if (consumed) {
            try {
                sourceIntent.removeExtra(INTERNAL_TOKEN_EXTRA);
            } catch (Throwable ignored) {
                // The token is already consumed; failure to scrub the outgoing Intent
                // does not make it valid for a second launch.
            }
        }
        return consumed;
    }

    private static void pruneInternalTokens(long now) {
        for (Map.Entry<String, PendingInternalLaunch> entry
                : PENDING_INTERNAL_LAUNCHES.entrySet()) {
            PendingInternalLaunch pending = entry.getValue();
            if (now - pending.createdAt >= INTERNAL_TOKEN_TTL_MILLIS) {
                PENDING_INTERNAL_LAUNCHES.remove(entry.getKey(), pending);
            }
        }
    }

    private boolean isUserApp(Context context, String packageName, int callerUid) {
        if (context == null || packageName == null || !isApplicationUid(callerUid)) {
            return false;
        }
        try {
            ApplicationInfo applicationInfo = getApplicationInfoForUid(
                    context.getPackageManager(), packageName, callerUid);
            return applicationInfo != null
                    && appIdForUid(applicationInfo.uid) == appIdForUid(callerUid)
                    && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to classify caller as a user app", error);
            return false;
        }
    }

    private static ApplicationInfo getApplicationInfoForUid(
            PackageManager packageManager, String packageName, int callerUid) {
        int callerUserId = userIdForUid(callerUid);
        try {
            // getApplicationInfoAsUser avoids accidentally querying user 0 for a
            // caller from a work profile or another secondary user.
            Method method = PackageManager.class.getDeclaredMethod(
                    "getApplicationInfoAsUser", String.class, int.class, int.class);
            method.setAccessible(true);
            Object value = method.invoke(packageManager, packageName, 0, callerUserId);
            if (value instanceof ApplicationInfo) {
                return (ApplicationInfo) value;
            }
        } catch (Throwable ignored) {
            // Fall back to the public UID lookup below on older or restricted runtimes.
        }

        String[] packagesForUid = packageManager.getPackagesForUid(callerUid);
        if (packagesForUid == null) {
            return null;
        }
        for (String packageForUid : packagesForUid) {
            if (packageName.equals(packageForUid)) {
                try {
                    return packageManager.getApplicationInfo(packageName, 0);
                } catch (PackageManager.NameNotFoundException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean isUserApp(ApplicationInfo applicationInfo) {
        return applicationInfo != null
                && isApplicationUid(applicationInfo.uid)
                && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    private static boolean isApplicationUid(int uid) {
        return appIdForUid(uid) >= FIRST_APPLICATION_UID;
    }

    private static int userIdForUid(int uid) {
        return uid < 0 ? -1 : uid / UID_PER_USER_RANGE;
    }

    private static int appIdForUid(int uid) {
        return uid < 0 ? -1 : uid % UID_PER_USER_RANGE;
    }

    private static Context findContext(Object manager) {
        Object value = readField(manager, "mContext");
        return value instanceof Context ? (Context) value : null;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // The field may be declared by a superclass in an OEM build.
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static final class PendingInternalLaunch {
        final String targetPackage;
        final int calleeUid;
        final long createdAt;

        PendingInternalLaunch(String targetPackage, int calleeUid, long createdAt) {
            this.targetPackage = targetPackage;
            this.calleeUid = calleeUid;
            this.createdAt = createdAt;
        }

        boolean matches(String actualTargetPackage, int actualCalleeUid) {
            return calleeUid == actualCalleeUid && targetPackage.equals(actualTargetPackage);
        }
    }

    private static final class RuleMatch {
        final LaunchRule rule;
        final String callerPackage;
        final String targetPackage;
        final ActivityInfo activityInfo;
        final Intent sourceIntent;
        final int requestCode;
        final int callerUid;

        RuleMatch(
                LaunchRule rule,
                String callerPackage,
                String targetPackage,
                ActivityInfo activityInfo,
                Intent sourceIntent,
                int requestCode,
                int callerUid) {
            this.rule = rule;
            this.callerPackage = callerPackage;
            this.targetPackage = targetPackage;
            this.activityInfo = activityInfo;
            this.sourceIntent = sourceIntent;
            this.requestCode = requestCode;
            this.callerUid = callerUid;
        }
    }
}
