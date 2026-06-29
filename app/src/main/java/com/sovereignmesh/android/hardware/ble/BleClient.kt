/*
 * Sovereign Mesh (Android)
 * Copyright (C) 2025 Sovereign Mesh Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sovereignmesh.android.hardware.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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

/**
 * BleClient handles the Bluetooth Low Energy connection to a Meshtastic device.
 *
 * It manages the lifecycle of a GATT connection, including service discovery,
 * MTU negotiation, and characteristic notifications. It supports both the
 * Standard (FromNum-notified) and Legacy (FromRadio-notified) Meshtastic protocols.
 */
@SuppressLint("MissingPermission") // Bluetooth permissions are verified at the Activity level.
class BleClient(
    private val context: Context,
    private val device: BluetoothDevice
) {

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    /** The current connection state of the BLE client. */
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** The latest error message, if any. */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>()
    /** A flow of raw packets received from the device. */
    val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var toDeviceChar: BluetoothGattCharacteristic? = null
    private var fromDeviceChar: BluetoothGattCharacteristic? = null
    private var isLegacy = false

    private val driverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    companion object {
        private const val TAG = "BleClient"
        
        // Meshtastic BLE UUIDs (Standard)
        private val SERVICE_UUID_STANDARD = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        private val TO_DEVICE_UUID_STANDARD = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        private val FROM_DEVICE_UUID_STANDARD = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        private val FROM_NUM_UUID_STANDARD = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        // Meshtastic BLE UUIDs (Legacy / Codebase compatibility)
        private val SERVICE_UUID_LEGACY = UUID.fromString("cb0b9050-c897-11e7-b861-9a745287fe97")
        private val TO_DEVICE_UUID_LEGACY = UUID.fromString("f75d693e-c8ac-11e7-ab66-3e7b1a13b632")
        private val FROM_DEVICE_UUID_LEGACY = UUID.fromString("2c55e69e-c8ac-11e7-919d-3f7992f599b4")

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 512
        private const val POLLING_INTERVAL_MS = 20000L
    }

    /**
     * Closes the GATT client and resets internal state.
     */
    private fun closeGatt() {
        synchronized(this) {
            stopPolling()
            bluetoothGatt?.close()
            bluetoothGatt = null
            toDeviceChar = null
            fromDeviceChar = null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection failure with status: $status")
                _errorMessage.value = "GATT error: ${getGattStatusName(status)} ($status)"
                _connectionState.value = BleConnectionState.ERROR
                closeGatt()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server. Requesting MTU $DEFAULT_MTU...")
                    _connectionState.value = BleConnectionState.CONNECTING
                    gatt.requestMtu(DEFAULT_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server.")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    closeGatt()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU successfully set to $mtu. Discovering services...")
            } else {
                Log.e(TAG, "Failed to set MTU (status $status), proceeding with default.")
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _errorMessage.value = "Service discovery failed (status $status)"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            setupMeshtasticService(gatt)
        }

        private fun setupMeshtasticService(gatt: BluetoothGatt) {
            var service = gatt.getService(SERVICE_UUID_STANDARD)
            var toDeviceUuid = TO_DEVICE_UUID_STANDARD
            var fromDeviceUuid = FROM_DEVICE_UUID_STANDARD
            var legacyMode = false

            if (service == null) {
                service = gatt.getService(SERVICE_UUID_LEGACY)
                toDeviceUuid = TO_DEVICE_UUID_LEGACY
                fromDeviceUuid = FROM_DEVICE_UUID_LEGACY
                legacyMode = true
            }

            if (service == null) {
                Log.e(TAG, "Meshtastic service not found on device.")
                _errorMessage.value = "Meshtastic service not found"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            Log.d(TAG, "Found Meshtastic service: ${service.uuid}, legacyMode=$legacyMode")
            
            val writeChar = service.getCharacteristic(toDeviceUuid)
            val readChar = service.getCharacteristic(fromDeviceUuid)

            if (writeChar == null || readChar == null) {
                Log.e(TAG, "Required characteristics not found: write=${writeChar != null}, read=${readChar != null}")
                _errorMessage.value = "Required characteristics missing"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            toDeviceChar = writeChar
            fromDeviceChar = readChar
            isLegacy = legacyMode

            enableNotifications(gatt, service, legacyMode)
        }

        private fun enableNotifications(gatt: BluetoothGatt, service: BluetoothGattService?, legacyMode: Boolean) {
            val notifyChar = if (legacyMode) {
                fromDeviceChar
            } else {
                service?.getCharacteristic(FROM_NUM_UUID_STANDARD)
            }

            if (notifyChar == null) {
                Log.e(TAG, "Notification characteristic not found")
                _errorMessage.value = "Notification setup failed"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            // Enable notifications locally
            gatt.setCharacteristicNotification(notifyChar, true)

            // Enable notifications on the remote device's CCCD
            val descriptor = notifyChar.getDescriptor(CCCD_UUID) ?: run {
                Log.e(TAG, "CCCD Descriptor not found on ${notifyChar.uuid}")
                _errorMessage.value = "CCCD descriptor missing"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val value = when {
                (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 -> 
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 -> 
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (!success) {
                Log.e(TAG, "Failed to initiate CCCD write")
                _errorMessage.value = "Failed to enable notifications"
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status, uuid=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.d(TAG, "Notifications enabled successfully. Connection complete.")
                _connectionState.value = BleConnectionState.CONNECTED
                startPolling()

                // Initial sync check for standard mode
                if (!isLegacy) {
                    fromDeviceChar?.let { gatt.readCharacteristic(it) }
                }
            } else {
                Log.e(TAG, "Descriptor write failed: status=$status")
                _errorMessage.value = "Failed to enable notifications"
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write error: status=$status for ${characteristic.uuid}")
            } else if (!isLegacy && characteristic.uuid == TO_DEVICE_UUID_STANDARD) {
                // Post-write read check for standard mode
                fromDeviceChar?.let { gatt.readCharacteristic(it) }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handleIncomingChanged(gatt, characteristic.uuid, it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingChanged(gatt, characteristic.uuid, value)
        }

        private fun handleIncomingChanged(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
            if (uuid == FROM_DEVICE_UUID_LEGACY) {
                emitPacket(value)
            } else if (uuid == FROM_NUM_UUID_STANDARD) {
                // Device notified us that data is available in the queue
                fromDeviceChar?.let { gatt.readCharacteristic(it) }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleIncomingRead(gatt, characteristic.uuid, it) }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleIncomingRead(gatt, characteristic.uuid, value)
            }
        }

        private fun handleIncomingRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
            if (uuid == FROM_DEVICE_UUID_STANDARD) {
                if (value.isNotEmpty()) {
                    emitPacket(value)
                    // Continue reading if there might be more chunks in the queue
                    fromDeviceChar?.let { gatt.readCharacteristic(it) }
                }
            }
        }

        private fun emitPacket(value: ByteArray) {
            val cloned = value.clone()
            driverScope.launch {
                _incomingPackets.emit(cloned)
            }
        }
    }

    /**
     * Initiates a connection to the BLE peripheral.
     * @return true if the connection request was successfully initiated.
     */
    fun connect(): Boolean {
        if (_connectionState.value == BleConnectionState.CONNECTED) return true
        
        _connectionState.value = BleConnectionState.CONNECTING
        _errorMessage.value = null

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        return bluetoothGatt != null
    }

    /**
     * Disconnects and releases the GATT session.
     */
    fun disconnect() {
        synchronized(this) {
            stopPolling()
            val gatt = bluetoothGatt
            if (gatt != null) {
                try {
                    gatt.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during disconnect", e)
                    closeGatt()
                }
            } else {
                closeGatt()
            }
            if (_connectionState.value != BleConnectionState.ERROR) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Writes a packet to the device.
     * @param packet The raw byte array to send.
     * @return true if the write request was successfully initiated.
     */
    fun write(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = toDeviceChar ?: return false
        
        if (_connectionState.value != BleConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot write: Not connected")
            return false
        }

        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        return synchronized(this) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, packet, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = packet
                char.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    private fun startPolling() {
        if (isLegacy) return
        stopPolling()
        pollJob = driverScope.launch {
            while (isActive) {
                delay(POLLING_INTERVAL_MS)
                val gatt = bluetoothGatt
                val char = fromDeviceChar
                if (gatt != null && char != null && _connectionState.value == BleConnectionState.CONNECTED) {
                    gatt.readCharacteristic(char)
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun getGattStatusName(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
        BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
        BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
        BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED"
        BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
        133 -> "GATT_ERROR (133)" // Common status for connection timeout/refusal
        else -> "UNKNOWN_STATUS"
    }
}
