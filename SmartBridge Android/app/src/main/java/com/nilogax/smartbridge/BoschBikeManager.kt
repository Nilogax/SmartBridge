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
    val batteryPercent: Int = 0
)

class BoschBikeManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onStatusChange: (String) -> Unit,
    private val onDataUpdate: (BoschRideData) -> Unit,
    var onRawPacketReceived: (Long, String, String, Int) -> Unit
) {
    var isConnected = false
        private set

    private val BOSCH_SERVICE_UUID = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_CHAR_UUID    = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val CCCD_UUID          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var boschGatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var riderPower = 0
    private var motorPower = 0
    private var cadence = 0
    private var lastBattery = 100

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
            Log.d(TAG, "Already bonded — connecting directly")
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
        boschGatt?.disconnect()
        boschGatt?.close()
        boschGatt = null
        isConnected = false
    }

    fun forget() {
        disconnect()
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
                    gatt.close()
                    boschGatt = null
                    mainHandler.post { onStatusChange("Disconnected") }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = gatt
                .getService(BOSCH_SERVICE_UUID)
                ?.getCharacteristic(BOSCH_CHAR_UUID) ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            Log.i(TAG, "Bosch handshake complete")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseBoschData(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            parseBoschData(characteristic.value)
        }
    }

    private fun parseBoschData(data: ByteArray) {
        val bytes = data.map { it.toInt() and 0xFF }

        val fullHex = bytes.joinToString("-") { "%02X".format(it) }
        onRawPacketReceived(System.currentTimeMillis(), fullHex, "Raw", 0)

        val messages = parseBoschPacket(bytes)
        var hasKnownMessage = false

        for (msg in messages) {
            var label = "Other"
            when (msg.messageId) {
                0x985B -> { riderPower = msg.value;        hasKnownMessage = true; label = "Rider Power" }
                0x985D -> { motorPower = msg.value;        hasKnownMessage = true; label = "Motor Power" }
                0x985A -> { cadence    = msg.value / 2;   hasKnownMessage = true; label = "Cadence"     }
                0x8088 -> {
                    lastBattery = msg.value
                    handleBatteryUpdate(msg.value)
                    hasKnownMessage = true
                    label = "Battery"
                }
            }
            onRawPacketReceived(
                System.currentTimeMillis(),
                msg.rawBytes.joinToString("-") { "%02X".format(it) },
                label,
                msg.value
            )
        }

        if (hasKnownMessage) {
            val rd = BoschRideData(riderPower, motorPower, cadence, lastBattery)
            onDataForward?.invoke(rd)
            onDataUpdate(rd)
        }
    }

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

    private fun decodeVarint(bytes: List<Int>, startIndex: Int = 0): Pair<Int, Int> {
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

                if (messageLength < 2 || messageLength > 50 || index + totalSize > bytes.size) {
                    index++; continue
                }

                val messageId = (bytes[index + 2] shl 8) or bytes[index + 3]
                val messageBytes = bytes.subList(index, index + totalSize)

                var dataValue = 0; var dataType = 0
                if (messageLength > 2 && 4 < messageBytes.size) {
                    dataType = messageBytes[4]
                    when (dataType) {
                        0x08 -> if (5 < messageBytes.size) dataValue = decodeVarint(messageBytes, 5).first
                        0x0A -> if (5 < messageBytes.size) dataValue = messageBytes[5]
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