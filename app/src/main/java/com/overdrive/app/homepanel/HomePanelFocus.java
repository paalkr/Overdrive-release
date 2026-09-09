package com.overdrive.app.homepanel;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.overdrive.app.services.KeepAliveAccessibilityService;

/**
 * Tracks whether the home screen is the thing on screen.
 *
 * <p>The signal is {@code TYPE_WINDOW_STATE_CHANGED} from the accessibility service
 * OverDrive already runs (it is already subscribed to that event type; it just used
 * to ignore everything except long-presses). No new permission, no polling.
 *
 * <p>The home package is resolved at runtime from the HOME intent rather than
 * hardcoded to {@code com.android.launcher3}: that IS what this unit ships, but the
 * whole point of the earlier launcher research was that the HOME role is swappable
 * by a /data app, and a panel that vanishes because someone changed launcher would
 * be a puzzling bug.
 */
public final class HomePanelFocus {

    private static final String TAG = "HomePanel";

    /**
     * Windows that appear over whatever has focus without really replacing it: the
     * shade, volume dialog, and other SystemUI surfaces. Treating these as "the
     * launcher lost focus" makes the panel flicker every time a toast or the shade
     * shows up, so the last real answer is kept instead.
     */
    private static final String SYSTEM_UI_PKG = "com.android.systemui";

    private static volatile boolean launcherForeground = false;
    private static volatile String homePackage = null;
    private static volatile String homeActivity = null;
    private static volatile String lastRealPackage = null;

    /**
     * Whether the launcher's HOME activity specifically is showing, as opposed to one
     * of its other screens. The launcher owns three MAIN activities on this firmware —
     * {@code .home.MainActivity} (the home screen), {@code .home.HomeSelectActivity}
     * (customize dashboard) and {@code .Launcher} (the app drawer) — and a
     * package-level check counts all three as "home", so the panel drew on top of the
     * app drawer and the customize UI. Reported from the car 2026-08-13.
     *
     * <p>**Defaults to TRUE, deliberately.** The launcher's resting state IS the home
     * screen; the drawer and the customize screen always announce themselves with a
     * window-state change that flips this false. Defaulting it to false instead broke
     * the panel completely on the next car-on: the only path that runs at service start
     * is {@link #refreshFromActiveWindow}, which has no activity class to work from, so
     * it ANDed against a false that nothing could ever set true, and the panel never
     * attached. That is the same never-attaches failure this class was already fixed for
     * once — reintroduced by adding the activity gate on top of it, and caught on the
     * car 2026-08-14. The cost of the true default is one wrong frame if the service
     * starts while the drawer happens to be open, which the next event corrects.
     */
    private static volatile boolean homeActivityShowing = true;

    /**
     * Whether anything is actually waiting on this signal. Set by the overlay service
     * when it starts and cleared when it stops, so the accessibility callback can skip
     * the active-window read entirely while the feature is off — TYPE_WINDOWS_CHANGED
     * is chatty and that read is not free.
     */
    private static volatile boolean wanted = false;

    /** Throttle for the missing-accessibility-instance warning. */
    private static final long NO_A11Y_LOG_MS = 60_000L;
    private static volatile long lastNoA11yLogMs = -NO_A11Y_LOG_MS;

    private HomePanelFocus() {}

    public static void setWanted(boolean value) {
        wanted = value;
    }

    public static boolean isWanted() {
        return wanted;
    }

    /**
     * Establish the CURRENT foreground package instead of waiting for the next change.
     *
     * <p>This exists because the first version only reacted to transitions, and the
     * watchdog that could have corrected it only ran once the panel was already
     * attached: a service that started while the launcher was in front therefore never
     * attached, and never would. Called on service start, and by the watchdog.
     *
     * @return true when the state changed as a result
     */
    public static boolean refreshFromActiveWindow(Context context) {
        try {
            KeepAliveAccessibilityService a11y = KeepAliveAccessibilityService.getInstance();
            if (a11y == null) {
                // BOTH of the panel's inputs run through this instance — the event callback
                // and this watchdog read — so a null one means the panel can neither attach
                // nor correct itself, and it used to say nothing while that happened. On the
                // car 2026-08-14 the panel was absent for 36 minutes for exactly this
                // reason: a force-stop rebound the service into a process that was then
                // killed again 18s later, and the process that replaced it was started for
                // an Activity, so nothing ever bound the service again. Rate-limited: the
                // watchdog ticks every 4s.
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastNoA11yLogMs > NO_A11Y_LOG_MS) {
                    lastNoA11yLogMs = now;
                    Log.w(TAG, "no accessibility instance — cannot tell what is in front, "
                            + "panel stays " + (launcherForeground ? "attached" : "detached"));
                }
                return false;
            }
            // Same evaluation as the event path, so the watchdog cannot disagree with it.
            // It also no longer needs an activity class to be useful, which is what made the
            // old version silently do nothing on every tick.
            String pkg = a11y.activeWindowPackage();
            if (pkg != null && !pkg.isEmpty()
                    && !SYSTEM_UI_PKG.equals(pkg) && !pkg.equals(context.getPackageName())) {
                lastRealPackage = pkg;
            }
            return apply(context, "window list " + (pkg == null ? "(no active window)" : pkg));
        } catch (Throwable t) {
            Log.w(TAG, "refreshFromActiveWindow failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Called from the accessibility service for every window change.
     *
     * @param activityClass the foreground activity's class when the event carries one
     *                      (window-state changes do; window-stack changes do not), else
     *                      null, in which case the last known activity verdict stands.
     */
    public static void onForegroundPackage(Context context, String pkg, String activityClass) {
        if (pkg == null || pkg.isEmpty()) return;

        // SystemUI is NO LONGER ignored here. It used to return early "so the shade does not
        // toggle the panel", but that is exactly backwards: tapping a status icon opens an
        // interactive SystemUI panel that lands BELOW our overlay in z-order (measured on the
        // car 2026-08-14: panel at #7, our window at #6), so the Wi-Fi list was covered by the
        // tiles and could not be touched. Any system surface that takes focus has to push the
        // panel out of the way. It is not recorded as the "last real package", though, so the
        // fallback verdict still describes the last real app.
        if (!SYSTEM_UI_PKG.equals(pkg) && !pkg.equals(context.getPackageName())) {
            lastRealPackage = pkg;
        }
        boolean isLauncherPkg = pkg.equals(resolveHomePackage(context));

        // A window-state event whose class is NOT an activity says nothing about which app is
        // in front: it is a popup, a panel, or — the one that bit us — OUR OWN overlay's
        // WebView, which fired `com.overdrive.app/android.webkit.WebView` and used to make the
        // panel dismiss itself ~400ms after attaching (car, 2026-08-14).
        //
        // Such a class no longer aborts the whole callback — it only fails to update
        // the fallback verdict below. Aborting was necessary while the verdict came FROM the
        // event; now it comes from the window list, so our own WebView's event is harmless,
        // and aborting actively hurt: the SystemUI panels from the note above announce
        // themselves with a View class, so the early return meant the one case we need to
        // react to never reached the evaluation.
        boolean classIsActivity = activityClass != null && !activityClass.isEmpty()
                && isActivity(context, pkg, activityClass);

        if (classIsActivity) {
            // An ACTIVITY class is authoritative: it tells us WHICH launcher screen this
            // is. A non-activity class is not, and must not touch the verdict —
            // TYPE_WINDOW_STATE_CHANGED also fires for popups and panels, whose class is
            // a View (the launcher emits `android.widget.FrameLayout` right after its own
            // home activity). Treating that as "not the home screen" made the panel
            // attach and detach again within 500ms, every time. Caught on the car
            // 2026-08-14, in the logs as:
            //   foreground …/com.android.launcher3.home.MainActivity -> home=true
            //   attached 1920x730 at y=85
            //   foreground …/android.widget.FrameLayout              -> home=false
            //   detached
            homeActivityShowing = isLauncherPkg && activityClass.equals(resolveHomeActivity(context));
        } else if (activityClass == null || activityClass.isEmpty()) {
            // A classless event naming our own package used to return here, on the theory that
            // it could only be the overlay. It can also be OverDrive's UI being re-shown, and
            // returning meant nothing re-evaluated. The window list distinguishes the two, so
            // fall through and let it: the overlay is never the active window, our UI is.
            if (!isLauncherPkg && !SYSTEM_UI_PKG.equals(pkg)
                    && !pkg.equals(context.getPackageName())) {
                // No class, but a different app is in front — unambiguous.
                homeActivityShowing = false;
            }
        }
        // No class AND the launcher package: the fallback verdict keeps its last value. It is
        // only a fallback now — the window list below is what actually decides.

        String reason = "foreground " + pkg + (activityClass != null ? "/" + activityClass : "");
        if (apply(context, reason)) HomePanelOverlayService.syncWithConfig(context);
    }

    /**
     * Decide from the accessibility window list, and fall back to the event-derived verdict
     * only when the list says nothing usable.
     *
     * <p>The window list is authoritative because it describes what is on screen NOW, whereas
     * the event-derived verdict is a guess cached from whichever earlier event happened to
     * carry an activity class. All three bugs Pål reported on 2026-08-14 were that cache being
     * stale or unreachable: the panel drew over the edit-layout screen (same activity, so no
     * event ever contradicted the cached "home"), returning home took two presses (the first
     * return fires a classless window-stack change, so the drawer's stale "not home" stood),
     * and a SystemUI panel could not be touched underneath the tiles.
     */
    private static boolean homeShowing(Context context) {
        KeepAliveAccessibilityService a11y = KeepAliveAccessibilityService.getInstance();
        String launcher = resolveHomePackage(context);
        if (a11y != null) {
            KeepAliveAccessibilityService.Surfaces s = a11y.surfaces();
            String active = s.activePackage;
            // Our own package as the ACTIVE window means OverDrive's real UI is in front, so
            // the panel must go. It cannot be the overlay: that window is FLAG_NOT_FOCUSABLE
            // and never becomes the active one — confirmed on the car, where the active
            // package reads as the launcher for the whole time the panel is attached.
            //
            // Treating this case as "ambiguous, use the cached verdict" instead is what broke
            // the own_ui_open test on 2026-08-14: bringing an ALREADY-RUNNING OverDrive to the
            // front fires a classless window-stack change, the event path said nothing about
            // it, and the cached verdict still read "home", so the panel stayed on top of
            // OverDrive's own UI.
            if (active != null) {
                if (!active.equals(launcher)) return false;   // us, another app, or a system panel
                if (s.editWindow) return false;               // edit-layout mode
                if (s.homeWindow) return true;                // plain home, whatever the cache says
                // Launcher package but neither window: the app drawer, or a launcher whose
                // windows we have no fingerprint for. Fall through to the cached verdict.
            }
        }
        return launcher != null && launcher.equals(lastRealPackage) && homeActivityShowing;
    }

    /**
     * Re-evaluate and log only on a change.
     *
     * @return true when the verdict changed, so the caller can act on it
     */
    private static boolean apply(Context context, String reason) {
        boolean isHome = homeShowing(context);
        if (isHome == launcherForeground) return false;
        launcherForeground = isHome;
        Log.i(TAG, reason + " -> home=" + isHome);
        return true;
    }

    /** Back-compat entry point for callers with no class to offer. */
    public static void onForegroundPackage(Context context, String pkg) {
        onForegroundPackage(context, pkg, null);
    }

    public static boolean isLauncherForeground() {
        return launcherForeground;
    }

    /** The last non-SystemUI package seen, for the service's watchdog log. */
    public static String lastPackage() {
        return lastRealPackage;
    }


    /**
     * Resolve and cache the current HOME package. Cached because this runs on the
     * accessibility callback path, which must stay cheap; call
     * {@link #clearHomePackageCache()} if the HOME role is ever changed at runtime.
     */
    private static String resolveHomePackage(Context context) {
        String cached = homePackage;
        if (cached != null) return cached;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                homePackage = ri.activityInfo.packageName;
                homeActivity = ri.activityInfo.name;
                Log.i(TAG, "home resolved: " + homePackage + "/" + homeActivity);
                return homePackage;
            }
        } catch (Throwable t) {
            Log.w(TAG, "home package resolve failed: " + t.getMessage());
        }
        return "";
    }

    /** component string -> is it a real Activity. Resolved once per class seen. */
    private static final java.util.Map<String, Boolean> ACTIVITY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether {@code cls} is an Activity in {@code pkg}, as opposed to a View class from
     * a popup's window-state event. Cached because this runs on the accessibility
     * callback path; the answer for a given class never changes while we are installed.
     */
    private static boolean isActivity(Context context, String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean cached = ACTIVITY_CACHE.get(key);
        if (cached != null) return cached;
        boolean result;
        try {
            context.getPackageManager().getActivityInfo(
                    new android.content.ComponentName(pkg, cls), 0);
            result = true;
        } catch (Throwable t) {
            result = false;
        }
        ACTIVITY_CACHE.put(key, result);
        return result;
    }

    /**
     * The HOME activity's class name, cached alongside the package. Same resolve, so
     * whichever app holds the HOME role defines both.
     */
    private static String resolveHomeActivity(Context context) {
        String cached = homeActivity;
        if (cached != null) return cached;
        resolveHomePackage(context);   // populates both
        return homeActivity != null ? homeActivity : "";
    }

    public static void clearHomePackageCache() {
        homePackage = null;
        homeActivity = null;
    }
}
