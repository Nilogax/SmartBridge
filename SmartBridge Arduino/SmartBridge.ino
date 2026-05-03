// Boards supported:
//   - Seeed XIAO nRF52840 Sense        (Default)
//   - Adafruit Feather nRF52840 Sense  (ARDUINO_ADAFRUIT_FEATHER_NRF52840_SENSE) - UNTESTED

#include <bluefruit.h>
#include <Wire.h>
#include "LSM6DS3.h"

#define FW_VERSION "0.9.2"

#define BLE_TX_POWER  0

#define ADV_INTERVAL_FAST   160
#define ADV_INTERVAL_SLOW   800

#if defined(ARDUINO_ADAFRUIT_FEATHER_NRF52840_SENSE)
  // Adafruit config
  #define IMU_INT1_PIN   11
  #define LED_A          LED_RED
  #define LED_B          LED_BLUE
  #define LED_A_AWAKE_NORMAL    LOW
  #define LED_B_AWAKE_NORMAL    HIGH
  #define LED_A_AWAKE_TRANSPORT HIGH
  #define LED_B_AWAKE_TRANSPORT LOW
  #define LED_A_SLEEP    HIGH
  #define LED_B_SLEEP    HIGH
  #define VBAT_PIN       PIN_A6
  #define VBAT_SCALE     (3.3f / 4096.0f * 2.0f)
#else
  // Seeed XIAO nRF52840 Sense

  #define IMU_INT1_PIN   PIN_LSM6DS3TR_C_INT1
  #define LED_A          LED_GREEN
  #define LED_B          LED_RED
  #define LED_A_AWAKE_NORMAL    LOW
  #define LED_B_AWAKE_NORMAL    HIGH
  #define LED_A_AWAKE_TRANSPORT HIGH
  #define LED_B_AWAKE_TRANSPORT LOW
  #define LED_A_SLEEP    HIGH
  #define LED_B_SLEEP    HIGH
  #define VBAT_SCALE     (3.6f / 4096.0f * 3.0f)
  #define HAS_VBAT_ENABLE

#endif

#ifndef VBAT_PIN
  #define VBAT_PIN  PIN_VBAT
#endif

#ifndef waitForEvent
  #define waitForEvent() __WFE()
#endif

// ─── Services ────────────────────────────────────────────────────────────────
BLEService        cps      = BLEService(0x1818);
BLECharacteristic cpm      = BLECharacteristic(0x2A63);
BLECharacteristic cpf      = BLECharacteristic(0x2A65);
BLECharacteristic csl      = BLECharacteristic(0x2A5D);
BLECharacteristic cpcp     = BLECharacteristic(0x2A66);

BLEService        bas      = BLEService(0x180F);
BLECharacteristic bat      = BLECharacteristic(0x2A19);

BLEService        dis      = BLEService(0x180A);
BLECharacteristic mfr      = BLECharacteristic(0x2A29);
BLECharacteristic mdl      = BLECharacteristic(0x2A24);
BLECharacteristic fwrev    = BLECharacteristic(0x2A26);

#define CUSTOM_SERVICE_UUID  "12345678-1234-1234-1234-123456789ABC"
#define CUSTOM_RX_CHAR_UUID  "12345678-1234-1234-1234-123456789ABD"
#define CUSTOM_TX_CHAR_UUID  "12345678-1234-1234-1234-123456789ABE"

BLEService        androidSvc = BLEService(CUSTOM_SERVICE_UUID);
BLECharacteristic androidRx  = BLECharacteristic(CUSTOM_RX_CHAR_UUID);
BLECharacteristic androidTx  = BLECharacteristic(CUSTOM_TX_CHAR_UUID);

// ─── States ──────────────────────────────────────────────────────────────────
enum DeviceState { AWAKE, SLEEP_NORMAL, SLEEP_TRANSPORT };
DeviceState currentState = AWAKE;

// ─── Globals ─────────────────────────────────────────────────────────────────
uint8_t  p_data[9]             = {0};
uint16_t crank_revs            = 0;
uint16_t last_crank_event_time = 0;
uint32_t last_notify           = 0;
uint32_t last_crank_advance    = 0;

volatile uint16_t target_cadence = 0;
volatile int16_t  target_watts   = 0;
volatile uint8_t  left_balance   = 100;

uint8_t  batteryPercent   = 99;
uint32_t lastBatteryRead  = 0;
uint32_t lastActivityTime = 0;

bool transportMode = false;

volatile bool     wakeRequested = false;
volatile uint8_t  tapCountISR  = 0;
volatile uint32_t firstTapTime = 0;

uint16_t garmin_conn_hdl = BLE_CONN_HANDLE_INVALID;
uint16_t android_conn_hdl = BLE_CONN_HANDLE_INVALID;

// ----------------- Battery

enum BatReadState { BAT_IDLE, BAT_ENABLE_WAIT, BAT_SAMPLING, BAT_DONE };
static BatReadState batState = BAT_IDLE;
static uint32_t batStateStart = 0;
static uint32_t batSum = 0;
static uint8_t  batSamples = 0;

LSM6DS3Core myIMU(I2C_MODE, 0x6A);

#define TAP_WINDOW_MS    3000UL
#define TAP_WAKE_COUNT   5
#define SLEEP_TIMEOUT_MS 180000UL // 3 minutes

#define PKT_POWER_CAD_BAL     0x01
#define PKT_TRANSPORT_MODE    0x02
#define PKT_TRANSPORT_STATUS  0x81

#define BAT_FULL   4.10f
#define BAT_EMPTY  3.30f
#define BAT_RANGE  (BAT_FULL - BAT_EMPTY)

// Forward declarations
void onCpmSubscribe(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t cccd_value);
void onAndroidTxSubscribe(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t cccd_value);

// ─────────────────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);

  pinMode(LED_A, OUTPUT);
  pinMode(LED_B, OUTPUT);
  setLEDs();

  Bluefruit.configPrphConn(0, 0, 3, 3);
  Bluefruit.begin(2, 0);
  setStaticAddressFromFICR();
  Bluefruit.Security.setIOCaps(false, false, false);
  Bluefruit.Security.begin();
  Bluefruit.setTxPower(BLE_TX_POWER);
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);
  Bluefruit.setAppearance(1157);
  Bluefruit.autoConnLed(true);

  if (myIMU.beginCore() != 0) {
    Serial.println("IMU init failed!");
  } else {
    Serial.println("LSM6DS3 ready");
    pinMode(IMU_INT1_PIN, INPUT_PULLDOWN);
    attachInterrupt(digitalPinToInterrupt(IMU_INT1_PIN), imuISR, RISING);
  }

  setupCPS();
  setupDIS();
  setupBattery();
  setupAndroidService();
  startAdv();

  lastActivityTime = millis();
  Serial.println("SmartBridge v" FW_VERSION);
}

void forceBlueLEDOff() {
#ifdef LED_BLUE
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_BLUE, HIGH);
  NRF_GPIO->PIN_CNF[LED_BLUE] =
    (GPIO_PIN_CNF_DIR_Output       << GPIO_PIN_CNF_DIR_Pos)   |
    (GPIO_PIN_CNF_INPUT_Disconnect << GPIO_PIN_CNF_INPUT_Pos) |
    (GPIO_PIN_CNF_PULL_Disabled    << GPIO_PIN_CNF_PULL_Pos);
#endif
}

void setLEDs() {
  switch (currentState) {
    case AWAKE:
      digitalWrite(LED_A, transportMode ? LED_A_AWAKE_TRANSPORT : LED_A_AWAKE_NORMAL);
      digitalWrite(LED_B, transportMode ? LED_B_AWAKE_TRANSPORT : LED_B_AWAKE_NORMAL);
      break;
    case SLEEP_NORMAL:
    case SLEEP_TRANSPORT:
      digitalWrite(LED_A, LED_A_SLEEP);
      digitalWrite(LED_B, LED_B_SLEEP);
      forceBlueLEDOff();
      break;
  }
}

void configureIMUForCurrentState() {
  myIMU.writeRegister(LSM6DS3_ACC_GYRO_CTRL1_XL, 0x00);
  myIMU.writeRegister(LSM6DS3_ACC_GYRO_MD1_CFG,  0x00);
  myIMU.writeRegister(LSM6DS3_ACC_GYRO_TAP_CFG1, 0x00);
  delay(20);

  // 0x30 = 50 Hz in sleep
  // 0x00 = off when awake
  uint8_t odr = (currentState == AWAKE) ? 0x00 : 0x30;
  myIMU.writeRegister(LSM6DS3_ACC_GYRO_CTRL1_XL, odr);
  delay(10);

  if (currentState == AWAKE) {
    Serial.println("IMU: Awake ");
    return;
  } else if (currentState == SLEEP_TRANSPORT) {
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_TAP_CFG1, 0x8E); // Enable XYZ tap
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_TAP_THS_6D, 0x8A); // deliberate firm taps
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_INT_DUR2, 0x7E);
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_MD1_CFG, 0x40);  // Route Single Tap to INT1

    Serial.println("IMU: Transport sleep (firm taps)");
  } else {
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_TAP_CFG1,    0x81);
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_WAKE_UP_THS, 0x09);
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_WAKE_UP_DUR, 0x01);
    myIMU.writeRegister(LSM6DS3_ACC_GYRO_MD1_CFG,     0x20);
    Serial.println("IMU: Normal sleep ( any movement)");
  }

  uint8_t dummy;
  myIMU.readRegister(&dummy, LSM6DS3_ACC_GYRO_WAKE_UP_SRC);
  myIMU.readRegister(&dummy, LSM6DS3_ACC_GYRO_TAP_SRC);
}

void imuISR() {
  uint8_t dummy;
  myIMU.readRegister(&dummy, LSM6DS3_ACC_GYRO_WAKE_UP_SRC);
  myIMU.readRegister(&dummy, LSM6DS3_ACC_GYRO_TAP_SRC);

  if (currentState == SLEEP_TRANSPORT) {
    uint32_t now = millis();
    if (now - firstTapTime > TAP_WINDOW_MS) {
      tapCountISR = 1;
      firstTapTime = now;
    } else {
      tapCountISR++;
    }
  } else if (currentState == SLEEP_NORMAL) {
    wakeRequested = true;
  }
}

// ─── Sleep / Wake ────────────────────────────────────────────────────────────
void enterSleep() {
  currentState = transportMode ? SLEEP_TRANSPORT : SLEEP_NORMAL;
  Bluefruit.Advertising.restartOnDisconnect(false);

for (uint16_t hdl = 0; hdl < BLE_MAX_CONNECTION; hdl++) {
    if (Bluefruit.connected(hdl)) Bluefruit.disconnect(hdl);
  }
  delay(200);

  Bluefruit.Advertising.stop();



  Bluefruit.autoConnLed(false);
  setLEDs();

  configureIMUForCurrentState();

  Serial.printf(">>> ENTERED %s SLEEP\n", transportMode ? "TRANSPORT" : "NORMAL");
  Serial.flush();

  while (currentState != AWAKE) {
    waitForEvent();

    if (wakeRequested) {
      wakeRequested = false;
      Serial.println(">>> MOVEMENT – waking normal sleep");
      wakeUp();
      break;
    }
    if (currentState == SLEEP_TRANSPORT && tapCountISR >= TAP_WAKE_COUNT) {
      Serial.println(">>> TAPS – waking transport sleep");
      wakeUp();
      tapCountISR = 0;
      break;
    }
  }
}

void wakeUp() {
  currentState = AWAKE;
  Bluefruit.autoConnLed(true);
  setLEDs();
  configureIMUForCurrentState();
  lastActivityTime = millis();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.start(0);
  Serial.println(">>> DEVICE AWAKE – BLE restarted");
}

// --- Battery ------------

void updateBatteryNonBlocking() {
  uint32_t now = millis();
  switch (batState) {
    case BAT_IDLE:
      if (now - lastBatteryRead < 5000) return;
      #ifdef HAS_VBAT_ENABLE
        pinMode(VBAT_ENABLE, OUTPUT);
        digitalWrite(VBAT_ENABLE, LOW);
      #endif
      analogReference(AR_DEFAULT);
      analogReadResolution(12);
      analogRead(VBAT_PIN); // dummy read
      batSum = 0; batSamples = 0;
      batStateStart = now;
      batState = BAT_ENABLE_WAIT;
      break;

    case BAT_ENABLE_WAIT:
      if (now - batStateStart < 10) return;
      batState = BAT_SAMPLING;
      break;

    case BAT_SAMPLING:
      batSum += analogRead(VBAT_PIN);
      batSamples++;
      if (batSamples < 16) return;   // come back next loop iteration
      batState = BAT_DONE;
      break;

    case BAT_DONE: {
      #ifdef HAS_VBAT_ENABLE
        digitalWrite(VBAT_ENABLE, HIGH);
        pinMode(VBAT_ENABLE, INPUT);
      #endif
      uint16_t adc = batSum / 16;
      float voltage = adc * VBAT_SCALE;
      uint16_t mv = (uint16_t)(voltage * 1000.0f);
      static uint16_t filteredMv = 0;
      if (filteredMv == 0) filteredMv = mv;
      else filteredMv = (uint16_t)((filteredMv * 7u + mv) / 8u);
      float v = filteredMv / 1000.0f;
      if      (v >= BAT_FULL)  batteryPercent = 100;
      else if (v <= BAT_EMPTY) batteryPercent = 0;
      else batteryPercent = (uint8_t)((v - BAT_EMPTY) * 100.0f / BAT_RANGE);
      bat.write8(batteryPercent);
      sendStatusToPhone();
      lastBatteryRead = now;
      batState = BAT_IDLE;
      break;
    }
  }
}

// ─── Main Loop ───────────────────────────────────────────────────────────────
void loop() {
  uint32_t now = millis();

  static uint32_t lastCrankUpdate = 0;
  if (now - lastCrankUpdate >= 10) {
    updateCrank();
    lastCrankUpdate = now;
  }

  if (currentState == AWAKE && now > lastActivityTime &&
      (now - lastActivityTime > SLEEP_TIMEOUT_MS)) {
    Serial.printf("Sleeping! Idle for %lu ms\n", now - lastActivityTime);
    enterSleep();
    return;
  }

  if (now - last_notify >= 750) {
    last_notify = now;

    if (garmin_conn_hdl != BLE_CONN_HANDLE_INVALID &&
    Bluefruit.connected(garmin_conn_hdl) &&
    cpm.notifyEnabled(garmin_conn_hdl))
      {
        sendPowerData(target_watts, left_balance * 2);
      }
  }

  updateBatteryNonBlocking();

  // waitForEvent();
}

// ─── Helpers ─────────────────────────────────────────────────────────────────
bool anyCpmNotifyEnabled() {
  for (uint16_t hdl = 0; hdl < BLE_MAX_CONNECTION; hdl++) {
    if (Bluefruit.connected(hdl) && cpm.notifyEnabled(hdl)) return true;
  }
  return false;
}

void sendPowerData(int16_t watts, uint8_t balance_byte) {
  p_data[0] = 0x23; p_data[1] = 0x00;
  p_data[2] = (uint8_t)(watts & 0xFF);
  p_data[3] = (uint8_t)((watts >> 8) & 0xFF);
  p_data[4] = balance_byte;
  p_data[5] = (uint8_t)(crank_revs & 0xFF);
  p_data[6] = (uint8_t)((crank_revs >> 8) & 0xFF);
  p_data[7] = (uint8_t)(last_crank_event_time & 0xFF);
  p_data[8] = (uint8_t)((last_crank_event_time >> 8) & 0xFF);
  if (garmin_conn_hdl != BLE_CONN_HANDLE_INVALID &&
      Bluefruit.connected(garmin_conn_hdl) &&
      cpm.notifyEnabled(garmin_conn_hdl))
  {
    cpm.notify(garmin_conn_hdl, p_data, 9);
  }
}

void sendStatusToPhone() {
  uint8_t payload[2] = { batteryPercent, (uint8_t)transportMode };
  if (android_conn_hdl != BLE_CONN_HANDLE_INVALID &&
      Bluefruit.connected(android_conn_hdl) &&
      androidTx.notifyEnabled(android_conn_hdl))
  {
    androidTx.notify(android_conn_hdl, payload, 2);
  }
}

void onAndroidWrite(uint16_t conn_hdl, BLECharacteristic* chr,
                    uint8_t* data, uint16_t len) {
  if (len < 6) return;
  uint8_t chk = 0;
  for (int i = 0; i < 5; i++) chk ^= data[i];
  if (chk != data[5]) return;

  if (data[0] == PKT_POWER_CAD_BAL) {
    int16_t watts   = (int16_t)(data[1] | (data[2] << 8));
    uint8_t cadence = data[3];
    uint8_t balance = data[4];
    if (watts   < 0)    watts   = 0;
    if (watts   > 2500) watts   = 2500;
    if (cadence > 200)  cadence = 200;
    if (balance > 100)  balance = 100;
    target_watts   = watts;
    target_cadence = cadence;
    left_balance   = balance;
    if (watts > 0 || cadence > 0) lastActivityTime = millis();
  } else if (data[0] == PKT_TRANSPORT_MODE) {
    transportMode = (data[1] != 0);
    Serial.printf("Sleep preference: %s\n", transportMode ? "Transport" : "Normal");
    setLEDs();
    sendStatusToPhone();
  }
}

void updateCrank() {
  if (target_cadence == 0) { last_crank_advance = millis(); return; }
  uint32_t now_ms   = millis();
  uint32_t interval = 60000UL / target_cadence;
  if (now_ms - last_crank_advance >= interval) {
    crank_revs++;
    last_crank_advance += interval;
    last_crank_event_time = (uint16_t)((last_crank_advance * 1024ULL) / 1000ULL);
  }
}

// ─── Battery ─────────────────────────────────────────────────────────────────
uint16_t readBatteryVoltage() {
#ifdef HAS_VBAT_ENABLE
  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, LOW);
  delay(10);
#endif

  analogReference(AR_DEFAULT);
  analogReadResolution(12);
  analogRead(VBAT_PIN);  // dummy read

  uint32_t sum = 0;
  for (int i = 0; i < 16; i++) { sum += analogRead(VBAT_PIN); delay(1); }
  uint16_t adc = sum / 16;

#ifdef HAS_VBAT_ENABLE
  digitalWrite(VBAT_ENABLE, HIGH);
  pinMode(VBAT_ENABLE, INPUT);
#endif

  float    voltage   = adc * VBAT_SCALE;
  uint16_t mv        = (uint16_t)(voltage * 1000.0f);

  static uint16_t filteredMv = 0;
  if (filteredMv == 0) filteredMv = mv;
  else filteredMv = (uint16_t)((filteredMv * 7u + mv) / 8u);

  float v = filteredMv / 1000.0f;
  if      (v >= BAT_FULL)  batteryPercent = 100;
  else if (v <= BAT_EMPTY) batteryPercent = 0;
  else batteryPercent = (uint8_t)((v - BAT_EMPTY) * 100.0f / BAT_RANGE);

  return filteredMv;
}

// ─── BLE Setup & Advertising ─────────────────────────────────────────────────
void onCpcpWrite(uint16_t conn_hdl, BLECharacteristic* chr,
                 uint8_t* data, uint16_t len) {
  Serial.printf("CPCP write: len=%d opcode=0x%02X\n", len, data[0]);
  if (len < 1) return;
  uint8_t response[3] = { 0x20, data[0], 0x02 };
  cpcp.indicate(conn_hdl, response, 3);
}

void onCpcpCccdWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t cccd_value) {
  Serial.printf("CPCP CCCD written: 0x%04X\n", cccd_value);
}

void setupCPS() {
  cps.begin();
  csl.setProperties(CHR_PROPS_READ);
  csl.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  csl.setFixedLen(1); csl.begin(); csl.write8(6);
  cpf.setProperties(CHR_PROPS_READ);
  cpf.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  cpf.setFixedLen(4); cpf.begin(); cpf.write32(0x00000011);
  cpm.setProperties(CHR_PROPS_NOTIFY);
  cpm.setFixedLen(9);
  cpm.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  cpm.setCccdWriteCallback(onCpmSubscribe);
  cpm.begin();
  cpcp.setProperties(CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
  cpcp.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  cpcp.setMaxLen(20);
  cpcp.setCccdWriteCallback(onCpcpCccdWrite);
  cpcp.setWriteCallback(onCpcpWrite);
  cpcp.begin();

}

void setupDIS() {
  dis.begin();
  mfr.setProperties(CHR_PROPS_READ);
  mfr.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  mfr.begin();
  mfr.write("Nilogax");
  mdl.setProperties(CHR_PROPS_READ);
  mdl.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  mdl.begin();
  mdl.write("SmartBridge");
  fwrev.setProperties(CHR_PROPS_READ);
  fwrev.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  fwrev.begin();
  fwrev.write(FW_VERSION);
}

void setupBattery() {
  bas.begin();
  bat.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  bat.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  bat.setFixedLen(1); bat.begin();
  readBatteryVoltage();
  bat.write8(batteryPercent);
}

void setupAndroidService() {
  androidSvc.begin();
  androidRx.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  androidRx.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  androidRx.setFixedLen(6);
  androidRx.setWriteCallback(onAndroidWrite);
  androidRx.begin();
  androidTx.setProperties(CHR_PROPS_NOTIFY);
  androidTx.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  androidTx.setFixedLen(2);
  androidTx.setCccdWriteCallback(onAndroidTxSubscribe);
  androidTx.begin();
}

void startAdv(void) {
  Bluefruit.Advertising.clearData();
  Bluefruit.ScanResponse.clearData();
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addService(cps);
  Bluefruit.Advertising.addService(bas);
  Bluefruit.Advertising.addName();
  Bluefruit.ScanResponse.addService(androidSvc);
  Bluefruit.ScanResponse.addService(bas);
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(ADV_INTERVAL_FAST, ADV_INTERVAL_SLOW);
  Bluefruit.Advertising.start(0);
}

void setStaticAddressFromFICR() {
  ble_gap_addr_t addr;
  addr.addr_type = BLE_GAP_ADDR_TYPE_RANDOM_STATIC;
  addr.addr[0] = (NRF_FICR->DEVICEADDR[0])        & 0xFF;
  addr.addr[1] = (NRF_FICR->DEVICEADDR[0] >>  8)  & 0xFF;
  addr.addr[2] = (NRF_FICR->DEVICEADDR[0] >> 16)  & 0xFF;
  addr.addr[3] = (NRF_FICR->DEVICEADDR[0] >> 24)  & 0xFF;
  addr.addr[4] = (NRF_FICR->DEVICEADDR[1])         & 0xFF;
  addr.addr[5] = (NRF_FICR->DEVICEADDR[1] >>  8)  & 0xFF;
  addr.addr[5] |= 0xC0;
  Bluefruit.setAddr(&addr);
  char name[24];
  snprintf(name, sizeof(name), "SmartBridge_%02X%02X", addr.addr[1], addr.addr[0]);
  Bluefruit.setName(name);
}

void connect_callback(uint16_t conn_hdl) {
  Serial.print("Connected handle: 0x");
  Serial.println(conn_hdl, HEX);

  lastActivityTime = millis();

  // Restart advertising if we still have free slots
  if (Bluefruit.connected() < 2) Bluefruit.Advertising.start(0);

}

void disconnect_callback(uint16_t conn_hdl, uint8_t reason)
{
  Serial.print("Disconnected handle: 0x");
  Serial.println(conn_hdl, HEX);

  if (conn_hdl == garmin_conn_hdl) {
    garmin_conn_hdl = BLE_CONN_HANDLE_INVALID;
    Serial.println("Garmin disconnected");
  }
  if (conn_hdl == android_conn_hdl) {
    android_conn_hdl = BLE_CONN_HANDLE_INVALID;
    Serial.println("Android app disconnected");
  }

  if (currentState == AWAKE) {
    Bluefruit.Advertising.restartOnDisconnect(true);  // re-assert this
    Bluefruit.Advertising.start(0);
  }
}

void onCpmSubscribe(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t cccd_value) {
  if (cccd_value & BLE_GATT_HVX_NOTIFICATION) {

    garmin_conn_hdl = conn_hdl;
    Serial.println("→ Identified as GARMIN (subscribed to CPM)");

  } else {
    // Unsubscribed
    if (conn_hdl == garmin_conn_hdl) {
      garmin_conn_hdl = BLE_CONN_HANDLE_INVALID;
      Serial.println("Garmin unsubscribed from CPM");
    }
  }
}

void onAndroidTxSubscribe(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t cccd_value) {
  if (cccd_value & BLE_GATT_HVX_NOTIFICATION) {

    android_conn_hdl = conn_hdl;
    Serial.println("→ Identified as ANDROID APP (subscribed to androidTx)");
    sendStatusToPhone(); // Now safe to send, handle is valid

  } else {
    if (conn_hdl == android_conn_hdl) {
      android_conn_hdl = BLE_CONN_HANDLE_INVALID;
      Serial.println("Android app unsubscribed");
    }
  }
}
