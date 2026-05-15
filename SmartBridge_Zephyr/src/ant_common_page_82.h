/*
 * ANT+ Common Page 82 (0x52) - Battery Status
 * Not present in NCS 2.6.0 ANT library.
 *
 * Byte layout:
 *   [0] 0x52  page number
 *   [1] 0xFF  reserved
 *   [2] 0xFF  battery identifier (0xFF = single battery / not used)
 *   [3] op_time bits  0-7
 *   [4] op_time bits  8-15
 *   [5] op_time bits 16-23  (resolution flag NOT used here; kept simple)
 *   [6] fractional voltage  (1/256 V per LSB)
 *   [7] bits 3-0 = coarse voltage (0-14 V; 15 = invalid)
 *       bits 6-4 = battery status
 *       bit    7 = 0 (reserved)
 *
 */
#ifndef ANT_COMMON_PAGE_82_H__
#define ANT_COMMON_PAGE_82_H__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ANT_COMMON_PAGE_82  (82u)   ///< Page number (0x52)

/* ── Battery status nibble (bits 6-4 of byte 7) ─────────────────────────── */
#define ANT_COMMON_PAGE82_BATTERY_STATUS_NEW        (1u)
#define ANT_COMMON_PAGE82_BATTERY_STATUS_GOOD       (2u)
#define ANT_COMMON_PAGE82_BATTERY_STATUS_OK         (3u)
#define ANT_COMMON_PAGE82_BATTERY_STATUS_LOW        (4u)
#define ANT_COMMON_PAGE82_BATTERY_STATUS_CRITICAL   (5u)
#define ANT_COMMON_PAGE82_BATTERY_STATUS_INVALID    (7u)

/**
 * @brief Data structure for ANT+ common page 82.
 *
 * coarse_voltage : whole volts (0-14; 15 = invalid)
 * frac_voltage   : fractional volts in units of 1/256 V (0-255)
 *   e.g. 3.9 V → coarse=3, frac=round(0.9×256)=230
 */
typedef struct
{
    uint32_t cumulative_op_time;    ///< Operating time; 0 if not tracked
    uint8_t  coarse_voltage;        ///< Whole volts (0-14; 15 = invalid)
    uint8_t  frac_voltage;          ///< Fractional voltage (1/256 V per LSB)
    uint8_t  battery_status;        ///< ANT_COMMON_PAGE82_BATTERY_STATUS_*
} ant_common_page82_data_t;

/** @brief Zero-initialise page 82 with invalid status. */
#define DEFAULT_ANT_COMMON_page82()         \
    (ant_common_page82_data_t)              \
    {                                       \
        .cumulative_op_time = 0,            \
        .coarse_voltage     = 15,           \
        .frac_voltage       = 0,            \
        .battery_status     = ANT_COMMON_PAGE82_BATTERY_STATUS_INVALID, \
    }

/** @brief Initialise page 82 with specific values. */
#define ANT_COMMON_page82(op_time, coarse, frac, status)    \
    (ant_common_page82_data_t)                              \
    {                                                       \
        .cumulative_op_time = (op_time),                    \
        .coarse_voltage     = (coarse),                     \
        .frac_voltage       = (frac),                       \
        .battery_status     = (status),                     \
    }

/**
 * @brief Encode page 82 into an 8-byte ANT broadcast buffer.
 */
static inline void ant_common_page_82_encode(
        uint8_t * p_buf,
        ant_common_page82_data_t const * p_page_data)
{
    p_buf[0] = ANT_COMMON_PAGE_82;  /* 0x52                          */
    p_buf[1] = 0xFF;                /* reserved                      */
    p_buf[2] = 0xFF;                /* battery identifier: not used  */

    /* Bytes 3-5: 24-bit cumulative operating time, little-endian    */
    p_buf[3] = (uint8_t)(p_page_data->cumulative_op_time);
    p_buf[4] = (uint8_t)(p_page_data->cumulative_op_time >> 8);
    p_buf[5] = (uint8_t)(p_page_data->cumulative_op_time >> 16);

    /* Byte 6: fractional voltage                                     */
    p_buf[6] = p_page_data->frac_voltage;

    /* Byte 7: coarse voltage (bits 3-0), status (bits 6-4), bit7=0  */
    p_buf[7] = (uint8_t)(
        (p_page_data->coarse_voltage  & 0x0Fu) |
        ((p_page_data->battery_status & 0x07u) << 4));
}

#ifdef __cplusplus
}
#endif

#endif /* ANT_COMMON_PAGE_82_H__ */