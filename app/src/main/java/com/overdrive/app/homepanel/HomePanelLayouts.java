package com.overdrive.app.homepanel;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the active layout out of config and prepares it for the renderer.
 *
 * <p>A layout is a flat list of cells on a {@link HomePanelGeometry#CELL_PX} grid:
 * <pre>
 *   {id, name, cells:[{type, x, y, w, h, ref, label}]}
 * </pre>
 * One cell is one control. Grouping several controls under a shared card is a
 * later feature (a container cell type); on a free canvas "what you place is what
 * you see" is the honest default.
 *
 * <p>Cells carry BOTH a machine ref and a display label, the same split the
 * seat-position automation action uses: the ref is what gets acted on and never
 * changes under you, the label is what the user reads. A renamed seat position or
 * a relabelled app therefore cannot silently repoint a button.
 */
public final class HomePanelLayouts {

    private static final String TAG = "HomePanel";

    /** package name → PNG data URI. Icons don't change while we're running. */
    private static final Map<String, String> ICON_CACHE = new HashMap<>();

    private HomePanelLayouts() {}

    /**
     * The layout to draw: the one named by {@code activeLayoutId}, else the first
     * stored one, else the starter dock. Never null, never throws — a broken
     * layouts array degrades to the starter rather than to a blank screen.
     */
    public static JSONObject activeLayout() {
        try {
            JSONObject cfg = UnifiedConfigManager.getHomePanel();
            JSONArray layouts = cfg.optJSONArray("layouts");
            if (layouts == null || layouts.length() == 0) return starterLayout();

            String wanted = cfg.optString("activeLayoutId", "");
            for (int i = 0; i < layouts.length(); i++) {
                JSONObject l = layouts.optJSONObject(i);
                if (l != null && wanted.equals(l.optString("id"))) return l;
            }
            JSONObject first = layouts.optJSONObject(0);
            return first != null ? first : starterLayout();
        } catch (Throwable t) {
            Log.w(TAG, "activeLayout failed, using starter: " + t.getMessage());
            return starterLayout();
        }
    }

    /**
     * Phase-1 starter: the dock. A low band near the bottom of the free area, so
     * most of the wallpaper survives and the whole thing reads as a second widget
     * row rather than as a dashboard that ate the home screen.
     *
     * <p>Positions assume a 48x18 cell grid (1920x730 free area). On a smaller
     * grid the cells still render; they just crowd the right edge, which is a
     * visible problem rather than a silent one.
     *
     * <p>The seat refs are intentionally the position IDs from
     * {@code GET /api/positions}, which are per-device. A starter layout cannot
     * know them, so they are left empty and the cells report "not configured"
     * when tapped. The designer (phase 2) is what fills these in.
     */
    public static JSONObject starterLayout() {
        try {
            // Two bands. An info shelf on top, right-aligned so it sits over the
            // action tiles below and leaves the left of the wallpaper alone, and the
            // action dock underneath.
            //
            // One blank cell between tiles, two between groups. Cells placed edge to
            // edge look like one pinched strip rather than separate tiles, which is
            // the visual mistake this design is specifically trying to avoid.
            JSONArray cells = new JSONArray();

            // One tile for the whole trip, not one per number: it is read in a single
            // glance, and six separate cards spend most of their area on gaps and
            // repeated labels. Sized to the content — a wider tile just stretches the gaps,
            // which is what made the first version look padded out.
            //
            // No hero: every trip metric is a row, so there is no tall number and no dead
            // area beside it. The eighth column is not slack: the label column is 1fr, so the
            // surplus widens the gap between each label and its number instead of pooling at
            // the edge.
            //
            // On the LEFT, sharing the dock's x=4 edge: this is the tile you read while
            // driving, and on a left-hand-drive car that means the driver's side (Pål,
            // 2026-08-14). It sits above the dock row, so nothing collides.
            //
            // 8 cells TALL, not 7: the type is ~12% larger than the battery tile's (Pål's
            // call) and at 7 the grid squeezed the "Aktiv tur" header to an 8px line box —
            // measured, and a cropped header is worse than a smaller number. y=4 rather than
            // 5 keeps a one-cell gap above the dock instead of the two tiles touching.
            cells.put(cellMetrics("metrics", 4, 4, 8, 8, "", "", "Aktiv tur")
                    .put("hero", false));

            // Action dock along the bottom, spanning 4..45: content is 34 cells plus 7 of
            // gap, and the wallpaper keeps the margins either side.
            //
            // Battery on the LEFT, seat positions on the RIGHT — swapped 2026-08-14 (Pål).
            // Widths differ (10 vs 13), so the coordinates are recomputed to keep BOTH the
            // dock's left edge at 4 and its right edge at 45, with the same gap sequence
            // (2, 2, 1, 2) it had before the swap.
            int y = 13, h = 4;
            // Seat positions share one surface for the same reason. `items` is filled
            // on first enable from the car's own stored positions (HomePanelSeed).
            cells.put(cellWithItems("seatGroup", 32, y, 13, h, new JSONArray()));
            // 5 cells, not 9: sized to a divided frame with the label under it. At 9 the
            // content sat in a box twice its size. The separator is a pipe because the
            // frame itself is divided by one.
            cells.put(cell("splitPair", 16, y, 5, h,
                    "com.telenav.app.arp+no.stink.byddab", "Telenav | DAB++"));
            cells.put(cell("app", 23, y, 3, h, "no.stink.byddab", "DAB++"));
            cells.put(cell("app", 27, y, 3, h, "com.telenav.app.arp", "Telenav"));
            // Charge and BOTH ranges on one surface: the car's own figure next to the one
            // derived from recent real consumption, which is the comparison worth having.
            // Now the leftmost dock tile, under the trip tile it shares an x=4 edge with.
            // A `metrics` cell, same hero-and-list renderer as the trip tile — the hero
            // steps down automatically for a short value. 10 cells is measured, not
            // guessed: the content runs 372px typically and ~400px at 100% with
            // three-digit ranges, against 400px of tile.
            cells.put(cellMetrics("metrics", 4, y, 10, h,
                    "soc,range,range.recent", "Batteri", "Rekkevidde"));

            return new JSONObject()
                    .put("id", "daily")
                    .put("name", "Daglig")
                    .put("cells", cells);
        } catch (Throwable t) {
            // Nothing sensible left to do; an empty layout renders the "nothing
            // placed yet" state, which is at least truthful.
            return new JSONObject();
        }
    }

    private static JSONObject cell(String type, int x, int y, int w, int h, String ref, String label)
            throws org.json.JSONException {
        return new JSONObject()
                .put("type", type).put("x", x).put("y", y).put("w", w).put("h", h)
                .put("ref", ref).put("label", label);
    }

    /**
     * A metrics cell: hero plus a labelled list. {@code listLabel} names the right-hand
     * column, so both sides of the rule carry a header.
     */
    private static JSONObject cellMetrics(String type, int x, int y, int w, int h,
                                          String ref, String label, String listLabel)
            throws org.json.JSONException {
        return cell(type, x, y, w, h, ref, label).put("listLabel", listLabel);
    }

    /** A container cell: several controls on one surface, listed in {@code items}. */
    private static JSONObject cellWithItems(String type, int x, int y, int w, int h, JSONArray items)
            throws org.json.JSONException {
        return new JSONObject()
                .put("type", type).put("x", x).put("y", y).put("w", w).put("h", h)
                .put("items", items);
    }

    /**
     * Add what only the app process can supply: real app icons and, where the
     * layout has no label, the app's own name. Everything else the renderer gets
     * verbatim from config.
     *
     * <p>Failures are per-cell and silent by design: a missing app should cost you
     * one grey tile, not the whole panel.
     */
    public static JSONObject enrich(Context context, JSONObject layout) {
        JSONArray cells = layout.optJSONArray("cells");
        if (cells == null) return layout;

        for (int i = 0; i < cells.length(); i++) {
            JSONObject c = cells.optJSONObject(i);
            if (c == null) continue;
            String type = c.optString("type");
            try {
                if ("app".equals(type)) {
                    String pkg = c.optString("ref");
                    String icon = iconDataUri(context, pkg);
                    if (icon != null) c.put("icon", icon);
                    if (c.optString("label").isEmpty()) {
                        String label = appLabel(context, pkg);
                        if (label != null) c.put("label", label);
                    }
                } else if ("splitPair".equals(type)) {
                    JSONArray icons = new JSONArray();
                    for (String pkg : c.optString("ref").split("\\+")) {
                        String icon = iconDataUri(context, pkg.trim());
                        icons.put(icon == null ? JSONObject.NULL : icon);
                    }
                    c.put("icons", icons);
                }
            } catch (Throwable t) {
                Log.w(TAG, "enrich cell " + i + " (" + type + ") failed: " + t.getMessage());
            }
        }
        return layout;
    }

    private static String appLabel(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The launcher icon as a base64 PNG data URI, so the WebView needs no file or
     * network access to draw it. Capped at 96px: these render at 44-46px, and a
     * data URI per cell is embedded in the JSON we hand to JavaScript.
     */
    private static String iconDataUri(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String cached = ICON_CACHE.get(pkg);
        if (cached != null) return cached;

        try {
            Drawable d = context.getPackageManager().getApplicationIcon(pkg);
            int size = 96;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
            bmp.recycle();

            String uri = "data:image/png;base64,"
                    + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
            ICON_CACHE.put(pkg, uri);
            return uri;
        } catch (Throwable t) {
            // Not installed, or an icon we can't rasterise. The renderer falls back
            // to its own glyph.
            return null;
        }
    }

    /** Drop cached icons, e.g. after an app install or uninstall. */
    public static void clearIconCache() {
        ICON_CACHE.clear();
    }
}
