#include <hal/nrf_power.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/printk.h>
#include <zephyr/sys/util.h>
#include "version.h"


#ifdef CONFIG_SMARTBRIDGE_ANT
#include "ant_bpwr.h"
#include "ant_lev.h"
#endif

#define FW_VERSION_STR   STRINGIFY(FW_VERSION_MAJOR) "." \
                         STRINGIFY(FW_VERSION_MINOR) "." \
                         STRINGIFY(FW_VERSION_PATCH)
/* ─────────────────────────────────────────────────────────────── */
/* BOARD: XIAO nRF52840 SENSE                                      */
/* ─────────────────────────────────────────────────────────────── */

static const struct gpio_dt_spec led_a    = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec led_b    = GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios);
static const struct gpio_dt_spec led_blue = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);

#define IMU_NODE DT_NODELABEL(lsm6ds3tr_c)
static const struct device *i2c_imu = DEVICE_DT_GET(DT_BUS(IMU_NODE));

static const struct gpio_dt_spec imu_int1 =
    GPIO_DT_SPEC_GET(IMU_NODE, irq_gpios);

static K_SEM_DEFINE(sleep_wake_sem, 0, 1);
static struct k_work save_transport_work;
static struct k_work imu_int_work;

/* ─────────────────────────────────────────────────────────────── */
/* BATTERY — NCS 2.6.0 / nRF52840 SAADC                          */
/*                                                                 */
/* Hardware:  VBAT → 100k/100k divider → AIN7 (P0.31)            */
/*            GPIO P0.14 ACTIVE_LOW enables the divider.          */
/* ADC config: gain=1/6, ref=internal(0.6V), 12-bit              */
/*   Full-scale at AIN7 = 0.6V × 6 = 3.6V                        */
/*   Divider ratio = 1:2  →  VBAT = (raw/4096) × 3.6 × 2         */
/*                                                                 */
/* Power GPIO polarity (ACTIVE_LOW):                              */
/*   GPIO_OUTPUT_INACTIVE → pin HIGH → divider OFF (boot default) */
/*   gpio_pin_set_dt(, 0) → pin LOW  → divider ON                 */
/*   gpio_pin_set_dt(, 1) → pin HIGH → divider OFF                */
/* ─────────────────────────────────────────────────────────────── */

/* Raw SAADC device — parent of channel@7 */
static const struct device *batt_adc_dev =
    DEVICE_DT_GET(DT_PARENT(DT_NODELABEL(batt_channel)));

#define BATT_ADC_CHANNEL_ID  7
#define BATT_ADC_INPUT_POS   8

/* Enable GPIO from the voltage-divider node (ACTIVE_LOW) */
static const struct gpio_dt_spec vbat_en =
    GPIO_DT_SPEC_GET(DT_NODELABEL(vbatt), power_gpios);

/* ─────────────────────────────────────────────────────────────── */
/* STATE MACHINE                                                   */
/* ─────────────────────────────────────────────────────────────── */

enum sleep_wake_state { AWAKE, SLEEP_NORMAL, SLEEP_TRANSPORT };
static enum sleep_wake_state current_state = AWAKE;

static bool transport_mode = false;
static volatile bool     wake_requested = false;
static volatile uint8_t  tap_count      = 0;
static volatile uint32_t first_tap_time = 0;

#define TAP_WINDOW_MS    3000UL
#define TAP_WAKE_COUNT   5
#define SLEEP_TIMEOUT_MS 180000UL

static uint32_t last_activity_time = 0;

#define GPREGRET_TRANSPORT_BIT BIT(0)

/* ─────────────────────────────────────────────────────────────── */
/* CRANK + POWER                                                   */
/* ─────────────────────────────────────────────────────────────── */

static uint16_t crank_revs            = 0;
static uint16_t last_crank_event_time = 0;
static uint32_t last_crank_advance    = 0;

static volatile uint16_t target_cadence = 0;
static volatile int16_t  target_watts   = 0;
static volatile uint8_t  left_balance   = 100;
static volatile uint8_t  target_bikebattery = 100;
static volatile uint8_t  target_assist = 0;
static volatile uint32_t target_odometer  = 0;   /* 0.01 km units, 24-bit  */
static volatile uint16_t target_speed     = 0;   /* 0.1 km/h units, 12-bit */
static volatile uint16_t target_range_km  = 0;   /* whole km; 0 = unknown  */

/* ─────────────────────────────────────────────────────────────── */
/* BATTERY STATE                                                   */
/* ─────────────────────────────────────────────────────────────── */

static uint8_t  battery_percent = 0;
static uint32_t last_batt_read  = 0;

/* LiPo discharge curve endpoints */
#define BAT_FULL    4.01f
#define BAT_EMPTY   3.63f  // Values modified to align with ADC readings vs measured voltage
#define BAT_RANGE   (BAT_FULL - BAT_EMPTY)

#define BAT_EXT_DIV         1.15f
#define BAT_ADC_MV_PER_LSB  (3600.0f * BAT_EXT_DIV / 4096.0f)

/* ─────────────────────────────────────────────────────────────── */
/* BLE UUIDs + CONSTANTS                                           */
/* ─────────────────────────────────────────────────────────────── */

#define BT_UUID_CPS_VAL         0x1818
#define BT_UUID_CPS             BT_UUID_DECLARE_16(BT_UUID_CPS_VAL)
#define BT_UUID_CPS_MEASUREMENT BT_UUID_DECLARE_16(0x2A63)
#define BT_UUID_CPF             BT_UUID_DECLARE_16(0x2A65)
#define BT_UUID_CSL             BT_UUID_DECLARE_16(0x2A5D)
#define BT_UUID_CPCP            BT_UUID_DECLARE_16(0x2A66)

#define PKT_POWER_CAD_BAL    0x01
#define PKT_TRANSPORT_MODE   0x02
#define PKT_TRANSPORT_STATUS 0x81

/*
 * Custom Android service UUIDs — BT_UUID_INIT_128 takes bytes LSB first.
 * 12345678-1234-1234-1234-123456789ABC → BC,9A,78,56,34,12,34,12,34,12,34,12,78,56,34,12
 */
static const struct bt_uuid_128 custom_svc_uuid =
    BT_UUID_INIT_128(0xBC,0x9A,0x78,0x56,0x34,0x12,0x34,0x12,
                     0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12);

static const struct bt_uuid_128 custom_rx_uuid =
    BT_UUID_INIT_128(0xBD,0x9A,0x78,0x56,0x34,0x12,0x34,0x12,
                     0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12);

static const struct bt_uuid_128 custom_tx_uuid =
    BT_UUID_INIT_128(0xBE,0x9A,0x78,0x56,0x34,0x12,0x34,0x12,
                     0x34,0x12,0x34,0x12,0x78,0x56,0x34,0x12);

/* Track which connection is which for BLE connections */
static struct bt_conn *garmin_conn  = NULL;
static struct bt_conn *android_conn = NULL;
static struct bt_conn *last_conn    = NULL;

static uint8_t cpm_buf[9];

/* Advertising packet — primary (UUID16 + name) */
static const struct bt_data ad[] = {
    BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
    BT_DATA_BYTES(BT_DATA_UUID16_ALL, 0x18, 0x18),
};

static const struct bt_le_adv_param adv_param =
    BT_LE_ADV_PARAM_INIT(
        BT_LE_ADV_OPT_CONNECTABLE | BT_LE_ADV_OPT_USE_NAME,
        BT_GAP_ADV_FAST_INT_MIN_2,
        BT_GAP_ADV_FAST_INT_MAX_2,
        NULL);

/* Scan response — custom 128-bit UUID (little-endian) */
static const struct bt_data sd[] = {
    BT_DATA_BYTES(BT_DATA_UUID128_ALL,
        0xBC, 0x9A, 0x78, 0x56, 0x34, 0x12, 0x34, 0x12,
        0x34, 0x12, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12)
};

/* ─────────────────────────────────────────────────────────────── */
/* IMU REGISTERS (LSM6DS3TR-C)                                    */
/* ─────────────────────────────────────────────────────────────── */

#define LSM6DS3_ADDR    0x6A
#define REG_CTRL1_XL    0x10
#define REG_TAP_CFG1    0x58
#define REG_TAP_THS_6D  0x59
#define REG_INT_DUR2    0x5A
#define REG_WAKE_UP_THS 0x5B
#define REG_WAKE_UP_DUR 0x5C
#define REG_MD1_CFG     0x5E
#define REG_WAKE_UP_SRC 0x1B
#define REG_TAP_SRC     0x1C

static int imu_write(uint8_t reg, uint8_t val)
{
    uint8_t buf[2] = { reg, val };
    return i2c_write(i2c_imu, buf, 2, LSM6DS3_ADDR);
}

static int imu_read(uint8_t reg, uint8_t *val)
{
    return i2c_write_read(i2c_imu, LSM6DS3_ADDR, &reg, 1, val, 1);
}

/* ─────────────────────────────────────────────────────────────── */
/* FORWARD DECLARATIONS                                            */
/* ─────────────────────────────────────────────────────────────── */

static void send_status_to_phone(void);
static void wake_up(void);
static void set_leds(void);
static void configure_imu_for_state(void);
static void save_transport_mode(void);

/* ─────────────────────────────────────────────────────────────── */
/* ANDROID RX WRITE HANDLER                                        */
/* ─────────────────────────────────────────────────────────────── */

static ssize_t android_rx_write(struct bt_conn *conn,
                                const struct bt_gatt_attr *attr,
                                const void *buf, uint16_t len,
                                uint16_t offset, uint8_t flags)
{
    /*
     * Packet layout (15 bytes, type 0x01):
     *  [0]     type
     *  [1-2]   watts LE
     *  [3]     cadence
     *  [4]     leftBalance
     *  [5]     batteryPercent
     *  [6]     assistMode
     *  [7-9]   odometer (0.01 km, 24-bit LE)
     *  [10-11] speed (0.1 km/h, 12-bit LE; bits 11-8 in low nibble of [11])
     *  [12-13] remaining range (km, 12-bit LE; bits 11-8 in low nibble of [13])
     *  [14]    XOR checksum of [0]..[13]
     *
     * Backwards-compatible: the legacy 8-byte packet is still accepted.
     */
    if (len < 8) return len;

    const uint8_t *d = buf;

    /* Verify checksum over all payload bytes except the last */
    uint8_t chk = 0;
    for (uint16_t i = 0; i < len - 1u; i++) chk ^= d[i];
    if (chk != d[len - 1u]) return len;

    if (d[0] == PKT_POWER_CAD_BAL) {
        int16_t watts   = (int16_t)((uint16_t)d[1] | ((uint16_t)d[2] << 8));
        uint8_t cadence = d[3];
        uint8_t balance = d[4];
        uint8_t bikebattery = d[5];
        uint8_t assist = d[6];

        if (watts   < 0)    watts   = 0;
        if (watts   > 2500) watts   = 2500;
        if (cadence > 200)  cadence = 200;
        if (balance > 100)  balance = 100;
        if (bikebattery > 100)  bikebattery = 100;

        target_watts   = watts;
        target_cadence = cadence;
        left_balance   = balance;
        target_bikebattery = bikebattery;
        target_assist = assist;

        /* Extended LEV fields — only present in the 15-byte packet */
        if (len >= 15u) {
            target_odometer = (uint32_t)d[7]
                            | ((uint32_t)d[8]  << 8)
                            | ((uint32_t)d[9]  << 16);
            target_speed    = (uint16_t)d[10]
                            | ((uint16_t)(d[11] & 0x0Fu) << 8);
            target_range_km = (uint16_t)d[12]
                            | ((uint16_t)(d[13] & 0x0Fu) << 8);
        }

        if (watts > 0 || cadence > 0)
            last_activity_time = k_uptime_get_32();

    } else if (d[0] == PKT_TRANSPORT_MODE) {
        transport_mode = (d[1] != 0);
        save_transport_mode();
        set_leds();
        send_status_to_phone();
    }

    return len;
}

/* ─────────────────────────────────────────────────────────────── */
/* CPS CHARACTERISTIC READ HANDLERS                                */
/* ─────────────────────────────────────────────────────────────── */

/* Cycling Power Feature — 0x00000011: crank revolution + wheel revolution data */
static uint32_t cpf_value = 0x00000011;

static ssize_t read_cpf(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                        void *buf, uint16_t len, uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             &cpf_value, sizeof(cpf_value));
}

/* Sensor Location — 6 = left crank */
static uint8_t csl_value = 6;

static ssize_t read_csl(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                        void *buf, uint16_t len, uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             &csl_value, sizeof(csl_value));
}

/* Pointer to the CPCP value attr — set after cps_svc is defined below */
static const struct bt_gatt_attr *cpcp_val_attr = NULL;

static struct bt_gatt_indicate_params cpcp_ind_params;
static uint8_t cpcp_response[3];

static void cpcp_indicate_cb(struct bt_conn *conn,
                              struct bt_gatt_indicate_params *params,
                              uint8_t err)
{
    if (err) {
        printk("CPCP indicate failed (err %d)\n", err);
    }
}

/* Cycling Power Control Point — reply "not supported" to any opcode.
   Must use bt_gatt_indicate (not notify) because CCCD is 0x0002. */
static ssize_t write_cpcp(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                           const void *buf, uint16_t len,
                           uint16_t offset, uint8_t flags)
{
    if (len < 1) return len;
    const uint8_t *d = buf;
    printk("CPCP write: opcode=0x%02X\n", d[0]);

    if (garmin_conn && cpcp_val_attr) {
        cpcp_response[0] = 0x20;   /* Response Code opcode */
        cpcp_response[1] = d[0];   /* Request Opcode echoed back */
        cpcp_response[2] = 0x02;   /* Op Code Not Supported */

        cpcp_ind_params.attr    = cpcp_val_attr;
        cpcp_ind_params.func    = cpcp_indicate_cb;
        cpcp_ind_params.destroy = NULL;
        cpcp_ind_params.data    = cpcp_response;
        cpcp_ind_params.len     = sizeof(cpcp_response);

        bt_gatt_indicate(garmin_conn, &cpcp_ind_params);
    }
    return len;
}

/* ─────────────────────────────────────────────────────────────── */
/* GATT SERVICE DEFINITIONS                                        */
/* ─────────────────────────────────────────────────────────────── */

/*
 * CPS service attribute index map:
 *   [0]  Primary service declaration
 *   [1]  CPM characteristic declaration
 *   [2]  CPM characteristic value        ← bt_gatt_notify target for power
 *   [3]  CPM CCC descriptor
 *   [4]  CPF characteristic declaration
 *   [5]  CPF characteristic value
 *   [6]  CSL characteristic declaration
 *   [7]  CSL characteristic value
 *   [8]  CPCP characteristic declaration
 *   [9]  CPCP characteristic value
 *   [10] CPCP CCC descriptor
 */
static void cpm_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value);
static void cpcp_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value);

BT_GATT_SERVICE_DEFINE(cps_svc,
    BT_GATT_PRIMARY_SERVICE(BT_UUID_CPS),

    /* [1,2,3] CPM — notify only */
    BT_GATT_CHARACTERISTIC(BT_UUID_CPS_MEASUREMENT,
        BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_NONE,
        NULL, NULL, NULL),
    BT_GATT_CCC(cpm_ccc_cfg_changed,
        BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

    /* [4,5] CPF — read (mandatory for Garmin) */
    BT_GATT_CHARACTERISTIC(BT_UUID_CPF,
        BT_GATT_CHRC_READ,
        BT_GATT_PERM_READ,
        read_cpf, NULL, NULL),

    /* [6,7] CSL — read (mandatory for Garmin) */
    BT_GATT_CHARACTERISTIC(BT_UUID_CSL,
        BT_GATT_CHRC_READ,
        BT_GATT_PERM_READ,
        read_csl, NULL, NULL),

    /* [8,9,10] CPCP — write + indicate (mandatory for Garmin) */
    BT_GATT_CHARACTERISTIC(BT_UUID_CPCP,
        BT_GATT_CHRC_WRITE | BT_GATT_CHRC_INDICATE,
        BT_GATT_PERM_WRITE,
        NULL, write_cpcp, NULL),
    BT_GATT_CCC(cpcp_ccc_cfg_changed,
        BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* Resolve the CPCP value attr pointer now that cps_svc is defined.
   Called once from main() before advertising starts. */
static void init_cpcp_attr(void)
{
    cpcp_val_attr = &cps_svc.attrs[9];
}

/* ─────────────────────────────────────────────────────────────── */
/* BAS (Battery Service  */

/*
 * BAS service attribute index map:
 *   [0]  Primary service declaration
 *   [1]  Battery level characteristic declaration
 *   [2]  Battery level characteristic value   ← bt_gatt_notify target
 *   [3]  Battery level CCC descriptor
 */
static ssize_t read_battery_level(struct bt_conn *conn,
    const struct bt_gatt_attr *attr, void *buf,
    uint16_t len, uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             attr->user_data, sizeof(uint8_t));
}

static void bas_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    printk("BAS CCC: 0x%04X\n", value);
}

BT_GATT_SERVICE_DEFINE(bas_svc,
    BT_GATT_PRIMARY_SERVICE(BT_UUID_BAS),
    BT_GATT_CHARACTERISTIC(BT_UUID_BAS_BATTERY_LEVEL,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ,
        read_battery_level, NULL, &battery_percent),
    BT_GATT_CCC(bas_ccc_cfg_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* ─────────────────────────────────────────────────────────────── */
/* Android custom service                                          */
/*                                                                 */
/* Attribute index map:                                            */
/*   [0]  Primary service declaration                              */
/*   [1]  RX characteristic declaration                            */
/*   [2]  RX characteristic value                                  */
/*   [3]  TX characteristic declaration                            */
/*   [4]  TX characteristic value  ← bt_gatt_notify target        */
/*   [5]  TX CCC descriptor                                        */
/* ─────────────────────────────────────────────────────────────── */

static void android_tx_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value);

BT_GATT_SERVICE_DEFINE(android_svc,
    BT_GATT_PRIMARY_SERVICE((struct bt_uuid *)&custom_svc_uuid),

    /* [1,2] RX — phone writes ride data here */
    BT_GATT_CHARACTERISTIC(&custom_rx_uuid.uuid,
        BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
        BT_GATT_PERM_WRITE,
        NULL, android_rx_write, NULL),

    /* [3,4,5] TX — board notifies status (battery, transport mode) */
    BT_GATT_CHARACTERISTIC(&custom_tx_uuid.uuid,
        BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_NONE,
        NULL, NULL, NULL),
    BT_GATT_CCC(android_tx_ccc_cfg_changed,
        BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* ─────────────────────────────────────────────────────────────── */
/* CONNECTION CALLBACKS                                            */
/* ─────────────────────────────────────────────────────────────── */

static void connected(struct bt_conn *conn, uint8_t err)
{
    if (err) {
        printk("BT conn failed (err %u)\n", err);
        return;
    }

    char addr[BT_ADDR_LE_STR_LEN];
    bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
    printk("Connected to: %s\n", addr);

    if (last_conn) {
        bt_conn_unref(last_conn);
    }
    last_conn = bt_conn_ref(conn);
    last_activity_time = k_uptime_get_32();
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    printk("Disconnected (reason 0x%02X)\n", reason);

    if (garmin_conn == conn) {
        bt_conn_unref(garmin_conn);
        garmin_conn = NULL;
        printk("Garmin disconnected\n");
    }
    if (android_conn == conn) {
        bt_conn_unref(android_conn);
        android_conn = NULL;
        printk("Android disconnected\n");
    }
    if (last_conn == conn) {
        bt_conn_unref(last_conn);
        last_conn = NULL;
    }

    if (current_state == AWAKE) {
        int err = bt_le_adv_start(&adv_param, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
        if (err && err != -EALREADY) {
            printk("Failed to restart advertising (err %d)\n", err);
        }
    }
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected    = connected,
    .disconnected = disconnected,
};

/* ─────────────────────────────────────────────────────────────── */
/* CCC CALLBACKS                                                   */
/* ─────────────────────────────────────────────────────────────── */

static void cpm_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    if (value & BT_GATT_CCC_NOTIFY) {
        printk("CPM notifications enabled\n");
        if (garmin_conn == NULL && last_conn != NULL) {
            garmin_conn = bt_conn_ref(last_conn);
            printk("Assigned as Garmin (CPM subscriber)\n");
        }
    } else {
        printk("CPM notifications disabled\n");
        if (garmin_conn) {
            bt_conn_unref(garmin_conn);
            garmin_conn = NULL;
        }
    }
}

static void cpcp_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    printk("CPCP CCCD: 0x%04X\n", value);
}

static void android_tx_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    if (value & BT_GATT_CCC_NOTIFY) {
        printk("Android TX notifications enabled\n");
        if (android_conn == NULL && last_conn != NULL) {
            android_conn = bt_conn_ref(last_conn);
            printk("Assigned as Android (TX subscriber)\n");
            send_status_to_phone();
        }
    } else {
        printk("Android TX notifications disabled\n");
        if (android_conn) {
            bt_conn_unref(android_conn);
            android_conn = NULL;
        }
    }
}

static void set_leds(void)
{
    int a, b;

    if (current_state == AWAKE) {
        if (!transport_mode) {
            a = 0; b = 1;   /* AWAKE NORMAL    — green on */
            printk("Normal mode - green LED\n");
        } else {
            printk("Transport mode - red LED\n");
            a = 1; b = 0;   /* AWAKE TRANSPORT — red on   */
        }
    } else {
        printk("SLEEPING - all off\n");
        a = 0; b = 0;
        gpio_pin_set_dt(&led_blue, 0);
    }

    gpio_pin_set_dt(&led_a, a);
    gpio_pin_set_dt(&led_b, b);
}

/* ─────────────────────────────────────────────────────────────── */
/* IMU CONFIG + ISR                                                */
/* ─────────────────────────────────────────────────────────────── */

static void configure_imu_for_state(void)
{
    imu_write(REG_CTRL1_XL, 0x00);
    imu_write(REG_MD1_CFG,  0x00);
    imu_write(REG_TAP_CFG1, 0x00);
    k_msleep(20);

    /* 0x30 = 50 Hz ODR while sleeping; 0x00 = off while awake */
    uint8_t odr = (current_state == AWAKE) ? 0x00 : 0x30;
    imu_write(REG_CTRL1_XL, odr);
    k_msleep(10);

    if (current_state == AWAKE) {
        printk("IMU: Awake\n");
        return;
    }

    if (current_state == SLEEP_TRANSPORT) {
        imu_write(REG_TAP_CFG1,   0x8E);
        imu_write(REG_TAP_THS_6D, 0x8A);
        imu_write(REG_INT_DUR2,   0x7E);
        imu_write(REG_MD1_CFG,    0x40);
        printk("IMU: Transport sleep (firm taps)\n");
    } else {
        imu_write(REG_TAP_CFG1,    0x81);
        imu_write(REG_WAKE_UP_THS, 0x08);
        imu_write(REG_WAKE_UP_DUR, 0x01);
        imu_write(REG_MD1_CFG,     0x20);
        printk("IMU: Normal sleep (any movement)\n");
    }

    uint8_t dummy;
    imu_read(REG_WAKE_UP_SRC, &dummy);
    imu_read(REG_TAP_SRC, &dummy);
}

static struct gpio_callback imu_cb_data;

/* Work handler — runs in system workqueue, safe to call I2C */
static void imu_int_work_handler(struct k_work *work)
{
    uint8_t wake_src = 0, tap_src = 0;
    imu_read(REG_WAKE_UP_SRC, &wake_src);
    imu_read(REG_TAP_SRC,     &tap_src);

    uint32_t now = k_uptime_get_32();

    if (current_state == SLEEP_TRANSPORT) {
        if (now - first_tap_time > TAP_WINDOW_MS) {
            tap_count      = 1;
            first_tap_time = now;
        } else {
            tap_count++;
        }
        if (tap_count >= TAP_WAKE_COUNT) {
            k_sem_give(&sleep_wake_sem);
        }
    } else if (current_state == SLEEP_NORMAL) {
        wake_requested = true;
        k_sem_give(&sleep_wake_sem);
    }
}

/* ISR — must do NO I2C, no semaphores, no blocking calls */
static void imu_int1_cb(const struct device *dev,
                        struct gpio_callback *cb,
                        uint32_t pins)
{
    k_work_submit(&imu_int_work);
}

/* ─────────────────────────────────────────────────────────────── */
/* CRANK                                                           */
/* ─────────────────────────────────────────────────────────────── */

static void update_crank(void)
{
    if (target_cadence == 0) {
        last_crank_advance = k_uptime_get_32();
        return;
    }

    uint32_t now      = k_uptime_get_32();
    uint32_t interval = 60000UL / target_cadence;

    if (now - last_crank_advance >= interval) {
        crank_revs++;
        last_crank_advance      += interval;
        last_crank_event_time    =
            (uint16_t)((last_crank_advance * 1024ULL) / 1000ULL);
    }
}

/* ─────────────────────────────────────────────────────────────── */
/* BATTERY                                                         */
/* ─────────────────────────────────────────────────────────────── */

static bool batt_adc_ready = false;

static void battery_adc_setup(void)
{
    if (!device_is_ready(batt_adc_dev)) {
        printk("Battery ADC device not ready\n");
        return;
    }

    struct adc_channel_cfg ch_cfg = {
        .gain             = ADC_GAIN_1_6,
        .reference        = ADC_REF_INTERNAL,
        .acquisition_time = ADC_ACQ_TIME_DEFAULT,
        .channel_id       = BATT_ADC_CHANNEL_ID,
        .differential     = 0,
        .input_positive   = BATT_ADC_INPUT_POS,
    };

    int rc = adc_channel_setup(batt_adc_dev, &ch_cfg);
    if (rc != 0 && rc != -EALREADY) {
        printk("Battery ADC setup failed (rc=%d)\n", rc);
        return;
    }

    batt_adc_ready = true;
    printk("Battery ADC ready\n");
}

static void read_battery_now(void)
{
    if (!batt_adc_ready) return;

    /* Enable voltage divider */
    if (device_is_ready(vbat_en.port)) {
        gpio_pin_set_dt(&vbat_en, 0);
        k_msleep(8);
    }

    int16_t raw = 0;
    struct adc_sequence seq = {
        .channels    = BIT(BATT_ADC_CHANNEL_ID),
        .buffer      = &raw,
        .buffer_size = sizeof(raw),
        .resolution  = 12,
    };

    int rc = adc_read(batt_adc_dev, &seq);

    /* Disable divider immediately */
    if (device_is_ready(vbat_en.port)) {
        gpio_pin_set_dt(&vbat_en, 1);
    }

    if (rc != 0 || raw <= 0) {
        printk("Battery read failed (rc=%d raw=%d)\n", rc, raw);
        return;
    }

    int32_t vbat_mv = (int32_t)(raw * BAT_ADC_MV_PER_LSB);
    float   vbat    = vbat_mv / 1000.0f;

    /* Simple filtering — fast response when voltage rises (charging),
     * slow when discharging to reject noise and short-term load spikes. */
    static float filtered_v = 0.0f;
    if (filtered_v == 0.0f) {
        filtered_v = vbat;   /* seed on first reading */
    } else if (vbat > filtered_v) {
        filtered_v = filtered_v * 0.5f + vbat * 0.5f;   /* fast on charge */
    } else {
        filtered_v = filtered_v * 0.875f + vbat * 0.125f; /* slow on discharge */
    }

    if (filtered_v >= BAT_FULL)       battery_percent = 100;
    else if (filtered_v <= BAT_EMPTY) battery_percent = 0;
    else battery_percent = (uint8_t)((filtered_v - BAT_EMPTY) * 100.0f / BAT_RANGE);

    printk("Battery RAW=%d | VBAT=%.3fV (filtered %.3fV) → %d%%\n",
           raw, vbat, filtered_v, battery_percent);

    /* Notify BAS and ANT  */
    bt_gatt_notify(NULL, &bas_svc.attrs[2], &battery_percent, sizeof(battery_percent));
    #ifdef CONFIG_SMARTBRIDGE_ANT
        ant_bpwr_update_battery((uint16_t)(filtered_v * 1000));
    #endif
}

static void update_battery(void)
{
    uint32_t now = k_uptime_get_32();
    if (now - last_batt_read < 5000) return;
    last_batt_read = now;
    read_battery_now();
    send_status_to_phone();
}

/* ─────────────────────────────────────────────────────────────── */
/* BLE NOTIFICATIONS                                               */
/* ─────────────────────────────────────────────────────────────── */

static void send_power(void)
{
    if (!garmin_conn) return;

    /* flags word (16-bit LE): bit 0=pedal balance present, bit 1=balance ref=left,
       bit 5=crank revolution data present → 0x0023. */
    cpm_buf[0] = 0x23;
    cpm_buf[1] = 0x00;
    cpm_buf[2] = (uint8_t)(target_watts & 0xFF);
    cpm_buf[3] = (uint8_t)((target_watts >> 8) & 0xFF);
    cpm_buf[4] = left_balance * 2;  /* pedal power balance — 0–200 (100 = 50%) */
    cpm_buf[5] = (uint8_t)(crank_revs & 0xFF);
    cpm_buf[6] = (uint8_t)((crank_revs >> 8) & 0xFF);
    cpm_buf[7] = (uint8_t)(last_crank_event_time & 0xFF);
    cpm_buf[8] = (uint8_t)((last_crank_event_time >> 8) & 0xFF);

    bt_gatt_notify(garmin_conn, &cps_svc.attrs[2], cpm_buf, sizeof(cpm_buf));
}

static void send_status_to_phone(void)
{
    if (!android_conn) return;

    uint8_t payload[2] = { battery_percent, (uint8_t)transport_mode };
    bt_gatt_notify(android_conn, &android_svc.attrs[4], payload, sizeof(payload));

}

/* ─────────────────────────────────────────────────────────────── */
/* SLEEP / WAKE                                                    */
/* ─────────────────────────────────────────────────────────────── */

static void enter_sleep(void)
{
    current_state = transport_mode ? SLEEP_TRANSPORT : SLEEP_NORMAL;

    bt_le_adv_stop();
    #ifdef CONFIG_SMARTBRIDGE_ANT
        ant_bpwr_stop();
        ant_lev_stop();
    #endif

    if (garmin_conn)  { bt_conn_disconnect(garmin_conn,  BT_HCI_ERR_REMOTE_USER_TERM_CONN); }
    if (android_conn) { bt_conn_disconnect(android_conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN); }
    k_msleep(200);

    set_leds();
    configure_imu_for_state();

    printk(">>> ENTERED %s SLEEP\n",
           (current_state == SLEEP_TRANSPORT) ? "TRANSPORT" : "NORMAL");

    wake_requested = false;
    tap_count      = 0;
    first_tap_time = 0;
    k_sem_reset(&sleep_wake_sem);

    while (current_state != AWAKE) {
        /* Block indefinitely until the IMU work handler posts the semaphore.
         * The CPU enters WFI (wait-for-interrupt) inside k_sem_take, drawing
         * only ~3-4 µA */
        k_sem_take(&sleep_wake_sem, K_FOREVER);

        if (wake_requested) {
            printk(">>> MOVEMENT - waking normal sleep\n");
            wake_up();
            break;
        }

        if (current_state == SLEEP_TRANSPORT &&
            tap_count >= TAP_WAKE_COUNT) {
            printk(">>> TAPS - waking transport sleep\n");
            wake_up();
            tap_count = 0;
            break;
        }
    }
}

static void wake_up(void)
{
    current_state = AWAKE;
    configure_imu_for_state();
    set_leds();
    last_activity_time = k_uptime_get_32();
    int err = bt_le_adv_start(&adv_param, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
    if (err && err != -EALREADY) {
        printk("Adv restart failed (err %d)\n", err);
    }
    #ifdef CONFIG_SMARTBRIDGE_ANT
        ant_bpwr_start();
        ant_lev_start();
    #endif

    printk(">>> DEVICE AWAKE\n");
}

/* ─────────────────────────────────────────────────────────────── */
/* ADDRESS + NAME FROM FICR                                        */
/* ─────────────────────────────────────────────────────────────── */

static void set_address_from_ficr(void)
{
    bt_addr_le_t addr;
    addr.type      = BT_ADDR_LE_RANDOM;
    addr.a.val[0]  = (NRF_FICR->DEVICEADDR[0])        & 0xFF;
    addr.a.val[1]  = (NRF_FICR->DEVICEADDR[0] >>  8)  & 0xFF;
    addr.a.val[2]  = (NRF_FICR->DEVICEADDR[0] >> 16)  & 0xFF;
    addr.a.val[3]  = (NRF_FICR->DEVICEADDR[0] >> 24)  & 0xFF;
    addr.a.val[4]  = (NRF_FICR->DEVICEADDR[1])         & 0xFF;
    addr.a.val[5]  = ((NRF_FICR->DEVICEADDR[1] >>  8)  & 0xFF) | 0xC0;

    int err = bt_id_create(&addr, NULL);
    if (err < 0) {
        printk("Failed to set BT address from FICR (err %d)\n", err);
    } else {
        printk("BT address set from FICR (identity %d)\n", err);
    }
}

static void set_name_from_ficr(void)
{
    uint32_t dev0 = NRF_FICR->DEVICEADDR[0];
    uint8_t  b0   = dev0 & 0xFF;
    uint8_t  b1   = (dev0 >> 8) & 0xFF;

    char name[24];
    snprintk(name, sizeof(name), "SmartBridge_%02X%02X", b1, b0);
    bt_set_name(name);
    printk("BT name: %s\n", bt_get_name());
}

/* ─────────────────────────────────────────────────────────────── */
/* SETTINGS (NVS) — persists transport_mode across resets          */
/* ─────────────────────────────────────────────────────────────── */

static int transport_settings_set(const char *key, size_t len,
                                   settings_read_cb read_cb, void *cb_arg)
{
    if (strcmp(key, "mode") == 0) {
        uint8_t val;
        if (read_cb(cb_arg, &val, sizeof(val)) == sizeof(val)) {
            transport_mode = (bool)val;
            printk("Settings: transport_mode loaded = %d\n", transport_mode);
        }
    }
    return 0;
}

SETTINGS_STATIC_HANDLER_DEFINE(smartbridge, "sb", NULL,
                                transport_settings_set, NULL, NULL);

static void save_transport_work_handler(struct k_work *work)
{
    uint32_t reg = nrf_power_gpregret_get(NRF_POWER,0);
    if (transport_mode)
        reg |=  GPREGRET_TRANSPORT_BIT;
    else
        reg &= ~GPREGRET_TRANSPORT_BIT;
    nrf_power_gpregret_set(NRF_POWER, 0, reg);
    printk("Transport mode saved to GPREGRET: %d\n", transport_mode);
}

static void save_transport_mode(void)
{
    k_work_submit(&save_transport_work);
}

static void load_transport_mode(void)
{
    uint32_t reg = nrf_power_gpregret_get(NRF_POWER, 0);
    transport_mode = (reg & GPREGRET_TRANSPORT_BIT) != 0;
    printk("Transport mode loaded from GPREGRET: %d\n", transport_mode);
}

/* ─────────────────────────────────────────────────────────────── */
/* MAIN                                                            */
/* ─────────────────────────────────────────────────────────────── */

int main(void)
{
    printk("SmartBridge %s (Zephyr, XIAO nRF52840 Sense)\n", FW_VERSION_STR);


    load_transport_mode();

    /* ── GPIO ── */
    gpio_pin_configure_dt(&led_a,    GPIO_OUTPUT);
    gpio_pin_configure_dt(&led_b,    GPIO_OUTPUT);
    gpio_pin_configure_dt(&led_blue, GPIO_OUTPUT_ACTIVE);
    set_leds();

    /* ── IMU ── */
    if (!device_is_ready(imu_int1.port) || !device_is_ready(i2c_imu)) {
        printk("IMU or INT1 not ready\n");
    } else {
        gpio_pin_configure_dt(&imu_int1, GPIO_INPUT);
        gpio_pin_interrupt_configure_dt(&imu_int1, GPIO_INT_EDGE_RISING);
        gpio_init_callback(&imu_cb_data, imu_int1_cb, BIT(imu_int1.pin));
        gpio_add_callback(imu_int1.port, &imu_cb_data);
        configure_imu_for_state();
    }

    /* ── Battery ADC setup ── */
    /*
     * The power-GPIO is ACTIVE_LOW. GPIO_OUTPUT_INACTIVE drives the pin
     * HIGH, which disables the divider until we actually sample.
     */
    if (device_is_ready(vbat_en.port)) {
        gpio_pin_configure_dt(&vbat_en, GPIO_OUTPUT_INACTIVE);
    }

    battery_adc_setup();
    if (batt_adc_ready) {
        read_battery_now();
        last_batt_read = k_uptime_get_32();
    }

    set_address_from_ficr();

    int err = bt_enable(NULL);
    if (err) {
        printk("BT enable failed (err %d)\n", err);
        return err;
    }

    init_cpcp_attr();
    set_name_from_ficr();

    err = bt_le_adv_start(&adv_param, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
    if (err == -EALREADY) {
        printk("Advertising already in progress\n");
    } else if (err) {
        printk("BT adv start failed (err %d)\n", err);
        return err;
    } else {
        printk("Advertising started\n");
    }

    #ifdef CONFIG_SMARTBRIDGE_ANT
        err = ant_bpwr_setup();
        if (err) printk("ANT setup failed (err %d)\n", err);
        ant_lev_setup();
    #endif

    k_work_init(&save_transport_work, save_transport_work_handler);
    k_work_init(&imu_int_work, imu_int_work_handler);

    last_activity_time = k_uptime_get_32();
    uint32_t last_ble_notify       = 0;
    #ifdef CONFIG_SMARTBRIDGE_ANT
        uint32_t last_ant_update    = 0;
    #endif
    uint32_t last_crank_update = 0;

    while (1) {
        uint32_t now = k_uptime_get_32();

        if (now - last_crank_update >= 10) {
            update_crank();
            last_crank_update = now;
        }

        if (current_state == AWAKE &&
            now > last_activity_time &&
            (now - last_activity_time > SLEEP_TIMEOUT_MS)) {
            printk("Sleeping! Idle for %lu ms\n",
                   (unsigned long)(now - last_activity_time));
            enter_sleep();
            continue;
        }

        /* ── Blue LED: blink while advertising to phone, off when phone connected ── */
        static uint32_t last_blue_blink = 0;
        if (current_state == AWAKE) {
            if ( android_conn == NULL) {
                /* Advertising — blink blue at 1 Hz */
                if (now - last_blue_blink >= 500) {
                    gpio_pin_toggle_dt(&led_blue);
                    last_blue_blink = now;
                }
            }
            else {
                /* Connected — blue off */
                gpio_pin_set_dt(&led_blue, 0);
            }
        }

        /* ── BLE Power notification to Garmin (750 ms) ── */
        if (now - last_ble_notify >= 750) {
            last_ble_notify = now;
            send_power();
        }

        #ifdef CONFIG_SMARTBRIDGE_ANT
            /* ── ANT Power & notification to Garmin (250 ms) ── */
            if (now - last_ant_update >= 250) {
                last_ant_update = now;
                ant_bpwr_update(target_watts, target_cadence, left_balance);
                float assist_pct = 1.0f - (float)left_balance / 100.0f;
                uint8_t assist_val = (uint8_t)(assist_pct * 100.0f);
                if (assist_val > 100u) assist_val = 100u;

                ant_lev_update(target_bikebattery, target_assist, assist_val,
                               target_odometer, target_speed, target_range_km);
            }
        #endif

        /* ── Periodic battery read ── */
        update_battery();

        k_sleep(K_MSEC(10));
    }

    return 0;
}