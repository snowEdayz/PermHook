package com.snoweday.permhook;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.snoweday.permhook.data.RuleStore;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Application entry that connects the Material UI to Modern Xposed service. */
public final class PermHookApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile XposedService service;

    public static XposedService getService() {
        return service;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                XposedServiceHelper.registerListener(this);
            } catch (Throwable ignored) {
                // The settings app can still keep a local copy when no framework is installed.
            }
        }
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        if (boundService != null) {
            RuleStore.synchronize(this, boundService);
        }
        notifyListeners();
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
        }
        notifyListeners();
    }

    private static void notifyListeners() {
        XposedService current = service;
        for (Listener listener : LISTENERS) {
            try {
                listener.onServiceChanged(current);
            } catch (RuntimeException ignored) {
                // A UI listener must not break service callbacks for other listeners.
            }
        }
    }

    public interface Listener {
        void onServiceChanged(XposedService service);
    }
}
