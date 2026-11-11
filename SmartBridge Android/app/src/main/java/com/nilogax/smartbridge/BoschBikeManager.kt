package com.nilogax.smartbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

private const val TAG = "BoschBikeManager"

data class BoschMessage(
    val messageId: Int,
    val messageType: Int,
    val value: Int,
    val rawBytes: List<Int>
)

data class BoschRideData(
    val riderPower: Int = 0,
    val motorPower: Int = 0,
    val cadence: Int = 0,
    val batteryPercent: Int = 100,
    val assistMode: Int = 0,
    val estimatedRangeKmWh: Int? = null,
    val odometerMetres: Int = 0,
    val speedHundredthsKmh: Int = 0
)

class BoschBikeManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onStatusChange: (String) -> Unit,
    private val onDataUpdate: (BoschRideData) -> Unit,
    var onRawPacketReceived: (Long, String, String, String, String) -> Unit
) {
    var isConnected = false
        private set

    private val BOSCH_SERVICE_UUID = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_CHAR_UUID = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_CONFIG_UUID = UUID.fromString("00000021-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_CONFIG_SVC_UUID = UUID.fromString("00000020-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var batteryCapacityWh: Int? = null
    private val batteryCapacityComponents = mutableListOf<Pair<String, Int>>()

    private var boschGatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var riderPower = 0
    private var motorPower = 0
    private var cadence = 0
    private var lastBattery = 100
    private var assistMode = 0

    private var odometer = 0
    private var odometerSeen = false

    // History window in 250 m segments: 5 km = 20, 10 km = 40, 15 km = 60
    var rangeHistorySegments: Int = 40

    private val rangeEstimatorWh = RangeEstimatorWh(context, prefs)

    private var bikeSpeed = 0
    private val SPEED_ZERO_TIMEOUT_MS = 7500L
    private var lastOdometerForSpeed = -1
    private var lastOdometerChangeMs = 0L

    private var batteryNotifyThreshold = 0
    private var lastNotifiedMilestone = 100

    var onDataForward: ((BoschRideData) -> Unit)? = null

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val CHANNEL_ID = "bosch_bike_battery_channel"
    private var scanCallback: ScanCallback? = null

    init {
        createNotificationChannel()

        val threshold = when (prefs.getInt("battery_alert_pos", 0)) { 1 -> 5; 2 -> 10; else -> 0 }
        setBatteryAlertThreshold(threshold)

        val historyKm = prefs.getInt("range_history_km", 10)
            .let { if (it in listOf(5, 10, 15)) it else 10 }
        rangeHistorySegments = historyKm * 4   // 250 m segments per km
        Log.i(TAG, "SESSION START: rangeHistorySegments=$rangeHistorySegments (${historyKm}km)")
    }

    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it); scanCallback = null }
    }

    fun setBatteryAlertThreshold(threshold: Int) {
        batteryNotifyThreshold = threshold
        lastNotifiedMilestone = 100
    }

    fun bondAndSave(device: BluetoothDevice) {
        prefs.edit()
            .putString("last_bike_mac", device.address)
            .putString("last_bike_name", device.name ?: "Bosch Bike")
            .apply()
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Initiating bond with ${device.address}")
            device.createBond()
        } else {
            connectToDevice(device)
        }
    }

    fun connectToMac(mac: String) {
        if (boschGatt != null) {
            Log.d(TAG, "connectToMac ignored — GATT already active")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        connectToDevice(adapter.getRemoteDevice(mac))
    }

    private fun connectToDevice(device: BluetoothDevice) {
        onStatusChange("Connecting...")
        boschGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        lastOdometerForSpeed = -1
        lastOdometerChangeMs = 0L
        boschGatt?.disconnect()
        boschGatt?.close()
        boschGatt = null
        isConnected = false
    }

    fun forget() {
        disconnect()
        rangeEstimatorWh.forget()
        prefs.edit().remove("last_bike_mac").remove("last_bike_name").apply()
        onStatusChange("Not Paired")
    }

    /** Clears all learned Wh/m and band efficiency history without unpairing the bike. */
    fun clearHistory() {
        rangeEstimatorWh.clearHistory()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services")
                    isConnected = true
                    rangeEstimatorWh.onConnected()
                    mainHandler.postDelayed({ gatt.discoverServices() }, 600)
                    mainHandler.post { onStatusChange("Connected") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    isConnected = false
                    odometerSeen = false
                    batteryCapacityWh = null
                    batteryCapacityComponents.clear()
                    rangeEstimatorWh.onDisconnected()
                    gatt.close()
                    boschGatt = null
                    mainHandler.post { onStatusChange("Disconnected") }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val rideChar = gatt.getService(BOSCH_SERVICE_UUID)?.getCharacteristic(BOSCH_CHAR_UUID)
            if (rideChar != null) {
                gatt.setCharacteristicNotification(rideChar, true)
                val descriptor = rideChar.getDescriptor(CCCD_UUID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Log.i(TAG, "Subscribed to ride data characteristic")
            }

            val configChar = gatt.getService(BOSCH_CONFIG_SVC_UUID)?.getCharacteristic(BOSCH_CONFIG_UUID)
            if (configChar != null) {
                gatt.setCharacteristicNotification(configChar, true)
                val descriptor = configChar.getDescriptor(CCCD_UUID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Log.i(TAG, "Subscribed to config characteristic")
            } else {
                Log.w(TAG, "CONFIG CHAR: not found on this device")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BOSCH_CHAR_UUID -> dispatchBoschData(value)
                BOSCH_CONFIG_UUID -> parseConfigData(value, source = "notify")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            when (characteristic.uuid) {
                BOSCH_CHAR_UUID -> dispatchBoschData(value)
                BOSCH_CONFIG_UUID -> parseConfigData(value, source = "notify")
            }
        }
    }


    private var lastDispatchedPayload: ByteArray? = null
    private var lastDispatchedAtMs: Long = 0L

    private fun dispatchBoschData(value: ByteArray) {
        val now = System.currentTimeMillis()
        val prev = lastDispatchedPayload
        if (prev != null && now - lastDispatchedAtMs < 150 && prev.contentEquals(value)) return
        lastDispatchedPayload = value
        lastDispatchedAtMs = now
        parseBoschData(value)
    }

    private fun decodeOtherValueString(msg: BoschMessage): String {
        val frame = msg.rawBytes
        if (frame.size < 4) return frame.joinToString("-") { "%02X".format(it) }
        if (frame.size == 4) return "0"

        val payloadStart = 4
        when (frame[payloadStart]) {
            0x08 -> {
                if (payloadStart + 1 < frame.size) {
                    val (value, _) = decodeVarint(frame, payloadStart + 1)
                    return value.toString()
                }
            }
        }

        val structured = decodeBoschStringField(frame)
        if (structured is StringFieldResult.Success) return structured.text

        val asciiText = frame.drop(4)
            .filter { it in 0x20..0x7E || it == 0x00 }
            .map { it.toChar() }
            .joinToString("")

        return if (asciiText.isNotBlank() && asciiText.length >= 3) {
            asciiText.trim()
        } else {
            if (frame.size > 4) frame.drop(4).joinToString("-") { "%02X".format(it) }
            else frame.joinToString("-") { "%02X".format(it) }
        }
    }

    private fun parseBoschData(data: ByteArray) {
        val bytes = data.map { it.toInt() and 0xFF }
        val messages = parseBoschPacket(bytes)
        var hasKnownMessage = false

        for (msg in messages) {
            var label = "Other"
            var displayValue: String = decodeOtherValueString(msg)

            when (msg.messageId) {
                0x985B -> {
                    riderPower = msg.value
                    hasKnownMessage = true
                    label = "Rider Power"
                    displayValue = msg.value.toString()
                }
                0x985D -> {
                    motorPower = msg.value
                    hasKnownMessage = true
                    label = "Motor Power"
                    displayValue = msg.value.toString()
                }
                0x985A -> {
                    cadence = msg.value / 2
                    hasKnownMessage = true
                    label = "Cadence"
                    displayValue = (msg.value / 2).toString()
                }
                0x8088 -> {
                    lastBattery = msg.value
                    handleBatteryUpdate(msg.value)
                    hasKnownMessage = true
                    label = "Battery"
                    displayValue = msg.value.toString()
                }
                0x9809 -> {
                    assistMode = msg.value
                    hasKnownMessage = true
                    label = "Assist Mode"
                    displayValue = msg.value.toString()
                }
                0x9818 -> {
                    odometer = msg.value and 0xFFFFFF
                    odometerSeen = true
                    hasKnownMessage = true
                    label = "Odometer"
                    displayValue = (msg.value and 0xFFFFFF).toString()
                }
                0x9808 -> {
                    bikeSpeed = msg.value
                    hasKnownMessage = true
                    label = "Bike Speed"
                    displayValue = msg.value.toString()
                }
                0x009B -> {
                    hasKnownMessage = true
                    label = "Battery Name"
                    handleBatteryNameMessage(msg.rawBytes)
                }
            }

            onRawPacketReceived(
                System.currentTimeMillis(),
                msg.rawBytes.joinToString("-") { "%02X".format(it) },
                label,
                formatMessageId(msg.messageId),
                displayValue
            )
        }

        if (hasKnownMessage) {
            val now = System.currentTimeMillis()
            if (odometer != lastOdometerForSpeed) {
                lastOdometerForSpeed = odometer
                lastOdometerChangeMs = now
            } else if (now - lastOdometerChangeMs > SPEED_ZERO_TIMEOUT_MS) {
                bikeSpeed = 0
                cadence = 0
            }

            val rangeMetresWh = if (odometerSeen) {
                rangeEstimatorWh.update(
                    motorPowerW = motorPower,
                    assistMode = assistMode,
                    odometerM = odometer,
                    batteryPct = lastBattery,
                    sensitivity = rangeHistorySegments
                )
            } else null
            val rangeKmWh = rangeMetresWh?.let { Math.round(it / 1000f) }

            val rd = BoschRideData(
                riderPower, motorPower, cadence, lastBattery, assistMode,
                estimatedRangeKmWh = rangeKmWh,
                odometerMetres = odometer,
                speedHundredthsKmh = bikeSpeed
            )
            onDataForward?.invoke(rd)
            onDataUpdate(rd)
        }
    }

    private fun formatMessageId(id: Int): String =
        "%02X-%02X".format((id shr 8) and 0xFF, id and 0xFF)

    private fun handleBatteryUpdate(level: Int) {
        if (batteryNotifyThreshold == 0) return
        if ((level % batteryNotifyThreshold == 0) && (level < lastNotifiedMilestone)) {
            lastNotifiedMilestone = level
            mainHandler.post {
                Log.i(TAG, "BATTERY ALERT: ${level}%")
                sendBatteryNotification(level)
            }
        }
    }

    private fun parseConfigData(data: ByteArray, source: String) {
        val bytes = data.map { it.toInt() and 0xFF }
        val messages = parseBoschPacket(bytes)
        val sourceLabel = if (source == "poll") "Config (poll)" else "Config"

        for (msg in messages) {
            val label = when (msg.messageId) {
                0x009B -> "Battery Name"
                else -> sourceLabel
            }
            val displayValue = decodeOtherValueString(msg)
            onRawPacketReceived(
                System.currentTimeMillis(),
                msg.rawBytes.joinToString("-") { "%02X".format(it) },
                label,
                formatMessageId(msg.messageId),
                displayValue
            )
            if (msg.messageId == 0x009B) handleBatteryNameMessage(msg.rawBytes)
        }
    }

    private sealed class StringFieldResult {
        data class Success(val text: String) : StringFieldResult()
        data class Failure(val reason: String) : StringFieldResult()
    }

    private fun decodeBoschStringField(rawBytes: List<Int>): StringFieldResult {
        if (rawBytes.size < 5) return StringFieldResult.Failure("too_short size=${rawBytes.size}")
        val (_, tagBytesConsumed) = decodeVarint(rawBytes, 4)
        if (tagBytesConsumed <= 0) return StringFieldResult.Failure("bad_tag size=${rawBytes.size}")
        val typeIndex = 4 + tagBytesConsumed
        if (typeIndex >= rawBytes.size || rawBytes[typeIndex] != 0x0A)
            return StringFieldResult.Failure("malformed_header size=${rawBytes.size} typeIndex=$typeIndex")
        val lenIndex = typeIndex + 1
        if (lenIndex >= rawBytes.size) return StringFieldResult.Failure("missing_length size=${rawBytes.size}")
        val strLen = rawBytes[lenIndex]
        if (strLen <= 0 || lenIndex + 1 + strLen > rawBytes.size)
            return StringFieldResult.Failure("bad_length strLen=$strLen size=${rawBytes.size}")
        val text = try {
            rawBytes.subList(lenIndex + 1, lenIndex + 1 + strLen)
                .map { it.toChar() }.joinToString("").trim()
        } catch (e: Exception) {
            return StringFieldResult.Failure("decode_exception msg=${e.message}")
        }
        return if (text.isBlank()) StringFieldResult.Failure("blank_text") else StringFieldResult.Success(text)
    }

    private fun handleBatteryNameMessage(rawBytes: List<Int>) {
        val result = decodeBoschStringField(rawBytes)
        val name = when (result) {
            is StringFieldResult.Failure -> {
                rangeEstimatorWh.logExternal("BATTERY_NAME_REJECTED", "reason=${result.reason}")
                return
            }
            is StringFieldResult.Success -> result.text
        }
        val wh = parseBatteryCapacityWh(name)
        if (wh == null) {
            rangeEstimatorWh.logExternal("BATTERY_NAME_REJECTED", "reason=unparseable_capacity name=\"$name\"")
            return
        }
        if (batteryCapacityComponents.none { it.first == name }) {
            batteryCapacityComponents.add(Pair(name, wh))
            batteryCapacityWh = batteryCapacityComponents.sumOf { it.second }
            rangeEstimatorWh.setBatteryCapacityWh(batteryCapacityWh!!)
            Log.i(TAG, "Battery capacity: '$name' = ${wh} Wh, total = $batteryCapacityWh Wh")
        }
    }

    private fun parseBatteryCapacityWh(name: String): Int? =
        Regex("\\d+").findAll(name).lastOrNull()?.value?.toIntOrNull()

    private fun decodeVarint(bytes: List<Int>, startIndex: Int): Pair<Int, Int> {
        if (startIndex >= bytes.size) return Pair(0, 0)
        var result = 0; var shift = 0; var idx = startIndex; var consumed = 0
        try {
            while (idx < bytes.size && consumed < 5) {
                val b = bytes[idx]
                result = result or ((b and 0x7F) shl shift)
                consumed++; idx++
                if ((b and 0x80) == 0) break
                shift += 7
            }
        } catch (e: Exception) {
            Log.e(TAG, "Varint decode error: ${e.message}")
            return Pair(0, 1)
        }
        return Pair(result, consumed)
    }

    private fun parseBoschPacket(bytes: List<Int>): List<BoschMessage> {
        val messages = mutableListOf<BoschMessage>()
        var index = 0
        try {
            while (index < bytes.size) {
                if (bytes[index] != 0x30) { index++; continue }
                if (index + 2 >= bytes.size) break
                val messageLength = bytes[index + 1]
                val totalSize = messageLength + 2
                if (messageLength < 2 || messageLength > 50 || index + totalSize > bytes.size) { index++; continue }
                val messageId = (bytes[index + 2] shl 8) or bytes[index + 3]
                val messageBytes = bytes.subList(index, index + totalSize)
                var dataValue = 0; var dataType = 0
                if (messageLength > 2 && messageBytes.size > 4) {
                    dataType = messageBytes[4]
                    when (dataType) {
                        0x08 -> if (messageBytes.size > 5) dataValue = decodeVarint(messageBytes, 5).first
                        0x0A -> if (messageBytes.size > 5) dataValue = messageBytes[5]
                    }
                }
                messages.add(BoschMessage(messageId, dataType, dataValue, messageBytes))
                index += totalSize
            }
        } catch (e: Exception) { Log.e(TAG, "Packet parse error: ${e.message}") }
        return messages
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Bike Battery", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications about bike battery level" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendBatteryNotification(percent: Int) {
        val capacitySuffix = batteryCapacityWh?.let { " (${it}Wh)" } ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Bike Battery Alert")
            .setContentText("Bike battery at $percent%$capacitySuffix")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1001, notification)
    }
}