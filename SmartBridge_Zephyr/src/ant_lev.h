
#ifndef SMARTBRIDGE_ANT_LEV_H
#define SMARTBRIDGE_ANT_LEV_H

#ifdef CONFIG_SMARTBRIDGE_ANT

#include <stdint.h>

/**
 * Initialise and open the ANT+ LEV sensor channel.
 * Call once after ant_bpwr_setup().
 * Returns 0 on success, negative error code on failure.
 */
int ant_lev_setup(void);

/**
 * Update LEV data. Call whenever values change (e.g. every 750 ms).
 *
 * @param battery_soc_pct   Battery state of charge 0-100 %
 * @param assist_mode       Internal assist level 0-4
 * @param assist_pct        Assistance level 0-100 % (motor / total power)
 * @param odometer_cm       Odometer in 0.01 km units (metres / 10), 24-bit
 * @param speed_dmkh        Speed in 0.1 km/h units (0.01 km/h value / 10), 12-bit
 * @param range_km          Estimated remaining range in whole km; 0 = unknown
 */
void ant_lev_update(uint8_t battery_soc_pct, uint8_t assist_mode, uint8_t assist_pct,
                    uint32_t odometer_cm, uint16_t speed_dmkh, uint16_t range_km);

/**
 * Close the LEV channel. Call from enter_sleep().
 */
void ant_lev_stop(void);

/**
 * Re-open the LEV channel. Call from wake_up().
 */
void ant_lev_start(void);

#endif /* CONFIG_SMARTBRIDGE_ANT */
#endif /* SMARTBRIDGE_ANT_LEV_H */