package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Immutable value object for a single telemetry reading.
 * Thread-safe by design — all fields are final.
 *
 * Serialized to compact JSON keys for storage efficiency in .jsonl.gz files.
 */
public class TelemetrySample {

    public final long timestampMs;
    public final int speedKmh;
    public final int accelPedalPercent;   // 0-100
    public final int brakePedalPercent;   // 0-100
    public final boolean brakePedalPressed;
    public final int gearMode;            // 1=P, 2=R, 3=N, 4=D, 5=M, 6=S
    public final double lat;
    public final double lon;
    public final double altitude;
    // The GPS fix's OWN timestamp (Location.getTime(), epoch ms); 0 if unknown.
    // timestampMs above is the SAMPLE time — the position in this sample is
    // typically ~1-4s older than that (GNSS latency on this HAL), and consumers
    // aligning the track against wall-clock truth (video sync) need the real
    // fix time per point.
    public final long gpsFixTimeMs;

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude) {
        this(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent,
                brakePedalPressed, gearMode, lat, lon, altitude, 0);
    }

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           long gpsFixTimeMs) {
        this.timestampMs = timestampMs;
        this.speedKmh = speedKmh;
        this.accelPedalPercent = accelPedalPercent;
        this.brakePedalPercent = brakePedalPercent;
        this.brakePedalPressed = brakePedalPressed;
        this.gearMode = gearMode;
        this.lat = lat;
        this.lon = lon;
        this.altitude = altitude;
        this.gpsFixTimeMs = gpsFixTimeMs;
    }

    /**
     * Serialize to JSON with compact keys for storage.
     * Keys: t, s, a, b, bp, g, la, lo, al, gt
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("t", timestampMs);
            json.put("s", speedKmh);
            json.put("a", accelPedalPercent);
            json.put("b", brakePedalPercent);
            json.put("bp", brakePedalPressed);
            json.put("g", gearMode);
            json.put("la", lat);
            json.put("lo", lon);
            json.put("al", altitude);
            if (gpsFixTimeMs > 0) json.put("gt", gpsFixTimeMs);
        } catch (Exception e) {
            // JSONObject.put only throws on null key, which won't happen here
        }
        return json;
    }

    /**
     * Deserialize from compact JSON.
     */
    public static TelemetrySample fromJson(JSONObject json) {
        return new TelemetrySample(
                json.optLong("t", 0),
                json.optInt("s", 0),
                json.optInt("a", 0),
                json.optInt("b", 0),
                json.optBoolean("bp", false),
                json.optInt("g", 1),
                json.optDouble("la", 0.0),
                json.optDouble("lo", 0.0),
                json.optDouble("al", 0.0),
                json.optLong("gt", 0)
        );
    }
}
