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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>()
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
    }

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
                _errorMessage.value = "GATT connection failed (status $status)"
                _connectionState.value = BleConnectionState.ERROR
                closeGatt()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Requesting MTU 512...")
                _connectionState.value = BleConnectionState.CONNECTING
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
                _connectionState.value = BleConnectionState.DISCONNECTED
                closeGatt()
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
                _errorMessage.value = "Service discovery failed (status $status)"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

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

            if (service != null) {
                Log.d(TAG, "Successfully found Meshtastic service UUID: ${service.uuid}, legacyMode=$legacyMode")
                for (char in service.characteristics) {
                    Log.d(TAG, "  Discovered characteristic: uuid=${char.uuid}, properties=${char.properties}")
                }
            } else {
                Log.e(TAG, "Failed to find standard or legacy Meshtastic services. Discovered service UUIDs are:")
                for (srv in gatt.services) {
                    Log.d(TAG, "  Discovered service: uuid=${srv.uuid}")
                }
            }

            if (service == null) {
                Log.e(TAG, "Meshtastic Service not found on device")
                _errorMessage.value = "Meshtastic service not found (ensure it's a Meshtastic device)"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val writeChar = service.getCharacteristic(toDeviceUuid)
            val readChar = service.getCharacteristic(fromDeviceUuid)

            if (writeChar == null || readChar == null) {
                Log.e(TAG, "Required characteristics not found: writeChar=${writeChar != null}, readChar=${readChar != null}")
                _errorMessage.value = "Required service characteristics not found"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            toDeviceChar = writeChar
            fromDeviceChar = readChar
            isLegacy = legacyMode

            val notifyChar = if (legacyMode) {
                readChar // In legacy mode, FromRadio supports notifications directly
            } else {
                service.getCharacteristic(FROM_NUM_UUID_STANDARD) // In standard mode, we notify on FromNum
            }

            if (notifyChar == null) {
                Log.e(TAG, "Notification characteristic not found")
                _errorMessage.value = "Notification characteristic not found"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            // Enable notifications locally
            val localSuccess = gatt.setCharacteristicNotification(notifyChar, true)
            Log.d(TAG, "setCharacteristicNotification locally for ${notifyChar.uuid}: success=$localSuccess")

            // Determine if the notifyChar supports NOTIFY or INDICATE
            val descriptorValue = when {
                (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 -> {
                    Log.d(TAG, "Characteristic ${notifyChar.uuid} supports NOTIFY. Writing ENABLE_NOTIFICATION_VALUE.")
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 -> {
                    Log.d(TAG, "Characteristic ${notifyChar.uuid} supports INDICATE. Writing ENABLE_INDICATION_VALUE.")
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                else -> {
                    Log.w(TAG, "Characteristic ${notifyChar.uuid} properties do not explicitly list NOTIFY or INDICATE. Defaulting to ENABLE_NOTIFICATION_VALUE.")
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            }

            // Enable notifications on the remote device's CCCD descriptor
            var descriptor = notifyChar.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                Log.w(TAG, "CCCD descriptor not found by standard UUID. Scanning available descriptors...")
                for (desc in notifyChar.descriptors) {
                    Log.d(TAG, "Found descriptor: ${desc.uuid}")
                    if (desc.uuid.toString().lowercase().contains("2902")) {
                        descriptor = desc
                        break
                    }
                }
            }

            if (descriptor == null) {
                Log.e(TAG, "CCCD Descriptor not found on notify characteristic")
                _errorMessage.value = "Notification descriptor (CCCD) not found. Try toggling Bluetooth or restarting the device."
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, descriptorValue) == 0
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = descriptorValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (!writeSuccess) {
                Log.e(TAG, "Failed to initiate CCCD write descriptor request")
                _errorMessage.value = "Failed to enable GATT notifications"
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status, uuid=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.d(TAG, "Notifications enabled successfully.")
                _connectionState.value = BleConnectionState.CONNECTED
                startPolling()

                // If standard mode, trigger an initial read on the fromDevice characteristic
                // to pull any packets that might already be waiting in the queue.
                if (!isLegacy) {
                    val readChar = fromDeviceChar
                    if (readChar != null) {
                        Log.d(TAG, "Initial sync check: reading FROM_DEVICE characteristic.")
                        val readSuccess = gatt.readCharacteristic(readChar)
                        Log.d(TAG, "Initial read FROM_DEVICE request success: $readSuccess")
                    }
                }
            } else {
                Log.e(TAG, "Failed to write descriptor to enable notifications: status=$status")
                _errorMessage.value = "Failed to enable notifications (status $status)"
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite: uuid=${characteristic.uuid}, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT characteristic write error: status=$status")
            } else {
                // If it was written to TO_DEVICE_UUID_STANDARD, we can optionally kickstart a read on FROM_DEVICE_UUID_STANDARD
                // to check if the device has queued a response.
                if (!isLegacy && characteristic.uuid == TO_DEVICE_UUID_STANDARD) {
                    val readChar = fromDeviceChar
                    if (readChar != null) {
                        Log.d(TAG, "Write to ToRadio completed successfully. Initiating check-read on FROM_DEVICE...")
                        val readSuccess = gatt.readCharacteristic(readChar)
                        Log.d(TAG, "Post-write read FROM_DEVICE request success: $readSuccess")
                    }
                }
            }
        }


        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncomingChanged(gatt, characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingChanged(gatt, characteristic.uuid, value)
        }

        private fun handleIncomingChanged(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
            Log.d(TAG, "handleIncomingChanged: uuid=$uuid, size=${value.size}")
            if (uuid == FROM_DEVICE_UUID_LEGACY) {
                val cloned = value.clone()
                driverScope.launch {
                    _incomingPackets.emit(cloned)
                }
            } else if (uuid == FROM_NUM_UUID_STANDARD) {
                val readChar = fromDeviceChar
                if (readChar != null) {
                    Log.d(TAG, "FROM_NUM notified standard device has data. Requesting read characteristic...")
                    val success = gatt.readCharacteristic(readChar)
                    Log.d(TAG, "readCharacteristic request initiated: success=$success")
                } else {
                    Log.e(TAG, "FROM_NUM notified but readChar (fromDeviceChar) is null!")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead (deprecated) status=$status, uuid=${characteristic.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleIncomingRead(characteristic.uuid, value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead status=$status, uuid=${characteristic.uuid}, valueSize=${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleIncomingRead(characteristic.uuid, value)
            }
        }

        private fun handleIncomingRead(uuid: UUID, value: ByteArray) {
            Log.d(TAG, "handleIncomingRead: uuid=$uuid, size=${value.size}")
            if (uuid == FROM_DEVICE_UUID_STANDARD) {
                if (value.isNotEmpty()) {
                    val cloned = value.clone()
                    driverScope.launch {
                        _incomingPackets.emit(cloned)
                    }
                    val gatt = bluetoothGatt
                    val readChar = fromDeviceChar
                    if (gatt != null && readChar != null) {
                        Log.d(TAG, "Reading next packet chunk from FROM_DEVICE...")
                        gatt.readCharacteristic(readChar)
                    }
                } else {
                    Log.d(TAG, "Empty packet returned from standard FROM_DEVICE, queue is empty.")
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
        _errorMessage.value = null

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
        synchronized(this) {
            stopPolling()
            val gatt = bluetoothGatt
            if (gatt != null) {
                try {
                    gatt.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling gatt.disconnect()", e)
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

    fun write(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = toDeviceChar ?: return false
        if (_connectionState.value != BleConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot write packet: BLE not connected (state=${_connectionState.value})")
            return false
        }

        // Determine if write with response (WRITE_TYPE_DEFAULT) is supported by the characteristic.
        // Meshtastic's ToRadio characteristic recommends write with response.
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        Log.d(TAG, "write: packetSize=${packet.size}, writeType=${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "WRITE_TYPE_DEFAULT" else "WRITE_TYPE_NO_RESPONSE"}")

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(char, packet, writeType)
            result == 0 // 0 is BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = packet
            char.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
        Log.d(TAG, "write completed: success=$success")
        return success
    }

    private fun startPolling() {
        if (isLegacy) return // Legacy mode notifies directly on FromRadio, no polling needed or supported
        stopPolling()
        pollJob = driverScope.launch {
            while (isActive) {
                delay(20000) // Poll every 20 seconds
                val gatt = bluetoothGatt
                val readChar = fromDeviceChar
                if (gatt != null && readChar != null && _connectionState.value == BleConnectionState.CONNECTED) {
//                    Log.d(TAG, "Polling FROM_DEVICE characteristic...")
                    val success = gatt.readCharacteristic(readChar)
//                    Log.d(TAG, "Poll read FROM_DEVICE request success: $success")
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
