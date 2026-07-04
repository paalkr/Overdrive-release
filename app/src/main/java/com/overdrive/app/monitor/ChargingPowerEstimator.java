package com.overdrive.app.monitor;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayDeque;

/**
 * Ring-buffer charging-power estimator — a FALLBACK power source for BYD models
 * that report no direct/external charging power (the charger-reported getters
 * read 0/UNAVAILABLE and the {@code onExternalChargingPowerChanged} callback is
 * silent under uid-2000, so {@link VehicleDataMonitor#getChargingState()} would
 * otherwise fall through to a nominal placeholder and the UI shows "--").
 *
 * <p>Mechanism: differentiate a monotonic charge-energy counter over a sliding
 * window — {@code ΔkWh × 3_600_000 / Δms = kW}. We feed it two counters: remaining
 * pack energy ({@code remainKwh}, PREFERRED) and the cumulative session
 * charge-energy counter ({@code chargingCapacityKwh}, fallback only); both rise
 * only while charging, so their positive time-derivative is charging power.
 * {@code remainKwh} is preferred because it is verified full-scale and SOC-tracking
 * on real hardware, whereas {@code getChargingCapacity()} is an unvalidated HAL
 * counter that on at least the Seal trim rises at ~half the true energy rate
 * (the BYD half-scale getter pattern) — differentiating it produced the
 * "charging power stuck at ~half" bug, so it is now used only when remain yields
 * no delta. Power is the slope across the WHOLE window (total ΔkWh / total Δt),
 * NOT a per-interval derivative: at the ~90 s parked cadence a single interval's
 * rise is only 1-2 counter quanta, so per-interval division amplified the ±½-quantum
 * error to ±50% and beat against the poll cadence — the periodic "8 → 3.3 → 8"
 * oscillation. Spanning the window averages that quantisation error down to ≈ q/WINDOW.
 * The slope is then EMA-smoothed across cycles.
 *
 * <p><b>Regen / V2L safety:</b> {@link #sample} only accumulates while the fused
 * {@link ChargingDetector} verdict is CHARGING <i>and</i> the car is in Park, and
 * only counts strictly-increasing counter values. Driving regen (gear D/R) and
 * V2L discharge (counter falling) are therefore structurally excluded — the
 * estimator clears its buffers the moment either gate opens, so a stale value can
 * never latch a phantom reading.
 *
 * <p><b>Accuracy caveat:</b> the daemon polls every ~5 s (ACC on) but ~90 s while
 * parked, and the counter resolution can be coarse (≈0.1 kWh), so the parked-AC
 * estimate is approximate. It is intentionally a last-resort source ordered
 * AFTER every real power getter and is only as alive as the counter feeding it —
 * if the model reports no capacity/remaining-energy movement either, this yields
 * nothing and the nominal placeholder still applies.
 *
 * <p>Threading: all access via {@code synchronized(lock)} — {@link #sample} runs
 * on the collector thread, {@link #estimatePowerKw} from HTTP/daemon threads.
 */
public final class ChargingPowerEstimator {

    private static final String TAG = "ChargingPowerEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final ChargingPowerEstimator INSTANCE = new ChargingPowerEstimator();
    public static ChargingPowerEstimator getInstance() { return INSTANCE; }

    /** Sliding window for the counter ring. The power estimate is the slope of the
     *  counter across this whole span (see {@link #pushAndDerive}), so the window
     *  length is the dominant accuracy knob: the irreducible error is ≈ q / WINDOW
     *  (q ≈ 0.1 kWh counter quantum). 10 min → total rise ~7 quanta at a 6-7 kW
     *  charge → ±q/2 endpoint error is ~±3.7% (1σ), vs ~±6% at 6 min. We trade a
     *  little extra averaging lag for that lower noise — fine for a parked-AC
     *  fallback whose power is near-constant. MUST stay < {@link #MAX_INTERVAL_MS}
     *  so the full-span dt always clears the staleness guard below. */
    private static final long WINDOW_MS = 10 * 60_000L;
    /** Reject a span longer than this (e.g. across a poll-rate change / long gap).
     *  Because the ring evicts points older than WINDOW_MS, the span is bounded by
     *  WINDOW_MS — this guard only fires if WINDOW_MS is ever raised past it. */
    private static final long MAX_INTERVAL_MS = 15 * 60_000L;
    /** Minimum counter increase (kWh) to treat a step as real movement, not sensor jitter. */
    private static final double MIN_STEP_KWH = 0.01;
    /** Physical plausibility band for the derived power (kW). */
    private static final double MIN_KW = 0.1;
    private static final double MAX_KW = 350.0;
    /**
     * EMA weight on the prior estimate when smoothing a fresh reading. Kept
     * modest: the ramp-up interval is now excluded structurally (see
     * {@link #pushAndDerive}) so the FIRST derived value already reflects
     * steady-state power and seeds the EMA directly — the smoothing only needs
     * to damp the per-step quantisation noise of a coarse (~0.1 kWh) counter,
     * not to drag a low ramp seed up over many minutes. A heavier prior (0.7)
     * was the dominant cause of the "stuck at ~3 kW for 6 min on a 6 kW charge"
     * lag, because it averaged the 0→full ramp interval in and crawled up.
     */
    private static final double SMOOTH_PRIOR = 0.5;
    /** Throttle for the (debug) diagnostic line so a real charge can be validated without spam. */
    private static final long LOG_THROTTLE_MS = 60_000L;

    private final Object lock = new Object();

    // (timestampMs, milliKwh, baselineFlag) rings — one per counter source.
    // baselineFlag==1 marks the first point of a charging session; the interval
    // ORIGINATING at that point spans the charger's 0→full ramp and is skipped.
    private final ArrayDeque<long[]> capRing = new ArrayDeque<>();      // {t, milliKwh, baseline}
    private final ArrayDeque<long[]> remainRing = new ArrayDeque<>();   // {t, milliKwh, baseline}
    private final ArrayDeque<long[]> socRing = new ArrayDeque<>();      // {t, milliKwh, baseline} — SOC×nominal×SOH

    /** Latest smoothed estimate, or NaN when no usable estimate is available. */
    private double estimateKw = Double.NaN;
    /** Last time the diagnostic line was emitted (throttle). */
    private long lastLogMs = 0L;
    /**
     * SOC→energy scale (kWh per 1.0 SOC-fraction = nominal × SOH), FROZEN at the
     * first charging sample of a session. socEnergyKwh must move ONLY with the SOC
     * gauge — if we recomputed it from the live SohEstimator every cycle, a
     * mid-charge nominal/SOH revision (auto-detect, capacity-Ah refine, calibration)
     * would jump the counter independent of SOC: an upward jump injects a false
     * power spike (recreating the ~7 kW bug), a downward one trips the
     * counter-went-backwards reset and wipes the ring. Freezing the scale makes
     * socE a pure function of SOC. Reset to NaN on charge-stop.
     */
    private double frozenSocScaleKwh = Double.NaN;

    private ChargingPowerEstimator() {}

    /**
     * Feed one collect-cycle observation. Counters are in kWh; pass NaN when a
     * counter is unavailable this cycle. {@code fusedCharging} is the
     * {@link ChargingDetector} verdict and {@code inPark} the gear==P gate — both
     * must be true for the estimator to accumulate (regen/V2L safety).
     */
    public void sample(long nowMs, double capacityKwh, double remainKwh,
                       double socPercent, double socScaleKwh,
                       boolean fusedCharging, boolean inPark) {
        synchronized (lock) {
            if (!fusedCharging || !inPark) {
                // Not a plug-in charge (or moving): drop everything so a later
                // genuine charge starts from a clean window and no stale delta
                // survives as a phantom reading.
                reset();
                return;
            }
            // SOC-derived energy = SOC-fraction × scale, where scale (nominal × SOH)
            // is FROZEN at the first charging sample so socE moves only with SOC and
            // not with live SohEstimator revisions (see frozenSocScaleKwh). PHEV
            // passes a valid socScaleKwh; BEV passes NaN → socE stays NaN and the
            // estimator falls through to remain/cap exactly as before.
            double socEnergyKwh = Double.NaN;
            if (!Double.isNaN(socPercent) && socPercent > 0 && !Double.isNaN(socScaleKwh) && socScaleKwh > 0) {
                if (Double.isNaN(frozenSocScaleKwh)) frozenSocScaleKwh = socScaleKwh;
                socEnergyKwh = (socPercent / 100.0) * frozenSocScaleKwh;
            }
            double socE = pushAndDerive(socRing, socEnergyKwh, nowMs, "socE");
            double rem = pushAndDerive(remainRing, remainKwh, nowMs, "remain");
            double cap = pushAndDerive(capRing, capacityKwh, nowMs, "cap");
            // Source selection, in order of trustworthiness:
            //   1. socEnergyKwh = SOC × nominal × SOH — PHEV ONLY (the caller passes
            //      NaN on BEV, so this never fires there and BEV stays remain-first).
            //      On PHEV the hardware energy getters lie: getBatteryRemainPowerEV=0,
            //      getBatteryPowerHEV is a dead constant, and getRemainingBatteryPower
            //      FREEZES for tens of minutes while charging (observed: pinned at 64.3
            //      for ~48 min). SOC still ticks, so its derivative is the only truthful
            //      power. externalChargingPower is worse still — it reports the EVSE's
            //      RATED capacity (a flat 7.13 kW), not the ~1.7 kW actually drawn.
            //   2. remainKwh — the BEV primary (verified full-scale on the Seal:
            //      85.1 kWh @ 79% ≈ 108.8 nominal), a good rate source when not frozen.
            //   3. chargingCapacityKwh — unvalidated HAL counter, last resort.
            // We prefer whichever produced a delta this cycle, highest priority first.
            // (socE is NaN on BEV, so BEV selection is remain → cap, unchanged.)
            String usedSrc;
            double derived;
            if (!Double.isNaN(socE))      { derived = socE; usedSrc = "socE"; }
            else if (!Double.isNaN(rem))  { derived = rem;  usedSrc = "remain"; }
            else                          { derived = cap;  usedSrc = "cap"; }
            if (!Double.isNaN(derived)) {
                estimateKw = (estimateKw > MIN_KW)
                    ? estimateKw * SMOOTH_PRIOR + derived * (1.0 - SMOOTH_PRIOR)
                    : derived;
                // Diagnostic: surface which counter won, its raw derived kW, and
                // the smoothed output so a single real charge confirms the magnitude
                // (the device reported 3.3 kW on a true ~6 kW charge before this).
                if (nowMs - lastLogMs > LOG_THROTTLE_MS) {
                    lastLogMs = nowMs;
                    // INFO (not debug) so it lands in a default-level log capture —
                    // throttled to 1/min, so it's not spammy. Drop back to debug once
                    // the on-device magnitude is confirmed.
                    logger.info(String.format(
                        "estimate: src=%s derived=%.2fkW smoothed=%.2fkW socE=%.3fkWh remain=%.3fkWh cap=%.3fkWh socRing=%d remainRing=%d capRing=%d",
                        usedSrc, derived, estimateKw,
                        socEnergyKwh, remainKwh, capacityKwh,
                        socRing.size(), remainRing.size(), capRing.size()));
                }
            }
            // If neither counter produced a delta this cycle we keep the last
            // smoothed value (it ages out via reset() when charging stops).
        }
    }

    /**
     * Push a counter reading into its ring (only strictly-increasing values),
     * evict stale entries, and return the average power (kW) as the slope of the
     * counter across the whole retained window (total ΔkWh / total Δt), or NaN
     * when there isn't enough movement to derive one.
     */
    private double pushAndDerive(ArrayDeque<long[]> ring, double counterKwh, long nowMs, String label) {
        if (Double.isNaN(counterKwh) || counterKwh <= 0) return Double.NaN;
        long milli = Math.round(counterKwh * 1000.0);
        long[] last = ring.peekLast();
        // Only record real upward movement. A flat or DECREASING counter (V2L /
        // session reset) records nothing — its derivative is not charging power.
        if (last == null) {
            // First point of a (re)started session — the BASELINE. The interval
            // that originates here covers the charger's 0→full ramp-up, so it is
            // excluded below (baseline flag = 1).
            ring.addLast(new long[]{ nowMs, milli, 1 });
        } else if (milli > last[1] + Math.round(MIN_STEP_KWH * 1000.0)) {
            ring.addLast(new long[]{ nowMs, milli, 0 });
        } else if (milli < last[1]) {
            // Counter went backwards (new session / reset): start fresh, and the
            // fresh point is the new baseline.
            ring.clear();
            ring.addLast(new long[]{ nowMs, milli, 1 });
            return Double.NaN;
        }
        while (!ring.isEmpty() && nowMs - ring.peekFirst()[0] > WINDOW_MS) {
            ring.removeFirst();
        }
        if (ring.size() < 2) return Double.NaN;

        // Slope over the WHOLE WINDOW SPAN, not per-interval-then-median.
        //
        // The counter (remainKwh) is coarsely quantised (~0.1 kWh steps). The old
        // per-interval method computed ΔkWh/Δt for each adjacent pair: at the ~90 s
        // parked cadence a single interval's ΔkWh is just 1-2 quanta (0.1 or 0.2),
        // so a ±0.05 quantisation error on that tiny delta becomes a ±50% power
        // error, and consecutive intervals ALTERNATE 0.1/0.2 (quantisation beat
        // against the near-constant poll interval) → per-interval power alternates
        // ~4 kW / ~8 kW. The median can't cancel that when the window holds only
        // 3-4 intervals (a 3-interval window's median flips 4↔8 as it slides), and
        // the EMA just lags between the two — the visible periodic "8 → 3.3 → 8"
        // oscillation.
        //
        // Fix: divide the TOTAL rise by the TOTAL elapsed time across the window.
        // Over the 10-min window at ~6.6 kW the total rise is ~1.1 kWh (~11 quanta),
        // so the ±0.05 kWh endpoint quantisation is ~±3.7% (1σ) instead of the
        // per-interval method's ±50% — the beat cancels by construction (the error
        // is q/WINDOW, not q/interval). We anchor on the first NON-baseline point
        // (the baseline-originating interval spans the charger ramp and reads low)
        // and the last.
        long[] first = null, lastPt = null;
        for (long[] pt : ring) {
            // Anchor start at the first point that is NOT the session baseline, so
            // the 0→full ramp interval is excluded from the span.
            if (first == null) {
                if (pt[2] == 1) continue;   // skip the baseline point itself
                first = pt;
            }
            lastPt = pt;
        }
        // If the baseline is still the only early point, fall back to anchoring on
        // it once a second point exists (better an approximate value than none).
        if (first == null || lastPt == null || first == lastPt) {
            first = ring.peekFirst();
            lastPt = ring.peekLast();
        }
        if (first == null || lastPt == null) return Double.NaN;

        long dtMs = lastPt[0] - first[0];
        long dKwhMilli = lastPt[1] - first[1];
        if (dtMs <= 0 || dtMs > MAX_INTERVAL_MS || dKwhMilli <= 0) return Double.NaN;
        double kw = (dKwhMilli / 1000.0) * 3_600_000.0 / dtMs;
        if (kw < MIN_KW || kw > MAX_KW) return Double.NaN;
        return kw;
    }

    /** Latest estimate (kW), or NaN if none. Safe to call from any thread. */
    public double estimatePowerKw() {
        synchronized (lock) { return estimateKw; }
    }

    private void reset() {
        socRing.clear();
        capRing.clear();
        remainRing.clear();
        estimateKw = Double.NaN;
        frozenSocScaleKwh = Double.NaN;  // next session re-freezes its own scale
    }
}
