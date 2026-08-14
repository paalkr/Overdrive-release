package com.overdrive.app.homepanel;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Day or night for the panel.
 *
 * <p>Resolved the same way {@code StatusOverlayService.themedContext()} resolves it for
 * the pill, so the two floating surfaces never disagree: an explicit light/dark choice in
 * the app's theme setting wins, and "follow system" defers to the head unit's own night
 * mode. The panel needs the ANSWER rather than a themed Context, because its palette
 * lives in CSS, not in Android resources — see the `data-theme="day"` block in
 * assets/web/local/home-panel.html.
 *
 * <p>Night is the fallback on any doubt: this screen is dark most of the time the car is
 * used, and a dark panel on a dark launcher is the less wrong of the two mistakes.
 */
public final class HomePanelTheme {

    public static final String DAY = "day";
    public static final String NIGHT = "night";

    private HomePanelTheme() {}

    /** {@code "day"} or {@code "night"} for the given context's configuration. */
    public static String forContext(Context context) {
        try {
            int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return NIGHT;
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return DAY;
            // Follow-system: the head unit's own day/night state, which is what the
            // native launcher follows too (verified 2026-08-14: its widget row goes
            // light blue-white #E7F4FE with near-black text in daylight).
            int night = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_NO ? DAY : NIGHT;
        } catch (Throwable t) {
            return NIGHT;
        }
    }

    /**
     * Convenience for callers holding no Context. Uses the running overlay service's
     * context when there is one, else reports night.
     */
    public static String current() {
        Context ctx = HomePanelOverlayService.appContext();
        return ctx == null ? NIGHT : forContext(ctx);
    }
}
