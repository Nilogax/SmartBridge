/*
 * SmartBridge ANT+ Bicycle Power interface
 */
#ifndef SMARTBRIDGE_ANT_BPWR_H
#define SMARTBRIDGE_ANT_BPWR_H

#ifdef CONFIG_SMARTBRIDGE_ANT

/**
 * Initialise ANT stack, register the bicycle power sensor channel,
 * and open it. Call once at the top of main() before the main loop.
 * Returns 0 on success, negative error code on failure.
 */
int ant_bpwr_setup(void);

/**
 * Update the transmitted power data. Call every 250 ms alongside
 * the existing BLE send_power() call.
 *
 * @param watts      Instantaneous power (W)
 * @param cadence    Instantaneous cadence (rpm)
 * @param left_pct   Left pedal balance (0-100, >100 = not available)
 */
void ant_bpwr_update(uint16_t watts, uint8_t cadence, uint8_t left_pct);

/**
 * Update ANT+ Page 82 (Battery Status). Call whenever a fresh ADC
 * reading is available — every 60 s is sufficient.
 *
 * @param vbat_mv       Battery voltage in millivolts (e.g. 3700).
 * @param op_time_16s   Cumulative operating time in 16-second units.
 *                      Pass 0 if not tracked.
 */
void ant_bpwr_update_battery(uint16_t vbat_mv);

/**
 * Close the ANT channel. Call from enter_sleep().
 */
void ant_bpwr_stop(void);

/**
 * Re-open the ANT channel. Call from wake_up().
 */
void ant_bpwr_start(void);

#endif /* CONFIG_SMARTBRIDGE_ANT */
#endif /* SMARTBRIDGE_ANT_BPWR_H */