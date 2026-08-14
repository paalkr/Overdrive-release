package com.overdrive.app.server;

import com.overdrive.app.byd.BydCarSettings;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.byd.BydFeatureIds;
import com.overdrive.app.byd.BydVehicleData;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * READ-ONLY debug endpoint for the interior-ambient (atmosphere) light state.
 *
 * <p>Exists because the ambient WRITE surface is complete and field-proven
 * ({@code setAmbientLightZoned} / {@code setAmbientBrightnessZoned} /
 * {@code setAmbientLightEnabledZoned}) while the READ surface is not: the main
 * switch reads UNAVAILABLE on at least one trim, brightness is exposed four
 * different ways, and colour is only ever read for the FRONT zone. Storing
 * ambient state in a saved seat position needs a capture that round-trips
 * through an apply, so every route has to be observable side by side on a real
 * car before the store schema is fixed.
 *
 * <p>Endpoint: {@code GET /api/debug/ambient/read}. No writes, no {@code confirm}
 * gate — nothing here actuates, the lights never change.
 *
 * <p>Three independent routes are reported per read, deliberately un-reconciled
 * so a disagreement stays visible rather than being resolved by this code:
 * <ul>
 *   <li>{@code sdk} — the Setting device's {@code getIALColor(area)} /
 *       {@code getIALBrightness(area)}, per area (1 front, 2 rear, 3 both).
 *       Brightness is documented as a 0..5 LEVEL here.</li>
 *   <li>{@code featureIds} — the Light device's atmosphere feature ids read
 *       through {@code callGetSingle}. {@code -10011} means the HAL has no
 *       cached value (CAN segment asleep), which is NOT the same as absent.</li>
 *   <li>{@code settings} — BYD's own {@code carsettings} provider keys, which
 *       the native UI writes. {@code lighting_ambient_brightness} is a 0..10
 *       range here, and {@code lighting_ambient_field} is believed (UNPROVEN)
 *       to be the selected-zone pointer using the same 1/2/3 area encoding.</li>
 * </ul>
 *
 * <p>{@code derived.ambientEnabled} is what the rest of the app actually sees
 * via {@link BydDataCollector#getAmbientLightEnabled()}, included so a blank
 * there can be traced to whichever tier below it came up empty.
 */
public final class AmbientDebugApiHandler {

    /** Sentinel used by the reads below for "no value / not readable". */
    private static final int NO_VALUE = BydVehicleData.UNAVAILABLE;

    private AmbientDebugApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

        int qIdx = path.indexOf('?');
        String pathOnly = qIdx >= 0 ? path.substring(0, qIdx) : path;

        if (!pathOnly.equals("/api/debug/ambient/read")) {
            HttpResponse.sendError(out, 404, "Unknown ambient debug endpoint");
            return true;
        }

        BydDataCollector collector = BydDataCollector.getInstance();
        Object settingDevice = deviceField(collector, "settingDevice");
        Object lightDevice = deviceField(collector, "lightDevice");
        Object bodyworkDevice = deviceField(collector, "bodyworkDevice");

        JSONObject r = new JSONObject();
        r.put("settingDeviceAvailable", settingDevice != null);
        r.put("lightDeviceAvailable", lightDevice != null);
        r.put("bodyworkDeviceAvailable", bodyworkDevice != null);

        // --- Route 1: SDK per-area getters (the only per-zone route that exists) ---
        JSONObject sdk = new JSONObject();
        for (int area = 1; area <= 3; area++) {
            JSONObject z = new JSONObject();
            z.put("ialColor", intOrNull(rawGetter(settingDevice, "getIALColor", area)));
            z.put("ialBrightness", intOrNull(rawGetter(settingDevice, "getIALBrightness", area)));
            sdk.put(areaName(area), z);
        }
        r.put("sdk", sdk);

        // --- Route 2: Light-device atmosphere feature ids ---
        JSONObject fids = new JSONObject();
        putFeature(fids, lightDevice, "mainSwitchStatus", BydFeatureIds.LIGHT_ATMOSPHERE_MAIN_SWITCH_STATUS);
        putFeature(fids, lightDevice, "customColor", BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_COLOR);
        putFeature(fids, lightDevice, "customBrightness", BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS);
        // Music sync is an _EXECUTE id on the BODYWORK device with no reader in
        // the app and no key in the carsettings provider, so today a position
        // could command it but never capture it. Probed here to find out whether
        // it answers a read at all — if it does, music mode can be captured like
        // any other ambient state; if not, it has to be an explicit choice on the
        // position rather than something read off the car.
        putFeature(fids, bodyworkDevice, "musicMode", BydFeatureIds.BODY_ATMOSPHERE_LIGHT_MUSIC);
        putFeature(fids, bodyworkDevice, "atmosphereSwitch", BydFeatureIds.BODY_ATMOSPHERE_LIGHT_SWITCH);
        r.put("featureIds", fids);

        // --- Route 3: BYD's own carsettings provider, what the native UI writes ---
        JSONObject settings = new JSONObject();
        putSetting(settings, "lighting_ambient_color");
        putSetting(settings, "lighting_ambient_brightness");
        putSetting(settings, "lighting_ambient_field");
        r.put("settings", settings);

        // --- What the app actually consumes today ---
        JSONObject derived = new JSONObject();
        int enabled;
        try {
            enabled = collector.getAmbientLightEnabled();
        } catch (Throwable t) {
            enabled = NO_VALUE;
        }
        derived.put("ambientEnabled", enabled == NO_VALUE ? JSONObject.NULL : enabled);
        r.put("derived", derived);

        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /**
     * The collector holds its BYD device handles privately and hands out only
     * typed helpers, so a debug probe that wants the RAW getter response has to
     * reach the field itself — same approach as
     * {@code AutoServiceDebugApiHandler}'s sdk-getter.
     */
    private static Object deviceField(BydDataCollector collector, String name) {
        try {
            java.lang.reflect.Field f = collector.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(collector);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns the getter's response untouched. The typed readers in
     * {@code BydDataCollector} coerce out-of-band answers to UNAVAILABLE, which
     * is right for callers and wrong here: the whole point is to see whether a
     * blank is a negative HAL code, a null (method absent) or a real value.
     */
    private static Object rawGetter(Object device, String name, int param) {
        if (device == null) return null;
        try {
            return BydDeviceHelper.callGetter(device, name, param);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object intOrNull(Object v) {
        return (v instanceof Number) ? ((Number) v).intValue() : JSONObject.NULL;
    }

    private static void putFeature(JSONObject o, Object device, String label, int featureId)
            throws JSONException {
        JSONObject e = new JSONObject();
        // Read first, serialise after: the HAL call is what can blow up, and
        // building the JSON inside its catch block just makes the writes throw a
        // checked exception the failure path can't do anything useful with.
        Object value = JSONObject.NULL;
        String error = null;
        if (device != null) {
            try {
                value = BydDeviceHelper.callGetSingle(device, featureId);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName();
            }
        }
        e.put("id", featureId);
        e.put("value", value);
        if (error != null) e.put("error", error);
        o.put(label, e);
    }

    private static void putSetting(JSONObject o, String key) throws JSONException {
        int v;
        try {
            v = BydCarSettings.getInstance().readInt(key, Integer.MIN_VALUE);
        } catch (Throwable t) {
            v = Integer.MIN_VALUE;
        }
        o.put(key, v == Integer.MIN_VALUE ? JSONObject.NULL : v);
    }

    private static String areaName(int area) {
        return area == 1 ? "front" : area == 2 ? "rear" : "both";
    }
}
