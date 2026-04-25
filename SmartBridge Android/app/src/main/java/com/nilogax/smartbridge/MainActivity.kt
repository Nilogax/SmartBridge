package com.nilogax.smartbridge

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity(), SmartBridgeService.UiListener {

    private lateinit var prefs: SharedPreferences
    private var service: SmartBridgeService? = null
    private var serviceBound = false

    // UI
    private lateinit var tvBikeStatus: TextView
    private lateinit var tvBridgeStatus: TextView
    private lateinit var tvRiderPower: TextView
    private lateinit var tvMotorPower: TextView
    private lateinit var tvCadence: TextView
    private lateinit var tvBikeBattery: TextView
    private lateinit var tvBikeName: TextView
    private lateinit var tvBridgeName: TextView
    private lateinit var btnBikeAction: Button
    private lateinit var btnBridgeAction: Button
    private lateinit var btnToggleLogging: Button
    private lateinit var spinnerBattery: Spinner

    private lateinit var btnTransportMode: Button
    private lateinit var switchDataEmulator: Switch

    private var isLoggingEnabled = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startAndBindService()
        else showPermissionRationale()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SmartBridgeService.LocalBinder).getService()
            serviceBound = true
            service!!.setUiListener(this@MainActivity)
            refreshButtonsAndNames()
            refreshTransportButton()
            onLoggingStateChanged(service!!.isLogging())
            switchDataEmulator.isChecked = service!!.isDataEmulatorEnabled()
            applyBatteryThreshold()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
        }


    }


    override fun onBikeStatusChanged(status: String) {
        runOnUiThread {
            tvBikeStatus.text = status
            val bikeConnected = status.startsWith("Connected")
            // Emulator is only meaningful when the bike is NOT connected.
            switchDataEmulator.isEnabled = !bikeConnected
        }
    }

    override fun onBridgeStatusChanged(status: String) {
        runOnUiThread {
            tvBridgeStatus.text = status
            refreshTransportButton()
        }
    }

    override fun onRideDataChanged(data: BoschRideData) {
        runOnUiThread {
            tvRiderPower.text  = "${data.riderPower} W"
            tvMotorPower.text  = "${data.motorPower} W"
            tvCadence.text     = "${data.cadence} RPM"
            tvBikeBattery.text = "${data.batteryPercent}%"
        }
    }

    override fun onRawPacket(timestamp: Long, rawHex: String, label: String, value: Int) {
        // Logging is handled in the service; UI only displays live data.
    }

    override fun onLogSaved(fileName: String) {
        runOnUiThread {
            Toast.makeText(this, "Log saved: $fileName", Toast.LENGTH_LONG).show()
        }
    }

    override fun onLoggingStateChanged(isLogging: Boolean) {
        runOnUiThread {
            isLoggingEnabled = isLogging
            btnToggleLogging.text = if (isLogging) "STOP LOGGING (2m)" else "Start Logging"
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("SmartBridgePrefs", MODE_PRIVATE)
        bindUI()
        setupActionButtons()
        setupLoggingButton()
        setupBatterySpinner()
        setupTransportModeButton()
        setupDataEmulatorSwitch()
    }

    override fun onStart() {
        super.onStart()
        if (hasBluetoothPermissions()) startAndBindService()
        else requestStartupPermissions()
    }

    override fun onStop() {
        if (serviceBound) {
            service?.setUiListener(null)
            unbindService(serviceConnection)
            serviceBound = false
            service = null
        }
        super.onStop()
    }

    private fun startAndBindService() {
        if (serviceBound) return
        val intent = Intent(this, SmartBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun bindUI() {
        tvBikeStatus   = findViewById(R.id.tvBikeStatus)
        tvBridgeStatus = findViewById(R.id.tvBridgeStatus)
        tvRiderPower   = findViewById(R.id.tvRiderPower)
        tvMotorPower   = findViewById(R.id.tvMotorPower)
        tvCadence      = findViewById(R.id.tvCadence)
        tvBikeBattery  = findViewById(R.id.tvBikeBattery)
        tvBikeName     = findViewById(R.id.tvBikeName)
        tvBridgeName   = findViewById(R.id.tvBridgeName)
        btnBikeAction  = findViewById(R.id.btnBikeAction)
        btnBridgeAction  = findViewById(R.id.btnBridgeAction)
        btnToggleLogging = findViewById(R.id.btnToggleLogging)
        spinnerBattery   = findViewById(R.id.spinnerBattery)
        btnTransportMode = findViewById(R.id.btnTransportMode)
        switchDataEmulator = findViewById(R.id.switchDataEmulator)
    }

    private fun refreshButtonsAndNames() {
        val bikeMac    = prefs.getString("last_bike_mac", null)
        val bridgeMac  = prefs.getString("last_bridge_mac", null)
        val bikeName   = prefs.getString("last_bike_name", null)
        val bridgeName = prefs.getString("last_bridge_name", null)

        btnBikeAction.text = if (bikeMac != null) "Forget Bike" else "Pair Bike"
        tvBikeName.text       = bikeName ?: ""
        tvBikeName.visibility = if (bikeMac != null) View.VISIBLE else View.GONE

        btnBridgeAction.text    = if (bridgeMac != null) "Forget Bridge" else "Pair Bridge"
        tvBridgeName.text       = bridgeName ?: ""
        tvBridgeName.visibility = if (bridgeMac != null) View.VISIBLE else View.GONE
    }

    private fun refreshTransportButton() {
        val isTransport = service?.isTransportMode() ?: false
        btnTransportMode.text = if (isTransport)
            "Set Ride Mode"
        else
            "Set Transport Mode"

        btnTransportMode.setBackgroundColor(
            if (isTransport)
                getColor(R.color.bike_button_bg)
            else
                getColor(R.color.bridge_button_bg)
        )

        btnTransportMode.setTextColor(
            if (isTransport)
                getColor(R.color.bike_text)
            else
                getColor(R.color.bridge_text)
        )

        btnTransportMode.visibility = if (tvBridgeStatus.text.startsWith("Connected"))  View.VISIBLE
                                        else View.GONE
    }

    private fun setupActionButtons() {
        btnBikeAction.setOnClickListener {
            if (prefs.getString("last_bike_mac", null) == null) {
                requestPermissions { startBikeScan() }
            } else {
                showForgetConfirmation("Bosch Bike") {
                    service?.bikeManager?.forget()
                        ?: prefs.edit().remove("last_bike_mac").remove("last_bike_name").apply()
                    refreshButtonsAndNames()
                }
            }
        }

        btnBridgeAction.setOnClickListener {
            if (prefs.getString("last_bridge_mac", null) == null) {
                requestPermissions { startBridgeScan() }
            } else {
                showForgetConfirmation("SmartBridge") {
                    service?.bridgeManager?.forgetDevice()
                        ?: prefs.edit().remove("last_bridge_mac").remove("last_bridge_name").apply()
                    refreshButtonsAndNames()
                }
            }
        }
    }

    private fun showForgetConfirmation(target: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Forget $target")
            .setMessage("Are you sure you want to forget this $target?")
            .setPositiveButton("Yes") { _, _ -> onConfirm() }
            .setNegativeButton("No", null)
            .show()
    }

    // ── Bike scan ─────────────────────────────────────────────────────────────
    private fun startBikeScan() {
        val manager = service?.bikeManager ?: BoschBikeManager(
            context = this, prefs = prefs,
            onStatusChange = {}, onDataUpdate = {},
            onRawPacketReceived = { _, _, _, _ -> }
        )

        val foundDevices = mutableListOf<BluetoothDevice>()
        val names = mutableListOf<String>()
        val dialogView = layoutInflater.inflate(R.layout.dialog_scan_list, null)
        val listView = dialogView.findViewById<ListView>(R.id.scan_list_view)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        manager.startScan { device ->
            if (!device.name.isNullOrBlank()) {
                val entry = "${device.name}\n${device.address}"
                if (!names.contains(entry)) {
                    foundDevices.add(device)
                    names.add(entry)
                    runOnUiThread { adapter.notifyDataSetChanged() }
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Bosch Bike")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ -> manager.stopScan() }
            .create()

        listView.setOnItemClickListener { _, _, which, _ ->
            manager.stopScan()
            manager.bondAndSave(foundDevices[which])
            refreshButtonsAndNames()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun startBridgeScan() {
        // Use a throwaway manager just for scanning — actual connection goes through
        // the service's bridgeManager so state flows correctly via the UiListener.
        val scanManager = SmartBridgeBleManager(this) { _, _ -> }

        val foundDevices = mutableListOf<BluetoothDevice>()
        val names = mutableListOf<String>()
        val dialogView = layoutInflater.inflate(R.layout.dialog_scan_list, null)
        val listView = dialogView.findViewById<ListView>(R.id.scan_list_view)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        scanManager.startScan { device ->
            if (!device.name.isNullOrBlank()) {
                val entry = "${device.name}\n${device.address}"
                if (!names.contains(entry)) {
                    foundDevices.add(device)
                    names.add(entry)
                    runOnUiThread { adapter.notifyDataSetChanged() }
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select SmartBridge")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ -> scanManager.stopScan() }
            .create()

        listView.setOnItemClickListener { _, _, which, _ ->
            scanManager.stopScan()
            val device = foundDevices[which]
            prefs.edit()
                .putString("last_bridge_mac", device.address)
                .putString("last_bridge_name", device.name ?: "SmartBridge")
                .apply()

            service?.bridgeManager?.connect(device)
            refreshButtonsAndNames()
            Log.d("MainActivity", "Bridge MAC saved: ${device.address}")
            dialog.dismiss()
        }
        dialog.show()
    }

    // ── Logging ───────────────────────────────────────────────────────────────
    private fun setupLoggingButton() {
        btnToggleLogging.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!isLoggingEnabled) {
                if (!svc.bikeManager.isConnected) {
                    Toast.makeText(this, "Connect bike first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                svc.startLogging()
                Toast.makeText(this, "Logging started in background...", Toast.LENGTH_SHORT).show()
            } else {
                svc.stopLogging()
            }
        }
    }


    // ── Battery spinner ───────────────────────────────────────────────────────
    private fun setupBatterySpinner() {
        val options = arrayOf("Off", "5%", "10%")
        spinnerBattery.adapter = ArrayAdapter(this, R.layout.spinner_item, options)
        spinnerBattery.setSelection(prefs.getInt("battery_alert_pos", 0))
        spinnerBattery.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("battery_alert_pos", pos).apply()
                applyBatteryThreshold()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun setupTransportModeButton() {
        btnTransportMode.setOnClickListener {
            val bridgeConnected = service?.bridgeManager?.isConnected == true

            if (!bridgeConnected) {
                Toast.makeText(this, "Bridge must be connected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentMode = service?.isTransportMode() ?: false
            val targetMode = !currentMode
            val targetName = if (targetMode) "Transport Mode" else "Ride Mode"

            AlertDialog.Builder(this)
                .setTitle("Change Operating Mode")
                .setMessage("Switch to $targetName?")
                .setPositiveButton("Yes") { _, _ ->
                    service?.setTransportMode(targetMode)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupDataEmulatorSwitch() {
        val enabled = prefs.getBoolean("data_emulator", false)
        switchDataEmulator.isChecked = enabled

        switchDataEmulator.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("data_emulator", isChecked).apply()
            service?.setDataEmulatorEnabled(isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Data emulator ON" else "Data emulator OFF",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyBatteryThreshold() {
        val threshold = when (prefs.getInt("battery_alert_pos", 0)) { 1 -> 5; 2 -> 10; else -> 0 }
        service?.bikeManager?.setBatteryAlertThreshold(threshold)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStartupPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestPermissions(action: () -> Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Bluetooth permissions are required to connect to the bike and SmartBridge.")
            .setPositiveButton("Try Again") { _, _ -> requestStartupPermissions() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}