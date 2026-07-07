package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.overdrive.app.ui.daemon.DaemonStartupManager;

/**
 * Minimal AccessibilityService that keeps the app process alive indefinitely.
 *
 * Android's OOM killer and OEM process killers (including BYD's DiLink firmware)
 * are hardcoded to never kill a process hosting an active AccessibilityService.
 * This gives our app the highest possible process priority — same tier as the
 * keyboard or phone call — preventing the 24-hour kill cycle on newer BYD firmware.
 *
 * The service itself is a no-op for accessibility events. Its sole purpose is
 * process keep-alive. The foreground notification provides user visibility.
 *
 * Enable via ADB (one-time):
 *   settings put secure enabled_accessibility_services com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService
 *   settings put secure accessibility_enabled 1
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeepAliveA11y";

    // Live instance, so callers (e.g. the setup wizard's autostart button) can
    // reach the bound service to drive AutoStartEnabler. volatile: written on the
    // main thread in onServiceConnected/onUnbind/onDestroy, read from callers.
    private static volatile KeepAliveAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isRunning() {
        return instance != null;
    }

    /** The bound service, or null when the a11y service isn't enabled/connected. */
    public static KeepAliveAccessibilityService getInstance() {
        return instance;
    }

    /**
     * Result of a deliberate, button-triggered autostart-enable run.
     * Posted on the main thread so UI callers can update views directly.
     */
    public interface Callback {
        void onResult(boolean success, AutoStartEnabler.Result result);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        Log.i(TAG, "AccessibilityService connected — process is now protected");

        // Config. Historically this was a lifecycle-only service (eventTypes=0,
        // flags=0). Phase 2a's AutoStartEnabler needs window-content retrieval, so
        // we now KEEP the capabilities declared in accessibility_service_config.xml
        // instead of zeroing them. We OR in the window-retrieval flags rather than
        // overwrite, so the XML-loaded flags survive. canRetrieveWindowContent is a
        // capability (not a flag) granted from the XML at bind time — it can't be
        // set here. Keep-alive is unaffected: process protection comes from the
        // service binding itself, not from these fields.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 5000;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);

        // No foreground notification needed — DaemonKeepaliveService already has one.
        // The AccessibilityService binding alone is enough to protect the process.

        // Ensure daemons are running (respawn if killed)
        try {
            DaemonStartupManager.Companion.startOnBoot(getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "Daemon startup from A11y service: " + e.getMessage());
        }

        // NOTE: the BYD-autostart enabler is NO LONGER auto-triggered here. It
        // re-fired on every reconnect/app-churn and repeatedly popped the BYD
        // dialog. It now runs ONLY from the setup wizard's autostart button
        // (SetupGuideDialog -> runAutoStartEnabler), which happens once per fresh
        // install with the user present and the display on.
    }

    /**
     * Drive AutoStartEnabler once, off the main thread, and report the outcome
     * back on the main thread. Triggered deliberately by the user (setup wizard
     * button) — never auto-run. Single-flight is enforced inside AutoStartEnabler,
     * so a double-tap can't double-run. Wrapped so it can never crash the service.
     */
    public void runAutoStartEnabler(final Callback callback) {
        try {
            final AutoStartEnabler enabler = new AutoStartEnabler(this);
            Thread worker = new Thread(() -> {
                AutoStartEnabler.Result result = null;
                try {
                    result = enabler.run();
                } catch (Throwable t) {
                    Log.w(TAG, "runAutoStartEnabler worker threw: " + t);
                }
                final AutoStartEnabler.Result fResult = result;
                final boolean success = result == AutoStartEnabler.Result.SUCCESS
                        || result == AutoStartEnabler.Result.ALREADY_OFF;
                mainHandler.post(() -> {
                    if (callback == null) return;
                    try {
                        callback.onResult(success, fResult);
                    } catch (Throwable t) {
                        Log.w(TAG, "AutoStartEnabler callback threw: " + t);
                    }
                });
            }, "autostart-enabler");
            worker.start();
        } catch (Throwable t) {
            Log.w(TAG, "runAutoStartEnabler failed to start worker: " + t);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, null));
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op — we don't process accessibility events
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "AccessibilityService unbound — clearing instance");
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "AccessibilityService destroyed — attempting restart");
        instance = null;

        // Self-restart: send broadcast to trigger re-enable
        try {
            Intent restartIntent = new Intent("com.overdrive.app.RESTART_ACCESSIBILITY");
            sendBroadcast(restartIntent);
        } catch (Exception e) {
            Log.e(TAG, "Restart broadcast failed: " + e.getMessage());
        }

        super.onDestroy();
    }
}
