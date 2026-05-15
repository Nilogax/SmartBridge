/*
 * SmartBridge ANT+ Bicycle Power sensor
 */

#ifdef CONFIG_SMARTBRIDGE_ANT

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

#include <ant_interface.h>
#include <ant_key_manager.h>
#include <ant_profiles/bpwr/ant_bpwr.h>
#include "ant_common_page_82.h"
#include "version.h"

LOG_MODULE_REGISTER(smartbridge_ant, LOG_LEVEL_INF);

/* ── Channel configuration ───────────────────────────────────────────────── */
#define ANT_BPWR_CHANNEL_NUM    0
#define ANT_BPWR_NETWORK_NUM    0
#define ANT_BPWR_TRANS_TYPE     5

#define ANT_DEVICE_NUMBER_DEFAULT  49

#define ANT_HW_VERSION          1
#define ANT_MFG_ID              255
#define ANT_MODEL_NUM           1
#define ANT_SW_VERSION_MAJOR    10*FW_VERSION_MAJOR + FW_VERSION_MINOR
#define ANT_SW_VERSION_MINOR    FW_VERSION_PATCH
#define ANT_SERIAL_NUM          ((uint32_t)(NRF_FICR->DEVICEADDR[0]))

/*
 * ── Page 82 interleave ────────────────────────────────────────────────────
*/
#define ANT_P82_INTERLEAVE_COUNT   122u

/* Battery voltage thresholds in mV */
#define BATT_THRESH_LOW_MV      3500u
#define BATT_THRESH_CRIT_MV     3200u

/* ── Module state ────────────────────────────────────────────────────────── */
static ant_bpwr_profile_t        bpwr;
static ant_common_page82_data_t  s_page82     = DEFAULT_ANT_COMMON_page82();
static uint32_t                  s_tx_count   = 0;

/* Forward declarations needed by config macros */
static void ant_bpwr_evt_handler(ant_bpwr_profile_t *p_profile,
                                  ant_bpwr_evt_t event);
static void ant_bpwr_calib_handler(ant_bpwr_profile_t *p_profile,
                                    ant_bpwr_page1_data_t *p_page1);

BPWR_SENS_CHANNEL_CONFIG_DEF(bpwr,
    ANT_BPWR_CHANNEL_NUM,
    ANT_BPWR_TRANS_TYPE,
    ANT_DEVICE_NUMBER_DEFAULT,
    ANT_BPWR_NETWORK_NUM);

BPWR_SENS_PROFILE_CONFIG_DEF(bpwr,
    TORQUE_NONE,
    ant_bpwr_calib_handler,
    ant_bpwr_evt_handler);

/* ── Profile event handler ───────────────────────────────────────────────── */
static void ant_bpwr_evt_handler(ant_bpwr_profile_t *p_profile,
                                  ant_bpwr_evt_t event)
{
    ARG_UNUSED(p_profile);
    ARG_UNUSED(event);
}

/* ── Calibration handler ─────────────────────────────────────────────────── */
static void ant_bpwr_calib_handler(ant_bpwr_profile_t *p_profile,
                                    ant_bpwr_page1_data_t *p_page1)
{
    switch (p_page1->calibration_id) {
    case ANT_BPWR_CALIB_ID_MANUAL:
    case ANT_BPWR_CALIB_ID_AUTO:
        p_profile->BPWR_PROFILE_calibration_id     = ANT_BPWR_CALIB_ID_MANUAL_SUCCESS;
        p_profile->BPWR_PROFILE_general_calib_data = 0;
        break;
    default:
        break;
    }
}

/* ── ANT event callback ──────────────────────────────────────────────────── */
static void ant_evt_handler(ant_evt_t *p_ant_evt)
{
    if (p_ant_evt->channel != ANT_BPWR_CHANNEL_NUM) {
        return;
    }

    /*
     * On every ANT_P82_INTERLEAVE_COUNT-th TX event, encode page 82
     * ourselves and skip the library's ant_message_send for that slot.
     */
    if (p_ant_evt->event == EVENT_TX) {
        s_tx_count++;
        if (s_tx_count >= ANT_P82_INTERLEAVE_COUNT) {
            s_tx_count = 0;

            uint8_t buf[ANT_STANDARD_DATA_PAYLOAD_SIZE];
            ant_common_page_82_encode(buf, &s_page82);

            int err = ant_broadcast_message_tx(ANT_BPWR_CHANNEL_NUM,
                                               sizeof(buf), buf);
            if (err) {
                LOG_WRN("Page 82 TX failed (err %d)", err);
            } else {
                LOG_INF("ANT: Page 82 sent (status=%u)", s_page82.battery_status);
            }
            /*
             * Return WITHOUT forwarding to ant_bpwr_sens_evt_handler.
             */
            return;
        }
    }

    /* All other events (including normal EVENT_TX) go to the library */
    ant_bpwr_sens_evt_handler(p_ant_evt, &bpwr);
}

/* ── Public API ──────────────────────────────────────────────────────────── */

int ant_bpwr_setup(void)
{
    int err;

    err = ant_init();
    if (err) {
        LOG_ERR("ant_init failed (err %d)", err);
        return err;
    }
    LOG_INF("ANT version: %s", ANT_VERSION_STRING);

    err = ant_cb_register(ant_evt_handler);
    if (err) {
        LOG_ERR("ant_cb_register failed (err %d)", err);
        return err;
    }

    err = ant_plus_key_set(ANT_BPWR_NETWORK_NUM);
    if (err) {
        LOG_ERR("ant_network_key_set failed (err %d)", err);
        return err;
    }

    err = ant_bpwr_sens_init(&bpwr,
                              BPWR_SENS_CHANNEL_CONFIG(bpwr),
                              BPWR_SENS_PROFILE_CONFIG(bpwr));
    if (err) {
        LOG_ERR("ant_bpwr_sens_init failed (err %d)", err);
        return err;
    }

    uint16_t dev_num = (uint16_t)(NRF_FICR->DEVICEADDR[0] & 0xFFFF);
    ant_channel_id_set(ANT_BPWR_CHANNEL_NUM,
                       dev_num,
                       BPWR_DEVICE_TYPE,
                       ANT_BPWR_TRANS_TYPE);

    bpwr.page_80 = ANT_COMMON_page80(ANT_HW_VERSION,
                                      ANT_MFG_ID,
                                      ANT_MODEL_NUM);
    bpwr.page_81 = ANT_COMMON_page81(ANT_SW_VERSION_MAJOR,
                                      ANT_SW_VERSION_MINOR,
                                      ANT_SERIAL_NUM);

    bpwr.BPWR_PROFILE_auto_zero_status = ANT_BPWR_AUTO_ZERO_OFF;

    err = ant_bpwr_sens_open(&bpwr);
    if (err) {
        LOG_ERR("ant_bpwr_sens_open failed (err %d)", err);
        return err;
    }

    LOG_INF("ANT+ Bicycle Power channel open (device %u)", dev_num);
    return 0;
}

void ant_bpwr_update(uint16_t watts, uint8_t cadence, uint8_t left_pct)
{
    bpwr.BPWR_PROFILE_power_update_event_count++;
    bpwr.BPWR_PROFILE_accumulated_power    += watts;
    bpwr.BPWR_PROFILE_instantaneous_power   = watts;
    bpwr.BPWR_PROFILE_instantaneous_cadence = cadence;

    if (left_pct <= 100) {
        bpwr.BPWR_PROFILE_pedal_power.distribution    = 100u - left_pct;
        bpwr.BPWR_PROFILE_pedal_power.differentiation = 1;
    } else {
        bpwr.page_16.pedal_power.byte = 0xFF;
    }
}

void ant_bpwr_update_battery(uint16_t vbat_mv)
{
    uint8_t coarse = (uint8_t)(vbat_mv / 1000u);
    uint8_t frac   = (uint8_t)(((vbat_mv % 1000u) * 256u) / 1000u);

    uint8_t status;
    if      (vbat_mv < BATT_THRESH_CRIT_MV) { status = ANT_COMMON_PAGE82_BATTERY_STATUS_CRITICAL; }
    else if (vbat_mv < BATT_THRESH_LOW_MV)  { status = ANT_COMMON_PAGE82_BATTERY_STATUS_LOW;      }
    else if (vbat_mv < 3800u)               { status = ANT_COMMON_PAGE82_BATTERY_STATUS_OK;       }
    else if (vbat_mv < 4100u)               { status = ANT_COMMON_PAGE82_BATTERY_STATUS_GOOD;     }
    else                                    { status = ANT_COMMON_PAGE82_BATTERY_STATUS_NEW;      }

    s_page82 = ANT_COMMON_page82(
        0,        /* cumulative_op_time not tracked */
        coarse,
        frac,
        status);

    LOG_INF("Battery: %u mV -> coarse=%u frac=%u", vbat_mv, coarse, frac);
    LOG_INF("Battery status: %u", status);
}

void ant_bpwr_stop(void)
{
    int err = ant_channel_close(ANT_BPWR_CHANNEL_NUM);
    if (err) {
        LOG_WRN("ant_channel_close failed (err %d)", err);
    } else {
        LOG_INF("ANT+ channel closed");
    }
}

void ant_bpwr_start(void)
{
    s_tx_count = 0;
    int err = ant_bpwr_sens_open(&bpwr);
    if (err) {
        LOG_WRN("ant_bpwr_sens_open failed on wake (err %d)", err);
    } else {
        LOG_INF("ANT+ channel reopened");
    }
}

#endif /* CONFIG_SMARTBRIDGE_ANT */