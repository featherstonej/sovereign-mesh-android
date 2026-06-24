package com.sovereignmesh.android.hardware.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission") // Checked at MainActivity level
class BleClient(
    private val context: Context,
    private val device: BluetoothDevice
) {

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>()
    val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var toDeviceChar: BluetoothGattCharacteristic? = null
    private var fromDeviceChar: BluetoothGattCharacteristic? = null

    private val driverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "BleClient"
        
        // Meshtastic BLE UUIDs
        private val SERVICE_UUID = UUID.fromString("cb0b9050-c897-11e7-b861-9a745287fe97")
        private val TO_DEVICE_UUID = UUID.fromString("f75d693e-c8ac-11e7-ab66-3e7b1a13b632") // Write
        private val FROM_DEVICE_UUID = UUID.fromString("2c55e69e-c8ac-11e7-919d-3f7992f599b4") // Notify
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection failure with status: $status")
                _connectionState.value = BleConnectionState.ERROR
                disconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Requesting MTU 512...")
                _connectionState.value = BleConnectionState.CONNECTING
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
                _connectionState.value = BleConnectionState.DISCONNECTED
                disconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU successfully set to $mtu. Discovering services...")
                gatt.discoverServices()
            } else {
                Log.e(TAG, "Failed to set MTU, proceeding anyway. Discovering services...")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed")
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(TAG, "Meshtastic Service not found on device")
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val writeChar = service.getCharacteristic(TO_DEVICE_UUID)
            val notifyChar = service.getCharacteristic(FROM_DEVICE_UUID)

            if (writeChar == null || notifyChar == null) {
                Log.e(TAG, "Required characteristics not found")
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            toDeviceChar = writeChar
            fromDeviceChar = notifyChar

            // Enable notifications locally
            gatt.setCharacteristicNotification(notifyChar, true)

            // Enable notifications on the remote device's CCCD descriptor
            val descriptor = notifyChar.getDescriptor(CCCD_UUID) ?: run {
                Log.e(TAG, "CCCD Descriptor not found on notify characteristic")
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (!writeSuccess) {
                Log.e(TAG, "Failed to initiate CCCD write descriptor request")
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status, uuid=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.d(TAG, "Notifications enabled successfully.")
                _connectionState.value = BleConnectionState.CONNECTED
            } else {
                Log.e(TAG, "Failed to write descriptor to enable notifications: status=$status")
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncomingValue(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingValue(characteristic.uuid, value)
        }

        private fun handleIncomingValue(uuid: UUID, value: ByteArray) {
            if (uuid == FROM_DEVICE_UUID) {
                val cloned = value.clone()
                driverScope.launch {
                    _incomingPackets.emit(cloned)
                }
            }
        }
    }

    /**
     * Initiates connection to the BLE peripheral.
     */
    fun connect(): Boolean {
        if (_connectionState.value == BleConnectionState.CONNECTED) return true
        _connectionState.value = BleConnectionState.CONNECTING

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        return bluetoothGatt != null
    }

    /**
     * Disconnects the active GATT session and frees resources.
     */
    fun disconnect() {
        bluetoothGatt?.apply {
            disconnect()
            close()
        }
        bluetoothGatt = null
        toDeviceChar = null
        fromDeviceChar = null
        if (_connectionState.value != BleConnectionState.ERROR) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    /**
     * Sends a raw packet out over BLE to the ToDevice characteristic.
     */
    fun write(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = toDeviceChar ?: return false
        if (_connectionState.value != BleConnectionState.CONNECTED) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            result == 0 // 0 is BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = packet
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }
}
