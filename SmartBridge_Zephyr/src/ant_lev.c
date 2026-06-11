
#ifdef CONFIG_SMARTBRIDGE_ANT

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <ant_interface.h>
#include <ant_parameters.h>
#include <ant_host_init.h>
#include <ant_channel_config.h>
#include <ant_key_manager.h>
#include "version.h"

LOG_MODULE_REGISTER(smartbridge_lev, LOG_LEVEL_INF);

#define ANT_LEV_CHANNEL_NUM     1
#define ANT_LEV_NETWORK_NUM     0
#define ANT_LEV_DEVICE_TYPE     0x14u
#define ANT_LEV_TRANS_TYPE      0x05u
#define ANT_LEV_RF_FREQ         0x39u
#define ANT_LEV_MSG_PERIOD      8192u
#define ANT_LEV_EXT_ASSIGN      0x00u

/* Pages 80/81 */
#define ANT_LEV_HW_REVISION     1
#define ANT_LEV_MFG_ID          63          /* Pretend to be Specialized */
#define ANT_LEV_MODEL_NUM       2
#define ANT_LEV_SERIAL_NUM      ((uint32_t)(NRF_FICR->DEVICEADDR[0]))


#define ANT_LEV_NUM_ASSIST_MODES  4u   /* excluding off */

#define COMMON_PAGE_INTERVAL    40u

/* ── Module state ────────────────────────────────────────────────────────── */
static uint8_t  s_soc_pct     = 0;
static uint8_t  s_assist_mode = 0;   /* internal 0-4 */
static uint8_t  s_assist_pct  = 0;
static uint32_t s_odometer    = 0;   /* 0.01 km units, 24-bit               */
static uint16_t s_speed       = 0;   /* 0.1 km/h units, 12-bit              */
static uint16_t s_range_km    = 0;   /* whole km; 0 = unknown               */
static uint32_t s_msg_count   = 0;

static void lev_ant_evt_handler(ant_evt_t *p_ant_evt);

static const ant_channel_config_t s_lev_channel_cfg = {
    .channel_number    = ANT_LEV_CHANNEL_NUM,
    .channel_type      = CHANNEL_TYPE_MASTER,
    .ext_assign        = ANT_LEV_EXT_ASSIGN,
    .rf_freq           = ANT_LEV_RF_FREQ,
    .transmission_type = ANT_LEV_TRANS_TYPE,
    .device_type       = ANT_LEV_DEVICE_TYPE,
    .device_number     = 0,
    .channel_period    = ANT_LEV_MSG_PERIOD,
    .network_number    = ANT_LEV_NETWORK_NUM,
};

/* ── Travel mode byte helper ─────────────────────────────────────────────── */

static inline uint8_t travel_mode_byte(void)
{
    uint8_t mode = s_assist_mode < 5u ? s_assist_mode : 4u;
    return (uint8_t)((mode & 0x07u) << 3);
}

/* ── Page encoders ───────────────────────────────────────────────────────── */

/*
 * Page 1 – Speed & System Information 1 (Table 5-2)
 *
 * Byte 0: 0x01
 * Byte 1: Temperature state — 0x00 = unknown
 * Byte 2: Travel mode state (Table 5-4)
 * Byte 3: System state — 0x00 = no lights/throttle/signals
 * Byte 4: Gear state   — 0x00 = no gears
 * Byte 5: Error message — 0x00 = no error
 * Byte 6: Speed LSB (12-bit, 0.1 km/h units)
 * Byte 7: Speed MSN (bits 3-0) | 0xF0 (reserved bits 7-4 = 0xF per spec)
 */
static void encode_page1(uint8_t *buf)
{
    uint16_t spd = s_speed & 0x0FFFu;
    buf[0] = 0x01u;
    buf[1] = 0x00u;           /* temperature unknown                        */
    buf[2] = travel_mode_byte();
    buf[3] = 0x00u;           /* system state: nothing supported            */
    buf[4] = 0x00u;           /* gear state: no gears                       */
    buf[5] = 0x00u;           /* no error                                   */
    buf[6] = (uint8_t)(spd & 0xFFu);
    buf[7] = (uint8_t)(0xF0u | ((spd >> 8) & 0x0Fu));
}

/*
 * Page 2 – Speed & Distance Information (Table 5-8)
 *
 * Byte 0: 0x02
 * Byte 1-3: Odometer (24-bit LE, 0.01 km)
 * Byte 4:   Remaining range LSB (12-bit km; 0x000 = unknown)
 * Byte 5:   Remaining range MSN (bits 3-0) | 0xF0 (reserved)
 * Byte 6:   Speed LSB (0.1 km/h, 12-bit)
 * Byte 7:   Speed MSN | 0xF0
 */
static void encode_page2(uint8_t *buf)
{
    uint32_t odo  = s_odometer & 0xFFFFFFu;
    uint16_t rng  = s_range_km & 0x0FFFu;
    uint16_t spd  = s_speed    & 0x0FFFu;
    buf[0] = 0x02u;
    buf[1] = (uint8_t)(odo & 0xFFu);
    buf[2] = (uint8_t)((odo >> 8) & 0xFFu);
    buf[3] = (uint8_t)((odo >> 16) & 0xFFu);
    buf[4] = (uint8_t)(rng & 0xFFu);
    buf[5] = (uint8_t)(0xF0u | ((rng >> 8) & 0x0Fu));
    buf[6] = (uint8_t)(spd & 0xFFu);
    buf[7] = (uint8_t)(0xF0u | ((spd >> 8) & 0x0Fu));
}

/*
 * Page 3 – Speed & System Information 2 (Table 5-10)
 *
 * Byte 0: 0x03
 * Byte 1: Battery SOC — bits 6-0 = %, bit 7 = empty warning
 * Byte 2: Travel mode state (same as page 1)
 * Byte 3: System state — 0x00
 * Byte 4: Gear state   — 0x00
 * Byte 5: % Assist     — 0xFF = unknown, or actual value 0-100
 * Byte 6: Speed LSB (0.1 km/h, 12-bit)
 * Byte 7: Speed MSN | 0xF0
 */
static void encode_page3(uint8_t *buf)
{
    uint8_t soc = s_soc_pct & 0x7Fu;   /* bit 7 = empty warning (set if <5%) */
    if (s_soc_pct < 5u) {
        soc |= 0x80u;
    }
    uint16_t spd = s_speed & 0x0FFFu;
    buf[0] = 0x03u;
    buf[1] = soc;
    buf[2] = travel_mode_byte();
    buf[3] = 0x00u;
    buf[4] = 0x00u;
    buf[5] = s_assist_pct;
    buf[6] = (uint8_t)(spd & 0xFFu);
    buf[7] = (uint8_t)(0xF0u | ((spd >> 8) & 0x0Fu));
}

/*
 * Page 5 – LEV Capabilities (Table 5-12)
 *
 * Byte 0: 0x05
 * Byte 1: 0xFF reserved
 * Byte 2: Travel modes supported — bits 5-3 = num assist, bits 2-0 = num regen
 * Byte 3: Wheel circumference LSB — 0x00 = unknown
 * Byte 4: Wheel circ MSN (bits 3-0) = 0, reserved bits 7-4 = 0xF
 * Bytes 5-7: 0xFF
 */
static void encode_page5(uint8_t *buf)
{
    buf[0] = 0x05u;
    buf[1] = 0xFFu;
    buf[2] = (uint8_t)((ANT_LEV_NUM_ASSIST_MODES & 0x07u) << 3); /* 4 assist, 0 regen */
    buf[3] = 0x00u;   /* wheel circumference unknown                        */
    buf[4] = 0xF0u;   /* MSN=0 (unknown), reserved=0xF                     */
    buf[5] = 0xFFu;
    buf[6] = 0xFFu;
    buf[7] = 0xFFu;
}

/*
 * Page 80 – Manufacturer info (Table 5-17)
 */
static void encode_page80(uint8_t *buf)
{
    buf[0] = 0x50u;
    buf[1] = 0xFFu;
    buf[2] = 0xFFu;
    buf[3] = ANT_LEV_HW_REVISION;
    buf[4] = (uint8_t)(ANT_LEV_MFG_ID & 0xFFu);
    buf[5] = (uint8_t)(ANT_LEV_MFG_ID >> 8);
    buf[6] = (uint8_t)(ANT_LEV_MODEL_NUM & 0xFFu);
    buf[7] = (uint8_t)(ANT_LEV_MODEL_NUM >> 8);
}

/*
 * Page 81 – Product info (Table 5-18)
 */
static void encode_page81(uint8_t *buf)
{
    buf[0] = 0x51u;
    buf[1] = 0xFFu;
    buf[2] = 0xFFu;
    buf[3] = FW_VERSION_MINOR;
    buf[4] = (uint8_t)(ANT_LEV_SERIAL_NUM);
    buf[5] = (uint8_t)(ANT_LEV_SERIAL_NUM >> 8);
    buf[6] = (uint8_t)(ANT_LEV_SERIAL_NUM >> 16);
    buf[7] = (uint8_t)(ANT_LEV_SERIAL_NUM >> 24);
}

/* ── TX rotation ─────────────────────────────────────────────────────────── */
static void lev_send_next(void)
{
    uint8_t buf[ANT_STANDARD_DATA_PAYLOAD_SIZE];
    uint8_t slot = (uint8_t)(s_msg_count % 4u);

    switch (slot) {
    case 0:
        encode_page1(buf);
        break;
    case 1:
        encode_page2(buf);
        break;
    case 2:
        encode_page3(buf);
        break;
    case 3:
    default:
        /*
         * 4th slot rotates: page 5 normally, page 80 every 40 messages,
         * page 81 every 80 messages, then reset.
         * s_msg_count is the count of the message about to be sent (pre-increment).
         */
        if (s_msg_count == COMMON_PAGE_INTERVAL - 1u) {
            encode_page80(buf);
        } else if (s_msg_count == COMMON_PAGE_INTERVAL * 2u - 1u) {
            encode_page81(buf);
        } else {
            encode_page5(buf);
        }
        break;
    }

    s_msg_count++;
    if (s_msg_count >= COMMON_PAGE_INTERVAL * 2u) {
        s_msg_count = 0u;
    }

    int err = ant_broadcast_message_tx(ANT_LEV_CHANNEL_NUM, sizeof(buf), buf);
    if (err) {
        LOG_WRN("LEV TX failed (err %d)", err);
    }
}

/* ── Page 16 decoder (Display → LEV) ────────────────────────────────────── */
/*
 * Page 16 – Display Data (Table 5-14)
*
 * Byte 0: 0x10
 * Byte 1: Wheel circumference LSB  (0xFF = not set by display)
 * Byte 2: Wheel circ MSN / reserved
 * Byte 3: Travel mode (Table 5-4 bits 5-0); 0xFF = not set
 * Byte 4: Display command LSB
 * Byte 5: Display command MSB
 * Byte 6: Manufacturer ID LSB
 * Byte 7: Manufacturer ID MSB
 */
static void handle_page16(const uint8_t *buf)
{
    uint8_t travel_mode = buf[3];
    if (travel_mode == 0xFFu) {
        return;
    }

    /* Extract assist level from bits 5-3 and clamp to our 0-4 range */
    uint8_t requested = (travel_mode >> 3) & 0x07u;
    if (requested > 4u) requested = 4u;
    s_assist_mode = requested;
    LOG_INF("Page 16: display requested assist %u", requested);
}

static void lev_ant_evt_handler(ant_evt_t *p_ant_evt)
{
    if (p_ant_evt->channel != ANT_LEV_CHANNEL_NUM) {
        return;
    }

    switch (p_ant_evt->event) {
    case EVENT_TX:
        lev_send_next();
        break;

    case EVENT_RX:
        /*
         * The Garmin may send page 16 as an acknowledged message on the
         * reverse channel. The payload is in p_ant_evt->message.ANT_MESSAGE_aucPayload.
         */
        if (p_ant_evt->message.ANT_MESSAGE_aucPayload[0] == 0x10u) {
            handle_page16(p_ant_evt->message.ANT_MESSAGE_aucPayload);
        }
        break;

    case EVENT_RX_FAIL:
        /* Normal on a master channel — slave didn't respond, not an error */
        break;

    case EVENT_CHANNEL_CLOSED:
        LOG_INF("LEV channel closed");
        break;

    default:
        break;
    }
}


int ant_lev_setup(void)
{
    int err;

    uint16_t dev_num = (uint16_t)((NRF_FICR->DEVICEADDR[0] & 0xFFFF) ^ 0x0001u);
    if (dev_num == 0u) {
        dev_num = 1u;
    }

    err = ant_cb_register(lev_ant_evt_handler);
    if (err) {
        LOG_ERR("ant_cb_register (LEV) failed (err %d)", err);
        return err;
    }

    ant_channel_config_t cfg = s_lev_channel_cfg;
    cfg.device_number = dev_num;

    err = ant_channel_init(&cfg);
    if (err) {
        LOG_ERR("ant_channel_init (LEV) failed (err %d)", err);
        return err;
    }

    /* Seed TX buffer with page 1 before opening */
    uint8_t buf[ANT_STANDARD_DATA_PAYLOAD_SIZE];
    encode_page1(buf);
    err = ant_broadcast_message_tx(ANT_LEV_CHANNEL_NUM, sizeof(buf), buf);
    if (err) {
        LOG_ERR("LEV initial TX failed (err %d)", err);
        return err;
    }

    err = ant_channel_open(ANT_LEV_CHANNEL_NUM);
    if (err) {
        LOG_ERR("ant_channel_open (LEV) failed (err %d)", err);
        return err;
    }

    LOG_INF("ANT+ LEV channel open (device %u, mfg %u)", dev_num, ANT_LEV_MFG_ID);
    return 0;
}

void ant_lev_update(uint8_t battery_soc_pct, uint8_t assist_mode, uint8_t assist_pct,
                    uint32_t odometer_cm, uint16_t speed_dmkh, uint16_t range_km)
{
    s_soc_pct     = (battery_soc_pct <= 100u) ? battery_soc_pct : 100u;
    s_assist_mode = assist_mode;
    s_assist_pct  = assist_pct;
    s_odometer    = odometer_cm  & 0xFFFFFFu;
    s_speed       = speed_dmkh   & 0x0FFFu;
    s_range_km    = range_km     & 0x0FFFu;
}

void ant_lev_stop(void)
{
    int err = ant_channel_close(ANT_LEV_CHANNEL_NUM);
    if (err) {
        LOG_WRN("ant_channel_close (LEV) failed (err %d)", err);
    } else {
        LOG_INF("ANT+ LEV channel closed");
    }
}

void ant_lev_start(void)
{
    s_msg_count = 0;

    uint8_t buf[ANT_STANDARD_DATA_PAYLOAD_SIZE];
    encode_page1(buf);
    ant_broadcast_message_tx(ANT_LEV_CHANNEL_NUM, sizeof(buf), buf);

    int err = ant_channel_open(ANT_LEV_CHANNEL_NUM);
    if (err) {
        LOG_WRN("ant_channel_open (LEV) failed on wake (err %d)", err);
    } else {
        LOG_INF("ANT+ LEV channel reopened");
    }
}

#endif /* CONFIG_SMARTBRIDGE_ANT */