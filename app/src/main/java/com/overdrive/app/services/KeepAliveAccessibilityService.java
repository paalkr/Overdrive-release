package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.overdrive.app.homepanel.HomePanelFocus;
import com.overdrive.app.ui.daemon.DaemonStartupManager;
import com.overdrive.app.util.DaemonHttpClient;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        Log.i(TAG, "AccessibilityService connected — process is now protected");

        // Start the app-process signal monitors FIRST — BEFORE the setServiceInfo() block
        // below, which early-returns if the info-set throws. These monitors are independent
        // of key-filtering (they relay phone-call + Bluetooth state to the daemon, which
        // can't read them from UID 2000); gating them behind a successful setServiceInfo
        // meant a key-filter wiring failure ALSO silently killed call/BT automations. Both
        // are idempotent + self-guarding, so starting them early is safe.
        try {
            CallStateMonitor.start(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "CallStateMonitor start failed: " + t.getMessage());
        }
        try {
            BluetoothStateMonitor.start(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "BluetoothStateMonitor start failed: " + t.getMessage());
        }

        // Config must match the WORKING shape proven on DiLink firmware (verified
        // against a known-good OEM app): a service that subscribes to ZERO event
        // types (eventTypes=0) is treated as inert by this firmware's
        // AccessibilityManager — it binds, but onKeyEvent is NEVER dispatched to
        // it (observed live: service bound, capabilities=8, yet keycode 302 hit
        // WindowManager with FLAG_PASS_TO_USER and never reached onKeyEvent). The
        // fix is to subscribe to a real event type (typeWindowStateChanged) and
        // enable window-content retrieval, which fully wires the service into the
        // input path so FLAG_REQUEST_FILTER_KEY_EVENTS actually delivers keys.
        //
        // We MODIFY the info returned by getServiceInfo() (which already carries
        // the manifest XML config) rather than rebuilding it, and only assert the
        // fields that matter — never zero out eventTypes.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        // Mirror the proven OEM config EXACTLY (no extra flags): subscribe to a
        // real event type + report-view-ids + filter-key-events. Adding more than
        // the known-good set risks a different firmware quirk, so match it 1:1.
        // FORK-LOCAL ADDITION on top of the proven OEM shape: AutoStartEnabler needs
        // TYPE_WINDOWS_CHANGED + FLAG_RETRIEVE_INTERACTIVE_WINDOWS to reach BYD's
        // com.byd.appstartmanagement dialog through getWindows(). The upstream comment
        // above cautions against extra flags because the key-filter wiring is
        // firmware-sensitive; both shapes were validated separately on this DiLink
        // build (enabler 2026-07-07, key filtering upstream) but NOT together, so a
        // deploy must re-verify hardware keys as well as the enabler. If keys regress,
        // the fix is to raise these two only for the duration of an enabler run and
        // restore the OEM shape afterwards, rather than carrying them permanently.
        // FORK-LOCAL ADDITION (2): seat-position capture subscribes to
        // TYPE_VIEW_LONG_CLICKED so onAccessibilityEvent sees the user long-pressing
        // BYD's seat-position buttons. Same firmware-sensitivity caveat as the enabler
        // additions above — a deploy carrying this MUST re-verify hardware key mapping
        // still fires (press a mapped steering/dash key), since eventTypes changes have
        // regressed key delivery on this firmware before.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        // Wrap the info-set and publish instance=this ONLY on success. Previously
        // `instance` was set before setServiceInfo(): if the info-set threw, the key
        // filter wasn't wired yet in-proc isRunning() reported a false "healthy" while
        // keys were dead. Setting instance only after the wiring is actually in force
        // makes the liveness signal honest (a partial connect reads as not-live, so the
        // watchdog rebinds rather than trusting a half-connected service).
        try {
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.e(TAG, "setServiceInfo failed — key filtering NOT wired; leaving instance null "
                    + "so the watchdog rebinds: " + t.getMessage());
            return;
        }
        instance = this;

        // Prime the key-mapping snapshot off-thread so the first hardware key
        // press already has its bindings (onKeyEvent never reads disk itself).
        try {
            KeyMapDispatcher.INSTANCE.warmUp();
        } catch (Throwable t) {
            Log.w(TAG, "KeyMapDispatcher warmUp failed: " + t.getMessage());
        }

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

    /**
     * BYD exposes the SAME three profile positions from two different apps, and a long-press
     * saves in both — so both have to be watched or capture silently misses half the ways the
     * user actually saves a position.
     *
     * <p>{@code com.byd.carsettings} is the Settings "Sjåfør" dialog, ids {@code location1..3}.
     *
     * <p>{@code com.byd.diLinkAccount} is a floating widget that appears OVER whatever app is
     * open, whenever the seat or mirrors are moved with the physical controls while the car is
     * on and stationary. Confirmed as the window owner on the car (2026-08-11). Its layout is
     * {@code window_glb_driver_pos} and its own prompt string reads "Long press the widget to
     * save the seat/steering wheel/rearview mirror position, and tap the widget to apply the
     * position" — i.e. the same save gesture, on the same three slots.
     */
    private static final String SEAT_POS_PKG = "com.byd.carsettings";
    private static final String SEAT_POS_WIDGET_PKG = "com.byd.diLinkAccount";

    /** Off-thread HTTP to the daemon so the a11y callback never blocks. */
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // Window changes tell the home dashboard whether the launcher is the thing on
        // screen. This service is already subscribed to both event types (see
        // onServiceConnected), so this costs no new permission.
        //
        // BOTH types are needed, learned on the car 2026-08-13: coming back to the
        // ALREADY-RUNNING launcher fires TYPE_WINDOWS_CHANGED, not
        // TYPE_WINDOW_STATE_CHANGED — the launcher's window already exists, so its
        // state does not change, only the window stack does. Handling only
        // WINDOW_STATE_CHANGED meant the panel never appeared when you pressed home,
        // which is the only way anyone actually gets there.
        //
        // WINDOWS_CHANGED carries no useful getPackageName(), so the package comes from
        // the active window instead. That read is not free and this event is chatty, so
        // it is gated on the feature being switched on at all.
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            try {
                if (HomePanelFocus.isWanted()) {
                    String fg = null;
                    String cls = null;
                    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                            && event.getPackageName() != null) {
                        fg = event.getPackageName().toString();
                        // The activity class distinguishes the launcher's home screen
                        // from its app drawer and customize screens, which are separate
                        // activities in the same package.
                        if (event.getClassName() != null) cls = event.getClassName().toString();
                    } else {
                        fg = activeWindowPackage();
                    }
                    if (fg != null) HomePanelFocus.onForegroundPackage(this, fg, cls);
                }
            } catch (Throwable t) {
                // A panel bug must never take down key filtering or seat capture.
                Log.w(TAG, "home-panel focus update failed: " + t.getMessage());
            }
            return;
        }

        // Only interested in the user long-pressing a BYD seat-position button (the
        // "save current position" gesture). Everything else is ignored cheaply.
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        if (!SEAT_POS_PKG.contentEquals(pkg) && !SEAT_POS_WIDGET_PKG.contentEquals(pkg)) return;
        int slot = seatSlotFromEvent(event);
        if (slot < 1) return;
        Log.i(TAG, "seat-position long-press: slot " + slot + " — capturing geometry");
        captureSeatPosition(slot);
    }

    /**
     * Resolve which native slot (1..3) was long-pressed, from the source view's resource-id.
     * Resource-id is language-independent, unlike the button text ("Posisjon N").
     *
     * <p>Two id schemes, because the same three slots are presented by two apps:
     * <ul>
     *   <li>Settings dialog: {@code com.byd.carsettings:id/location1|2|3} — the number IS the slot.
     *   <li>Floating widget: {@code com.byd.diLinkAccount:id/(iv|tv)_(drive|rest|standby)} —
     *       named, not numbered. Both the image and the label carry an id, and which one the
     *       long-press reports depends on where the finger lands, so accept either.
     * </ul>
     *
     * <p>The widget's drive/rest/standby → 1/2/3 mapping is INFERRED from the order the three
     * groups appear in {@code window_glb_driver_pos} matching the app's own "Position 1/2/3"
     * strings. It is not proven. If it were wrong the mistake would be loud rather than silent:
     * the daemon names a captured entry from {@code driverPos_N}, so pressing the top slot would
     * visibly store it as "Posisjon 2". Worth a glance on the first widget capture.
     *
     * <p>Returns -1 if the long-press wasn't on a seat-position slot.
     */
    private int seatSlotFromEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo src = null;
        try {
            src = event.getSource();
            if (src == null) return -1;
            CharSequence rid = src.getViewIdResourceName();
            if (rid == null) return -1;
            String s = rid.toString();

            // Settings dialog — numbered ids.
            String prefix = SEAT_POS_PKG + ":id/location";
            if (s.startsWith(prefix) && s.length() == prefix.length() + 1) {
                char c = s.charAt(s.length() - 1);
                if (c >= '1' && c <= '3') return c - '0';
            }

            // Floating widget — named ids, either the icon (iv_) or the label (tv_).
            String widgetPrefix = SEAT_POS_WIDGET_PKG + ":id/";
            if (s.startsWith(widgetPrefix)) {
                String name = s.substring(widgetPrefix.length());
                if (name.startsWith("iv_") || name.startsWith("tv_")) {
                    String slot = name.substring(3);
                    if ("drive".equals(slot)) return 1;
                    if ("rest".equals(slot)) return 2;
                    if ("standby".equals(slot)) return 3;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "seatSlotFromEvent: " + t.getMessage());
        } finally {
            if (src != null) { try { src.recycle(); } catch (Throwable ignored) {} }
        }
        return -1;
    }

    /**
     * Package owning the active window, or null. Used for the events that do not carry
     * a useful package name, and by the home dashboard's own watchdog through
     * {@link #getRootInActiveWindow()}.
     */
    public String activeWindowPackage() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Titles of the launcher windows that identify which launcher surface is up. The BYD
     * launcher keeps ONE window at a fixed id and retitles it per surface, so the title is
     * the only thing that distinguishes them — the activity never changes. Observed on the
     * car 2026-08-14 by driving the UI: plain home carries {@code BydLauncher_CardBarwindow};
     * entering "edit home layout" replaces it with {@code EditWindow} at the same window id;
     * the app drawer has neither (it is a real activity, {@code .Launcher}).
     */
    private static final String LAUNCHER_HOME_WINDOW = "BydLauncher_CardBarwindow";
    private static final String LAUNCHER_EDIT_WINDOW = "EditWindow";

    /** What the accessibility window list says is on screen right now. */
    public static final class Surfaces {
        /** Package owning the active window, or null when it cannot be read. */
        public final String activePackage;
        /** The launcher's plain-home window is present. */
        public final boolean homeWindow;
        /** The launcher's edit-layout window is present. */
        public final boolean editWindow;

        Surfaces(String activePackage, boolean homeWindow, boolean editWindow) {
            this.activePackage = activePackage;
            this.homeWindow = homeWindow;
            this.editWindow = editWindow;
        }
    }

    /**
     * Read the current surfaces from the accessibility window list.
     *
     * <p>This exists because the window list answers questions the event stream cannot. An
     * event's activity class is absent on window-stack changes and unchanged for a launcher
     * that swaps surfaces inside one activity, so any verdict cached from the last event that
     * happened to carry a class goes stale — which is what left the home panel drawn over the
     * edit-layout screen, and made returning home take two presses (2026-08-14).
     *
     * <p>Titles only, deliberately: no {@code getRoot()} per window, which would walk node
     * trees on a chatty callback.
     */
    public Surfaces surfaces() {
        String active = activeWindowPackage();
        boolean home = false;
        boolean edit = false;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    CharSequence title = w.getTitle();
                    if (title == null) continue;
                    if (LAUNCHER_HOME_WINDOW.contentEquals(title)) home = true;
                    else if (LAUNCHER_EDIT_WINDOW.contentEquals(title)) edit = true;
                }
            }
        } catch (Throwable t) {
            // A window-list read must never break key filtering or seat capture.
            Log.w(TAG, "surfaces() failed: " + t.getMessage());
        }
        return new Surfaces(active, home, edit);
    }

    /**
     * Ask the daemon to read the live seat+mirror geometry and store it as the
     * captured entry for this native slot. The a11y service runs in the app UI
     * process (uid = app), which cannot read BYD geometry directly (signature-perm
     * gated to uid 2000); the daemon on :8080 can, so we POST there — same pattern
     * as KeyMapDispatcher.fire(). Runs off the a11y callback thread.
     */
    private void captureSeatPosition(int slot) {
        captureExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/positions/capture?slot=" + slot, "POST", 3000, 8000);
                int code = conn.getResponseCode();
                Log.i(TAG, "capture slot " + slot + " -> HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "capture slot " + slot + " failed: " + t.getMessage());
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
            }
        });
    }

    /**
     * Hardware key filter. With FLAG_REQUEST_FILTER_KEY_EVENTS this is invoked
     * for physical KeyEvents (steering-wheel / dash buttons) BEFORE the OEM
     * handler. Returning true CONSUMES the event so the default action does not
     * also run; false lets it pass through untouched.
     *
     * Delegates to {@link KeyMapDispatcher}, which is fast (a config lookup) and
     * punts any actual actuation to a background thread — this callback runs on
     * the platform input-dispatch path and must never block, or the whole system
     * UI would ANR. If the feature is off or the key is unmapped, the dispatcher
     * returns false and the key behaves exactly as stock.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            // Bring-up diagnostic for "does this firmware dispatch hardware keys
            // to our filter at all". Now gated behind isLoggable: this method runs
            // on the PLATFORM INPUT-DISPATCH path, so an unconditional Log.i +
            // string concat here taxes every hardware key press system-wide (and
            // fires continuously while a key is held down, via getRepeatCount).
            // Key mapping is field-confirmed, so the log stays available on demand
            // (`setprop log.tag.KeepAliveA11y DEBUG`) without paying for it on
            // every keystroke.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onKeyEvent keyCode=" + event.getKeyCode()
                        + " down=" + down + " repeat=" + event.getRepeatCount());
            }
            return KeyMapDispatcher.INSTANCE.onKey(
                    event.getKeyCode(), down, event.getRepeatCount());
        } catch (Throwable t) {
            // Never let a mapping bug swallow keys or crash input dispatch —
            // fail open (pass the key through) on any error.
            Log.w(TAG, "onKeyEvent error, passing through: " + t.getMessage());
            return false;
        }
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

        // Clear transient key-gesture state so a gesture torn down mid-flight
        // (service destroyed between a promoting DOWN and its UP) can't strand a
        // suppressLongUntilUp entry and swallow the next long-press after re-enable.
        // Honors KeyMapDispatcher.teardown()'s documented contract; the FileObserver
        // is intentionally left running (warmUp()'s watcher != null guard keeps a
        // reconnect from leaking a second observer).
        try {
            KeyMapDispatcher.INSTANCE.teardown();
        } catch (Throwable t) {
            Log.w(TAG, "KeyMapDispatcher teardown failed: " + t.getMessage());
        }

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
