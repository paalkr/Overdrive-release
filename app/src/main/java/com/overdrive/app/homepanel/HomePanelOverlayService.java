package com.overdrive.app.homepanel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.overdrive.app.R;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.services.KeepAliveAccessibilityService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts the home dashboard: a WebView in an overlay window, shown only while the
 * home screen has focus.
 *
 * <p><b>Why a WebView and not inflated views.</b> The panel is a user-arranged
 * canvas of mixed cell types, and phase 2 adds an editor for it. Expressing that in
 * XML layouts means writing a layout engine; expressing it in CSS means using one.
 * The same renderer file then serves both the in-car panel and the browser-based
 * designer.
 *
 * <p><b>Why this is a separate service from {@link com.overdrive.app.overlay.StatusOverlayService}.</b>
 * The pill and the dashboard are siblings, not two sizes of one thing: the pill draws
 * over every app with no focus gating at all, the dashboard only over the launcher,
 * and the two are enabled independently. Sharing a service would tangle two
 * lifecycles that genuinely differ.
 *
 * <p><b>Focus.</b> The window keeps {@code FLAG_NOT_FOCUSABLE} so it can never take
 * focus from the launcher. That is a hard constraint on the renderer too: a text
 * input inside it would silently refuse the keyboard, so there are none.
 */
public class HomePanelOverlayService extends Service {

    private static final String TAG = "HomePanel";
    private static final String CHANNEL_ID = "home_panel";
    private static final int NOTIFICATION_ID = 9002;

    /** Asset path of the renderer, read straight out of the APK. */
    private static final String RENDERER_ASSET = "web/local/home-panel.html";

    /**
     * How often to re-check the foreground app while the panel is attached.
     * The accessibility event is the primary signal; this is the safety net for a
     * missed one (sub 43 proved that service can miss a window), and it only runs
     * while we are actually on screen.
     */
    private static final long WATCHDOG_MS = 4000;

    /**
     * Faster tick while the panel is NOT showing. Some surfaces close without firing any window
     * change we can see — dismissing a SystemUI panel is the case that showed up in testing —
     * so the watchdog IS the return path, and at 4s the panel visibly lagged behind the
     * screen. Only the cheap window-list read runs on this tick.
     */
    private static final long WATCHDOG_DETACHED_MS = 1200;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * One thread for the daemon reads behind {@link #pushState()}. Single-threaded on
     * purpose: the pushes are cheap, and serialising them means a slow daemon delays the
     * next push instead of piling up connections.
     */
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "home-panel-io");
                t.setDaemon(true);
                return t;
            });

    private WindowManager windowManager;
    private WebView webView;
    private WindowManager.LayoutParams layoutParams;
    private boolean attached = false;

    /**
     * Proxy for the hidden {@code OnComputeInternalInsetsListener}, held so it can be removed
     * on detach instead of stacking up one per attach.
     */
    private Object insetsListenerProxy;
    private boolean rendererLoaded = false;

    private UnifiedConfigManager.ConfigChangeListener configListener;

    /**
     * Application context of the running service, for helpers that need a Context but are
     * called from places that hold none (HomePanelTheme.current, reached from the state
     * builder). Null when the service is not running, which those helpers treat as
     * "assume night" rather than guessing.
     */
    private static volatile Context appContext;

    static Context appContext() {
        return appContext;
    }

    /** Repaint against a freshly-chosen theme. Mirrors StatusOverlayService.refreshTheme. */
    public static final String ACTION_REFRESH_THEME = "com.overdrive.app.action.HOME_PANEL_REFRESH_THEME";

    // ==================== lifecycle ====================

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        appContext = getApplicationContext();
        createNotificationChannel();
        startPanelForeground();
        registerConfigListener();
        // Tell the accessibility callback that someone is listening, so it starts
        // resolving the foreground package for us.
        HomePanelFocus.setWanted(true);
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running.set(true);
        if (intent != null && ACTION_REFRESH_THEME.equals(intent.getAction())) {
            // AppCompat only refires onConfigurationChanged for foreground Activities, so
            // a plain Service never sees a theme-setting flip. Same reason the pill needs
            // its own action.
            main.post(this::pushTheme);
            return START_STICKY;
        }
        // First run with no layout: fill one in from the car's own seat positions so
        // the feature does something the moment it is switched on. Blocking HTTP, so
        // off the main thread; the config listener repaints when it lands.
        new Thread(() -> {
            boolean changed = HomePanelSeed.migrate();
            changed |= HomePanelSeed.ensureSeeded();
            if (changed) {
                main.post(() -> { if (attached) pushLayout(); });
            }
        }, "home-panel-seed").start();

        // Establish the CURRENT foreground state rather than waiting for the next
        // change, then evaluate. Without this, enabling the feature while already on
        // the home screen left the panel invisible until some other app took focus and
        // gave it back — which is exactly how it failed on the first on-car test.
        main.post(() -> {
            HomePanelFocus.setWanted(true);
            HomePanelFocus.refreshFromActiveWindow(this);
            evaluate();
            scheduleWatchdog();
        });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        appContext = null;
        HomePanelFocus.setWanted(false);
        if (configListener != null) {
            // The listener list is process-wide and the lambda captures this service,
            // so leaving it behind would leak a whole service instance per restart.
            UnifiedConfigManager.removeListener(configListener);
            configListener = null;
        }
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();   // an in-flight daemon read must not outlive the service
        detach();
        destroyWebView();
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotation changes the free area. Re-measure and re-place rather than
        // reloading: the layout document is orientation-independent in phase 1, and
        // the renderer reflows itself because cells are positioned in grid units.
        main.post(() -> {
            if (attached) applyGeometry();
            // A real system config change is also how the head unit's day/night flip
            // arrives when the theme setting is "follow system".
            pushTheme();
        });
    }

    // ==================== visibility ====================

    /**
     * Two independent conditions, deliberately: the master switch, and whether the
     * home screen is in front. Neither implies the other.
     */
    private boolean shouldShow() {
        return UnifiedConfigManager.isHomePanelEnabled() && HomePanelFocus.isLauncherForeground();
    }

    private void evaluate() {
        if (!running.get()) return;
        if (shouldShow()) {
            attach();
        } else {
            detach();
        }
    }

    private void attach() {
        if (attached) return;
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "attach skipped: no overlay permission");
            return;
        }
        Rect area = HomePanelGeometry.freeArea(this);
        if (area.isEmpty()) {
            Log.w(TAG, "attach skipped: no usable free area on this display");
            return;
        }
        try {
            ensureWebView();
            layoutParams = new WindowManager.LayoutParams(
                    area.width(),
                    area.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // NOT_FOCUSABLE keeps the launcher's focus intact; NOT_TOUCH_MODAL
                    // lets taps outside our bounds reach the launcher underneath.
                    //
                    // HARDWARE_ACCELERATED is REQUIRED, not an optimisation: Chromium
                    // does not render into a software-drawn window, so a WebView in an
                    // un-accelerated overlay produces a perfectly valid, perfectly
                    // transparent surface. The window attaches, SurfaceFlinger shows a
                    // visible 1920x730 RGBA buffer, ViewRootImpl logs a draw, and the
                    // screen looks untouched. Activities get this flag implicitly,
                    // which is why the same page renders fine in the app's own WebView
                    // and not here. Diagnosed on the car 2026-08-13.
                    // LAYOUT_IN_SCREEN makes y screen-absolute. Without it the origin
                    // is the content area BELOW the status bar, so the measured 85px
                    // inset was applied twice: SurfaceFlinger reported pos=(0,169) for
                    // a window asked to sit at 85, and the panel's bottom edge ran into
                    // the launcher's own widget row. Observed on the car 2026-08-13.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.x = area.left;
            layoutParams.y = area.top;

            windowManager.addView(webView, layoutParams);
            attached = true;
            applyTouchableRegion();
            Log.i(TAG, "attached " + area.width() + "x" + area.height() + " at y=" + area.top);
            pushLayout();
            pushTheme();
            pushState();
        } catch (Throwable t) {
            Log.w(TAG, "attach failed: " + t.getMessage());
            attached = false;
        }
    }

    /**
     * Restrict the window's touchable area to the tiles, so everything between them falls
     * through to the launcher.
     *
     * <p>Without this the window is one 1920x730 touch target and the wallpaper underneath is
     * dead: BYD's own floating widget button in the bottom-left corner stopped responding, and
     * so would any launcher gesture in the gap between tiles. {@code FLAG_NOT_TOUCH_MODAL}
     * only passes through taps OUTSIDE the window, which is why it was not enough. Found by
     * the UI test matrix on 2026-08-14 (case {@code passthrough_star}).
     *
     * <p>Reflection because {@code ViewTreeObserver.OnComputeInternalInsetsListener} and
     * {@code InternalInsetsInfo} are {@code @hide}: there is no public way to give a window a
     * non-rectangular touch area. It is an interface, so a {@link Proxy} can implement it. If
     * any part of that is unavailable the window simply stays fully touchable — the tiles must
     * keep working, and eating wallpaper touches is the lesser failure.
     */
    private void applyTouchableRegion() {
        if (webView == null) return;
        removeTouchableRegion();
        try {
            final Class<?> listenerCls =
                    Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            final Class<?> infoCls =
                    Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            final Method setTouchableInsets = infoCls.getMethod("setTouchableInsets", int.class);
            final Field regionField = infoCls.getField("touchableRegion");
            final int TOUCHABLE_INSETS_REGION =
                    (Integer) infoCls.getField("TOUCHABLE_INSETS_REGION").get(null);

            Object proxy = Proxy.newProxyInstance(
                    listenerCls.getClassLoader(), new Class<?>[]{listenerCls},
                    (p, method, args) -> {
                        String name = method.getName();
                        // A Proxy also receives Object's methods; answer them plainly rather
                        // than falling into the handler body with the wrong argument shape.
                        if ("equals".equals(name)) return proxy(args[0]);
                        if ("hashCode".equals(name)) return System.identityHashCode(p);
                        if ("toString".equals(name)) return "HomePanelInsetsListener";
                        if ("onComputeInternalInsets".equals(name) && args != null
                                && args.length == 1 && args[0] != null) {
                            Region region = tileRegion();
                            if (region != null && !region.isEmpty()) {
                                setTouchableInsets.invoke(args[0], TOUCHABLE_INSETS_REGION);
                                ((Region) regionField.get(args[0])).set(region);
                            }
                        }
                        return null;
                    });

            ViewTreeObserver.class
                    .getMethod("addOnComputeInternalInsetsListener", listenerCls)
                    .invoke(webView.getViewTreeObserver(), proxy);
            insetsListenerProxy = proxy;
            webView.requestLayout();
            Log.i(TAG, "touchable region installed for " + tileCount() + " tiles");
        } catch (Throwable t) {
            insetsListenerProxy = null;
            Log.w(TAG, "touchable region unavailable (" + t
                    + ") — window stays fully touchable");
        }
    }

    private boolean proxy(Object other) {
        return other != null && other == insetsListenerProxy;
    }

    private void removeTouchableRegion() {
        if (insetsListenerProxy == null || webView == null) return;
        try {
            Class<?> listenerCls =
                    Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            ViewTreeObserver.class
                    .getMethod("removeOnComputeInternalInsetsListener", listenerCls)
                    .invoke(webView.getViewTreeObserver(), insetsListenerProxy);
        } catch (Throwable ignored) {
            // Nothing to do: a stale listener on a discarded WebView costs nothing.
        }
        insetsListenerProxy = null;
    }

    /** Union of the active layout's cell rectangles, in window coordinates. */
    private Region tileRegion() {
        Region region = new Region();
        try {
            JSONObject layout = HomePanelLayouts.activeLayout();
            JSONArray cells = layout == null ? null : layout.optJSONArray("cells");
            int cell = HomePanelGeometry.CELL_PX;
            for (int i = 0; cells != null && i < cells.length(); i++) {
                JSONObject c = cells.optJSONObject(i);
                if (c == null) continue;
                int x = c.optInt("x", -1), y = c.optInt("y", -1);
                int w = c.optInt("w", 0), h = c.optInt("h", 0);
                if (x < 0 || y < 0 || w <= 0 || h <= 0) continue;
                region.union(new Rect(x * cell, y * cell, (x + w) * cell, (y + h) * cell));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tileRegion failed: " + t.getMessage());
            return null;
        }
        return region.isEmpty() ? null : region;
    }

    private int tileCount() {
        try {
            JSONObject layout = HomePanelLayouts.activeLayout();
            JSONArray cells = layout == null ? null : layout.optJSONArray("cells");
            return cells == null ? 0 : cells.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void detach() {
        // The watchdog keeps running: it is what notices the launcher coming back.
        if (!attached || webView == null) {
            attached = false;
            return;
        }
        removeTouchableRegion();
        try {
            windowManager.removeView(webView);
        } catch (Throwable t) {
            Log.w(TAG, "detach failed: " + t.getMessage());
        }
        attached = false;
        Log.i(TAG, "detached");
    }

    private void applyGeometry() {
        Rect area = HomePanelGeometry.freeArea(this);
        if (area.isEmpty() || layoutParams == null || webView == null) return;
        layoutParams.width = area.width();
        layoutParams.height = area.height();
        layoutParams.x = area.left;
        layoutParams.y = area.top;
        try {
            windowManager.updateViewLayout(webView, layoutParams);
            Log.i(TAG, "geometry updated to " + area.width() + "x" + area.height());
        } catch (Throwable t) {
            Log.w(TAG, "geometry update failed: " + t.getMessage());
        }
    }

    /**
     * Safety net for a missed accessibility event. Reads the active window's package
     * from the accessibility service instance if it is alive; does nothing at all if
     * it is not, rather than guessing.
     */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            // Runs whether or not we are attached, deliberately. The first version
            // returned early unless already attached, which made it useless for the
            // case that actually broke: never attached, so never corrected, so never
            // attached. It has to be able to turn the panel ON as well as off.
            if (HomePanelFocus.refreshFromActiveWindow(HomePanelOverlayService.this)) {
                evaluate();
            }
            scheduleWatchdog();
        }
    };

    private void scheduleWatchdog() {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, attached ? WATCHDOG_MS : WATCHDOG_DETACHED_MS);
    }

    // ==================== WebView ====================

    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        // chrome://inspect over adb. A blank overlay is otherwise close to
        // undiagnosable from the outside, as tonight demonstrated twice.
        try {
            if (com.overdrive.app.BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true);
            }
        } catch (Throwable ignored) {
        }

        android.webkit.WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // No file, no content, no network: the renderer is one self-contained
        // document handed over as a string, and everything it needs comes through
        // the bridge. Nothing here should ever be able to fetch.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        // REQUIRED for the renderer's viewport meta to be honoured. This screen reports
        // devicePixelRatio 1.5, so without a wide viewport the page lays out in 1280x487
        // CSS pixels while the layout grid is expressed in the 1920x730 device pixels
        // the cells were designed and previewed in. Everything rendered correctly and
        // sat entirely outside the viewport: a full-size, perfectly empty panel.
        // Diagnosed over chrome://inspect on the car 2026-08-13.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        // Forward the page's console to logcat. Without this a renderer fault is
        // completely silent: the window attaches, the WebView draws, and the screen
        // just looks untouched. That is precisely how the truncated-document bug
        // below hid for a full on-car round trip.
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.i(TAG, "panel console: " + m.message() + " (line " + m.lineNumber() + ")");
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "ODPanel");
        loadRenderer();
    }

    private void loadRenderer() {
        String html = readAsset(RENDERER_ASSET);
        if (html == null) {
            Log.w(TAG, "renderer asset missing: " + RENDERER_ASSET);
            return;
        }
        // The base URL must be NON-NULL. With null, loadDataWithBaseURL degrades to
        // loadData's data-URI semantics, where the first '#' in the payload starts a
        // URL fragment and EVERYTHING AFTER IT IS DISCARDED. The first '#' here is a
        // colour literal ~30 lines into the CSS, so the WebView loaded a truncated
        // stub: no body, no script, a fully transparent 1920x730 window. It attached,
        // it drew, it logged nothing, and the screen looked untouched. Cost one
        // on-car debugging round on 2026-08-13.
        //
        // A synthetic https base gives a normal opaque-ish origin without needing
        // file access (the URL is never fetched — the HTML is supplied here). Anything
        // non-null would do; this one is self-documenting in a stack trace.
        webView.loadDataWithBaseURL("https://homepanel.overdrive.local/",
                html, "text/html", "utf-8", null);
        rendererLoaded = true;
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            return HomePanelState.readAll(in);
        } catch (Throwable t) {
            Log.w(TAG, "readAsset " + path + " failed: " + t.getMessage());
            return null;
        }
    }

    private void destroyWebView() {
        if (webView == null) return;
        try {
            webView.removeJavascriptInterface("ODPanel");
            webView.destroy();
        } catch (Throwable ignored) {
        }
        webView = null;
        rendererLoaded = false;
    }

    // ==================== push to the page ====================

    private void pushLayout() {
        JSONObject layout = HomePanelLayouts.enrich(this, HomePanelLayouts.activeLayout());
        callJs("ODPanelHost.onLayout", layout.toString());
        // The touchable region is derived from the cells, so a layout change has to trigger a
        // layout pass — the insets callback only runs on one. Without this a tile that moved
        // would keep its old touch area until something else forced a relayout.
        if (webView != null) main.post(webView::requestLayout);
    }

    private void pushTheme() {
        callJs("ODPanelHost.onTheme", HomePanelTheme.forContext(this));
    }

    /**
     * Off the main thread, deliberately. {@link HomePanelState#snapshot()} makes two HTTP
     * calls to the daemon, so calling this from {@code attach()} (which runs on main) threw
     * {@code NetworkOnMainThreadException} — whose {@code getMessage()} is null, which is
     * why it only ever showed up as "summary read failed: null" in the log. Every Java-side
     * push failed that way, so the panel painted dashes on every attach and only filled in
     * when the page's own poll came round, since that runs on the JS bridge thread. Found on
     * the car 2026-08-14. {@code callJs} hops back to main by itself.
     */
    private void pushState() {
        io.execute(() -> {
            try {
                callJs("ODPanelHost.onState", HomePanelState.snapshot().toString());
            } catch (Throwable t) {
                Log.w(TAG, "pushState failed: " + t);
            }
        });
    }

    /** Hand one JSON string to a page function. Main thread only. */
    private void callJs(String fn, String json) {
        if (webView == null || !rendererLoaded) return;
        final String script = fn + "(" + JSONObject.quote(json) + ");";
        main.post(() -> {
            if (webView == null) return;
            try {
                webView.evaluateJavascript(script, null);
            } catch (Throwable t) {
                Log.w(TAG, "callJs " + fn + " failed: " + t.getMessage());
            }
        });
    }

    // ==================== the bridge ====================

    /**
     * The page's whole view of the outside world. Getters are synchronous because
     * they are cheap in-process reads; {@link #invoke} is not, so it returns
     * immediately and the answer comes back through {@code ODPanelHost.onResult}.
     */
    private final class Bridge {

        @JavascriptInterface
        public String getLayout() {
            try {
                return HomePanelLayouts.enrich(HomePanelOverlayService.this,
                        HomePanelLayouts.activeLayout()).toString();
            } catch (Throwable t) {
                Log.w(TAG, "getLayout failed: " + t.getMessage());
                return "{}";
            }
        }

        @JavascriptInterface
        public String getState() {
            try {
                return HomePanelState.snapshot().toString();
            } catch (Throwable t) {
                Log.w(TAG, "getState failed: " + t.getMessage());
                return "{}";
            }
        }

        @JavascriptInterface
        public void invoke(String action, String ref) {
            HomePanelActions.invoke(HomePanelOverlayService.this, action, ref, (ok, message) -> {
                JSONObject res = new JSONObject();
                try {
                    res.put("ok", ok);
                    if (message != null && !message.isEmpty()) res.put("message", message);
                } catch (Throwable ignored) {
                }
                callJs("ODPanelHost.onResult", res.toString());
                // A seat move changes what the glance cells and the seat gate say.
                main.postDelayed(HomePanelOverlayService.this::pushState, 600);
            });
        }
    }

    // ==================== config ====================

    private void registerConfigListener() {
        configListener = (section, config) -> {
            if (!running.get()) return;
            if (!"homePanel".equals(section)) return;
            // Listeners run inside the config file lock, so this must not block.
            // Hop to the main thread and do the work there.
            main.post(() -> {
                if (!running.get()) return;
                evaluate();
                if (attached) pushLayout();
            });
        };
        UnifiedConfigManager.addListener(configListener);
    }

    // ==================== notification ====================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Home Dashboard", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Home-screen dashboard overlay");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void startPanelForeground() {
        Notification notification = buildNotification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // No type on Q..T. The manifest declares only `specialUse`, whose constant did
                // not exist until API 34, so any type requested here (DATA_SYNC was) is not a
                // subset of it and startForeground throws — which it did on this head unit
                // (API 29) on every single service start, then silently fell back to the
                // untyped call. Going straight to the untyped call is the same end state
                // without the exception, and matches what the pill service does.
                startForeground(NOTIFICATION_ID, notification);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground with type failed, falling back: " + t.getMessage());
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.home_panel_notif_title))
                .setContentText(getString(R.string.home_panel_notif_text))
                .setSmallIcon(R.drawable.ic_recording)
                .setContentIntent(pi)
                .setOngoing(true)
                .setGroup(com.overdrive.app.services.DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }

    // ==================== static helpers ====================

    public static boolean hasOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * Start (or nudge) the service when the feature is on, stop it when it is off.
     * Every caller that changes an input — the settings toggle, a focus change, boot
     * — calls this and lets the service work out what that means.
     */
    public static void syncWithConfig(Context context) {
        try {
            if (!UnifiedConfigManager.isHomePanelEnabled()) {
                stop(context);
                return;
            }
            if (!hasOverlayPermission(context)) {
                Log.w(TAG, "syncWithConfig: enabled but no overlay permission");
                return;
            }
            context.startForegroundService(new Intent(context, HomePanelOverlayService.class));
        } catch (Throwable t) {
            Log.w(TAG, "syncWithConfig failed: " + t.getMessage());
        }
    }

    /**
     * Nudge the panel to repaint against a freshly-chosen theme. No-op when the feature is
     * off or the overlay permission is missing, so this never resurrects an unwanted panel.
     */
    public static void refreshTheme(Context context) {
        try {
            if (!UnifiedConfigManager.isHomePanelEnabled()) return;
            if (!hasOverlayPermission(context)) return;
            Intent intent = new Intent(context, HomePanelOverlayService.class);
            intent.setAction(ACTION_REFRESH_THEME);
            context.startForegroundService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "refreshTheme failed: " + t.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, HomePanelOverlayService.class));
        } catch (Throwable ignored) {
        }
    }
}
