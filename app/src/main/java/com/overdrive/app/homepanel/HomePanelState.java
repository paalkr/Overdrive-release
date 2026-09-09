package com.overdrive.app.homepanel;

import android.util.Log;

import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.DrivingRangeData;
import com.overdrive.app.monitor.VehicleDataMonitor;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * The live values a layout needs: whatever the {@code glance} cells ask for, plus
 * the seat gate so seat cells can look unavailable instead of failing on tap.
 *
 * <p>Reads go straight to the in-process monitors — the panel lives in the app
 * process, and {@link VehicleDataMonitor} is already there. A value the monitor
 * does not have comes back as JSON null with {@code stale:true} rather than as a
 * zero: a glance tile showing "0 %" when the truth is "not known yet" is worse
 * than a dash.
 */
public final class HomePanelState {

    private static final String TAG = "HomePanel";

    /** How long a seat-gate read stays good enough to reuse. */
    private static final long SEAT_GATE_TTL_MS = 4000;

    private static volatile JSONObject cachedSeatGate;
    private static volatile long cachedSeatGateAt;

    private HomePanelState() {}

    /**
     * {@code {fields:{soc:{value,unit,sub,stale}, ...}, seat:{acc,movementBlocked,known}}}
     *
     * <p>Never throws: the renderer keeps its last good values when a read fails,
     * so a monitor blip must not become an exception crossing the JS bridge.
     */
    public static JSONObject snapshot() {
        JSONObject state = new JSONObject();
        JSONObject fields = new JSONObject();
        try {
            // Vehicle basics come from the DAEMON's aggregate, not from
            // VehicleDataMonitor directly: that monitor only holds data in the daemon
            // process, and the panel runs in the app process, where it is an empty
            // instance that answers null to everything. /api/launcher/v1/summary exists
            // precisely so a separate UI can read vehicle state over localhost, and the
            // panel is exactly that consumer.
            JSONObject summary = launcherSummary();
            Integer socPct = null;
            Integer elecKm = null;
            if (summary != null) {
                JSONObject battery = summary.optJSONObject("battery");
                JSONObject range = summary.optJSONObject("range");
                if (battery != null && !battery.isNull("socPct")) socPct = battery.optInt("socPct");
                if (range != null && !range.isNull("elecKm")) elecKm = range.optInt("elecKm");
            }

            // The SoC tile carries range as its subtitle when both are known: that is
            // the pairing the driver actually reads, and it saves a whole cell.
            String socSub = elecKm != null ? (elecKm + " km") : "";
            boolean vehicleStale = summaryIsStale();
            fields.put("soc", field(socPct, "%", socSub, vehicleStale));
            fields.put("range", field(elecKm, "km", "", vehicleStale));

            addTripFields(fields);

            state.put("fields", fields);
            state.put("seat", seatGate());
            state.put("theme", HomePanelTheme.current());
        } catch (Throwable t) {
            Log.w(TAG, "state snapshot failed: " + t.getMessage());
            try { state.put("fields", fields); } catch (Throwable ignored) {}
        }
        return state;
    }

    private static Integer round(double d) {
        if (Double.isNaN(d)) return null;
        return (int) Math.round(d);
    }

    // ==================== trip fields ====================

    /** How long a live-trip read stays good enough to reuse. */
    private static final long TRIP_TTL_MS = 3000;

    private static volatile JSONObject cachedTrip;
    private static volatile long cachedTripAt;
    private static volatile long lastTripAttemptAt;

    /**
     * "So far this trip" values plus the recent real-world consumption, from
     * {@code GET /api/trips/current}. The trip system lives in the daemon, so this
     * is an HTTP read, cached briefly — the panel polls every 5s and several cells
     * can read the same snapshot.
     *
     * <p>Formatting happens here rather than in the renderer because the units and
     * the sensible number of decimals belong to the value, not to the layout. Every
     * one of these is null-if-unknown, never zero-if-unknown.
     */
    private static void addTripFields(JSONObject fields) {
        JSONObject trip = tripSnapshot();
        boolean active = trip != null && trip.optBoolean("active", false);

        Double consumption = optDouble(trip, "consumptionKwhPer100Km");
        Double distance = active ? optDouble(trip, "distanceKm") : null;
        Double avgSpeed = active ? optDouble(trip, "avgSpeedKmh") : null;
        Double maxSpeed = active ? optDouble(trip, "maxSpeedKmh") : null;
        Double energy = active ? optDouble(trip, "energyUsedKwh") : null;
        long durationSec = trip != null ? trip.optLong("durationSeconds", 0) : 0;

        String distanceSub = durationSec > 0 ? hhmm(durationSec) : "";
        try {
            // Consumption so far. The subtitle carries what it was computed from, so
            // a number that looks wrong can be checked rather than just distrusted.
            String consSub = "";
            if (energy != null && distance != null) {
                consSub = one(energy) + " kWh · " + one(distance) + " km";
            }
            // One flag for the whole block: these all come from the same snapshot, so they are
            // stale together or not at all.
            boolean tripStale = tripIsStale();
            fields.put("trip.consumption",
                    field(consumption == null ? null : one(consumption), "kWh/100km", consSub, tripStale));

            fields.put("trip.distance",
                    field(distance == null ? null : one(distance), "km", distanceSub, tripStale));

            // Energy consumed so far, in kWh. This used to ride along as the consumption
            // tile's subtitle and fell off the panel when that tile became a hero layout,
            // which renders no subtitle. It is a number worth its own row: the kWh is what
            // you pay for, the kWh/100km is only how it is spread over the distance.
            fields.put("trip.energy",
                    field(energy == null ? null : one(energy), "kWh", "", tripStale));

            // Two shapes of the same pair, because both tiles want it differently: the
            // standalone glance carries max in its subtitle, the trip summary gives max
            // its own slot next to average.
            fields.put("trip.speed",
                    field(avgSpeed == null ? null : (Object) Math.round(avgSpeed), "km/h",
                            maxSpeed == null ? "" : ("maks " + Math.round(maxSpeed) + " km/h"), tripStale));
            fields.put("trip.maxSpeed",
                    field(maxSpeed == null ? null : (Object) Math.round(maxSpeed), "km/h", "", tripStale));

            fields.put("trip.duration",
                    field(durationSec > 0 ? hhmm(durationSec) : null, "", "", tripStale));

            Double recent = optDouble(trip, "recentConsumptionKwhPer100Km");
            double window = trip != null ? trip.optDouble("recentConsumptionWindowKm", 50) : 50;
            fields.put("recent.consumption",
                    field(recent == null ? null : one(recent), "kWh/100km",
                            "siste " + Math.round(window) + " km"));

            addRangeField(fields, recent);
        } catch (Throwable t) {
            Log.w(TAG, "trip fields failed: " + t.getMessage());
        }
    }

    /** How long the vehicle aggregate stays good enough to reuse. */
    private static final long SUMMARY_TTL_MS = 4000;

    /**
     * How long a last-good reading is still shown (marked stale) after reads start failing.
     * Past this, values fall back to dashes — a number this old is worse than no number.
     */
    private static final long STALE_GRACE_MS = 60_000;

    private static volatile JSONObject cachedSummary;
    private static volatile long cachedSummaryAt;
    private static volatile long lastSummaryAttemptAt;

    private static JSONObject launcherSummary() {
        long now = System.currentTimeMillis();
        JSONObject cached = cachedSummary;
        if (cached != null && now - cachedSummaryAt < SUMMARY_TTL_MS) return cached;
        if (now - lastSummaryAttemptAt < SUMMARY_TTL_MS) return cached;   // back off; may be stale
        lastSummaryAttemptAt = now;

        JSONObject out = null;
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/launcher/v1/summary", "GET", 2000, 4000);
            if (conn.getResponseCode() == 200) {
                out = new JSONObject(readAll(conn.getInputStream()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "summary read failed: " + t.getMessage());
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        if (out != null) {
            cachedSummary = out;
            cachedSummaryAt = now;
            return out;
        }
        // See tripSnapshot(): serve the last good reading, keep the retry backoff separate.
        // A failed read used to overwrite the cache with null, so one hiccup blanked every
        // vehicle number to a dash — seen on the car 2026-08-14, where the battery tile showed
        // "Estimert – km" while the daemon was answering 427 perfectly well, because the read
        // that happened to coincide with a seat apply timed out. Keep the last good reading and
        // let it age instead: the fields carry a `stale` flag the renderer already dims.
        if (cached != null && now - cachedSummaryAt < STALE_GRACE_MS) return cached;
        cachedSummary = null;
        return null;
    }

    /**
     * Range from recent real-world consumption: usable energy now divided by the
     * kWh/100km the car has actually been doing.
     *
     * <p>Deliberately NOT the car's own range number (that is what {@code range} is)
     * and NOT OverDrive's bucket estimator on {@code /api/trips/range} either, which
     * conditions on speed, temperature and terrain from trip history. This one is the
     * simple honest arithmetic the Audi shows: what the last {@code ~50 km} imply.
     *
     * <p>Absent unless BOTH the recent consumption and remaining energy are known.
     * No fallback to a nominal capacity, because a range figure derived from a guessed
     * pack size is exactly the kind of number that gets trusted and shouldn't be.
     */
    private static void addRangeField(JSONObject fields, Double recentKwhPer100Km) throws Throwable {
        // The arithmetic happens in the daemon (rangeFromRecentKm), for the same reason
        // the battery reading does: remaining energy is only knowable there.
        JSONObject trip = tripSnapshot();
        Integer km = null;
        if (trip != null && !trip.isNull("rangeFromRecentKm")) {
            int v = trip.optInt("rangeFromRecentKm", -1);
            if (v > 0) km = v;
        }
        fields.put("range.recent", field(km, "km",
                (km == null || recentKwhPer100Km == null)
                        ? "" : ("ved " + one(recentKwhPer100Km) + " kWh/100km"),
                tripIsStale()));
    }

    private static JSONObject tripSnapshot() {
        long now = System.currentTimeMillis();
        JSONObject cached = cachedTrip;
        if (cached != null && now - cachedTripAt < TRIP_TTL_MS) return cached;
        if (now - lastTripAttemptAt < TRIP_TTL_MS) return cached;   // back off; may be stale
        lastTripAttemptAt = now;

        JSONObject trip = null;
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/trips/current", "GET", 2000, 4000);
            if (conn.getResponseCode() == 200) {
                trip = new JSONObject(readAll(conn.getInputStream())).optJSONObject("trip");
            }
        } catch (Throwable t) {
            Log.w(TAG, "trip snapshot failed: " + t.getMessage());
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        if (trip != null) {
            cachedTrip = trip;
            cachedTripAt = now;
            return trip;
        }
        // Ride out a hiccup on the last good snapshot rather than blanking the tile. The
        // attempt timestamp is separate from the data timestamp on purpose: `lastTripAttemptAt`
        // keeps a down daemon from being re-dialled by every cell on every tick, while
        // `cachedTripAt` measures how old the numbers on screen actually are.
        if (cached != null && now - cachedTripAt < STALE_GRACE_MS) return cached;
        cachedTrip = null;
        return null;
    }

    private static Double optDouble(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        double d = o.optDouble(key, Double.NaN);
        return Double.isNaN(d) ? null : d;
    }

    /** One decimal, comma-separated: this is read in Norwegian, on a Norwegian car. */
    private static String one(double d) {
        return String.format(java.util.Locale.forLanguageTag("nb-NO"), "%.1f", d);
    }

    private static String hhmm(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h + ":" + (m < 10 ? "0" : "") + m;
    }

    private static JSONObject field(Object value, String unit, String sub) {
        return field(value, unit, sub, false);
    }

    /**
     * @param stale true when the value is real but came from cache after a failed read, so the
     *              renderer dims it. A missing value is always stale; a present one is only
     *              stale if its source says so. Without this the last-good values introduced
     *              on 2026-08-14 would be shown as if they were current readings.
     */
    private static JSONObject field(Object value, String unit, String sub, boolean stale) {
        JSONObject f = new JSONObject();
        try {
            f.put("value", value == null ? JSONObject.NULL : value);
            f.put("unit", unit);
            f.put("sub", sub);
            f.put("stale", value == null || stale);
        } catch (Throwable ignored) {}
        return f;
    }

    /** True when the cached reading behind these fields is older than its own TTL. */
    private static boolean summaryIsStale() {
        return cachedSummary != null
                && System.currentTimeMillis() - cachedSummaryAt >= SUMMARY_TTL_MS;
    }

    private static boolean tripIsStale() {
        return cachedTrip != null
                && System.currentTimeMillis() - cachedTripAt >= TRIP_TTL_MS;
    }

    /**
     * The same gate the Seat Positions page uses to enable its Apply button:
     * {@code acc} and {@code movementBlocked} from {@code GET /api/positions}
     * (see assets/web/shared/seat-positions.js).
     *
     * <p>This is NOT the whole story and must not be presented as one: the car
     * itself refuses a seat write unless the gear is in P, and that refusal happens
     * below OverDrive with the car's own warning (settled on the car 2026-08-11).
     * So a seat cell that looks available can still be refused. {@code known} is
     * false when the read failed, and the renderer then leaves the cell enabled
     * rather than guessing.
     */
    private static JSONObject seatGate() {
        long now = System.currentTimeMillis();
        JSONObject cached = cachedSeatGate;
        if (cached != null && now - cachedSeatGateAt < SEAT_GATE_TTL_MS) return cached;

        JSONObject gate = new JSONObject();
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/positions", "GET", 2000, 3000);
            if (conn.getResponseCode() == 200) {
                JSONObject body = new JSONObject(readAll(conn.getInputStream()));
                gate.put("acc", body.optBoolean("acc", false));
                gate.put("movementBlocked", body.optBoolean("movementBlocked", false));
                gate.put("known", true);
                // Current display names, keyed by position id. The layout stores a label at
                // seed time, which then went stale the moment a position was renamed — Pål
                // plans aliases for the car's own captured slots too (2026-08-14), so the
                // panel has to follow the store rather than a snapshot of it. Rides along
                // with the gate because this response is already being read and parsed; no
                // extra request, and it lands within one state poll of a rename.
                JSONObject names = new JSONObject();
                org.json.JSONArray positions = body.optJSONArray("positions");
                for (int i = 0; positions != null && i < positions.length(); i++) {
                    JSONObject pos = positions.optJSONObject(i);
                    if (pos == null) continue;
                    String id = pos.optString("id", "");
                    String name = HomePanelSeed.displayNameOrNull(pos.optString("name", ""));
                    if (!id.isEmpty() && name != null) names.put(id, name);
                }
                gate.put("names", names);
            } else {
                gate.put("known", false);
            }
        } catch (Throwable t) {
            try { gate.put("known", false); } catch (Throwable ignored) {}
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        cachedSeatGate = gate;
        cachedSeatGateAt = now;
        return gate;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    /** Forget the cached seat gate, e.g. right after an apply. */
    static void invalidateSeatGate() {
        cachedSeatGateAt = 0;
    }
}
