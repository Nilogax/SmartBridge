package com.nilogax.smartbridge

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789ABC")
private val RX_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-123456789ABD")
private val TX_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-123456789ABE")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val TAG = "SmartBridgeBLE"
private const val WRITE_INTERVAL_MS = 250L
private const val RECONNECT_SCAN_TIMEOUT_MS = 4500L

enum class BleState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

data class RideData(
    val watts: Int = 0,
    val cadence: Int = 0,
    val leftBalance: Int = 100,
    val batteryPercent: Int = 100,
    val assistMode: Int = 0,
    val odometerCm: Int = 0,      // 0.01 km units
    val speedDmkh: Int = 0,       // 0.1 km/h
    val remainingRangeKm: Int = 0 // whole km
)

class SmartBridgeBleManager(
    private val context: Context,
    private val onStateChange: (BleState, String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val prefs = context.getSharedPreferences("SmartBridgePrefs", Context.MODE_PRIVATE)

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var writeRunnable: Runnable? = null
    private var reconnectScanRunnable: Runnable? = null

    private var bridgeBattery: Int = -1
    private var targetMac: String? = null

    var currentData = RideData()
        private set

    private var lastSentData: RideData? = null

    var isConnected = false
        private set

    var transportMode: Boolean = false
        private set

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        if (!hasPermissions()) {
            onStateChange(BleState.ERROR, "Missing BLE permissions")
            return
        }

        stopScan()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
            }
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        scanner?.startScan(scanCallback)
        onStateChange(BleState.SCANNING, "Scanning")
    }

    fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
    }

    fun connect(device: BluetoothDevice) {
        targetMac = device.address
        prefs.edit().putString("last_bridge_mac", device.address).apply()
        bridgeBattery = -1

        onStateChange(BleState.CONNECTING, "Connecting...")
        stopReconnectScan()

        disconnectGatt()

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun connectToSavedDevice(mac: String) {
        targetMac = mac
        val device = bluetoothAdapter.getRemoteDevice(mac)
        connect(device)
    }

    fun disconnect() {
        stopReconnectScan()
        stopWriteLoop()
        disconnectGatt()
        targetMac = null
        isConnected = false
        bridgeBattery = -1
        onStateChange(BleState.IDLE, "Not Paired")
    }

    fun forgetDevice() {
        disconnect()
        prefs.edit().remove("last_bridge_mac").remove("last_bridge_name").apply()
    }

    private fun disconnectGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxCharacteristic = null
    }

    private fun startReconnectScan() {
        if (targetMac == null || reconnectScanRunnable != null) return

        onStateChange(BleState.CONNECTING, "Waiting...")

        val filter = ScanFilter.Builder().setDeviceAddress(targetMac).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address.equals(targetMac, ignoreCase = true)) {
                    Log.d(TAG, "Bridge found in advertisement → connecting")
                    stopReconnectScan()
                    connect(result.device)
                }
            }
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        scanner?.startScan(listOf(filter), settings, scanCallback!!)

        reconnectScanRunnable = Runnable {
            stopReconnectScan()
            if (!isConnected) startReconnectScan()
        }
        mainHandler.postDelayed(reconnectScanRunnable!!, RECONNECT_SCAN_TIMEOUT_MS)
    }

    private fun stopReconnectScan() {
        reconnectScanRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectScanRunnable = null
        stopScan()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT Connected")
                    isConnected = true
                    stopReconnectScan()
                    mainHandler.postDelayed({ gatt.discoverServices() }, 250)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT Disconnected")
                    isConnected = false
                    stopWriteLoop()
                    rxCharacteristic = null
                    lastSentData = null

                    if (targetMac != null) {
                        startReconnectScan()
                    } else {
                        onStateChange(BleState.IDLE, "Not Paired")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(SERVICE_UUID) ?: return
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)

            service.getCharacteristic(TX_CHAR_UUID)?.let { tx ->
                gatt.setCharacteristicNotification(tx, true)
                tx.getDescriptor(CCCD_UUID)?.let { descriptor ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            notifyConnectedStatus()
            startWriteLoop()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID)?.let {
                    gatt.readCharacteristic(it)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHAR_UUID && value.isNotEmpty()) {
                handleIncomingStatus(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                characteristic.value?.let {
                    if (it.isNotEmpty()) handleBatteryNotification(it[0].toInt() and 0xFF)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == TX_CHAR_UUID && value.isNotEmpty()) {
                handleBatteryNotification(value[0].toInt() and 0xFF)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == TX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                characteristic.value?.let {
                    if (it.isNotEmpty()) handleBatteryNotification(it[0].toInt() and 0xFF)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Write failed: $status")
        }
    }

    private fun handleBatteryNotification(percent: Int) {
        bridgeBattery = percent
        notifyConnectedStatus()
    }

    private fun handleIncomingStatus(value: ByteArray) {
        if (value.size >= 2) {
            val battery = value[0].toInt() and 0xFF
            val modeByte = value[1].toInt() and 0xFF

            bridgeBattery = battery
            val newMode = modeByte == 0x01

            if (newMode != transportMode) {
                transportMode = newMode
                prefs.edit().putBoolean(
                    "transport_mode",
                    transportMode
                )
                Log.d(TAG, "Board reported Transport Mode: $transportMode")
            }

            mainHandler.post {
                onStateChange(BleState.CONNECTED,
                    if (bridgeBattery >= 0) "Connected $bridgeBattery%" else "Connected __%")
            }
        } else if (value.isNotEmpty()) {
            handleBatteryNotification(value[0].toInt() and 0xFF)
        }
    }
    private fun notifyConnectedStatus() {
        val statusText = if (bridgeBattery >= 0) "Connected $bridgeBattery%" else "Connected __%"
        onStateChange(BleState.CONNECTED, statusText)
    }

    fun updateData(watts: Int, cadence: Int, leftBalance: Int, batteryPercent: Int, assistMode: Int,
                   odometerMetres: Int = 0, speedHundredthsKmh: Int = 0, remainingRangeKm: Int = 0) {
        currentData = RideData(
            watts.coerceIn(0, 2500),
            cadence.coerceIn(0, 200),
            leftBalance.coerceIn(0, 100),
            batteryPercent.coerceIn(0, 100),
            assistMode.coerceIn(0, 4),
            // Convert to ANT LEV units:
            //   odometer: metres → 0.01 km  (÷10), capped at 24-bit max
            //   speed:    0.01 km/h → 0.1 km/h (÷10), capped at 12-bit max (0xFFF)
            //   range:    whole km, capped at 12-bit max (0xFFE; 0xFFF = unknown in some impls)
            odometerCm        = (odometerMetres / 10).coerceIn(0, 0xFFFFFF),
            speedDmkh         = (speedHundredthsKmh / 10).coerceIn(0, 0xFFF),
            remainingRangeKm  = remainingRangeKm.coerceIn(0, 4094)
        )
    }

    private fun startWriteLoop() {
        writeRunnable = object : Runnable {
            override fun run() {
                writePacket()
                mainHandler.postDelayed(this, WRITE_INTERVAL_MS)
            }
        }
        mainHandler.post(writeRunnable!!)
    }

    private fun stopWriteLoop() {
        writeRunnable?.let { mainHandler.removeCallbacks(it) }
        writeRunnable = null
    }

    private fun writePacket() {
        val chr = rxCharacteristic ?: return
        val g = gatt ?: return
        val d = currentData

        if (d == lastSentData) return // no TX if no change

        lastSentData = d

        /*
         * Packet layout (15 bytes):
         *  [0]     0x01  type
         *  [1-2]   watts LE
         *  [3]     cadence
         *  [4]     leftBalance
         *  [5]     batteryPercent
         *  [6]     assistMode
         *  [7-9]   odometer (0.01 km units, 24-bit LE)
         *  [10-11] speed (0.1 km/h units, 12-bit LE – bits 11-8 in low nibble of [11])
         *  [12-13] remaining range (whole km, 12-bit LE – bits 11-8 in low nibble of [13])
         *  [14]    XOR checksum of [0]..[13]
         */
        val odo0 = (d.odometerCm and 0xFF).toByte()
        val odo1 = ((d.odometerCm shr 8) and 0xFF).toByte()
        val odo2 = ((d.odometerCm shr 16) and 0xFF).toByte()
        val spd0 = (d.speedDmkh and 0xFF).toByte()
        val spd1 = ((d.speedDmkh shr 8) and 0x0F).toByte()
        val rng0 = (d.remainingRangeKm and 0xFF).toByte()
        val rng1 = ((d.remainingRangeKm shr 8) and 0x0F).toByte()

        val pkt = ByteArray(15).also {
            it[0]  = 0x01
            it[1]  = (d.watts and 0xFF).toByte()
            it[2]  = ((d.watts shr 8) and 0xFF).toByte()
            it[3]  = d.cadence.toByte()
            it[4]  = d.leftBalance.toByte()
            it[5]  = d.batteryPercent.toByte()
            it[6]  = d.assistMode.toByte()
            it[7]  = odo0
            it[8]  = odo1
            it[9]  = odo2
            it[10] = spd0
            it[11] = spd1
            it[12] = rng0
            it[13] = rng1
            it[14] = (it[0].toInt() xor it[1].toInt() xor it[2].toInt() xor it[3].toInt()
                    xor it[4].toInt() xor it[5].toInt() xor it[6].toInt() xor it[7].toInt()
                    xor it[8].toInt() xor it[9].toInt() xor it[10].toInt() xor it[11].toInt()
                    xor it[12].toInt() xor it[13].toInt()).toByte()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(chr, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            chr.value = pkt
            @Suppress("DEPRECATION")
            g.writeCharacteristic(chr)
        }
    }

    fun sendTransportMode(enabled: Boolean) {
        val chr = rxCharacteristic ?: return
        val g = gatt ?: return

        val pkt = ByteArray(6).also {
            it[0] = 0x02
            it[1] = if (enabled) 0x01 else 0x00
            it[2] = 0x00
            it[3] = 0x00
            it[4] = 0x00
            it[5] = (it[0].toInt() xor it[1].toInt() xor it[2].toInt() xor it[3].toInt() xor it[4].toInt()).toByte()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(chr, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            chr.value = pkt
            @Suppress("DEPRECATION")
            g.writeCharacteristic(chr)
        }
    }
}