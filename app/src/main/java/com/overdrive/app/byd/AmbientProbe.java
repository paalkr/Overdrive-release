package com.overdrive.app.byd;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Interior-ambient (atmosphere) light state as a capturable, applicable bundle — the
 * ambient half of a saved position, alongside {@link BodyworkSeatProbe}'s geometry.
 *
 * <p>Everything here is modelled on how BYD's own ambient screen
 * ({@code Di4IviAmbientLightFragment}) drives the HAL, because that screen is the
 * definition of "what the car is set to" and a capture that disagrees with it is
 * wrong by construction:
 *
 * <ul>
 *   <li><b>Zones are real and independent.</b> {@code setIALColor}/{@code setIALBrightness}
 *       take the area as their first argument and the car stores a separate value per
 *       zone. Area 1 = front, 2 = rear. Area 3 ("all rows") is a UI convenience that
 *       writes both, and is NOT readable — asking for area 3 returns a HAL error, which
 *       is why reads here only ever ask for 1 and 2.</li>
 *   <li><b>Brightness is a 0..5 level, not a percentage.</b> BYD's slider max is 5 and
 *       the value goes to the HAL unscaled, so a level captured here re-applies exactly.
 *       {@code BydDataCollector}'s percent-based API converts, which is right for a user
 *       typing a percentage and wrong for a round-trip.</li>
 *   <li><b>The colour count varies by trim</b> ({@code SET_IAL_COLOR_CONFIG} selects 6,
 *       30, 63 or 126). The bound is read rather than assumed, so a car with a bigger
 *       palette is not silently clamped to someone else's range.</li>
 *   <li><b>The star-ring zone is not implemented on this firmware.</b> Its feature ids
 *       exist, but BYD's own accessor has a "to be added" placeholder branch where the
 *       read should be. Not captured; it would only ever record a zero.</li>
 * </ul>
 *
 * <p>Modes matter for apply ORDER, not just content. Music mode and dynamic colours both
 * drive the colour continuously, so applying a fixed colour while either is on means the
 * car overwrites it immediately. Apply therefore turns the modes off first, writes the
 * static state, and only then restores whichever modes the position actually wants.
 */
public final class AmbientProbe {

    public static final String SETTING_DEVICE =
            "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    public static final String LIGHT_DEVICE =
            "android.hardware.bydauto.light.BYDAutoLightDevice";

    /** Zones the car actually implements. Star-ring (5) is declared but unimplemented. */
    public static final int AREA_FRONT = 1;
    public static final int AREA_REAR = 2;
    /** "All rows" — a write-only convenience. Never read with this. */
    public static final int AREA_ALL = 3;

    /** BYD's boolean encoding on these switches: 1 = on, 2 = off (not 0). */
    private static final int ON = 1;
    private static final int OFF = 2;

    private static final int BRIGHTNESS_MAX = 5;

    // Switch/mode feature ids, cross-checked against BYD's own Lights/Setting constants.
    private static final int MAIN_SWITCH_STATUS = 0x3F300046;
    private static final int MAIN_SWITCH_SET = 0x4C109044;
    private static final int MUSIC_MODE_STATE = 0x42E00040;
    private static final int MUSIC_MODE_SET = 0x4C109019;
    private static final int DYNAMIC_COLOURS_STATE = 0x2880014A;
    private static final int DYNAMIC_COLOURS_SET = 0x4C10901E;
    private static final int NIGHT_DIM_FEEDBACK = 0x2EB0003D;
    private static final int NIGHT_DIM_SET = 0x4EF42044;
    private static final int CUSTOM_MODE = 0x2730001A;
    private static final int CUSTOM_MODE_SET = 0x4C11302D;

    private AmbientProbe() {}

    // ── read ────────────────────────────────────────────────────────────────────

    /**
     * Capture the ambient state as it stands, or null when the setting device cannot be
     * reached at all.
     *
     * <p>Individual fields are omitted rather than defaulted when the HAL will not answer
     * — an absent key means "this car did not tell us", and apply skips what it cannot
     * see. Writing a plausible default here would make a capture assert something about
     * the car that was never read.
     */
    public static JSONObject read(Context ctx) {
        Object setting = device(ctx, SETTING_DEVICE);
        Object light = device(ctx, LIGHT_DEVICE);
        if (setting == null) return null;

        try {
            JSONObject r = new JSONObject();
            putIf(r, "area", intOrNull(BydDeviceHelper.callGetter(setting, "getIALArea")));
            r.put("front", readZone(setting, AREA_FRONT));
            r.put("rear", readZone(setting, AREA_REAR));

            // Modes. Stored as booleans because 1/2 is BYD's wire encoding, not a concept
            // the position store or the UI should have to carry.
            putBool(r, "mainSwitch", readSwitch(light, MAIN_SWITCH_STATUS));
            putBool(r, "musicMode", readSwitch(setting, MUSIC_MODE_STATE));
            putBool(r, "dynamicColours", readSwitch(light, DYNAMIC_COLOURS_STATE));
            putBool(r, "nightAutoDim", readSwitch(light, NIGHT_DIM_FEEDBACK));
            putIf(r, "customMode", validOrNull(BydDeviceHelper.callGetSingle(light, CUSTOM_MODE)));
            return r;
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject readZone(Object setting, int area) throws JSONException {
        JSONObject z = new JSONObject();
        putIf(z, "colour", intOrNull(BydDeviceHelper.callGetter(setting, "getIALColor", area)));
        putIf(z, "brightness", intOrNull(BydDeviceHelper.callGetter(setting, "getIALBrightness", area)));
        return z;
    }

    /**
     * The palette size this trim exposes, so the UI offers the colours the car has rather
     * than a hardcoded range. Falls back to BYD's own default when the config is
     * unreadable.
     */
    public static int colourMax(Context ctx) {
        Object setting = device(ctx, SETTING_DEVICE);
        Integer cfg = intOrNull(BydDeviceHelper.callGetter(setting, "getIALColorConfig"));
        if (cfg == null) return 30;
        switch (cfg) {
            case 3:  return 6;
            case 5:  return 63;
            case 6:  return 126;
            default: return 30;
        }
    }

    // ── apply ───────────────────────────────────────────────────────────────────

    /**
     * Apply a captured ambient bundle. Returns a per-step result rather than a single
     * boolean: ambient is several independent writes, and "something did not land" is
     * only actionable if you can see which.
     *
     * <p>Order is deliberate. Music mode and dynamic colours are switched OFF before the
     * static colour is written, because either of them running means the car is driving
     * the colour itself and would overwrite what we just set. They are restored last,
     * only if the captured state actually had them on.
     */
    public static JSONObject apply(Context ctx, JSONObject ambient) throws JSONException {
        JSONObject result = new JSONObject();
        if (ambient == null) {
            result.put("applied", false);
            result.put("reason", "no ambient state on this position");
            return result;
        }
        Object setting = device(ctx, SETTING_DEVICE);
        Object light = device(ctx, LIGHT_DEVICE);
        if (setting == null) {
            result.put("applied", false);
            result.put("reason", "setting device unavailable");
            return result;
        }

        JSONObject steps = new JSONObject();

        // 1. Main switch on first when the capture had it on: the zone writes below are
        //    pointless against lights that are off.
        if (ambient.has("mainSwitch") && ambient.getBoolean("mainSwitch")) {
            steps.put("mainSwitch", writeSwitch(light, MAIN_SWITCH_SET, true));
        }

        // 2. Silence the dynamic drivers before writing a static colour.
        boolean wantMusic = ambient.optBoolean("musicMode", false);
        boolean wantDynamic = ambient.optBoolean("dynamicColours", false);
        if (ambient.has("musicMode")) steps.put("musicModeOff", writeSwitch(setting, MUSIC_MODE_SET, false));
        if (ambient.has("dynamicColours")) steps.put("dynamicColoursOff", writeSwitch(light, DYNAMIC_COLOURS_SET, false));

        // 3. Per-zone colour + brightness.
        steps.put("front", applyZone(setting, AREA_FRONT, ambient.optJSONObject("front")));
        steps.put("rear", applyZone(setting, AREA_REAR, ambient.optJSONObject("rear")));

        // 4. Standing preferences that do not interact with colour.
        if (ambient.has("nightAutoDim")) {
            steps.put("nightAutoDim", writeSwitch(light, NIGHT_DIM_SET, ambient.getBoolean("nightAutoDim")));
        }
        if (ambient.has("customMode")) {
            steps.put("customMode", BydDeviceHelper.sendSetCommand(light, CUSTOM_MODE_SET, ambient.getInt("customMode")));
        }

        // 5. Restore the dynamic modes last, so they take over from a known static state
        //    rather than fighting the writes above.
        if (wantMusic) steps.put("musicModeOn", writeSwitch(setting, MUSIC_MODE_SET, true));
        if (wantDynamic) steps.put("dynamicColoursOn", writeSwitch(light, DYNAMIC_COLOURS_SET, true));

        // 6. Main switch OFF last when the capture had it off — doing it first would make
        //    every write above a no-op against dark lights.
        if (ambient.has("mainSwitch") && !ambient.getBoolean("mainSwitch")) {
            steps.put("mainSwitch", writeSwitch(light, MAIN_SWITCH_SET, false));
        }

        result.put("applied", true);
        result.put("steps", steps);
        return result;
    }

    /**
     * One zone's colour and brightness. Uses the 3-arg setters BYD's own sliders call,
     * which carry the area explicitly — so no separate area-selection step is needed and
     * the currently-selected zone is left alone.
     */
    private static JSONObject applyZone(Object setting, int area, JSONObject zone) throws JSONException {
        JSONObject r = new JSONObject();
        if (zone == null) {
            r.put("skipped", "not captured");
            return r;
        }
        if (zone.has("colour")) {
            int colour = zone.getInt("colour");
            r.put("colour", ok(BydDeviceHelper.callMethod(setting, "setIALColor", area, colour, 0)));
        }
        if (zone.has("brightness")) {
            int level = clamp(zone.getInt("brightness"), 0, BRIGHTNESS_MAX);
            r.put("brightness", ok(BydDeviceHelper.callMethod(setting, "setIALBrightness", area, level, 0)));
        }
        return r;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────

    /**
     * BYD devices refuse an ordinary app context; the permissive wrapper is what makes
     * them answer from the daemon.
     *
     * <p>Passing the permissive context to {@code getInstance} is not sufficient on its
     * own: these devices are singletons, so one created earlier by something else keeps
     * whatever context it was built with. {@code swapContext} overwrites the cached field,
     * which is the step that actually makes the permission gate pass. Reuses
     * {@link BodyworkSeatProbe}'s pair rather than adding a third copy of both.
     */
    private static Object device(Context ctx, String cls) {
        if (ctx == null) return null;
        try {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            Context permissive = new BodyworkSeatProbe.PermissiveContext(app);
            Object device = BydDeviceHelper.getDevice(cls, permissive);
            if (device != null) BodyworkSeatProbe.swapContext(device, permissive);
            return device;
        } catch (Throwable t) {
            return null;
        }
    }

    /** null unless the HAL gave a real 0/1-style answer; negatives are error codes. */
    private static Boolean readSwitch(Object device, int featureId) {
        if (device == null) return null;
        int v = BydDeviceHelper.callGetSingle(device, featureId);
        if (v == ON) return Boolean.TRUE;
        if (v == OFF) return Boolean.FALSE;
        return null;
    }

    private static boolean writeSwitch(Object device, int featureId, boolean on) {
        return BydDeviceHelper.sendSetCommand(device, featureId, on ? ON : OFF);
    }

    private static boolean ok(Object result) {
        return result instanceof Integer && (Integer) result == 0;
    }

    private static Integer intOrNull(Object v) {
        if (!(v instanceof Number)) return null;
        int i = ((Number) v).intValue();
        return i < 0 ? null : i;   // negatives are HAL error codes, not values
    }

    private static Integer validOrNull(int v) {
        return v < 0 ? null : v;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void putIf(JSONObject o, String key, Integer v) throws JSONException {
        if (v != null) o.put(key, (int) v);
    }

    private static void putBool(JSONObject o, String key, Boolean v) throws JSONException {
        if (v != null) o.put(key, v.booleanValue());
    }
}
