package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
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

    private static KeepAliveAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
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

        // Phase 2a: once per (re)install, auto-flip OverDrive's BYD autostart
        // switch OFF so autostart-after-reboot keeps working. No-op on ordinary
        // reboots (fingerprint match) and idempotent if already OFF.
        try {
            new AutoStartEnabler(this).maybeRunForNewInstall();
        } catch (Exception e) {
            Log.w(TAG, "AutoStartEnabler trigger failed: " + e.getMessage());
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
