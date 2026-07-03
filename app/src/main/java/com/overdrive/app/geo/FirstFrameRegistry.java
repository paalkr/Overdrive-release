package com.overdrive.app.geo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges the true first-encoded-frame wall-clock from the recorder
 * ({@code HardwareEventRecorderGpu}) to the sidecar writer, keyed by the final
 * {@code .mp4} absolute path.
 *
 * Why a registry instead of threading the value through: the recorder captures
 * the first-frame time deep in the muxer-write path, but the sidecar is written
 * from several call sites (final close + each segment rotation). Rather than
 * thread a new parameter through every {@code submit(...)} signature, the
 * recorder drops the value here and {@link LocationSidecarWriter} takes it by
 * the mp4 file it's already handed.
 *
 * The cam_ filename timestamp is stamped at file-create/rotation, ~7-8s before
 * the first frame is actually encoded; this carries the real start instead.
 *
 * Bounded: sentry/event clips also pass through the recorder's first-frame path
 * but never get a sidecar-take, so entries are capped (eldest evicted) to keep
 * the map from growing unbounded across a long parked surveillance session.
 */
public final class FirstFrameRegistry {
    private FirstFrameRegistry() {}

    private static final int MAX = 256;
    private static final Map<String, Long> MAP =
        new LinkedHashMap<String, Long>(64, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
                return size() > MAX;
            }
        };

    /** Record the first-frame wall-clock (epoch ms) for a recording's final .mp4 path. */
    public static synchronized void put(String mp4AbsPath, long utcMs) {
        if (mp4AbsPath != null && utcMs > 0) MAP.put(mp4AbsPath, utcMs);
    }

    /** Fetch and remove the value for a path; 0 if unknown. */
    public static synchronized long take(String mp4AbsPath) {
        Long v = (mp4AbsPath != null) ? MAP.remove(mp4AbsPath) : null;
        return v != null ? v : 0L;
    }
}
