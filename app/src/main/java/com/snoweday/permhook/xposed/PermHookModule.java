package com.snoweday.permhook.xposed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import com.snoweday.permhook.data.LaunchRule;
import com.snoweday.permhook.data.RuleStore;

/**
 * Modern API entry point. It runs only in system_server and augments the
 * platform's own app-start confirmation decision with user-authored rules.
 */
public final class PermHookModule extends XposedModule {
    private static final String TAG = "PermHook";
    private static final String MANAGER_CLASS =
            "com.android.server.wm.OplusAppStartConfirmManager";
    private static final String ACTIVITY_RECORD_CLASS = "com.android.server.wm.ActivityRecord";
    private static final String METHOD_NAME = "checkStartActivityForConfirm";

    private static final String CONFIRM_ACTION = "oplus.app.action.CHECK_ALLOW_START_ACTIVITY";
    private static final String CONFIRM_PACKAGE = "com.oplus.securitypermission";
    private static final String EXTRA_CALLER_PACKAGE = "caller_package";
    private static final String EXTRA_CALLEE_PACKAGE = "callee_package";
    private static final String EXTRA_USER_ID = "extra_userid";
    private static final String EXTRA_CALLER_UID = "caller_uid";
    private static final String EXTRA_CALLEE_UID = "callee_uid";
    private static final String EXTRA_REQUEST_CODE = "request_code";
    private static final String EXTRA_START_TYPE = "start_type";
    private static final String EXTRA_CONFIRM_VERSION = "activity_start_confirm_version";
    private static final String INTERNAL_MARKER =
            "com.snoweday.permhook.extra.FORCE_CONFIRM_CONSUMED";

    private static final int CONFIRM_VERSION = 2;
    private static final int START_TYPE_NORMAL = 0;
    private static final int UID_PER_USER_RANGE = 100000;

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
        Object originalResult = chain.proceed();
        try {
            Object forcedResult = forceConfirmationIfMatched(chain);
            return forcedResult == null ? originalResult : forcedResult;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Rule evaluation failed; preserving platform result", error);
            return originalResult;
        }
    }

    private Object forceConfirmationIfMatched(XposedInterface.Chain chain) {
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
        String targetPackage = activityInfo.applicationInfo == null
                ? null : activityInfo.applicationInfo.packageName;
        if (targetPackage == null
                || CONFIRM_PACKAGE.equals(callerPackage)
                || CONFIRM_PACKAGE.equals(targetPackage)
                || sourceIntent.getBooleanExtra(INTERNAL_MARKER, false)) {
            return null;
        }

        ComponentName component = sourceIntent.getComponent();
        if (component == null) {
            component = new ComponentName(targetPackage, activityInfo.name);
        }
        String fullComponent = component.flattenToString();
        String shortComponent = component.flattenToShortString();
        String action = sourceIntent.getAction();
        for (LaunchRule rule : rules) {
            if (rule.matches(callerPackage, targetPackage, fullComponent, shortComponent, action)) {
                return buildConfirmationResult(
                        chain.getThisObject(),
                        callerPackage,
                        targetPackage,
                        activityInfo,
                        sourceIntent,
                        (Integer) requestCodeObject,
                        (Integer) callingUidObject);
            }
        }
        return null;
    }

    private Object buildConfirmationResult(
            Object manager,
            String callerPackage,
            String targetPackage,
            ActivityInfo activityInfo,
            Intent sourceIntent,
            int requestCode,
            int callerUid) {
        Context context = findContext(manager);
        if (context == null) {
            log(Log.WARN, TAG, "Oplus confirmation context is unavailable");
            return null;
        }

        int calleeUid = activityInfo.applicationInfo.uid;
        Intent confirmIntent = new Intent(CONFIRM_ACTION)
                .addFlags(0x00800000)
                .setPackage(CONFIRM_PACKAGE)
                .putExtra(EXTRA_CALLER_PACKAGE, callerPackage)
                .putExtra(EXTRA_CALLEE_PACKAGE, targetPackage)
                .putExtra(EXTRA_USER_ID, calleeUid / UID_PER_USER_RANGE)
                .putExtra(EXTRA_CALLER_UID, callerUid)
                .putExtra(EXTRA_CALLEE_UID, calleeUid)
                .putExtra(EXTRA_REQUEST_CODE, requestCode)
                .putExtra(EXTRA_START_TYPE, START_TYPE_NORMAL)
                .putExtra(EXTRA_CONFIRM_VERSION, CONFIRM_VERSION);

        Intent originalIntent = new Intent(sourceIntent);
        originalIntent.putExtra(INTERNAL_MARKER, true);
        confirmIntent.putExtra(Intent.EXTRA_INTENT, originalIntent);

        ResolveInfo resolveInfo;
        try {
            resolveInfo = context.getPackageManager().resolveActivity(confirmIntent, 0);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to resolve Oplus confirmation activity", error);
            return null;
        }
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            log(Log.WARN, TAG, "Oplus confirmation activity is not installed");
            return null;
        }

        Pair<Intent, ActivityInfo> resolvedActivity =
                new Pair<>(confirmIntent, resolveInfo.activityInfo);
        return new Pair<>(resolvedActivity, Boolean.FALSE);
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
        return LaunchRule.decode(preferences.getString(RuleStore.RULES_KEY, "[]"));
    }

    private static Context findContext(Object manager) {
        if (manager == null) {
            return null;
        }
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mContext");
                field.setAccessible(true);
                Object value = field.get(manager);
                return value instanceof Context ? (Context) value : null;
            } catch (NoSuchFieldException ignored) {
                // The field may be declared by a superclass in an OEM build.
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }
}
