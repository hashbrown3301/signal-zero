package com.itantra.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

enum class BtAvailability { UNSUPPORTED, NO_PERMISSION, OFF, ON }

data class PairedDevice(val name: String, val address: String, val isPhone: Boolean)

data class BluetoothState(
    val availability: BtAvailability,
    /** Phones first, then other paired devices; each group by name. Empty unless [availability] is ON. */
    val devices: List<PairedDevice> = emptyList(),
)

object Bluetooth {
    private const val TAG = "iTantra"

    /** The runtime permission to request, or null where none is needed (Android 8–11). */
    val connectPermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasPermission(context: Context): Boolean = connectPermission == null ||
        ContextCompat.checkSelfPermission(context, connectPermission) == PackageManager.PERMISSION_GRANTED

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission") // bondedDevices is only read after hasPermission()
    fun state(context: Context): BluetoothState {
        val adapter = adapter(context) ?: return BluetoothState(BtAvailability.UNSUPPORTED)
        if (!hasPermission(context)) return BluetoothState(BtAvailability.NO_PERMISSION)
        if (!adapter.isEnabled) return BluetoothState(BtAvailability.OFF)
        val devices = adapter.bondedDevices.orEmpty()
            .map { d ->
                PairedDevice(
                    name = d.name ?: d.address,
                    address = d.address,
                    isPhone = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE,
                )
            }
            .sortedWith(compareBy({ !it.isPhone }, { it.name.lowercase() }))
        Log.i(TAG, "Bluetooth on, paired: " + devices.joinToString { "${it.name}${if (it.isPhone) " [phone]" else ""}" })
        return BluetoothState(BtAvailability.ON, devices)
    }
}

/**
 * Bluetooth availability + paired devices, refreshed when the screen resumes (e.g. back from
 * Settings or a permission dialog) and whenever Bluetooth is switched on or off.
 */
@Composable
fun rememberBluetoothState(): State<BluetoothState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Bluetooth.state(context)) }

    LifecycleResumeEffect(Unit) {
        state.value = Bluetooth.state(context)
        onPauseOrDispose { }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.value = Bluetooth.state(context)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}
