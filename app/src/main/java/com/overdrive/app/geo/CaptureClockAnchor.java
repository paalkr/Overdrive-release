package com.overdrive.app.geo;

/**
 * Maps the camera HAL sensor-clock (the monotonic domain of
 * {@code Image.getTimestamp()}, which becomes the encoder PTS) to wall-clock.
 *
 * Why this exists: {@code first_frame_utc} must be the wall-clock at which the
 * first frame of a recording was CAPTURED. The recorder only learns of that
 * frame at MUXER-WRITE time, which — because recording flushes a pre-record
 * ring buffer oldest-first — is 5-10s AFTER the frame was captured. Sampling
 * {@code System.currentTimeMillis()} there (the original first_frame_utc bug)
 * therefore over-stamps by the buffer depth, which varies per segment.
 *
 * The fix: {@link com.overdrive.app.surveillance.GpuMosaicRecorder#drawFrame}
 * composites frames LIVE (before they enter the ring buffer) and knows each
 * frame's sensor PTS. On every live draw it records the pair
 * (wall-clock now, sensor PTS now) here. Any later frame's capture wall-clock
 * is then {@code anchorWallMs - (anchorPtsNs - framePtsNs)/1e6} — the sensor
 * domain cancels, so we never need to know whether it's REALTIME or UNKNOWN.
 *
 * Global (not per-recorder): the sensor↔wall mapping is a property of the
 * capture clock, identical for every recorder draining the same camera.
 */
public final class CaptureClockAnchor {
    private CaptureClockAnchor() {}

    // volatile: written on the GL/compositor thread, read on the recorder's
    // muxer-drain thread. A torn read is harmless (both fields drift together
    // by at most one frame interval), but volatile keeps them fresh.
    private static volatile long anchorWallMs = 0;
    private static volatile long anchorPtsNs = 0;

    /** Record a live (wall-clock, sensor-PTS) pair at compositing time. */
    public static void mark(long wallMs, long sensorPtsNs) {
        anchorWallMs = wallMs;
        anchorPtsNs = sensorPtsNs;
    }

    /**
     * Convert a frame's sensor PTS (ns) to its capture wall-clock (epoch ms)
     * using the latest anchor. Returns 0 if no anchor has been recorded yet.
     */
    public static long wallClockForPtsNs(long framePtsNs) {
        long w = anchorWallMs, p = anchorPtsNs;   // snapshot
        if (w <= 0) return 0;
        return w - (p - framePtsNs) / 1_000_000L;
    }
}
