package com.overdrive.app.homepanel;

import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time setup of a usable layout, so enabling the dashboard produces something
 * that works rather than a row of buttons wired to nothing.
 *
 * <p>The starter layout cannot name seat positions: their ids are per-device and per
 * signed-in profile, so they only exist on the car. This fills them in from
 * {@code GET /api/positions} the first time the feature is switched on, writing ids
 * AND names into the layout document — the same id-stored / name-shown split the
 * seat-position automation action uses, so renaming a position later cannot silently
 * repoint a button.
 *
 * <p>Runs exactly once: it only touches a config that has no layouts yet. After that
 * the document is the user's, editable by hand today and by the designer later, and
 * this never overwrites it.
 */
public final class HomePanelSeed {

    private static final String TAG = "HomePanel";

    /**
     * How many buttons the starter seat group gets. Three, to mirror the car's own
     * Pos 1/2/3 — and three buttons on a 13-cell tile stay wide enough to read.
     */
    private static final int SEAT_BUTTONS = 3;

    private static volatile boolean attemptedThisProcess = false;
    private static volatile boolean migratedThisProcess = false;

    private HomePanelSeed() {}

    /**
     * Write the starter layout into config if there is none yet. Blocking HTTP, so
     * call it off the main thread.
     *
     * @return true if a layout was written
     */
    /**
     * Current layout-document schema. Bumped when a shipped layout needs correcting in
     * place, since the seeder only ever writes a layout when there is none and would
     * otherwise leave a bad one alone forever.
     */
    /*
     * History, because the lesson cost a debugging session:
     *  - 5, not 4: schema 4 shipped and stamped configs BEFORE the battery cell's new geometry
     *    was added to DOCK_BY_SCHEMA, so those configs claimed to be current while still
     *    carrying the old 6-wide glance battery. Editing an already-shipped schema's CONTENTS
     *    is the mistake; the number has to move with them. Verified on the car 2026-08-14: the
     *    stored document read schema=4 with the glance cell still at 39,13.
     *  - 6: trip tile moved to the driver's side (see the migration below).
     *  - 7: battery and seat-group swapped ends of the dock; trip tile one cell taller for
     *    its larger type.
     */
    private static final int SCHEMA = 7;

    /** Package names that shipped wrong, and what they should have been. */
    private static final String[][] PACKAGE_FIXES = {
        // Guessed rather than looked up, and no such package exists: the DAB++ tile and
        // the split-screen pair both tried to open nothing. The real id comes from the
        // byd-dab repo's applicationId. Found on the car 2026-08-13.
        {"com.overdrive.dab", "no.stink.byddab"},
    };

    /**
     * Repair layouts written by an older build. Runs before seeding, and only rewrites
     * package references that are known to have been wrong — it never touches
     * positions, sizes or anything the user may have arranged.
     *
     * @return true if the stored config was changed
     */
    public static boolean migrate() {
        // Two onStartCommand calls at startup each spawned a seed thread, both read the schema
        // before either wrote, and the second rewrote the document redundantly. Harmless but
        // noisy in the log and a real race if a future migration is not idempotent.
        if (migratedThisProcess) return false;
        try {
            JSONObject cfg = UnifiedConfigManager.getHomePanel();
            if (cfg.optInt("schema", 1) >= SCHEMA) {
                migratedThisProcess = true;
                return false;
            }
            JSONArray layouts = cfg.optJSONArray("layouts");

            int fixed = 0;
            if (layouts != null) {
                for (int i = 0; i < layouts.length(); i++) {
                    JSONObject layout = layouts.optJSONObject(i);
                    JSONArray cells = layout == null ? null : layout.optJSONArray("cells");
                    if (cells == null) continue;
                    for (int c = 0; c < cells.length(); c++) {
                        JSONObject cell = cells.optJSONObject(c);
                        if (cell == null) continue;
                        if ("tripSummary".equals(cell.optString("type"))
                                || ("metrics".equals(cell.optString("type"))
                                    && cell.optString("ref", "").isEmpty())) {
                            cell.put("type", "metrics");
                            if (cell.optString("listLabel", "").isEmpty()) {
                                cell.put("listLabel", "Aktiv tur");
                                fixed++;
                            }
                            // The trip tile lost its hero: every metric is a row now, so the
                            // tile is 7x7 instead of 13x6. Moved only from the one shipped
                            // position, same rule as the dock.
                            if (!cell.has("hero")
                                    && cell.optInt("x", -1) == 32 && cell.optInt("y", -1) == 6
                                    && cell.optInt("w", -1) == 13 && cell.optInt("h", -1) == 6) {
                                cell.put("hero", false)
                                    .put("x", TRIP_X).put("y", 5).put("w", 8).put("h", 7);
                                fixed++;
                            }
                            // Schema 6: moved from the right edge to the driver's side, where
                            // it can be read while driving (Pål, 2026-08-14 — this is a
                            // left-hand-drive car, and it is his call until the configurator
                            // exists). Left edge shared with the dock's seat group at x=4 so
                            // the two line up. Same pristine rule as everything else: only a
                            // tile still at the one shipped position is moved.
                            if (cell.optInt("x", -1) == 37 && cell.optInt("y", -1) == 5
                                    && cell.optInt("w", -1) == 8 && cell.optInt("h", -1) == 7) {
                                cell.put("x", TRIP_X);
                                fixed++;
                            }
                            // Schema 7: one cell taller, one cell higher. The trip tile's type
                            // grew ~12% (Pål, 2026-08-14) and at h=7 the grid squeezed the
                            // "Aktiv tur" header down to an 8px line box — measured in the
                            // renderer, not guessed. y=4 keeps a one-cell gap above the dock.
                            // Runs after the rule above, so a tile arriving from either older
                            // shipped position lands here in the same pass.
                            if (cell.optInt("x", -1) == TRIP_X && cell.optInt("y", -1) == 5
                                    && cell.optInt("w", -1) == 8 && cell.optInt("h", -1) == 7) {
                                cell.put("y", 4).put("h", 8);
                                fixed++;
                            }
                        }
                        String ref = cell.optString("ref", "");
                        if (ref.isEmpty()) continue;
                        String updated = ref;
                        for (String[] fix : PACKAGE_FIXES) {
                            updated = updated.replace(fix[0], fix[1]);
                        }
                        if (!updated.equals(ref)) {
                            cell.put("ref", updated);
                            fixed++;
                        }
                        // The trip tile gained a header over its right-hand column and the
                        // generic type name. Additive and display-only, so it applies
                        // regardless of whether the layout has been rearranged.
                        // The split tile's separator is a pipe now, matching the divider
                        // drawn between the two panes. Only rewritten for splitPair, so a
                        // user label containing a plus elsewhere is untouched.
                        if ("splitPair".equals(cell.optString("type"))) {
                            String label = cell.optString("label", "");
                            if (label.contains(" + ")) {
                                cell.put("label", label.replace(" + ", " | "));
                                fixed++;
                            }
                        }
                    }
                }
            }

            // Schema 3: the split tile became a divided frame with the label beneath, so
            // it is 5 cells wide instead of 9 and the dock re-spaced around it. Geometry
            // is only rewritten for a layout still sitting at the shipped starter
            // coordinates — i.e. one nobody has rearranged. A layout that differs
            // anywhere is left completely alone, because guessing at someone's
            // arrangement is worse than one badly-proportioned tile.
            int respaced = 0;
            if (layouts != null && cfg.optInt("schema", 1) < SCHEMA) {
                for (int i = 0; i < layouts.length(); i++) {
                    JSONObject layout = layouts.optJSONObject(i);
                    if (layout == null) continue;
                    if (respaceIfPristine(layout.optJSONArray("cells"))) respaced++;
                }
            }
            JSONObject write = new JSONObject().put("schema", SCHEMA);
            if ((fixed > 0 || respaced > 0) && layouts != null) write.put("layouts", layouts);
            boolean ok = UnifiedConfigManager.setHomePanel(write);
            Log.i(TAG, "migrated layout document to schema " + SCHEMA
                    + " (" + fixed + " package refs corrected, " + respaced
                    + " layouts re-spaced, written: " + ok + ")");
            if (ok) migratedThisProcess = true;
            return (fixed > 0 || respaced > 0) && ok;
        } catch (Throwable t) {
            Log.w(TAG, "migrate failed: " + t.getMessage());
            return false;
        }
    }

    public static boolean ensureSeeded() {
        try {
            JSONObject cfg = UnifiedConfigManager.getHomePanel();
            JSONArray existing = cfg.optJSONArray("layouts");
            if (existing != null && existing.length() > 0) return false;
            if (attemptedThisProcess) return false;
            attemptedThisProcess = true;

            JSONObject layout = HomePanelLayouts.starterLayout();
            int filled = fillSeatCells(layout);

            JSONArray layouts = new JSONArray();
            layouts.put(layout);
            boolean ok = UnifiedConfigManager.setHomePanel(new JSONObject()
                    .put("layouts", layouts)
                    .put("activeLayoutId", layout.optString("id", "daily"))
                    .put("schema", SCHEMA));
            Log.i(TAG, "seeded starter layout (seat cells filled: " + filled + ", written: " + ok + ")");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "seed failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Dock geometry per schema, in layout order (seatGroup, splitPair, app, app, battery),
     * as {x, y, w, h}. A layout is only moved when it still matches its era's row exactly;
     * see {@link #respaceIfPristine}.
     */
    private static final int[][][] DOCK_BY_SCHEMA = {
        // schema <= 2: split tile was 9 wide, battery a 6-wide glance
        {{3, 13, 13, 4}, {19, 13, 9, 4}, {30, 13, 3, 4}, {34, 13, 3, 4}, {39, 13, 6, 4}},
        // schema 3: split tile cut to 5, dock right-anchored from x8
        {{8, 13, 13, 4}, {23, 13, 5, 4}, {30, 13, 3, 4}, {34, 13, 3, 4}, {39, 13, 6, 4}},
        // schema 4: battery becomes a 10-wide metrics cell carrying both ranges
        {{4, 13, 13, 4}, {19, 13, 5, 4}, {26, 13, 3, 4}, {30, 13, 3, 4}, {35, 13, 10, 4}},
        // schema 7: battery and seat group swap ends. Rows are in CELLS-ARRAY order
        // (seatGroup, splitPair, app, app, battery), not left-to-right screen order, because
        // respaceIfPristine() matches positionally against the stored array — so the seat
        // group's entry is the x=32 one and the battery's is x=4. Both dock edges (4 and 45)
        // and the gap sequence (2, 2, 1, 2) are preserved across the swap.
        {{32, 13, 13, 4}, {16, 13, 5, 4}, {23, 13, 3, 4}, {27, 13, 3, 4}, {4, 13, 10, 4}},
    };
    /** The current row: the last entry above. */
    private static final int[][] NEW_DOCK = DOCK_BY_SCHEMA[DOCK_BY_SCHEMA.length - 1];

    /** Every shipped era puts the dock on this row; used to tell dock cells from others. */
    private static final int DOCK_ROW_Y = 13;

    /** Trip tile's left edge: the dock's own left edge, on the driver's side. */
    private static final int TRIP_X = 4;

    /**
     * Move the dock cells to the schema-3 coordinates, but only if every one of them is
     * still exactly where the old starter put it. Returns false without touching anything
     * otherwise.
     */
    private static boolean respaceIfPristine(JSONArray cells) {
        if (cells == null) return false;
        List<JSONObject> dock = new ArrayList<>();
        for (int i = 0; i < cells.length(); i++) {
            JSONObject c = cells.optJSONObject(i);
            if (c == null) continue;
            String t = c.optString("type");
            // Restricted to the dock row. The trip tile is also a "metrics" cell but sits
            // on the row above, so without the y test it joined the dock list, made it one
            // longer than NEW_DOCK, and the size check below then refused to respace
            // anything at all — which is why the battery cell stayed a glance on the car
            // (2026-08-14). A dock cell the user has moved off row 13 legitimately drops
            // out here, and the same size check then leaves the layout alone.
            if (c.optInt("y", -1) != DOCK_ROW_Y) continue;
            if ("seatGroup".equals(t) || "splitPair".equals(t) || "app".equals(t)
                    || "glance".equals(t) || "metrics".equals(t)) {
                dock.add(c);
            }
        }
        if (dock.size() != NEW_DOCK.length) return false;

        // Match against every shipped era, not just the previous one, so a layout that
        // skipped a version still gets moved instead of being treated as user-arranged.
        int era = -1;
        for (int e = 0; e < DOCK_BY_SCHEMA.length && era < 0; e++) {
            boolean all = true;
            for (int i = 0; i < dock.size() && all; i++) {
                JSONObject c = dock.get(i);
                int[] o = DOCK_BY_SCHEMA[e][i];
                all = c.optInt("x", -1) == o[0] && c.optInt("y", -1) == o[1]
                        && c.optInt("w", -1) == o[2] && c.optInt("h", -1) == o[3];
            }
            if (all) era = e;
        }
        if (era < 0) {
            Log.i(TAG, "layout has been rearranged; leaving its geometry alone");
            return false;
        }
        if (era == DOCK_BY_SCHEMA.length - 1) return false;   // already current

        try {
            for (int i = 0; i < dock.size(); i++) {
                int[] n = NEW_DOCK[i];
                dock.get(i).put("x", n[0]).put("y", n[1]).put("w", n[2]).put("h", n[3]);
            }
            // The battery cell also changes type and gains a ref list at schema 4.
            JSONObject battery = dock.get(dock.size() - 1);
            if ("glance".equals(battery.optString("type"))) {
                battery.put("type", "metrics")
                       .put("ref", "soc,range,range.recent")
                       .put("listLabel", "Rekkevidde");
            }
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /**
     * Fill the seat group's buttons from the car's stored positions, in the order the
     * daemon lists them. Both id and display name are written, the same id-stored /
     * name-shown split the seat automation action uses, so renaming a position later
     * cannot silently repoint a button.
     *
     * <p>A group that gets nothing shows "no positions selected" rather than three
     * dead buttons, which is a more honest first run.
     */
    private static int fillSeatCells(JSONObject layout) {
        JSONArray positions = orderForSeeding(fetchList());
        if (positions == null || positions.length() == 0) return 0;

        JSONArray cells = layout.optJSONArray("cells");
        if (cells == null) return 0;

        int filled = 0;
        for (int i = 0; i < cells.length(); i++) {
            JSONObject c = cells.optJSONObject(i);
            if (c == null) continue;
            String type = c.optString("type");

            if ("seatGroup".equals(type)) {
                JSONArray items = c.optJSONArray("items");
                if (items != null && items.length() > 0) continue;   // user-configured
                JSONArray built = new JSONArray();
                for (int p = 0; p < positions.length() && built.length() < SEAT_BUTTONS; p++) {
                    JSONObject pos = positions.optJSONObject(p);
                    if (pos == null) continue;
                    String id = pos.optString("id", "");
                    if (id.isEmpty()) continue;
                    try {
                        built.put(new JSONObject()
                                .put("ref", id)
                                .put("label", shortLabel(pos.optString("name", ""), p)));
                    } catch (Throwable ignored) {
                    }
                }
                if (built.length() == 0) continue;
                try {
                    c.put("items", built);
                    filled += built.length();
                } catch (Throwable ignored) {
                }
            } else if ("seatProfile".equals(type) && c.optString("ref").isEmpty()) {
                // Single-position tiles are still a supported type; fill them too.
                JSONObject pos = positions.optJSONObject(filled);
                if (pos == null) continue;
                String id = pos.optString("id", "");
                if (id.isEmpty()) continue;
                try {
                    c.put("ref", id);
                    c.put("label", shortLabel(pos.optString("name", ""), filled));
                    filled++;
                } catch (Throwable ignored) {
                }
            }
        }
        return filled;
    }

    /**
     * A tile-sized label. The stored names are long by construction on this car
     * ("&lt;account&gt; - Posisjon 2", built from the BYD profile provider), and a
     * button 120 px wide can show about a dozen characters. Falls back to the tail of
     * the stored name, then to a number.
     */
    /**
     * Package-visible so {@link HomePanelState} can shorten the SAME way when it refreshes
     * labels from the store — two different shortenings would make a button's text change
     * shape a few seconds after the panel appeared.
     */
    /**
     * Longest label a seat button shows in full. The button is ~173px wide at three buttons on
     * a 13-cell tile; the CSS ellipsis is the real backstop, this just keeps the string sane.
     */
    private static final int MAX_LABEL_CHARS = 14;

    private static String clip(String s) {
        return s.length() <= MAX_LABEL_CHARS ? s : s.substring(0, MAX_LABEL_CHARS - 1).trim() + "…";
    }

    static String shortLabel(String storedName, int index) {
        String name = storedName == null ? "" : storedName.trim();
        if (name.isEmpty()) return "Posisjon " + (index + 1);
        if (name.length() <= MAX_LABEL_CHARS) return name;
        // The part after " - " is the position's own name; everything before it is the DiLink
        // profile ("paa*****@gmail.com - Posisjon 2"). Prefer that tail, and truncate the TAIL
        // when it is still too long. The previous version only used the tail if it already
        // fitted and otherwise truncated the whole string from the left, which showed the
        // masked email and hid the only meaningful part: "… - Vinter/kald sete" rendered as
        // "paa*****@gmai…". Caught before shipping while checking what Pål's planned aliases
        // for the car's own slots would look like (2026-08-14).
        int dash = name.lastIndexOf(" - ");
        if (dash >= 0 && dash + 3 < name.length()) {
            String tail = name.substring(dash + 3).trim();
            if (!tail.isEmpty()) return clip(tail);
        }
        return clip(name);
    }

    /**
     * Display name for a stored position, or null when the store has nothing usable to show.
     * Unlike {@link #shortLabel(String, int)} this never invents "Posisjon N": a refresh with
     * no name must leave the label the layout already carries, not overwrite it with a guess.
     */
    static String displayNameOrNull(String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return null;
        return shortLabel(storedName, 0);
    }

    /**
     * The car's Pos 1/2/3 for the signed-in DiLink profile, in slot order, ahead of
     * anything else.
     *
     * <p>The store holds captured entries keyed by profile ({@code source:"captured"},
     * {@code profile}, {@code slot}) alongside freely-named user entries. A car with
     * two drivers therefore has two sets of slot 1, and seeding "the first three in
     * the list" would mix them. So: the signed-in profile's captured slots first in
     * slot order, then that profile's user-created entries, then everything else, and
     * only then does the caller take as many as fit.
     *
     * <p>When the profile cannot be resolved, falls back to whichever profile has the
     * most captured slots rather than guessing at the account name.
     */
    private static JSONArray orderForSeeding(JSONObject list) {
        if (list == null) return null;
        JSONArray positions = list.optJSONArray("positions");
        if (positions == null || positions.length() == 0) return null;

        String profile = list.isNull("currentProfile") ? null : list.optString("currentProfile", null);
        if (profile == null || profile.isEmpty()) profile = profileWithMostCaptures(positions);
        Log.i(TAG, "seeding seat buttons for profile: " + profile);

        List<JSONObject> mine = new ArrayList<>();
        List<JSONObject> mineUser = new ArrayList<>();
        List<JSONObject> rest = new ArrayList<>();
        for (int i = 0; i < positions.length(); i++) {
            JSONObject p = positions.optJSONObject(i);
            if (p == null) continue;
            boolean captured = "captured".equals(p.optString("source"));
            boolean sameProfile = profile != null && profile.equals(p.optString("profile", null));
            if (captured && sameProfile) mine.add(p);
            else if (sameProfile) mineUser.add(p);
            else rest.add(p);
        }
        // Slot order, so the buttons read 1, 2, 3 like the car's own UI.
        Collections.sort(mine, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Integer.compare(a.optInt("slot", 99), b.optInt("slot", 99));
            }
        });

        JSONArray ordered = new JSONArray();
        for (JSONObject p : mine) ordered.put(p);
        for (JSONObject p : mineUser) ordered.put(p);
        for (JSONObject p : rest) ordered.put(p);
        return ordered;
    }

    private static String profileWithMostCaptures(JSONArray positions) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < positions.length(); i++) {
            JSONObject p = positions.optJSONObject(i);
            if (p == null || !"captured".equals(p.optString("source"))) continue;
            String prof = p.optString("profile", "");
            if (prof.isEmpty()) continue;
            Integer c = counts.get(prof);
            counts.put(prof, c == null ? 1 : c + 1);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        return best;
    }

    private static JSONObject fetchList() {
        HttpURLConnection conn = null;
        try {
            // withProfile=1: this is the one caller that needs to know whose slots
            // these are, and it runs once.
            conn = DaemonHttpClient.open("/api/positions?withProfile=1", "GET", 2500, 6000);
            if (conn.getResponseCode() != 200) return null;
            return new JSONObject(HomePanelState.readAll(conn.getInputStream()));
        } catch (Throwable t) {
            Log.w(TAG, "seed could not read positions: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }
}
