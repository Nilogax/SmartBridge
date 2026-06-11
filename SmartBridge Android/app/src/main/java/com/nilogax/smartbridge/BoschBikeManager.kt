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
    val estimatedRangeKm: Int? = null,
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
    private var lastOdometerForRange = -1
    private var lastBatteryForRange = -1
    private var ignoreFirstBatteryDrop = true

    private var assistOffDuringWindow = false

    private val MAX_SAMPLES = 20
    private val metresPerPercent = ArrayDeque<Int>()

    private var cachedRangeKm: Int? = null
    private var cachedRangeValid = false

    private val T_CRIT_95 = doubleArrayOf(
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086
    )
    private val T_CRIT_LARGE_SAMPLE = 1.960

    private val SAMPLE_MIN_SOC_PCT = 85
    var rangeSensitivity: Int = 10

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

        val count = prefs.getInt("range_sample_count", 0)
        for (i in 0 until count) {
            val v = prefs.getInt("range_sample_$i", -1)
            if (v >= 0) metresPerPercent.addLast(v)
        }
        rangeSensitivity = prefs.getInt("range_sensitivity", 10).coerceIn(3, MAX_SAMPLES)

        Log.i(TAG, "SESSION START: sensitivity=$rangeSensitivity samples=${metresPerPercent.size}")
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
        Log.i(TAG, "SESSION END: samples=${metresPerPercent.size}")
        boschGatt?.disconnect()
        boschGatt?.close()
        boschGatt = null
        isConnected = false
    }

    fun forget() {
        disconnect()
        clearRangeSamples()
        prefs.edit().remove("last_bike_mac").remove("last_bike_name").apply()
        onStatusChange("Not Paired")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services")
                    isConnected = true
                    mainHandler.postDelayed({ gatt.discoverServices() }, 600)
                    mainHandler.post { onStatusChange("Connected") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    isConnected = false
                    ignoreFirstBatteryDrop = true
                    lastOdometerForRange = -1
                    lastBatteryForRange = -1
                    assistOffDuringWindow = false
                    batteryCapacityWh = null
                    batteryCapacityComponents.clear()
                    gatt.close()
                    boschGatt = null
                    mainHandler.post { onStatusChange("Disconnected") }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Ride data subscription
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

            // Config subscription
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
                BOSCH_CHAR_UUID -> parseBoschData(value)
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
                BOSCH_CHAR_UUID -> parseBoschData(value)
                BOSCH_CONFIG_UUID -> parseConfigData(value, source = "notify")
            }
        }
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
            0x0A -> {
                val lenIndex = payloadStart + 1
                if (lenIndex < frame.size) {
                    val strLen = frame[lenIndex]
                    if (strLen > 0 && lenIndex + 1 + strLen <= frame.size) {
                        val payload = frame.subList(lenIndex + 1, lenIndex + 1 + strLen)
                        if (payload.all { it in 0x20..0x7E || it == 0x00 }) {
                            return payload.map { it.toChar() }.joinToString("").trim()
                        }
                    }
                }
            }
        }

        val asciiText = frame.drop(4)
            .filter { it in 0x20..0x7E || it == 0x00 }
            .map { it.toChar() }
            .joinToString("")

        return if (asciiText.isNotBlank() && asciiText.length >= 3) {
            asciiText.trim()
        } else {
            if (frame.size > 4) {
                frame.drop(4).joinToString("-") { "%02X".format(it) }
            } else {
                frame.joinToString("-") { "%02X".format(it) }
            }
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
                    if (msg.value != lastBattery) cachedRangeValid = false
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
            updateRangeEstimate()
            val rd = BoschRideData(riderPower, motorPower, cadence, lastBattery, assistMode,
                estimatedRangeKm = calculateEstimatedRange(),
                odometerMetres = odometer,
                speedHundredthsKmh = bikeSpeed)
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

    private fun updateRangeEstimate() {
        val currentBattery = lastBattery
        val currentOdometer = odometer

        if (assistMode == 0) assistOffDuringWindow = true

        if (lastBatteryForRange < 0) {
            lastBatteryForRange = currentBattery
            lastOdometerForRange = currentOdometer
            assistOffDuringWindow = (assistMode == 0)
            return
        }

        val batteryDrop = lastBatteryForRange - currentBattery
        if (batteryDrop <= 0) {
            if (batteryDrop < 0) {
                lastBatteryForRange = currentBattery
                lastOdometerForRange = currentOdometer
                assistOffDuringWindow = (assistMode == 0)
            }
            return
        }

        if (batteryDrop >= 1) {
            if (ignoreFirstBatteryDrop) {
                ignoreFirstBatteryDrop = false
                lastBatteryForRange = currentBattery
                lastOdometerForRange = currentOdometer
                assistOffDuringWindow = (assistMode == 0)
                return
            }

            // only start excluding high battery samples once buffer is full
            if (metresPerPercent.size >= MAX_SAMPLES && lastBatteryForRange > SAMPLE_MIN_SOC_PCT) {
                lastBatteryForRange = currentBattery
                lastOdometerForRange = currentOdometer
                assistOffDuringWindow = (assistMode == 0)
                return
            }

            // Discard this sample if assist was off at any point during the window
            if (!assistOffDuringWindow) {
                val metresTravelled = currentOdometer - lastOdometerForRange
                if (metresTravelled > 0) {
                    val metresPerPct = metresTravelled / batteryDrop
                    repeat(batteryDrop) {
                        if (metresPerPercent.size >= MAX_SAMPLES) metresPerPercent.removeFirst()
                        metresPerPercent.addLast(metresPerPct)
                    }
                    persistRangeSamples()
                    cachedRangeValid = false
                }
            } else {
                Log.d(TAG, "Range sample discarded: assist was off during this % window " +
                        "(soc ${lastBatteryForRange}→${currentBattery}, " +
                        "${currentOdometer - lastOdometerForRange}m)")
            }

            lastBatteryForRange = currentBattery
            lastOdometerForRange = currentOdometer
            assistOffDuringWindow = (assistMode == 0)
        }
    }

    fun calculateEstimatedRange(): Int? {
        if (cachedRangeValid) return cachedRangeKm
        if (metresPerPercent.isEmpty()) return null

        val sampleSize = rangeSensitivity.coerceIn(3, metresPerPercent.size)
        val window = metresPerPercent.toList().takeLast(sampleSize)
        if (window.size < 3) return null
        val n = window.size

        val mean = window.sum().toDouble() / n
        val variance = window.sumOf { (it - mean) * (it - mean) } / (n - 1)
        val stdDev = Math.sqrt(variance)
        val sem = stdDev / Math.sqrt(n.toDouble())

        val df = n - 1
        val tCrit = if (df - 1 < T_CRIT_95.size) T_CRIT_95[df - 1] else T_CRIT_LARGE_SAMPLE

        val ciLower = mean - tCrit * sem
        val conservativeMetresPerPct = maxOf(ciLower, 1.0)

        val linearPct = minOf(lastBattery, SAMPLE_MIN_SOC_PCT).toDouble()
        val topPct = maxOf(0, lastBattery - SAMPLE_MIN_SOC_PCT).toDouble()
        val rangeKm = (linearPct * conservativeMetresPerPct + topPct * conservativeMetresPerPct * 0.7) / 1000.0

        cachedRangeKm = Math.round(rangeKm).toInt()
        cachedRangeValid = true
        return cachedRangeKm
    }

    private fun persistRangeSamples() {
        val editor = prefs.edit()
        editor.putInt("range_sample_count", metresPerPercent.size)
        metresPerPercent.forEachIndexed { i, v -> editor.putInt("range_sample_$i", v) }
        editor.apply()
    }

    private fun clearRangeSamples() {
        metresPerPercent.clear()
        cachedRangeKm = null
        cachedRangeValid = false
        val editor = prefs.edit()
        editor.putInt("range_sample_count", 0)
        for (i in 0 until MAX_SAMPLES) editor.remove("range_sample_$i")
        editor.apply()
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

            if (msg.messageId == 0x009B) {
                handleBatteryNameMessage(msg.rawBytes)
            }
        }
    }

    private fun handleBatteryNameMessage(rawBytes: List<Int>) {
        if (rawBytes.size < 6 || rawBytes[4] != 0x0A) return

        val lenIndex = 5
        val strLen = rawBytes[lenIndex]
        if (strLen <= 0 || lenIndex + 1 + strLen > rawBytes.size) return

        val name = try {
            rawBytes.subList(lenIndex + 1, lenIndex + 1 + strLen)
                .map { it.toChar() }
                .joinToString("")
                .trim()
        } catch (e: Exception) {
            Log.w(TAG, "Battery name decode failed: ${e.message}")
            return
        }

        if (name.isBlank()) return

        val wh = parseBatteryCapacityWh(name) ?: return

        if (batteryCapacityComponents.none { it.first == name }) {
            batteryCapacityComponents.add(Pair(name, wh))
            batteryCapacityWh = batteryCapacityComponents.sumOf { it.second }
            Log.i(TAG, "Battery capacity: '$name' = ${wh} Wh, total = $batteryCapacityWh Wh")
        }
    }

    private fun parseBatteryCapacityWh(name: String): Int? =
        Regex("\\d+").findAll(name).lastOrNull()?.value?.toIntOrNull()

    private fun decodeVarint(bytes: List<Int>, startIndex: Int): Pair<Int, Int> {
        if (startIndex >= bytes.size) return Pair(0, 0)
        var result = 0
        var shift = 0
        var idx = startIndex
        var consumed = 0
        try {
            while (idx < bytes.size && consumed < 5) {
                val b = bytes[idx]
                result = result or ((b and 0x7F) shl shift)
                consumed++
                idx++
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

                if (messageLength < 2 || messageLength > 50 || index + totalSize > bytes.size) {
                    index++; continue
                }

                val messageId = (bytes[index + 2] shl 8) or bytes[index + 3]
                val messageBytes = bytes.subList(index, index + totalSize)

                var dataValue = 0
                var dataType = 0
                if (messageLength == 2) {
                    dataValue = 0
                } else if (messageLength > 2 && messageBytes.size > 4) {
                    dataType = messageBytes[4]
                    when (dataType) {
                        0x08 -> if (messageBytes.size > 5) dataValue = decodeVarint(messageBytes, 5).first
                        0x0A -> if (messageBytes.size > 5) dataValue = messageBytes[5]
                    }
                }

                messages.add(BoschMessage(messageId, dataType, dataValue, messageBytes))
                index += totalSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet parse error: ${e.message}")
        }
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Bike Battery Alert")
            .setContentText("Bike battery at $percent%")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1001, notification)
    }
}