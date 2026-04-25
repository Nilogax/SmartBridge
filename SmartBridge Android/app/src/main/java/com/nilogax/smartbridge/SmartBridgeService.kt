package com.nilogax.smartbridge

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.Manifest
import android.os.*
import androidx.core.app.NotificationCompat
import android.util.Log
import kotlin.math.sin
import android.content.SharedPreferences

private const val TAG = "SmartBridgeService"

class SmartBridgeService : Service() {

    private var isStopping = false
    private var startupGraceUntilMs = 0L
    private var reconnectGraceUntilMs = 0L

    private var isLoggingEnabled = false
    private var logBuilder: StringBuilder? = null
    private val logHandler = Handler(Looper.getMainLooper())
    private var autoStopLoggingRunnable: Runnable? = null
    private var lastRideData: BoschRideData? = null

    private val emulatorHandler = Handler(Looper.getMainLooper())
    private var dataEmulatorEnabled = false
    private var emulatorRunnable: Runnable? = null
    private lateinit var prefs: SharedPreferences

    var bikeStatus: String = "Not Paired"
        private set(v) { field = v; listener?.onBikeStatusChanged(v) }

    var bridgeStatus: String = "Not Paired"
        private set(v) {
            field = v
            listener?.onBridgeStatusChanged(v)
            if (!isStopping) updateNotification()
        }
    private var wasBridgeConnected = false
    private var wasBikeConnected = false

    interface UiListener {
        fun onBikeStatusChanged(status: String)
        fun onBridgeStatusChanged(status: String)
        fun onRideDataChanged(data: BoschRideData)
        fun onRawPacket(timestamp: Long, rawHex: String, label: String, value: Int)
        fun onLogSaved(fileName: String)
        fun onLoggingStateChanged(isLogging: Boolean)
    }

    private var listener: UiListener? = null

    fun setUiListener(l: UiListener?) {
        listener = l
        if (l != null) {
            l.onBikeStatusChanged(bikeStatus)
            l.onBridgeStatusChanged(bridgeStatus)
            l.onLoggingStateChanged(isLoggingEnabled)
            lastRideData?.let { l.onRideDataChanged(it) }
        }
        stopSelfIfIdle()
    }

    lateinit var bikeManager: BoschBikeManager
    lateinit var bridgeManager: SmartBridgeBleManager

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): SmartBridgeService = this@SmartBridgeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        prefs = getSharedPreferences("SmartBridgePrefs", MODE_PRIVATE)
        dataEmulatorEnabled = prefs.getBoolean("data_emulator", false)

        bikeManager = BoschBikeManager(
            context = this,
            prefs = prefs,
            onStatusChange = { status ->
                bikeStatus = status
                val nowBikeConnected = status.startsWith("Connected")
                if (nowBikeConnected && !wasBikeConnected) {
                    maybeNotifyTransportModeWarning()
                }
                wasBikeConnected = nowBikeConnected
                // Start/stop emulator depending on bike connection state.
                if (bikeManager.isConnected) stopDataEmulator()
                else maybeStartDataEmulator()
                stopSelfIfIdle()
            },
            onDataUpdate = { data ->
                lastRideData = data
                listener?.onRideDataChanged(data)
                bridgeManager.updateData(
                    data.riderPower,
                    data.cadence,
                    calculateRiderBalance(data.riderPower, data.motorPower)
                )
            },
            onRawPacketReceived = { ts, hex, label, value ->
                listener?.onRawPacket(ts, hex, label, value)   // still update UI if open
                if (isLoggingEnabled) {
                    logBuilder?.append("$ts,$hex,$label,$value\n")
                }
            }
        )

        bridgeManager = SmartBridgeBleManager(this) { state, msg ->
            bridgeStatus = msg
            stopSelfIfIdle()

            val nowConnected = msg.startsWith("Connected")
            // Only send transport mode once per (re)connection.
            if (nowConnected && !wasBridgeConnected) {
                val transportEnabled = getSharedPreferences("SmartBridgePrefs", MODE_PRIVATE)
                    .getBoolean("transport_mode", false)
                bridgeManager.sendTransportMode(transportEnabled)
            }
            wasBridgeConnected = nowConnected
        }

        // If emulator was enabled last run, start it once managers exist.
        maybeStartDataEmulator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        if (!hasBluetoothPermissions()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startupGraceUntilMs = System.currentTimeMillis() + 10_000L

        // Keep the service around briefly to allow quick background reconnects

        val hasSavedBike = prefs.getString("last_bike_mac", null) != null
        val hasSavedBridge = prefs.getString("last_bridge_mac", null) != null
        if (hasSavedBike || hasSavedBridge) {
            reconnectGraceUntilMs = System.currentTimeMillis() + 45_000L
        }

        connectBikeIfSaved()
        connectBridgeIfSaved()

        return START_STICKY
    }

    private fun stopSelfIfIdle() {
        if (isStopping) return

        val bikeConnected = bikeManager.isConnected
        val bridgeConnected = bridgeManager.isConnected
        val appInForeground = listener != null
        val now = System.currentTimeMillis()
        val inGrace = now < startupGraceUntilMs || now < reconnectGraceUntilMs

        val shouldStay = bikeConnected || bridgeConnected || appInForeground || inGrace

        Log.d(TAG, "stopSelfIfIdle() → bike=$bikeConnected, bridge=$bridgeConnected, " +
                "foreground=$appInForeground, grace=$inGrace → stay=$shouldStay")

        if (!shouldStay) {
            isStopping = true
            Log.i(TAG, "Stopping service - nothing keeping it alive")
            stopForeground(STOP_FOREGROUND_REMOVE)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelfIfIdle()
        super.onTaskRemoved(rootIntent)
    }

    fun connectBikeIfSaved() {
        val mac = getSharedPreferences("SmartBridgePrefs", MODE_PRIVATE)
            .getString("last_bike_mac", null) ?: run {
            bikeStatus = "Not Paired"
            return
        }
        bikeManager.connectToMac(mac)
    }

    fun connectBridgeIfSaved() {
        val mac = getSharedPreferences("SmartBridgePrefs", MODE_PRIVATE)
            .getString("last_bridge_mac", null) ?: run {
            bridgeStatus = "Not Paired"
            return
        }
        reconnectGraceUntilMs = System.currentTimeMillis() + 45_000L
        bridgeManager.connectToSavedDevice(mac)
    }

    private fun calculateRiderBalance(riderPower: Int, motorPower: Int): Int {
        val total = riderPower + motorPower
        return if ((total > 0) && (riderPower > 0)) (riderPower * 100 / total) else 100
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartBridge Active")
            .setContentText("Bridge: $bridgeStatus")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()
    }

    private fun startForegroundNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SmartBridge Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SmartBridge running while bike or bridge is connected"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        isStopping = true
        bikeManager.disconnect()
        bridgeManager.disconnect()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    fun setTransportMode(enabled: Boolean) {
        prefs.edit().putBoolean("transport_mode", enabled).apply()
        bridgeManager.sendTransportMode(enabled)
    }

    fun isTransportMode(): Boolean = bridgeManager.transportMode

    fun startLogging() {
        if (isLoggingEnabled) return
        if (!bikeManager.isConnected) {
            Log.w(TAG, "Cannot start logging - bike not connected")
            return
        }

        isLoggingEnabled = true
        logBuilder = StringBuilder("timestamp,raw_hex,label,value\n")
        Log.i(TAG, "Logging started (service)")
        listener?.onLoggingStateChanged(true)

        // Auto-stop after 2 minutes
        autoStopLoggingRunnable = Runnable { stopLoggingAndSave() }
        logHandler.postDelayed(autoStopLoggingRunnable!!, 120_000L)
    }

    fun stopLogging() {
        stopLoggingAndSave()
    }

    private fun stopLoggingAndSave() {
        if (!isLoggingEnabled) return
        isLoggingEnabled = false
        autoStopLoggingRunnable?.let { logHandler.removeCallbacks(it) }
        autoStopLoggingRunnable = null
        listener?.onLoggingStateChanged(false)

        val data = logBuilder?.toString() ?: ""
        logBuilder = null

        if (data.length > 100) {
            saveCsvToDownloads(data)
        } else {
            Log.w(TAG, "No data logged")
        }
    }

    private fun saveCsvToDownloads(data: String) {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val fileName = "SmartBridge_$ts.csv"

        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        try {
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(data.toByteArray())
                }
                Log.i(TAG, "Log saved: $fileName")
                listener?.onLogSaved(fileName)
                showLogSavedNotification(fileName, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save log", e)
        }
    }

    private fun showLogSavedNotification(fileName: String, uri: android.net.Uri) {
        createLogNotificationChannelIfNeeded()

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            42,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, LOG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("SmartBridge log saved")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(LOG_SAVED_NOTIFICATION_ID, notification)
    }

    private fun createLogNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val existing = nm.getNotificationChannel(LOG_CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                LOG_CHANNEL_ID,
                "SmartBridge Logs",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when a log file is saved"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun maybeNotifyTransportModeWarning() {
        val transportEnabled = prefs.getBoolean("transport_mode", false)
        if (!transportEnabled) return

        createWarningNotificationChannelIfNeeded()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 7, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Warning")
            .setContentText("Bridge is in Transport Mode")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(TRANSPORT_WARNING_NOTIFICATION_ID, notification)
    }

    private fun createWarningNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val existing = nm.getNotificationChannel(WARNING_CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                WARNING_CHANNEL_ID,
                "SmartBridge Warnings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Warnings about SmartBridge state"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun isLogging(): Boolean = isLoggingEnabled

    fun isDataEmulatorEnabled(): Boolean = dataEmulatorEnabled

    fun setDataEmulatorEnabled(enabled: Boolean) {
        dataEmulatorEnabled = enabled
        getSharedPreferences("SmartBridgePrefs", MODE_PRIVATE)
            .edit()
            .putBoolean("data_emulator", enabled)
            .apply()

        if (enabled) maybeStartDataEmulator() else stopDataEmulator(resetData = true)
    }

    private fun maybeStartDataEmulator() {
        if (!dataEmulatorEnabled) return
        if (bikeManager.isConnected) return
        if (emulatorRunnable != null) return

        val startMs = SystemClock.elapsedRealtime()
        emulatorRunnable = object : Runnable {
            override fun run() {
                if (!dataEmulatorEnabled || bikeManager.isConnected) {
                    stopDataEmulator()
                    return
                }

                val t = (SystemClock.elapsedRealtime() - startMs).toDouble() / 1000.0

                val rider = (100.0 + 25.0 * sin(t * 2.0 * Math.PI / 6.0)).toInt().coerceAtLeast(0)
                val motor = (50.0 + 15.0 * sin((t + 1.3) * 2.0 * Math.PI / 7.5)).toInt().coerceAtLeast(0)
                val cadence = (80.0 + 6.0 * sin((t + 0.4) * 2.0 * Math.PI / 5.0)).toInt().coerceAtLeast(0)

                val data = BoschRideData(
                    riderPower = rider,
                    motorPower = motor,
                    cadence = cadence,
                    batteryPercent = 100
                )

                lastRideData = data
                listener?.onRideDataChanged(data)
                bridgeManager.updateData(
                    data.riderPower,
                    data.cadence,
                    calculateRiderBalance(data.riderPower, data.motorPower)
                )

                emulatorHandler.postDelayed(this, 750L)
            }
        }

        emulatorHandler.post(emulatorRunnable!!)
        Log.i(TAG, "Data emulator started")
    }

    private fun stopDataEmulator(resetData: Boolean = false) {
        emulatorRunnable?.let { emulatorHandler.removeCallbacks(it) }
        emulatorRunnable = null
        Log.i(TAG, "Data emulator stopped")

        if (resetData) {
            val zero = BoschRideData(
                riderPower = 0,
                motorPower = 0,
                cadence = 0,
                batteryPercent = lastRideData?.batteryPercent ?: 100
            )
            lastRideData = zero
            listener?.onRideDataChanged(zero)
            bridgeManager.updateData(
                zero.riderPower,
                zero.cadence,
                calculateRiderBalance(zero.riderPower, zero.motorPower)
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "bridge_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val LOG_CHANNEL_ID = "smartbridge_log_channel"
        private const val LOG_SAVED_NOTIFICATION_ID = 3001
        private const val WARNING_CHANNEL_ID = "smartbridge_warning_channel"
        private const val TRANSPORT_WARNING_NOTIFICATION_ID = 4001
    }
}