package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.overdrive.app.R;
import com.overdrive.app.logging.LogConfig;
import com.overdrive.app.logging.LogManager;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.overlay.SetupGuideDialog;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-re-enables OverDrive's autostart after each (re)install.
 *
 * <p>BYD's DiLink firmware blocks any /data app from autostarting at boot unless
 * its per-app value in {@code com.byd.appstartmanagement} ("Deaktiver Autostart")
 * is toggled OFF. That value is RESET (app re-blocked) on every install/update but
 * PERSISTS across ordinary reboots. So this routine runs ONCE PER INSTALL — the
 * first time the a11y service connects after a new install fingerprint — and does
 * nothing on plain reboots.
 *
 * <p>The dialog is a {@code mShowToOwnerOnly} window above the launcher, so
 * {@code getRootInActiveWindow()} returns the launcher, not the dialog. Only
 * {@link AccessibilityService#getWindows()} (with flagRetrieveInteractiveWindows)
 * reaches its nodes. This first build doubles as the plan's V1 verification that
 * getWindows() actually surfaces the dialog — hence the verbose logging.
 *
 * <p>Delegated to from {@link KeepAliveAccessibilityService}; takes the service as
 * its accessibility context. Single-flight guarded; never throws to the caller.
 */
public class AutoStartEnabler {

    private static final String TAG = "AutostartEnabler";

    /**
     * Feature flag. Default enabled. A full settings UI is out of scope for
     * Phase 2a; flip this to false to disable the auto-enable behaviour entirely.
     */
    public static final boolean FEATURE_ENABLED = true;

    private static final String PREFS_NAME = "overdrive_autostart";
    private static final String KEY_APPLIED_FINGERPRINT = "applied_install_fingerprint";

    // BYD dialog package (the row-and-switch screen).
    private static final String BYD_APPSTART_PKG = SetupGuideDialog.BYD_APPSTART_PKG;

    // Timing / bounds.
    private static final long DIALOG_WAIT_MS = 5000L;
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int MAX_SCROLLS = 20;
    private static final long CLICK_CONFIRM_DELAY_MS = 600L;
    private static final long GESTURE_TIMEOUT_MS = 2000L;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_ANCESTOR_CLIMB = 6;

    // Single-flight across the whole process — only ever one run in flight.
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final AccessibilityService service;

    public AutoStartEnabler(AccessibilityService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------------
    // Public entry points
    // ---------------------------------------------------------------------

    /**
     * Run the enabler on a background thread IF this is a new install (fingerprint
     * mismatch) and the feature is enabled. Safe to call from onServiceConnected
     * (main thread) — it offloads the blocking work itself.
     */
    public void maybeRunForNewInstall() {
        if (!FEATURE_ENABLED) {
            log("feature disabled (FEATURE_ENABLED=false) — skipping");
            return;
        }
        final Context ctx = service.getApplicationContext();
        final String current = currentFingerprint(ctx);
        final String applied = readAppliedFingerprint(ctx);
        log("fingerprint check: current=" + current + " applied=" + applied);
        if (current != null && current.equals(applied)) {
            log("fingerprint matches (ordinary reboot / no reinstall) — nothing to do");
            return;
        }
        log("fingerprint mismatch — a (re)install happened, running enabler once");
        new Thread(this::runBlocking, "autostart-enabler").start();
    }

    // ---------------------------------------------------------------------
    // Core state machine (background thread)
    // ---------------------------------------------------------------------

    private void runBlocking() {
        if (!RUNNING.compareAndSet(false, true)) {
            log("another enabler run is already in flight — aborting this one");
            return;
        }
        try {
            // Light, non-blocking UX guard: log the ACC state but still proceed.
            // This runs right after a (re)install, which is a user-initiated app
            // launch (foreground moment the user just triggered), so a brief
            // dialog flash here is acceptable. We do NOT gate on parked/ACC —
            // gating risks never running when the value is already reset.
            log("UX context: accOn=" + AccMonitor.isAccOn()
                    + " inSentry=" + AccMonitor.isInSentryMode()
                    + " (not gating; proceeding)");

            boolean success = false;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS && !success; attempt++) {
                log("attempt " + attempt + "/" + MAX_ATTEMPTS);
                Result r = attemptOnce();
                log("attempt " + attempt + " result=" + r);
                if (r == Result.SUCCESS || r == Result.ALREADY_OFF) {
                    success = true;
                } else if (r == Result.NOT_THIS_FIRMWARE) {
                    // No appstartmanagement component — never going to work here.
                    log("appstartmanagement not present on this firmware — no retry");
                    break;
                }
            }

            // Always close the dialog, whatever happened.
            closeDialog();

            if (success) {
                String fp = currentFingerprint(service.getApplicationContext());
                writeAppliedFingerprint(service.getApplicationContext(), fp);
                log("SUCCESS — persisted install fingerprint=" + fp);
            } else {
                // TODO(phase2b): post a user notification "Autostart not
                // auto-enabled; tap to fix" deep-linking to the dialog. Not
                // implemented in 2a — we only log the failure here.
                log("FAILED after " + MAX_ATTEMPTS + " attempt(s) — fingerprint NOT persisted "
                        + "(will retry on next service connect); notification hook TODO (phase 2b)");
            }
        } catch (Throwable t) {
            log("runBlocking threw: " + t);
        } finally {
            RUNNING.set(false);
        }
    }

    private enum Result {
        SUCCESS,          // switch flipped ON->OFF and confirmed
        ALREADY_OFF,      // switch was already OFF (idempotent no-op)
        NOT_THIS_FIRMWARE,// appstartmanagement activity missing -> abort, no retry
        NO_DIALOG,        // dialog window never surfaced
        NO_ROW,           // OverDrive row / switch not found
        FLIP_UNCONFIRMED  // clicked but state didn't confirm OFF
    }

    private Result attemptOnce() {
        // (a) Guard: service must be connected (getWindows returns [] otherwise).
        List<AccessibilityWindowInfo> pre = service.getWindows();
        if (pre == null) {
            log("getWindows() returned null — service not connected? aborting attempt");
            return Result.NO_DIALOG;
        }

        // (b) Launch the BYD dialog (reuse OverDrive's canonical intent).
        try {
            Intent intent = SetupGuideDialog.buildAppStartManagementIntent();
            service.startActivity(intent);
            log("launched " + BYD_APPSTART_PKG + " dialog intent");
        } catch (ActivityNotFoundException anfe) {
            log("ActivityNotFoundException launching appstartmanagement: " + anfe.getMessage());
            return Result.NOT_THIS_FIRMWARE;
        } catch (Throwable t) {
            log("failed launching appstartmanagement dialog: " + t);
            return Result.NO_DIALOG;
        }

        // (c)+(d) Wait for + locate the dialog window by polling getWindows().
        AccessibilityNodeInfo dialogRoot = waitForDialogRoot();
        if (dialogRoot == null) {
            log("dialog window for " + BYD_APPSTART_PKG + " NOT found within "
                    + DIALOG_WAIT_MS + "ms");
            return Result.NO_DIALOG;
        }
        log("dialog root acquired: pkg=" + dialogRoot.getPackageName());

        // (e) Find OverDrive's row + its Switch (scrolling if needed).
        AccessibilityNodeInfo overdriveSwitch = findOverDriveSwitch(dialogRoot);
        if (overdriveSwitch == null) {
            log("OverDrive switch NOT found in dialog (after scroll search)");
            return Result.NO_ROW;
        }

        // (f) Read + flip.
        boolean checkedBefore = overdriveSwitch.isChecked();
        log("OverDrive switch found: isChecked(before)=" + checkedBefore
                + " (ON=blocked, OFF=autostart-allowed)");
        if (!checkedBefore) {
            log("switch already OFF — autostart already allowed, no-op");
            return Result.ALREADY_OFF;
        }

        boolean clicked = clickSwitch(overdriveSwitch);
        log("switch click issued (nodeClick+fallbacks)=" + clicked);

        // (f cont.) Re-read after a short delay to confirm the flip.
        sleep(CLICK_CONFIRM_DELAY_MS);
        AccessibilityNodeInfo freshRoot = findDialogRoot();
        AccessibilityNodeInfo freshSwitch = (freshRoot != null) ? findOverDriveSwitch(freshRoot) : null;
        if (freshSwitch == null) {
            log("could not re-locate switch to confirm flip");
            return Result.FLIP_UNCONFIRMED;
        }
        boolean checkedAfter = freshSwitch.isChecked();
        log("OverDrive switch isChecked(after)=" + checkedAfter);
        if (!checkedAfter) {
            return Result.SUCCESS;
        }
        return Result.FLIP_UNCONFIRMED;
    }

    // ---------------------------------------------------------------------
    // Window / node helpers
    // ---------------------------------------------------------------------

    private AccessibilityNodeInfo waitForDialogRoot() {
        long deadline = System.currentTimeMillis() + DIALOG_WAIT_MS;
        int iteration = 0;
        while (System.currentTimeMillis() < deadline) {
            iteration++;
            AccessibilityNodeInfo root = findDialogRoot();
            if (root != null) {
                log("dialog root found on poll iteration " + iteration);
                return root;
            }
            sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Iterate getWindows() and return the root whose package is the BYD dialog.
     * Logs the FULL window enumeration each call — this is the V1 make-or-break
     * evidence that getWindows() reaches the dialog window.
     */
    private AccessibilityNodeInfo findDialogRoot() {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null || windows.isEmpty()) {
            log("getWindows() empty/null");
            return null;
        }
        AccessibilityNodeInfo match = null;
        StringBuilder dump = new StringBuilder("getWindows() enumeration (")
                .append(windows.size()).append(" windows):");
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo w = windows.get(i);
            AccessibilityNodeInfo root = (w != null) ? w.getRoot() : null;
            CharSequence pkg = (root != null) ? root.getPackageName() : null;
            CharSequence title = null;
            int type = -1;
            if (w != null) {
                type = w.getType();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    title = w.getTitle();
                }
            }
            dump.append("\n  [").append(i).append("] type=").append(type)
                    .append(" pkg=").append(pkg)
                    .append(" root!=null=").append(root != null)
                    .append(" title=").append(title);
            if (root != null && BYD_APPSTART_PKG.contentEquals(pkg == null ? "" : pkg)) {
                match = root; // keep scanning so the full dump is logged
            }
        }
        dump.append("\n  -> appstartmanagement window found=").append(match != null);
        log(dump.toString());
        return match;
    }

    /**
     * Find OverDrive's row Switch. Searches for the app-label text node, climbs to
     * its row container and looks for a Switch descendant. If the row isn't
     * realized (RecyclerView virtualization), scrolls forward and retries until
     * found or the list can't advance.
     */
    private AccessibilityNodeInfo findOverDriveSwitch(AccessibilityNodeInfo root) {
        final String label = overDriveLabel();
        for (int scroll = 0; scroll <= MAX_SCROLLS; scroll++) {
            AccessibilityNodeInfo current = (scroll == 0) ? root : findDialogRoot();
            if (current == null) {
                log("dialog root vanished mid-scroll-search");
                return null;
            }
            AccessibilityNodeInfo sw = locateSwitchForLabel(current, label);
            if (sw != null) {
                if (scroll > 0) log("OverDrive switch located after " + scroll + " scroll(s)");
                return sw;
            }
            // Not visible yet — try to scroll the list forward.
            AccessibilityNodeInfo scrollable = findScrollable(current);
            if (scrollable == null) {
                log("no scrollable container found and OverDrive row not visible");
                return null;
            }
            boolean advanced = scrollable.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            log("scroll forward (" + (scroll + 1) + ") advanced=" + advanced);
            if (!advanced) {
                log("scroll could not advance further — OverDrive row not present");
                return null;
            }
            sleep(400L);
        }
        log("exhausted MAX_SCROLLS (" + MAX_SCROLLS + ") without finding OverDrive row");
        return null;
    }

    /** From every "OverDrive" label node, climb ancestors and find a Switch. */
    private AccessibilityNodeInfo locateSwitchForLabel(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> labels = root.findAccessibilityNodeInfosByText(label);
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        log("found " + labels.size() + " node(s) matching label '" + label + "'");
        for (AccessibilityNodeInfo labelNode : labels) {
            // Exact-ish match: the BYD row label is the bare app name. Skip nodes
            // whose text is clearly something else that merely contains it.
            AccessibilityNodeInfo ancestor = labelNode;
            for (int climb = 0; climb < MAX_ANCESTOR_CLIMB && ancestor != null; climb++) {
                AccessibilityNodeInfo sw = findSwitchDescendant(ancestor);
                if (sw != null) {
                    return sw;
                }
                ancestor = ancestor.getParent();
            }
        }
        return null;
    }

    /** Depth-first search for an android.widget.Switch descendant. */
    private AccessibilityNodeInfo findSwitchDescendant(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("Switch")) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findSwitchDescendant(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** DFS for the first scrollable node. */
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findScrollable(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Click / gesture
    // ---------------------------------------------------------------------

    /**
     * Try, in order: ACTION_CLICK on the switch, ACTION_CLICK on the nearest
     * clickable ancestor (the row), then a dispatchGesture tap at the switch's
     * on-screen center.
     */
    private boolean clickSwitch(AccessibilityNodeInfo sw) {
        if (sw.isClickable() && sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("node ACTION_CLICK on Switch succeeded");
            return true;
        }
        log("Switch ACTION_CLICK failed/!clickable — trying clickable ancestor");
        AccessibilityNodeInfo a = sw.getParent();
        for (int i = 0; i < MAX_ANCESTOR_CLIMB && a != null; i++) {
            if (a.isClickable() && a.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("node ACTION_CLICK on clickable ancestor (climb " + i + ") succeeded");
                return true;
            }
            a = a.getParent();
        }
        log("ancestor ACTION_CLICK failed — trying dispatchGesture tap fallback");
        return tapCenter(sw);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            log("dispatchGesture fallback aborted — empty bounds");
            return false;
        }
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        log("dispatchGesture tap at (" + cx + "," + cy + ") bounds=" + bounds.toShortString());
        Path path = new Path();
        path.moveTo(cx, cy);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] done = {false};
        boolean dispatched = service.dispatchGesture(gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        done[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription g) {
                        done[0] = false;
                        latch.countDown();
                    }
                }, null);
        if (!dispatched) {
            log("dispatchGesture returned false (not dispatched)");
            return false;
        }
        try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        log("dispatchGesture completed=" + done[0]);
        return done[0];
    }

    private void closeDialog() {
        try {
            boolean back = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            log("closeDialog: GLOBAL_ACTION_BACK=" + back);
        } catch (Throwable t) {
            log("closeDialog failed: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // Fingerprint (once-per-install gate)
    // ---------------------------------------------------------------------

    /**
     * Install fingerprint that changes on EVERY (re)install even when versionCode
     * is unchanged: the /data/app code path (regenerated each install) plus
     * lastUpdateTime as a belt. Deliberately NOT keyed on versionCode — dev
     * rebuilds reuse it.
     */
    private String currentFingerprint(Context ctx) {
        String codePath = null;
        try {
            codePath = ctx.getPackageCodePath();
        } catch (Throwable t) {
            log("getPackageCodePath failed: " + t);
        }
        long lastUpdate = 0L;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            lastUpdate = pi.lastUpdateTime;
        } catch (Throwable t) {
            log("lastUpdateTime lookup failed: " + t);
        }
        return codePath + "|" + lastUpdate;
    }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String readAppliedFingerprint(Context ctx) {
        return prefs(ctx).getString(KEY_APPLIED_FINGERPRINT, null);
    }

    private void writeAppliedFingerprint(Context ctx, String fp) {
        prefs(ctx).edit().putString(KEY_APPLIED_FINGERPRINT, fp).apply();
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    private String overDriveLabel() {
        try {
            String s = service.getString(R.string.app_name);
            if (s != null && !s.trim().isEmpty()) {
                return s;
            }
        } catch (Throwable ignored) {
        }
        return "OverDrive";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String msg) {
        // android.util.Log directly: the Kotlin LogManager's getInstance takes a
        // LogConfig whose factory is LogConfig.default(), and `default` is a Java
        // reserved word so it's uncallable from Java. logcat is what we capture for
        // the V1 verification anyway; file-logging via LogManager can be added later
        // through a Kotlin bridge that doesn't expose the `default` name to Java.
        android.util.Log.i(TAG, msg);
    }
}
