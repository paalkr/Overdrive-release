package com.overdrive.app.homepanel;

import android.content.Context;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Performs what a panel cell asks for.
 *
 * <p>Everything routes through the daemon's HTTP API rather than being done
 * in-process, for two different reasons that happen to point the same way:
 * <ul>
 *   <li>Seat geometry is signature-gated to uid 2000, so the app process cannot
 *       write it at all (this is the same reason the a11y service POSTs its seat
 *       captures — see KeepAliveAccessibilityService.captureSeatPosition).</li>
 *   <li>App launching needs {@code am start --windowingMode}, which an app uid
 *       cannot run. {@link com.overdrive.app.launcher.AppLauncher} already does the
 *       field-tested version of this from the daemon, including the ghost-activity
 *       trick for an app that is already running, so reimplementing it here would
 *       be a worse copy.</li>
 * </ul>
 *
 * <p>All calls are off the caller's thread. The bridge is invoked from the WebView's
 * JavaScript thread, which must never block on IPC.
 */
public final class HomePanelActions {

    private static final String TAG = "HomePanel";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-panel-actions");
        t.setDaemon(true);
        return t;
    });

    /** Result sink. Called on the action thread, never on the caller's. */
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private HomePanelActions() {}

    public static void invoke(Context context, String action, String ref, Callback cb) {
        EXEC.execute(() -> {
            try {
                switch (action == null ? "" : action) {
                    case "seatApply":
                        seatApply(context, ref, cb);
                        break;
                    case "launchApp":
                        launchApp(context, ref, false, cb);
                        break;
                    case "launchSplit":
                        launchSplit(context, ref, cb);
                        break;
                    default:
                        Log.w(TAG, "unknown action: " + action);
                        cb.onResult(false, context.getString(R.string.home_panel_err_unknown_action));
                }
            } catch (Throwable t) {
                Log.w(TAG, "action " + action + " failed: " + t.getMessage());
                cb.onResult(false, context.getString(R.string.home_panel_err_failed));
            }
        });
    }

    // ==================== seat ====================

    /**
     * Apply a stored seat position. The verdict mirrors the Seat Positions page
     * exactly (assets/web/shared/seat-positions.js): a reply carrying
     * {@code error}, {@code skipped} or {@code needsModelAck} is a failure.
     *
     * <p>No {@code force}, for the reason settled on the car on 2026-08-11: the
     * refusal out of P comes from the VEHICLE, below OverDrive, so forcing buys a
     * doomed write plus the car's own popup.
     *
     * <p>{@code needsModelAck} is reported as a failure pointing at the Seat
     * Positions page rather than acknowledged here. The acknowledgement is a real
     * decision with an explanation attached, and an overlay that cannot take focus
     * is the wrong place to ask for it.
     */
    private static void seatApply(Context context, String id, Callback cb) throws Exception {
        if (id == null || id.trim().isEmpty()) {
            cb.onResult(false, context.getString(R.string.home_panel_err_no_position));
            return;
        }
        JSONObject res = postJson("/api/positions/apply", new JSONObject().put("id", id));
        HomePanelState.invalidateSeatGate();

        if (res == null) {
            cb.onResult(false, context.getString(R.string.home_panel_err_failed));
            return;
        }
        if (res.has("needsModelAck") && res.optBoolean("needsModelAck")) {
            cb.onResult(false, context.getString(R.string.home_panel_err_needs_ack));
            return;
        }
        String error = res.optString("error", "");
        boolean skipped = res.optBoolean("skipped", false)
                || childSkipped(res, "batch1") || childSkipped(res, "batch2");
        if (!error.isEmpty() || skipped) {
            String reason = res.optString("reason", "");
            cb.onResult(false, !reason.isEmpty() ? reason
                    : !error.isEmpty() ? error
                    : context.getString(R.string.home_panel_err_seat_failed));
            return;
        }
        // Success is silent: the cell shows a checkmark. A toast on every seat move
        // would be noise for something you can watch happen.
        cb.onResult(true, null);
    }

    private static boolean childSkipped(JSONObject res, String key) {
        JSONObject child = res.optJSONObject(key);
        return child != null && (child.optBoolean("skipped", false) || child.has("error"));
    }

    // ==================== apps ====================

    private static void launchApp(Context context, String pkg, boolean split, Callback cb) throws Exception {
        if (pkg == null || pkg.trim().isEmpty()) {
            cb.onResult(false, context.getString(R.string.home_panel_err_no_app));
            return;
        }
        JSONObject res = postJson("/api/apps/launch",
                new JSONObject().put("package", pkg.trim()).put("split", split));
        if (res == null || !res.optBoolean("success", false)) {
            String err = res != null ? res.optString("error", "") : "";
            cb.onResult(false, err.isEmpty()
                    ? context.getString(R.string.home_panel_err_app_failed) : err);
            return;
        }
        cb.onResult(true, null);
    }

    /**
     * Launch two apps side by side from one tap.
     *
     * <p>The sequence is: dock the first into split-screen-primary, then start the
     * second normally so it lands in the other pane. Docking one app is
     * field-tested inside {@link com.overdrive.app.launcher.AppLauncher}; the PAIR
     * is new here, and whether the second app reliably lands in the secondary pane
     * on this firmware is UNVERIFIED until it runs on the car. If it turns out to
     * replace the first instead, the fix belongs in AppLauncher (a dockSecondary
     * that starts with windowingMode 4), not in a retry loop here.
     */
    private static void launchSplit(Context context, String ref, Callback cb) throws Exception {
        String[] parts = ref == null ? new String[0] : ref.split("\\+");
        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            cb.onResult(false, context.getString(R.string.home_panel_err_no_pair));
            return;
        }
        JSONObject first = postJson("/api/apps/launch",
                new JSONObject().put("package", parts[0].trim()).put("split", true));
        if (first == null || !first.optBoolean("success", false)) {
            cb.onResult(false, context.getString(R.string.home_panel_err_split_failed));
            return;
        }
        // Give the window manager time to settle the split before the second start;
        // without a pause the second launch can be swallowed by the transition.
        Thread.sleep(700);
        JSONObject second = postJson("/api/apps/launch",
                new JSONObject().put("package", parts[1].trim()).put("split", false));
        if (second == null || !second.optBoolean("success", false)) {
            // The first app IS up and docked, so this is a partial success. Say so
            // rather than claiming failure: the user can see half of what they asked for.
            cb.onResult(false, context.getString(R.string.home_panel_err_split_second));
            return;
        }
        cb.onResult(true, null);
    }

    // ==================== plumbing ====================

    /**
     * POST JSON to the daemon on localhost with auth attached, and parse the reply.
     * Returns null when the request could not be completed at all, which callers
     * distinguish from a reply that says no.
     */
    private static JSONObject postJson(String path, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open(path, "POST", 3000, 12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            InputStreamHolder holder = new InputStreamHolder(conn, code);
            String text = HomePanelState.readAll(holder.stream());
            Log.i(TAG, "POST " + path + " -> " + code);
            if (text == null || text.isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } catch (Throwable t) {
            Log.w(TAG, "POST " + path + " failed: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** Picks the error stream for a non-2xx so a JSON error body is still readable. */
    private static final class InputStreamHolder {
        private final HttpURLConnection conn;
        private final int code;

        InputStreamHolder(HttpURLConnection conn, int code) {
            this.conn = conn;
            this.code = code;
        }

        java.io.InputStream stream() throws Exception {
            if (code >= 200 && code < 300) return conn.getInputStream();
            java.io.InputStream err = conn.getErrorStream();
            return err != null ? err : conn.getInputStream();
        }
    }
}
