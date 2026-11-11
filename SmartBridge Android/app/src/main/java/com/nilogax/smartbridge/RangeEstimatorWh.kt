package com.nilogax.smartbridge

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "RangeEstimatorWh"

private const val LIVE_ESTIMATE_THROTTLE_MS = 10_000L  // 10 seconds

private const val LOGGING_ENABLED = false
private const val LOG_FLUSH_INTERVAL_MS     = 2 * 60 * 1000L  // flush to disk every 2 min
private const val TEMP_LOG_FILENAME         = "rangelog_temp.csv"

/**
 * Energy-based range estimator.
 *
 * Two independent mechanisms:
 *
 * 1. Per-assist-mode Wh/metre tracking. Motor watt-seconds and distance are accumulated
 *    continuously while the assist mode stays constant. A sample is committed once the
 *    segment clears [MIN_SEGMENT_METRES]. All physically valid samples enter the pool
 *     — outlier filtering happens at estimate time
 *    inside [ownModeEstimate], where the current window is filtered before the CI is
 *    computed. This means the pool from prior rides ages out naturally as new
 *    samples enter, rather than blocking new segments from ever entering because they look
 *    like outliers against stale context.
 *
 * 2. SOC-banded efficiency curve. Calibrated empirically against battery-name-derived
 *    capacity on every 1% drop, in 10%-wide SOC bands. Real samples persist permanently.
 *
 * Live range = whRemainingLive ÷ Wh/metre[mode]. whRemainingLive is decremented each
 * packet by motor Wh and corrected to the band model on every battery % drop, giving a
 * continuously-moving value rather than a stepped one. Corrections will never
 * increase whRemainingLive (energy can't go up), and are blended when the calibrating
 * band has few samples to avoid jittery estimates on a single measurement.
 */
class RangeEstimatorWh(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        // SOC bands: index 0 = 0–9%, index 9 = 90–99%
        private const val BAND_COUNT = 10
        private const val BAND_WIDTH = 100 / BAND_COUNT

        // Static prior used only when NO band anywhere has real data yet, based on data collected from SX/400Wh
        private val BAND_EFFICIENCY_SEED = doubleArrayOf(
            0.510,  // b0  0–9%
            0.560,  // b1  10–19%
            0.620,  // b2  20–29%
            0.640,  // b3  30–39%
            0.645,  // b4  40–49%
            0.680,  // b5  50–59%
            0.625,  // b6  60–69%
            0.535,  // b7  70–79%
            0.555,  // b8  80–89%
            0.545   // b9  90–99%
        )
        // Minimum distance (metres) a mode-segment must accumulate
        private const val MIN_SEGMENT_METRES = 250

        // Outlier rejection applied at ESTIMATE TIME (not insertion time).

        private const val OUTLIER_MAD_MULTIPLIER = 5.0
        private const val OUTLIER_MAD_FLOOR_FRACTION = 0.20
        private const val MIN_SAMPLES_FOR_OUTLIER_CHECK = 5

        // Hard gates at insertion — upper and lower bounds.
        // Upper: ~0.05 Wh/m = 50 Wh/km; physically impossible even at full Turbo.
        // Lower: 0.001 Wh/m = 1 Wh/km. Below this the motor is effectively off
        private const val MAX_PHYSICAL_WH_PER_METRE = 0.05
        private const val MIN_WH_PER_METRE = 0.0005

        // Per-mode rolling pool size
        // 60 = 15 km at 250 m/segment (CHANGE THIS IF HISTORY LENGTH CHANGES)
        private const val MAX_SAMPLES_PER_MODE = 60
        const val MIN_SENSITIVITY = 3

        // Seeded Wh/metre defaults, used only until a mode has at least one real sample.
        private const val ECO_BASELINE_WH_PER_METRE = 400.0 / 60_000.0   // 400Wh over 60km

        // Mapping of modes based on published assist percentages - only used until real data is available
        private val ASSIST_RATIO_PERCENT = mapOf(
            1 to 60.0,   // Eco
            2 to 140.0,  // Tour
            3 to 240.0,  // Sport
            4 to 340.0   // Turbo
        )

        private val SEEDED_WH_PER_METRE: Map<Int, Double> = ASSIST_RATIO_PERCENT.mapValues { (_, pct) ->
            ECO_BASELINE_WH_PER_METRE * (pct / ASSIST_RATIO_PERCENT.getValue(1))
        }

        // Mode 0 (assist off) → hold mode 1 (Eco) estimate while coasting.
        private const val MODE_FOR_ASSIST_OFF_DISPLAY = 1

        private val T_CRIT_95 = doubleArrayOf(
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086
        )
        private const val T_CRIT_LARGE_SAMPLE = 1.960

        private const val PREFS_PREFIX = "rangewh_"

        // When correcting whRemainingLive on a battery % drop, blend toward the band-model
        // value rather than snapping to it when the band has few samples. At n >= this
        // threshold the correction is applied fully (blend factor = 1.0).
        private const val CORRECTION_FULL_BLEND_N = 3

        // Discard a calibration window if the bike was stationary (zero odometer delta
        // AND zero motor power) for more than this fraction of the window duration.

        private const val MAX_STOPPED_FRACTION = 0.25
        private const val MIN_STOP_SECONDS_BEFORE_COUNTING = 5.0
    }

    // ── Per-mode Wh/metre pools ──────────────────────────────────────────────────

    private val modeSamples: MutableMap<Int, ArrayDeque<Double>> = mutableMapOf()

    // ── SOC-banded efficiency ────────────────────────────────────────────────────

    private val bandSamples: Array<ArrayDeque<Double>> = Array(BAND_COUNT) { ArrayDeque() }

    // ── Continuous whRemaining tracker ──────────────────────────────────────────
    // Decremented per-packet by motor Wh draw; corrected to the band model on every
    // battery % drop. Null until capacity is known and first battery % is seen.

    private var whRemainingLive: Double? = null

    // ── Live mode-segment accumulator ───────────────────────────────────────────

    private var segmentMode: Int = -1
    private var segmentWh: Double = 0.0
    private var segmentMetres: Double = 0.0
    private var lastPacketTimeMs: Long = -1
    private var lastOdometerForSegment: Int = -1

    // ── Calibration window (battery-% based, mode-agnostic) ─────────────────────

    private var calibWh: Double = 0.0
    private var calibTotalSeconds: Double = 0.0
    private var calibStoppedSeconds: Double = 0.0
    private var consecutiveStoppedSeconds: Double = 0.0
    private var lastBatteryForCalib: Int = -1
    private var ignoreFirstCalibDrop: Boolean = true

    // ── Battery capacity ─────────────────────────────────────────────────────────

    private var batteryCapacityWh: Int? = null
    private var loggedCapacityUnknownThisSession = false

    // ── LIVE_ESTIMATE log throttling ─────────────────────────────────────────────
    // Tracked separately from segment/calib state so the triggers are independent.

    private var lastLiveLogMs: Long = 0L
    private var lastLoggedMode: Int = -1
    private var lastLoggedBattery: Int = -1
    private var segmentCommittedSinceLog: Boolean = false

    // ── Wh/metre estimate cache ──────────────────────────────────────────────────
    // whPerMetre only changes on segment commits and mode changes; caching avoids
    // re-running the MAD sort + CI calculation on every BLE packet.

    private var cachedWhPerMetre: Double? = null
    private var cachedWhPerMetreMode: Int = -1

    // ── ESTIMATE_FILTER dedup ────────────────────────────────────────────────────
    // Log only when the filter result changes, not on every packet.

    private var lastFilteredCount: Int = -1
    private var lastFilterMedian: Double = -1.0

    // ── Ride logging ─────────────────────────────────────────────────────────────

    private var rideLogBuffer: StringBuilder? = null
    private var rideLogHasData = false
    private var tempLogFile: java.io.File? = null
    private var lastFlushMs: Long = 0L
    private val rideLogLock = Any()

    init {
        loadAll()
    }

    // ── Public: battery capacity ─────────────────────────────────────────────────

    fun setBatteryCapacityWh(wh: Int) {
        if (batteryCapacityWh != wh) {
            batteryCapacityWh = wh
            prefs.edit().putInt(PREFS_PREFIX + "capacity_wh", wh).apply()
            log("CAPACITY", "wh=$wh")
        }
    }

    // ── Public: connection lifecycle ─────────────────────────────────────────────

    fun onConnected() {
        openRideLog()
    }

    fun onDisconnected() {
        flushSegment(discard = true, reason = "disconnect")
        segmentMode = -1
        lastPacketTimeMs = -1
        lastOdometerForSegment = -1
        calibWh = 0.0
        calibTotalSeconds = 0.0
        calibStoppedSeconds = 0.0
        consecutiveStoppedSeconds = 0.0
        lastBatteryForCalib = -1
        ignoreFirstCalibDrop = true
        loggedCapacityUnknownThisSession = false
        whRemainingLive = null
        lastLiveLogMs = 0L
        lastLoggedMode = -1
        lastLoggedBattery = -1
        segmentCommittedSinceLog = false
        cachedWhPerMetre = null
        cachedWhPerMetreMode = -1
        lastFilteredCount = -1
        lastFilterMedian = -1.0
        closeRideLog()
    }

    fun forget() {
        onDisconnected()
        clearHistory()
        batteryCapacityWh = null
        prefs.edit().remove(PREFS_PREFIX + "capacity_wh").apply()
        log("FORGET", "all history and capacity cleared")
    }

    /** Clears all learned Wh/m and band efficiency history without affecting the
     *  connection or capacity.  */
    fun clearHistory() {
        modeSamples.clear()
        bandSamples.forEach { it.clear() }
        // Reset cached values that depend on history
        cachedWhPerMetre = null
        cachedWhPerMetreMode = -1
        lastFilteredCount = -1
        lastFilterMedian = -1.0
        // whRemainingLive will re-initialise from the band model on the next packet
        whRemainingLive = null
        val editor = prefs.edit()
        for (mode in 0..4) {
            editor.remove(PREFS_PREFIX + "mode_${mode}_count")
            for (i in 0 until MAX_SAMPLES_PER_MODE) editor.remove(PREFS_PREFIX + "mode_${mode}_$i")
        }
        for (band in 0 until BAND_COUNT) {
            editor.remove(PREFS_PREFIX + "band_${band}_count")
            for (i in 0 until MAX_SAMPLES_PER_MODE) editor.remove(PREFS_PREFIX + "band_${band}_$i")
        }
        editor.apply()
        log("HISTORY_CLEARED", "Wh/m pool and band efficiency history cleared")
    }


    /**
     * Feed one packet's worth of data. Returns live range estimate in metres for the
     * current mode, or null if not enough data yet.
     *
     * @param motorPowerW   instantaneous motor power, watts (0 if assist off)
     * @param assistMode    current assist mode int (0=off)
     * @param odometerM     cumulative odometer, metres
     * @param batteryPct    current battery percent
     * @param sensitivity   3..20, how many recent per-mode samples feed the estimate
     */
    fun update(
        motorPowerW: Int,
        assistMode: Int,
        odometerM: Int,
        batteryPct: Int,
        sensitivity: Int
    ): Float? {
        val systime = System.currentTimeMillis()

        // Capture dt before accumulateSegment() advances lastPacketTimeMs.
        val dtSeconds = if (lastPacketTimeMs > 0) (systime - lastPacketTimeMs).coerceAtLeast(0L) / 1000.0 else 0.0

        accumulateCalibWh(motorPowerW, dtSeconds)

        // Track stopped time for calibration quality.
        //
        // A packet only contributes to consecutiveStoppedSeconds when BOTH:
        //   - odometer did not advance (≤ 0 after rollover handling), and
        //   - motor power is zero.
        // Time only starts counting toward calibStoppedSeconds after
        // MIN_STOP_SECONDS_BEFORE_COUNTING of continuous such packets. This filters
        // brief pauses so only real stationary periods affect the stopped fraction.
        if (dtSeconds > 0.0) {
            calibTotalSeconds += dtSeconds
            val odomDelta = if (lastOdometerForSegment >= 0) {
                var d = (odometerM - lastOdometerForSegment).toDouble()
                if (d < -1_000_000) d += 0x1000000  // 24-bit rollover
                d
            } else 0.0

            val isStationary = odomDelta <= 0.0 && motorPowerW <= 0
            if (isStationary) {
                consecutiveStoppedSeconds += dtSeconds
                if (consecutiveStoppedSeconds > MIN_STOP_SECONDS_BEFORE_COUNTING) {
                    calibStoppedSeconds += dtSeconds
                }
            } else {
                consecutiveStoppedSeconds = 0.0
            }
        }

        accumulateSegment(systime, motorPowerW, assistMode, odometerM)
        runCalibration(batteryPct)

        if (whRemainingLive == null) {
            whRemainingLive = estimateWhRemaining(batteryPct)
        }

        val whRemaining = whRemainingLive
        if (whRemaining == null) {
            if (!loggedCapacityUnknownThisSession) {
                log("CAPACITY_UNKNOWN", "battery name message not yet received — no range estimate possible")
                loggedCapacityUnknownThisSession = true
            }
            return null
        }

        // Mode 0 (assist off) → hold mode 1 (Eco) estimate while coasting.
        val modeForEstimate = if (assistMode == 0) MODE_FOR_ASSIST_OFF_DISPLAY else assistMode

        // Only recompute whPerMetre when the pool has changed (segment commit) or the
        // mode changed
        val whPerMetre: Double
        if (segmentCommittedSinceLog || modeForEstimate != cachedWhPerMetreMode || cachedWhPerMetre == null) {
            val computed = estimateWhPerMetre(modeForEstimate, sensitivity) ?: return null
            if (computed <= 0.0) return null
            cachedWhPerMetre = computed
            cachedWhPerMetreMode = modeForEstimate
            whPerMetre = computed
        } else {
            whPerMetre = cachedWhPerMetre ?: return null
        }

        val rangeM = (whRemaining / whPerMetre).toFloat()

        val modeChanged    = assistMode  != lastLoggedMode
        val batteryChanged = batteryPct  != lastLoggedBattery
        val timerFired     = (systime - lastLiveLogMs) >= LIVE_ESTIMATE_THROTTLE_MS

        if (modeChanged || batteryChanged || timerFired || segmentCommittedSinceLog) {
            val trigger = listOfNotNull(
                "mode".takeIf    { modeChanged },
                "battery".takeIf { batteryChanged },
                "timer".takeIf   { timerFired },
                "segment".takeIf { segmentCommittedSinceLog }
            ).joinToString("+")
            log(
                "LIVE_ESTIMATE",
                "trigger=$trigger mode=$assistMode modeForEstimate=$modeForEstimate " +
                        "whRemaining=%.2f whPerMetre=%.5f rangeM=%.0f battery=$batteryPct"
                            .format(whRemaining, whPerMetre, rangeM)
            )
            lastLoggedMode    = assistMode
            lastLoggedBattery = batteryPct
            lastLiveLogMs     = systime
            segmentCommittedSinceLog = false
        }

        return rangeM
    }

    // ── Mode-segment accumulation ────────────────────────────────────────────────

    private fun accumulateSegment(nowMs: Long, motorPowerW: Int, assistMode: Int, odometerM: Int) {
        if (segmentMode == -1) {
            segmentMode = assistMode
            segmentWh = 0.0
            segmentMetres = 0.0
            lastPacketTimeMs = nowMs
            lastOdometerForSegment = odometerM
            return
        }

        if (assistMode != segmentMode) {
            flushSegment(discard = false, reason = "mode_switch")
            segmentMode = assistMode
            segmentWh = 0.0
            segmentMetres = 0.0
            lastPacketTimeMs = nowMs
            lastOdometerForSegment = odometerM
            return
        }

        val dtSeconds = (nowMs - lastPacketTimeMs).coerceAtLeast(0L) / 1000.0
        var deltaM = (odometerM - lastOdometerForSegment).toDouble()
        if (deltaM < -1_000_000) deltaM += 0x1000000 // 24-bit odometer rollover

        if (dtSeconds > 0.0 && deltaM >= 0.0) {
            segmentWh += (motorPowerW * dtSeconds) / 3600.0
            segmentMetres += deltaM
        }

        lastPacketTimeMs = nowMs
        lastOdometerForSegment = odometerM

        if (segmentMetres >= MIN_SEGMENT_METRES) {
            flushSegment(discard = false, reason = "distance_threshold")
            segmentWh = 0.0
            segmentMetres = 0.0
        }
    }

    private fun flushSegment(discard: Boolean, reason: String) {
        if (segmentMode == -1) return

        val metres = segmentMetres
        val wh     = segmentWh
        segmentMetres = 0.0
        segmentWh     = 0.0

        if (discard || metres < MIN_SEGMENT_METRES) {
            log(
                "SEGMENT_DISCARD",
                "mode=$segmentMode metres=%.1f wh=%.3f reason=$reason".format(metres, wh)
            )
            return
        }

        val whPerMetre = wh / metres
        val accepted = tryAddModeSample(segmentMode, whPerMetre)
        if (accepted) {
            segmentCommittedSinceLog = true
        }

        log(
            "SEGMENT_COMMIT",
            "mode=$segmentMode metres=%.1f wh=%.3f whPerMetre=%.5f accepted=$accepted"
                .format(metres, wh, whPerMetre)
        )
    }

    // ── Pool insertion (outliers filtered at estimate time) ──

    private fun tryAddModeSample(mode: Int, whPerMetre: Double): Boolean {
        if (whPerMetre <= 0.0 || whPerMetre.isNaN() || whPerMetre.isInfinite()) return false

        if (whPerMetre < MIN_WH_PER_METRE) {
            log(
                "SAMPLE_REJECTED_LOW",
                "mode=$mode whPerMetre=%.5f below minimum %.5f (coasting/descent)"
                    .format(whPerMetre, MIN_WH_PER_METRE)
            )
            return false
        }

        if (whPerMetre > MAX_PHYSICAL_WH_PER_METRE) {
            log(
                "SAMPLE_REJECTED_PHYSICAL",
                "mode=$mode whPerMetre=%.5f exceeds physical maximum %.5f"
                    .format(whPerMetre, MAX_PHYSICAL_WH_PER_METRE)
            )
            return false
        }

        val pool = modeSamples.getOrPut(mode) { ArrayDeque() }
        pool.addLast(whPerMetre)
        while (pool.size > MAX_SAMPLES_PER_MODE) pool.removeFirst()
        persistModeSamples(mode)
        return true
    }

    // ── Per-mode Wh/metre estimate ───────────────────────────────────────────────

    private fun ownModeEstimate(mode: Int, sensitivity: Int): Double? {
        val pool = modeSamples[mode]
        if (pool == null || pool.isEmpty()) return null

        val sampleSize = sensitivity.coerceIn(MIN_SENSITIVITY, pool.size).coerceAtMost(pool.size)
        val rawWindow = pool.toList().takeLast(sampleSize)

        val window = if (rawWindow.size >= MIN_SAMPLES_FOR_OUTLIER_CHECK) {
            val med = median(rawWindow)
            val mad = medianAbsoluteDeviation(rawWindow, med)
            val effectiveMad = maxOf(mad, med * OUTLIER_MAD_FLOOR_FRACTION)
            if (effectiveMad > 0.0) {
                val filtered = rawWindow.filter { abs(it - med) / effectiveMad <= OUTLIER_MAD_MULTIPLIER }
                val filteredCount = rawWindow.size - filtered.size

                if (filteredCount != lastFilteredCount || abs(med - lastFilterMedian) > 1e-6) {
                    if (filteredCount > 0) {
                        log(
                            "ESTIMATE_FILTER",
                            "mode=$mode window=${rawWindow.size} filtered=$filteredCount " +
                                    "median=%.5f effectiveMad=%.5f".format(med, effectiveMad)
                        )
                    }
                    lastFilteredCount = filteredCount
                    lastFilterMedian = med
                }
                filtered.ifEmpty { rawWindow } // safety: never reduce to empty
            } else rawWindow
        } else rawWindow

        if (window.size < MIN_SENSITIVITY) {
            return window.average()
        }

        val n = window.size
        val mean = window.sum() / n
        val variance = window.sumOf { (it - mean) * (it - mean) } / (n - 1)
        val stdDev = sqrt(variance)
        val sem = stdDev / sqrt(n.toDouble())

        val df = n - 1
        val tCrit = if (df - 1 in T_CRIT_95.indices) T_CRIT_95[df - 1] else T_CRIT_LARGE_SAMPLE

        val ciUpper = mean + tCrit * sem
        return maxOf(ciUpper, 1e-6)
    }

    /**
     * Wh/metre for [mode], in priority order:
     *  1. This mode's own real data.
     *  2. Rescaled from the nearest other mode that has real data (by assist-ratio distance).
     *  3. Static seed, only when no mode anywhere has real data yet.
     */
    private fun estimateWhPerMetre(mode: Int, sensitivity: Int): Double? {
        ownModeEstimate(mode, sensitivity)?.let { return it }

        val targetRatio = ASSIST_RATIO_PERCENT[mode] ?: return SEEDED_WH_PER_METRE[mode]

        val rescaled = ASSIST_RATIO_PERCENT.keys
            .filter { it != mode }
            .sortedBy { kotlin.math.abs((ASSIST_RATIO_PERCENT[it] ?: 0.0) - targetRatio) }
            .firstNotNullOfOrNull { otherMode ->
                ownModeEstimate(otherMode, sensitivity)?.let { otherEstimate ->
                    val otherRatio = ASSIST_RATIO_PERCENT.getValue(otherMode)
                    otherEstimate * (targetRatio / otherRatio)
                }
            }
        if (rescaled != null) return rescaled

        return SEEDED_WH_PER_METRE[mode]
    }

    // ── SOC-banded efficiency calibration ────────────────────────────────────────

    private fun runCalibration(batteryPct: Int) {
        if (lastBatteryForCalib < 0) {
            lastBatteryForCalib = batteryPct
            calibWh = 0.0
            calibTotalSeconds = 0.0
            calibStoppedSeconds = 0.0
            consecutiveStoppedSeconds = 0.0
            return
        }

        val batteryDrop = lastBatteryForCalib - batteryPct
        if (batteryDrop <= 0) {
            if (batteryDrop < 0) {
                lastBatteryForCalib = batteryPct
                calibWh = 0.0
                calibTotalSeconds = 0.0
                calibStoppedSeconds = 0.0
                consecutiveStoppedSeconds = 0.0
                ignoreFirstCalibDrop = true
            }
            return
        }

        if (ignoreFirstCalibDrop) {
            ignoreFirstCalibDrop = false
            lastBatteryForCalib = batteryPct
            calibWh = 0.0
            calibTotalSeconds = 0.0
            calibStoppedSeconds = 0.0
            consecutiveStoppedSeconds = 0.0
            return
        }

        val socFrom        = lastBatteryForCalib
        val whAccumulated  = calibWh
        val totalSecs      = calibTotalSeconds
        val stoppedSecs    = calibStoppedSeconds
        lastBatteryForCalib = batteryPct
        calibWh             = 0.0
        calibTotalSeconds   = 0.0
        calibStoppedSeconds = 0.0
        consecutiveStoppedSeconds = 0.0

        // Discard windows where the bike was stopped for too long.
        val stoppedFraction = if (totalSecs > 0.0) stoppedSecs / totalSecs else 0.0
        if (stoppedFraction > MAX_STOPPED_FRACTION) {
            log(
                "CALIBRATION_DISCARDED",
                "reason=stopped_fraction stopped=%.1fs total=%.1fs fraction=%.2f socFrom=$socFrom socTo=$batteryPct"
                    .format(stoppedSecs, totalSecs, stoppedFraction)
            )
            return
        }

        val capacityWh = batteryCapacityWh
        if (capacityWh != null && capacityWh > 0 && whAccumulated > 0.0) {
            val nominalWhPerPct = capacityWh / 100.0
            val actualWhPerPct  = whAccumulated / batteryDrop
            val efficiency      = actualWhPerPct / nominalWhPerPct

            val midSoc = (socFrom + batteryPct) / 2
            val band   = (midSoc / BAND_WIDTH).coerceIn(0, BAND_COUNT - 1)

            val accepted = tryAddBandSample(band, efficiency)
            log(
                "CALIBRATION",
                "socFrom=$socFrom socTo=$batteryPct band=$band " +
                        "calibWh=%.3f nominalWhPerPct=%.3f actualWhPerPct=%.3f efficiency=%.4f stopped=%.1fs total=%.1fs fraction=%.2f accepted=$accepted"
                            .format(whAccumulated, nominalWhPerPct, actualWhPerPct, efficiency, stoppedSecs, totalSecs, stoppedFraction)
            )
            if (accepted) logBandEfficiencies()

            val corrected = estimateWhRemaining(batteryPct)
            if (corrected != null && whRemainingLive != null) {
                val n = bandSamples[band].size
                val blendFactor = when {
                    n >= CORRECTION_FULL_BLEND_N -> 1.0
                    else -> n.toDouble() / CORRECTION_FULL_BLEND_N.toDouble()
                }
                val blended = whRemainingLive!! + (corrected - whRemainingLive!!) * blendFactor
                val clamped = blended.coerceAtMost(whRemainingLive!!)
                log(
                    "WH_REMAINING_CORRECTION",
                    "before=%.2f corrected=%.2f blended=%.2f after=%.2f drift=%.2f blendFactor=%.2f battery=$batteryPct"
                        .format(whRemainingLive!!, corrected, blended, clamped, clamped - whRemainingLive!!, blendFactor)
                )
                whRemainingLive = clamped
            }
        }

    }


    private fun logBandEfficiencies() {
        val parts = (0 until BAND_COUNT).map { band ->
            val pool = bandSamples[band]
            if (pool.isNotEmpty()) {
                "b$band=%.4f(n=${pool.size})".format(pool.average())
            } else {
                val fallback = bandEfficiency(band) // nearest-neighbour or static prior
                "b$band=%.4f(prior)".format(fallback)
            }
        }
        log("BAND_EFFICIENCIES", parts.joinToString(" "))
    }

    /** Accumulates motor Wh for calibration and decrements whRemainingLive. Call once per packet. */
    private fun accumulateCalibWh(motorPowerW: Int, dtSeconds: Double) {
        if (dtSeconds > 0.0) {
            val deltaWh = (motorPowerW * dtSeconds) / 3600.0
            calibWh += deltaWh
            whRemainingLive = whRemainingLive?.minus(deltaWh)?.coerceAtLeast(0.0)
        }
    }

    private fun tryAddBandSample(band: Int, efficiency: Double): Boolean {
        if (efficiency.isNaN() || efficiency.isInfinite() || efficiency <= 0.0) return false

        val pool = bandSamples[band]

        if (pool.size >= MIN_SAMPLES_FOR_OUTLIER_CHECK) {
            val median = median(pool)
            val mad = medianAbsoluteDeviation(pool, median)
            if (mad > 0.0) {
                val effectiveMad = maxOf(mad, median * OUTLIER_MAD_FLOOR_FRACTION)
                val deviation = abs(efficiency - median) / effectiveMad
                if (deviation > OUTLIER_MAD_MULTIPLIER) {
                    log(
                        "OUTLIER_REJECTED_BAND",
                        "band=$band efficiency=%.5f median=%.5f mad=%.5f effectiveMad=%.5f deviation=%.2f"
                            .format(efficiency, median, mad, effectiveMad, deviation)
                    )
                    return false
                }
            }
        }

        pool.addLast(efficiency)
        while (pool.size > MAX_SAMPLES_PER_MODE) pool.removeFirst()
        persistBandSamples(band)
        return true
    }

    /** Reads real data if present, else nearest populated band, else static prior. */
    private fun bandEfficiency(band: Int): Double {
        val own = bandSamples[band]
        if (own.isNotEmpty()) return own.average()

        // Nearest-neighbour from closest populated band
        for (dist in 1 until BAND_COUNT) {
            val higher = band + dist
            if (higher < BAND_COUNT && bandSamples[higher].isNotEmpty())
                return bandSamples[higher].average()
            val lower = band - dist
            if (lower >= 0 && bandSamples[lower].isNotEmpty())
                return bandSamples[lower].average()
        }

        return BAND_EFFICIENCY_SEED[band.coerceIn(0, BAND_COUNT - 1)]
    }


    private fun estimateWhRemaining(batteryPct: Int): Double? {
        val capacityWh = batteryCapacityWh ?: return null
        if (capacityWh <= 0 || batteryPct <= 0) return if (batteryPct <= 0) 0.0 else null

        var whRemaining = 0.0
        var pct = 0
        while (pct < batteryPct) {
            val band = (pct / BAND_WIDTH).coerceIn(0, BAND_COUNT - 1)
            val bandTopPct = minOf(batteryPct, ((band + 1) * BAND_WIDTH))
            val pctInBand = bandTopPct - pct
            if (pctInBand <= 0) break
            whRemaining += pctInBand * (capacityWh / 100.0) * bandEfficiency(band)
            pct = bandTopPct
        }
        return whRemaining
    }


    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun medianAbsoluteDeviation(values: Collection<Double>, med: Double): Double {
        return median(values.map { abs(it - med) })
    }


    private fun persistModeSamples(mode: Int) {
        val pool = modeSamples[mode] ?: return
        val editor = prefs.edit()
        editor.putInt(PREFS_PREFIX + "mode_${mode}_count", pool.size)
        pool.forEachIndexed { i, v -> editor.putFloat(PREFS_PREFIX + "mode_${mode}_$i", v.toFloat()) }
        editor.apply()
    }

    private fun persistBandSamples(band: Int) {
        val pool = bandSamples[band]
        val editor = prefs.edit()
        editor.putInt(PREFS_PREFIX + "band_${band}_count", pool.size)
        pool.forEachIndexed { i, v -> editor.putFloat(PREFS_PREFIX + "band_${band}_$i", v.toFloat()) }
        editor.apply()
    }

    private fun loadAll() {
        batteryCapacityWh = prefs.getInt(PREFS_PREFIX + "capacity_wh", -1).let { if (it > 0) it else null }

        for (mode in 0..4) {
            val count = prefs.getInt(PREFS_PREFIX + "mode_${mode}_count", 0)
            if (count <= 0) continue
            val pool = ArrayDeque<Double>()
            for (i in 0 until count) {
                val v = prefs.getFloat(PREFS_PREFIX + "mode_${mode}_$i", -1f)
                if (v > 0f) pool.addLast(v.toDouble())
            }
            if (pool.isNotEmpty()) modeSamples[mode] = pool
        }

        for (band in 0 until BAND_COUNT) {
            val count = prefs.getInt(PREFS_PREFIX + "band_${band}_count", 0)
            if (count <= 0) continue
            for (i in 0 until count) {
                val v = prefs.getFloat(PREFS_PREFIX + "band_${band}_$i", -1f)
                if (v > 0f) bandSamples[band].addLast(v.toDouble())
            }
        }

        Log.i(
            TAG,
            "Loaded: capacity=${batteryCapacityWh} modes=${modeSamples.mapValues { it.value.size }} " +
                    "bands=${bandSamples.map { it.size }}"
        )
    }


    private fun openRideLog() {
        val file = java.io.File(context.filesDir, TEMP_LOG_FILENAME)
        if (file.exists() && file.length() > 30L) {
            Log.w(TAG, "Found leftover temp log (${file.length()} bytes) — saving to Downloads before new session")
            saveFileToDownloads(file, recovered = true)
        }
        file.writeText("timestamp,event,details\n")
        synchronized(rideLogLock) {
            tempLogFile    = file
            rideLogBuffer  = StringBuilder()
            rideLogHasData = false
            lastFlushMs    = System.currentTimeMillis()
        }
        Log.i(TAG, "Range log opened: ${file.absolutePath}")
    }

    private fun flushLogToDisk() {
        val file: java.io.File?
        val chunk: String
        synchronized(rideLogLock) {
            file = tempLogFile
            val buf = rideLogBuffer
            if (file == null || buf == null || buf.isEmpty()) return
            chunk = buf.toString()
            buf.clear()
            lastFlushMs = System.currentTimeMillis()
        }
        try {
            file!!.appendText(chunk)
            Log.i(TAG, "Range log flushed (${chunk.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Range log flush failed: ${e.message}")
            synchronized(rideLogLock) { rideLogBuffer?.insert(0, chunk) }
        }
    }

    private fun closeRideLog() {
        flushLogToDisk()
        val file: java.io.File?
        val hadData: Boolean
        synchronized(rideLogLock) {
            file           = tempLogFile
            hadData        = rideLogHasData
            tempLogFile    = null
            rideLogBuffer  = null
            rideLogHasData = false
        }
        if (file == null || !file.exists() || !hadData) {
            Log.i(TAG, "Range log: nothing to save")
            file?.delete()
            return
        }
        saveFileToDownloads(file, recovered = false)
    }

    private fun saveFileToDownloads(file: java.io.File, recovered: Boolean) {
        try {
            val bytes  = file.readBytes()
            val stamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (recovered) "SmartBridge_RangeLog_RECOVERED_" else "SmartBridge_RangeLog_"
            val fileName = "$prefix$stamp.csv"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes); os.flush() }
                Log.i(TAG, "Range log saved: $fileName (${bytes.size} bytes)")
                file.delete()
            } else {
                Log.e(TAG, "Range log: MediaStore insert returned null URI — temp file kept at ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save range log: ${e.message} — temp file kept at ${file.absolutePath}")
        }
    }

    fun logExternal(event: String, details: String) = log(event, details)

    private fun log(event: String, details: String) {
        if (LOGGING_ENABLED) {
            val ts = System.currentTimeMillis()
            synchronized(rideLogLock) {
                rideLogBuffer?.let {
                    it.append("$ts,$event,\"$details\"\n")
                    rideLogHasData = true
                }
            }
            if (ts - lastFlushMs >= LOG_FLUSH_INTERVAL_MS) flushLogToDisk()
            Log.d(TAG, "$event: $details")
        }
    }
}