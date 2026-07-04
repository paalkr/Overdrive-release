package com.overdrive.app.monitor;

import android.content.Context;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton coordinator for BYD vehicle data.
 * 
 * Phase 3: Thin wrapper around BydDataCollector.
 * All data reads delegate to the collector. Keeps the same API surface
 * so existing consumers (HttpServer, SurveillanceIpcServer, TripDetector, etc.)
 * don't need changes.
 * 
 * The BatteryPowerMonitor is kept for AccSentryDaemon's voltage-based MCU control
 * (it needs listener callbacks for real-time voltage changes).
 */
public class VehicleDataMonitor {
    
    private static final String TAG = "VehicleDataMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private static VehicleDataMonitor instance;
    private static final Object lock = new Object();
    
    // Only BatteryPowerMonitor kept — AccSentryDaemon needs its listener for voltage-based MCU control
    private final BatteryPowerMonitor batteryPowerMonitor;
    
    private final CopyOnWriteArrayList<VehicleDataListener> listeners = new CopyOnWriteArrayList<>();
    private boolean isRunning = false;
    private Context context;

    // Throttle for the charging-power resolution diagnostic (see getChargingState).
    private volatile long lastChargePowerLogMs = 0L;

    /** Compact formatter for a possibly-NaN candidate value in the diag line. */
    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format("%.2f", v);
    }
    
    private VehicleDataMonitor() {
        this.batteryPowerMonitor = new BatteryPowerMonitor();
    }
    
    public static VehicleDataMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new VehicleDataMonitor();
            }
        }
        return instance;
    }

    // ==================== LIFECYCLE ====================
    
    public void init(Context context) {
        this.context = context;
        logger.info("Initializing VehicleDataMonitor (BydDataCollector mode)");
        
        // Only init battery power monitor (for AccSentryDaemon voltage listener)
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
        
        logger.info("Initialization complete (data from BydDataCollector)");
    }
    
    public void initBatteryPowerOnly(Context context) {
        this.context = context;
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
    }
    
    public synchronized void start() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
        logger.info("VehicleDataMonitor started");
    }
    
    public synchronized void startBatteryPowerOnly() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
    }
    
    public synchronized void stop() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
        logger.info("VehicleDataMonitor stopped");
    }
    
    public synchronized void stopBatteryPowerOnly() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
    }
    
    public boolean isRunning() { return isRunning; }
    
    // ==================== DATA ACCESS (delegates to BydDataCollector) ====================
    
    public BydVehicleData getVd() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c.isInitialized() ? c.getData() : null;
        } catch (Exception e) { return null; }
    }
    
    public BatteryVoltageData getBatteryVoltage() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
            return new BatteryVoltageData(vd.voltageLevelRaw);
        }
        return null;
    }
    
    public BatteryPowerData getBatteryPower() {
        // Try collector first, fallback to monitor (for AccSentryDaemon compatibility)
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.voltage12v)) {
            return new BatteryPowerData(vd.voltage12v);
        }
        return batteryPowerMonitor.getCurrentValue();
    }
    
    public BatterySocData getBatterySoc() {
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.socPercent)) {
            return new BatterySocData(vd.socPercent);
        }
        return null;
    }

    /**
     * Drivetrain probe — true on PHEV/HEV vehicles where {@code fuelPercent}
     * and {@code fuelRangeKm} carry real readings. Trips code uses this to
     * decide whether to populate fuel-cost fields and whether the per-trip UI
     * should render the petrol-leg breakdown.
     */
    public boolean isPhev() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c != null && c.isInitialized() && c.isPhevPublic();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Charging state derivation — fused detector.
     *
     * The "is charging?" decision is owned by {@link ChargingDetector},
     * which fuses three independent signals and two edge inputs:
     *
     *   L1. BMS state edge (chargingState == 1) via the typed
     *       AbsBYDAutoChargingListener registered in BydDataCollector.
     *   L2. BYDAutoPowerDevice.isCharging() polled once per cycle as a
     *       cross-check that catches the PHEV "BMS stuck at 15 IDLE while
     *       charging" firmware bug.
     *   L3. Power-flow inference with hysteresis and a positive AC/DC gun
     *       assertion (gun==2/3/4/5; UNAVAILABLE no longer slips through
     *       like the old "!= 1 disconnected" guard).
     *   E1. ACTION_POWER_CONNECTED — biases fusion toward charging during
     *       the ramp-up window before BMS reports.
     *   E2. ACTION_POWER_DISCONNECTED — overrides the fused state to
     *       NOT_CHARGING for {@code UNPLUG_OVERRIDE_MS} so a stale BMS
     *       value can't keep us in "charging" after the cable comes out.
     *
     * This method is now a thin presentation wrapper: ask the detector
     * for the verdict, choose an effective state code (CHARGING when the
     * detector says yes, else the BMS state if known, else null), and
     * resolve power magnitude.
     *
     * Power magnitude resolution (when fused says CHARGING):
     *   1. chargePower (InstrumentDevice.getChargePower — real DC rate INTO the
     *      pack; matches the BYD app/cloud). Preferred over externalChargingPower
     *      because on PHEV trims that one reports the AC wall-side input (higher:
     *      includes onboard-charger conversion loss) or a 104857.5 sentinel.
     *   2. external charging power (InstrumentDevice — charger-reported, AC-side)
     *   3. chargingDevice.chargingPower
     *   4. abs(engine power) — only when ACC is on (NaN-guarded after
     *      ACC OFF invalidation in BydDataCollector.setAccState)
     *   5. ring-buffer counter-derivative estimator (measured, not nominal)
     *   6. nominal-capacity hint (3.3 kW PHEV / 7 kW BEV, marked estimated)
     *
     * @return ChargingStateData populated from the fused detector, or
     *         null when no state signal is available at all.
     */
    public ChargingStateData getChargingState() {
        BydVehicleData vd = getVd();
        if (vd == null) return null;

        boolean fusedCharging = ChargingDetector.getInstance().isCharging();

        int effectiveState;
        if (fusedCharging) {
            effectiveState = ChargingStateData.CHARGING_BATTERY_STATE_CHARGING;
        } else if (vd.chargingState != BydVehicleData.UNAVAILABLE) {
            // Pass through whatever the BMS reports (READY, FINISHED, IDLE, error...)
            effectiveState = vd.chargingState;
        } else {
            // No BMS state and detector is OFF — caller has nothing to show.
            return null;
        }

        ChargingStateData data = new ChargingStateData(effectiveState);

        // ---- Power magnitude ----
        if (effectiveState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
            String powerSource;  // which cascade branch won — surfaced in the diag log below
            double estKw = ChargingPowerEstimator.getInstance().estimatePowerKw();
            // On PHEV, externalChargingPower is NOT trustworthy for charging power:
            // it reports the EVSE's RATED capacity (observed a flat 7.13 kW on a real
            // ~1.7 kW charge), not the actual draw. So on PHEV the SOC-derived ring
            // estimator (which tracks the true rate via the SOC gauge) OUTRANKS the
            // external/device getters. On BEV the external getter is genuine, so it
            // keeps priority and the estimator stays the last-resort fallback.
            boolean phev;
            try { phev = isPhev(); } catch (Throwable t) { phev = false; }
            boolean estUsable = !Double.isNaN(estKw);

            if (phev && estUsable) {
                // PHEV: the SOC-derived ring estimator is ground truth from the SOC
                // gauge, so it OUTRANKS every hardware getter — including chargePowerKw.
                // On PHEV those getters are unreliable: getChargePower() has been seen
                // returning the AC/EVSE-side ~7 kW (not the true DC pack rate) on some
                // trims, and getExternalChargingPower() reports the EVSE's rated
                // capacity. Trusting either produced the "shows 7 kW on a 1.7 kW
                // charge" bug. Only once the estimator has a value do we prefer it;
                // before warm-up we fall to the phev placeholder branch below.
                data.updateChargingPower(estKw);
                powerSource = "estimator(ring,phev)";
            } else if (!phev && !Double.isNaN(vd.chargePowerKw) && vd.chargePowerKw > 0.1 && vd.chargePowerKw <= 300) {
                // Real DC charge rate into the pack (InstrumentDevice.getChargePower).
                // BEV ONLY: it is the battery-side power the BYD app/cloud shows. This
                // is NOT trusted on PHEV — getChargePower() has been seen returning the
                // AC/EVSE-side ~7 kW there (not the true DC pack rate), so PHEV is
                // handled entirely by the estimator/placeholder branches (above and
                // below), never by a raw hardware getter.
                data.updateChargingPower(vd.chargePowerKw);
                powerSource = "chargePowerKw(DC)";
            } else if (phev) {
                // PHEV but the SOC estimator hasn't warmed up yet (needs ≥2 SOC ticks
                // ≈ 15 min on a slow charge). Do NOT fall through to
                // externalChargingPower/chargingPower here: on PHEV externalChargingPower
                // is the EVSE's RATED capacity (a flat, confidently-WRONG 7.13 kW), not
                // the real draw — displaying it would poison the session peak/avg and
                // mislead. Skip to the engine-power / nominal-placeholder branches so
                // the early window shows an honest estimated hint (isEstimated=true)
                // instead of a wrong measured-looking value.
                if (!Double.isNaN(vd.enginePowerKw) && vd.enginePowerKw < -0.3) {
                    data.updateChargingPower(Math.abs(vd.enginePowerKw));
                    powerSource = "enginePowerKw(phev)";
                } else {
                    powerSource = "phev-awaiting-estimator";
                    try {
                        com.overdrive.app.abrp.SohEstimator soh =
                            com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                        if (soh != null && soh.getNominalCapacityKwh() > 0) {
                            double nominal = soh.getNominalCapacityKwh();
                            data.updateChargingPower(nominal < 30 ? 3.3 : 7.0);
                            data.isEstimated = true;
                            powerSource = "nominalPlaceholder(phev)";
                        }
                    } catch (Exception ignored) { /* leave power at 0 */ }
                }
            } else if (!Double.isNaN(vd.externalChargingPowerKw) && vd.externalChargingPowerKw > 0) {
                data.updateChargingPower(vd.externalChargingPowerKw);
                powerSource = "externalChargingPowerKw(AC)";
            } else if (!Double.isNaN(vd.chargingPowerKw) && vd.chargingPowerKw > 0) {
                data.updateChargingPower(vd.chargingPowerKw);
                powerSource = "chargingPowerKw(device)";
            } else if (!Double.isNaN(vd.enginePowerKw) && vd.enginePowerKw < -0.3) {
                // engine current flowing into pack. setAccState(false) wipes
                // this to NaN, so a value here is fresh from an ACC-on cycle.
                data.updateChargingPower(Math.abs(vd.enginePowerKw));
                powerSource = "enginePowerKw";
            } else if (estUsable) {
                // FALLBACK: no charger-reported power on this model. Derive it
                // from the time-derivative of the rising charge-energy counter
                // (ring-buffer estimator). This is a MEASURED-from-counter value,
                // not a nominal guess, so it is NOT flagged isEstimated — it can
                // legitimately seed the session peak/avg and drive the live curve.
                // The estimator only produces a value while fused-CHARGING + Park,
                // so it is regen/V2L-safe by construction.
                data.updateChargingPower(estKw);
                powerSource = "estimator(ring)";
            } else {
                // Detector says CHARGING but no real kW signal arrived.
                // Show a nominal-based hint so the UI doesn't say "Charging at 0 kW".
                powerSource = "none";
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    if (soh != null && soh.getNominalCapacityKwh() > 0) {
                        double nominal = soh.getNominalCapacityKwh();
                        // < 30 kWh nominal pack → PHEV (3.3 kW AC); else BEV (7 kW AC)
                        data.updateChargingPower(nominal < 30 ? 3.3 : 7.0);
                        data.isEstimated = true;
                        powerSource = "nominalPlaceholder";
                    }
                } catch (Exception ignored) { /* leave power at 0 */ }
            }
            // Diagnostic (throttled 1/min): which branch resolved the displayed
            // charging power, the chosen value, and EVERY candidate's raw value —
            // so a single charge log says definitively whether 3.4 kW is the DC
            // getter reading half, or the ring estimator. INFO so it lands in a
            // default-level capture.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastChargePowerLogMs > 60_000L) {
                lastChargePowerLogMs = nowMs;
                logger.info(String.format(
                    "ChargingPower resolved=%.2fkW source=%s | candidates: chargePowerKw=%s extChgKw=%s chgDevKw=%s engineKw=%s estimatorKw=%s",
                    data.chargingPowerKW, powerSource,
                    fmt(vd.chargePowerKw), fmt(vd.externalChargingPowerKw),
                    fmt(vd.chargingPowerKw), fmt(vd.enginePowerKw),
                    fmt(ChargingPowerEstimator.getInstance().estimatePowerKw())));
            }
        }
        return data;
    }
    
    public DrivingRangeData getDrivingRange() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            return new DrivingRangeData(
                vd.elecRangeKm,
                vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0,
                vd.fuelPercent  // NaN on BEVs (BydDataCollector only sets it on PHEVs)
            );
        }
        return null;
    }

    /**
     * PHEV cumulative liquid-fuel consumption counter, in litres, straight from
     * the BYD statistic HAL ({@code getTotalFuelConValue}). This is the vehicle's
     * own metered lifetime fuel-burned accumulator — a delta between two reads
     * gives the true litres consumed over an interval, independent of tank size
     * and free of the 1%-resolution gauge quantisation.
     *
     * <p>Intentionally NOT routed through {@link #getDrivingRange()}: that helper
     * returns null whenever {@code elecRangeKm} is momentarily unavailable, which
     * would silently drop the fuel snapshot. Trip code reads this directly so the
     * accumulator capture is decoupled from the elec-range gate.
     *
     * @return cumulative litres consumed, or {@code NaN} when the HAL doesn't
     *         report it (pure BEV, or trim without the accumulator).
     */
    public double getTotalFuelCon() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.totalFuelCon : Double.NaN;
    }
    
    public BatteryThermalData getBatteryThermal() {
        BydVehicleData vd = getVd();
        if (vd != null) {
            double hi = vd.highCellTempC;
            double lo = vd.lowCellTempC;
            double avg = vd.avgCellTempC;
            if (!Double.isNaN(hi) || !Double.isNaN(lo) || !Double.isNaN(avg)) {
                return new BatteryThermalData(hi, lo, avg, System.currentTimeMillis());
            }
        }
        return null;
    }
    
    public double getBatteryRemainPowerKwh() {
        BydVehicleData vd = getVd();
        if (vd == null) return 0.0;

        double soc = Double.isNaN(vd.socPercent) ? 0 : vd.socPercent;
        double rawKwh = Double.isNaN(vd.remainKwh) ? 0 : vd.remainKwh;

        try {
            com.overdrive.app.abrp.SohEstimator soh =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (soh != null && soh.getNominalCapacityKwh() > 0 && soc > 0) {
                double nominal = soh.getNominalCapacityKwh();
                // SINGLE SOURCE OF TRUTH for remaining energy. SOH is the
                // displayed (capped ≤100, independently-anchored) value so this
                // number always agrees with the SOH chip/card. Default 100 until
                // a real measurement exists.
                double sohPercent = soh.hasDisplaySoh() ? soh.getDisplaySoh() : 100.0;
                if (sohPercent <= 0) sohPercent = 100.0;
                double computedKwh = (soc / 100.0) * nominal * (sohPercent / 100.0);

                // PHEV: the BYD HAL remaining-energy getters are unreliable —
                // half-scale on some firmwares, STALE/FROZEN when the ICE is
                // running, and frame-ambiguous (no single sample can tell half
                // from gross). We therefore do NOT trust the raw getter for
                // display or accounting on PHEV: remaining is ALWAYS synthesized
                // from the reliable SOC + the user's nominal + the capped SOH.
                // This is the one value every surface (dash, MQTT, ABRP, trips,
                // history) reads, so they agree by construction and it tracks SOC
                // live — eliminating the frozen / doubled / halved / divergent
                // symptoms at the root. (The raw getter still feeds the INDEPENDENT
                // SOH anchors — capacity-Ah coulomb count, calibration — never this
                // display path, so there is no self-reference loop.)
                if (isPhev()) {
                    return computedKwh;
                }

                // BEV: getBatteryRemainPowerEV is authoritative. Trust it within a
                // plausible band (a pack can't exceed nameplate → 1.12 ceiling;
                // a degraded pack reads below → 0.5 floor); else synthesize.
                if (rawKwh > 0) {
                    double impliedCap = rawKwh / (soc / 100.0);
                    double ratio = impliedCap / nominal;
                    if (ratio < 0.5 || ratio > 1.12) {
                        return computedKwh;
                    }
                    return rawKwh;
                }

                // No raw reading: synthesize from SOC × nominal × SOH.
                return computedKwh;
            }
        } catch (Exception e) { /* fall through to raw */ }

        // SohEstimator not ready: use raw BMS value if available
        if (rawKwh > 0) return rawKwh;

        return 0.0;
    }
    
    public JSONObject getAllData() {
        JSONObject json = new JSONObject();
        BydVehicleData vd = getVd();
        
        try {
            // Battery voltage (old format for BatteryMonitor compatibility)
            if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
                JSONObject bvJson = new JSONObject();
                bvJson.put("level", vd.voltageLevelRaw);
                bvJson.put("levelName", vd.voltageLevelRaw == 1 ? "NORMAL" : vd.voltageLevelRaw == 0 ? "LOW" : "INVALID");
                json.put("batteryVoltage", bvJson);
            }
            
            // Battery power (old format)
            if (vd != null && !Double.isNaN(vd.voltage12v)) {
                JSONObject bpJson = new JSONObject();
                bpJson.put("voltageVolts", vd.voltage12v);
                bpJson.put("isWarning", vd.voltage12v < 11.5);
                bpJson.put("isCritical", vd.voltage12v < 10.5);
                bpJson.put("healthStatus", vd.voltage12v < 10.5 ? "CRITICAL" : vd.voltage12v < 11.5 ? "WARNING" : "NORMAL");
                json.put("batteryPower", bpJson);
            }
            
            // Battery SOC (old format)
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                JSONObject bsJson = new JSONObject();
                bsJson.put("socPercent", vd.socPercent);
                bsJson.put("isLow", vd.socPercent < 20);
                bsJson.put("isCritical", vd.socPercent < 10);
                json.put("batterySoc", bsJson);
            }
            
            // Charging state — single source of truth via getChargingState()
            // so this JSON dump matches what SOC graph / ABRP / MQTT see. The
            // raw BMS field (vd.chargingState) is no longer surfaced standalone
            // because it's known to lag and to misreport on PHEVs.
            ChargingStateData cs = getChargingState();
            if (cs != null) {
                JSONObject csJson = new JSONObject();
                csJson.put("stateCode", cs.stateCode);
                csJson.put("stateName", cs.stateName);
                csJson.put("status", cs.status.name());
                csJson.put("isError", cs.isError);
                csJson.put("chargingPowerKW", cs.chargingPowerKW);
                csJson.put("isDischarging", cs.isDischarging);
                csJson.put("isEstimated", cs.isEstimated);
                json.put("chargingState", csJson);
            }
            
            // Driving range (old format)
            if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
                JSONObject drJson = new JSONObject();
                drJson.put("elecRangeKm", vd.elecRangeKm);
                drJson.put("fuelRangeKm", vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0);
                drJson.put("totalRangeKm", vd.elecRangeKm + (vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0));
                json.put("drivingRange", drJson);
            }
            
            // Battery thermal (old format)
            if (vd != null && (!Double.isNaN(vd.highCellTempC) || !Double.isNaN(vd.avgCellTempC))) {
                JSONObject btJson = new JSONObject();
                if (!Double.isNaN(vd.highCellTempC)) btJson.put("highestTempC", vd.highCellTempC);
                if (!Double.isNaN(vd.lowCellTempC)) btJson.put("lowestTempC", vd.lowCellTempC);
                if (!Double.isNaN(vd.avgCellTempC)) btJson.put("averageTempC", vd.avgCellTempC);
                json.put("batteryThermal", btJson);
            }
            
            json.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Failed to create JSON", e);
        }
        
        return json;
    }
    
    public Map<String, Boolean> getAvailability() {
        Map<String, Boolean> availability = new HashMap<>();
        BydDataCollector c = BydDataCollector.getInstance();
        boolean ready = c.isInitialized();
        availability.put("batteryVoltage", ready);
        availability.put("batteryPower", ready || batteryPowerMonitor.isAvailable());
        availability.put("batterySoc", ready);
        availability.put("chargingState", ready);
        availability.put("drivingRange", ready);
        availability.put("batteryThermal", ready);
        return availability;
    }
    
    // ==================== MONITOR ACCESS (kept for backward compat) ====================
    
    public BatteryPowerMonitor getBatteryPowerMonitor() { return batteryPowerMonitor; }
    
    // These return null now — consumers should use the data access methods above
    public BatteryVoltageMonitor getBatteryVoltageMonitor() { return null; }
    public DrivingRangeMonitor getDrivingRangeMonitor() { return null; }
    
    // ==================== LISTENER MANAGEMENT ====================
    
    public void addListener(VehicleDataListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(VehicleDataListener listener) {
        if (listener != null) listeners.remove(listener);
    }
    
    public void notifyBatteryVoltageChanged(BatteryVoltageData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryVoltageChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyBatteryPowerChanged(BatteryPowerData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryPowerChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingStateChanged(ChargingStateData data) {
        for (VehicleDataListener l : listeners) { try { l.onChargingStateChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingPowerChanged(double powerKW) {
        for (VehicleDataListener l : listeners) { try { l.onChargingPowerChanged(powerKW); } catch (Exception ignored) {} }
    }
    
    public void notifyDataUnavailable(String monitorName, String reason) {
        for (VehicleDataListener l : listeners) { try { l.onDataUnavailable(monitorName, reason); } catch (Exception ignored) {} }
    }
}
