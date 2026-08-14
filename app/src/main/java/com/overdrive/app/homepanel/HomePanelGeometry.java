package com.overdrive.app.homepanel;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.overdrive.app.config.UnifiedConfigManager;

/**
 * Where on the screen the home dashboard is allowed to draw, and how big a grid
 * cell is.
 *
 * <p>The panel fills the strip the stock launcher leaves as wallpaper: below the
 * system status bar, above the launcher's own widget row. Those two insets are
 * NOT queryable — the widget row belongs to the launcher, not to us, and an
 * overlay window has no way to ask where another app put its content. So they are
 * measured constants with a config override.
 *
 * <p>The reference numbers come from a screenshot of the stock launcher on the
 * Seal's 1920x1080 landscape head unit: the status bar ends at y=85, the widget
 * row starts at y=815 (so 265px from the bottom). On a display of a different
 * size they are scaled proportionally, which is a guess, not a measurement —
 * hence {@code homePanel.insetTop} / {@code homePanel.insetBottom} to correct it
 * without a rebuild.
 */
public final class HomePanelGeometry {

    /** Grid unit. MUST match {@code --cell} in assets/web/local/home-panel.html. */
    public static final int CELL_PX = 40;

    private static final int REF_WIDTH = 1920;
    private static final int REF_HEIGHT = 1080;
    private static final int REF_INSET_TOP = 85;
    private static final int REF_INSET_BOTTOM = 265;

    /** Smallest strip worth drawing into: two grid cells. Below this we stay hidden. */
    private static final int MIN_HEIGHT = CELL_PX * 2;

    private HomePanelGeometry() {}

    /**
     * The free area in display pixels, or an empty rect if the display is too
     * small (or so oddly shaped) that the insets leave nothing usable. Callers
     * must treat an empty rect as "do not attach the window".
     */
    public static Rect freeArea(Context context) {
        DisplayMetrics dm = displayMetrics(context);
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        int top = inset("insetTop", REF_INSET_TOP, h);
        int bottom = inset("insetBottom", REF_INSET_BOTTOM, h);

        int height = h - top - bottom;
        if (w <= 0 || height < MIN_HEIGHT) return new Rect();
        return new Rect(0, top, w, top + height);
    }

    /** Grid size of the free area, in whole cells. */
    public static int columns(Context context) {
        return freeArea(context).width() / CELL_PX;
    }

    public static int rows(Context context) {
        return freeArea(context).height() / CELL_PX;
    }

    /**
     * Config override first, otherwise the reference inset scaled by how far this
     * display's height differs from the reference. Scaling by height for both
     * insets is deliberate: both are horizontal bars, so their thickness tracks
     * vertical resolution, not width.
     */
    private static int inset(String key, int reference, int displayHeight) {
        int configured = UnifiedConfigManager.getHomePanel().optInt(key, -1);
        if (configured >= 0) return configured;
        if (displayHeight == REF_HEIGHT) return reference;
        return Math.round(reference * (displayHeight / (float) REF_HEIGHT));
    }

    private static DisplayMetrics displayMetrics(Context context) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            // getRealMetrics, not getMetrics: we want the whole panel including the
            // area under the system bars, because our y-offset is measured from the
            // physical top of the screen.
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        if (dm.widthPixels == 0) {
            dm = context.getResources().getDisplayMetrics();
        }
        return dm;
    }

    /** True when the display matches the head unit this was measured on. */
    public static boolean isReferenceDisplay(Context context) {
        DisplayMetrics dm = displayMetrics(context);
        return dm.widthPixels == REF_WIDTH && dm.heightPixels == REF_HEIGHT;
    }
}
