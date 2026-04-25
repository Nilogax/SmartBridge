package com.nilogax.smartbridge

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val savedMac = context
            .getSharedPreferences("SmartBridgePrefs", Context.MODE_PRIVATE)
            .getString("last_bike_mac", null)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed — waiting for OS to reconnect bonded bike")
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (device?.address == savedMac) {
                    Log.d(TAG, "Bike connected (ACL) — starting service")
                    startService(context)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (device?.address == savedMac) {
                    Log.d(TAG, "Bike disconnected (ACL) — stopping service")
                    context.stopService(Intent(context, SmartBridgeService::class.java))
                }
            }
        }
    }

    private fun startService(context: Context) {
        val intent = Intent(context, SmartBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}