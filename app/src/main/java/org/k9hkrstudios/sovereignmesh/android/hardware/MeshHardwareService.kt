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

package org.k9hkrstudios.sovereignmesh.android.hardware

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.k9hkrstudios.sovereignmesh.android.hardware.usb.UsbConnectionState
import org.k9hkrstudios.sovereignmesh.android.hardware.usb.UsbSerialDriver
import org.k9hkrstudios.sovereignmesh.android.hardware.usb.UsbSerialManager
import org.k9hkrstudios.sovereignmesh.android.hardware.slip.SlipDecoder
import org.k9hkrstudios.sovereignmesh.android.hardware.slip.SlipFramer
import org.k9hkrstudios.sovereignmesh.android.hardware.ble.BleClient
import org.k9hkrstudios.sovereignmesh.android.hardware.ble.BleConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * MeshHardwareService is a Foreground Service that manages low-level connectivity
 * to Meshtastic hardware via USB-OTG and Bluetooth LE.
 *
 * It provides a unified stream of incoming packets and manages the lifecycle of
 * hardware drivers, ensuring the connection remains active even when the UI
 * is in the background.
 */
class MeshHardwareService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var usbSerialManager: UsbSerialManager
    
    // USB properties
    private var activeDriver: UsbSerialDriver? = null
    private var activeDevice: UsbDevice? = null
    private var usbConnectionJob: Job? = null

    private val _usbConnectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    /** The current connection state of the USB-OTG interface. */
    val usbConnectionState: StateFlow<UsbConnectionState> = _usbConnectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>()
    /** Raw byte stream from the active USB interface. */
    val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>()
    /** Decoded Meshtastic packets received from any active hardware interface. */
    val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private val slipDecoder = SlipDecoder()

    // Bluetooth properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleClient: BleClient? = null
    private var bleConnectionJob: Job? = null

    private val _bleConnectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    /** The current connection state of the Bluetooth LE interface. */
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

    private val _bleErrorMessage = MutableStateFlow<String?>(null)
    /** Latest error message from the BLE subsystem. */
    val bleErrorMessage: StateFlow<String?> = _bleErrorMessage.asStateFlow()

    private val _discoveredBleDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    /** List of BLE devices discovered during an active scan. */
    val discoveredBleDevices: StateFlow<List<BluetoothDevice>> = _discoveredBleDevices.asStateFlow()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mesh_hardware_service_channel"
        private const val TAG = "MeshHardwareService"
        
        // Meshtastic serial packet constants
        private const val SERIAL_HEADER_1 = 0x94.toByte()
        private const val SERIAL_HEADER_2 = 0xC3.toByte()
    }

    inner class LocalBinder : Binder() {
        /** Returns the [MeshHardwareService] instance. */
        fun getService(): MeshHardwareService = this@MeshHardwareService
    }

    private val binder = LocalBinder()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbSerialManager.ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }
                        
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(TAG, "USB Permission result: granted=$granted for device=${device?.deviceName}")

                        if (granted && device != null) {
                            connectUsbDevice(device)
                        } else {
                            _usbConnectionState.value = UsbConnectionState.ERROR
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                    }
                    Log.d(TAG, "USB Device Detached: ${device?.deviceName}")
                    if (device != null && device == activeDevice) {
                        disconnectUsbDevice()
                    }
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val currentList = _discoveredBleDevices.value
            if (device !in currentList) {
                _discoveredBleDevices.value = currentList + device
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: List<ScanResult>) {
            val currentList = _discoveredBleDevices.value.toMutableList()
            var changed = false
            for (res in results) {
                val dev = res.device
                if (dev !in currentList) {
                    currentList.add(dev)
                    changed = true
                }
            }
            if (changed) {
                _discoveredBleDevices.value = currentList
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan Failed: $errorCode")
            _bleConnectionState.value = BleConnectionState.ERROR
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating MeshHardwareService")
        usbSerialManager = UsbSerialManager(this)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        createNotificationChannel()
        startServiceForeground()

        val filter = IntentFilter().apply {
            addAction(UsbSerialManager.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroying MeshHardwareService")
        disconnectUsbDevice()
        disconnectBleDevice()
        unregisterReceiver(usbReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Scans and connects to the first compatible USB OTG serial device discovered.
     */
    fun autoConnectUsb() {
        val devices = usbSerialManager.findConnectedDevices()
        if (devices.isEmpty()) {
            Log.d(TAG, "No compatible USB devices found during auto-connect")
            return
        }

        val targetDevice = devices.first()
        if (usbSerialManager.hasPermission(targetDevice)) {
            connectUsbDevice(targetDevice)
        } else {
            usbSerialManager.requestPermission(targetDevice)
        }
    }

    /**
     * Starts the connection sequence for the specified USB peripheral.
     */
    fun connectUsbDevice(device: UsbDevice) {
        if (activeDevice == device && _usbConnectionState.value == UsbConnectionState.CONNECTED) {
            return
        }

        disconnectUsbDevice()
        slipDecoder.reset()
        activeDevice = device

        val driver = usbSerialManager.getDriverForDevice(device)
        if (driver == null) {
            Log.e(TAG, "Failed to instantiate driver for device: ${device.deviceName}")
            _usbConnectionState.value = UsbConnectionState.ERROR
            return
        }

        activeDriver = driver

        usbConnectionJob = serviceScope.launch {
            // Forward driver state to the service's state flow
            launch {
                driver.connectionState.collect { state ->
                    _usbConnectionState.value = state
                    updateNotification()
                }
            }

            // Forward incoming bytes from driver, feed to SLIP decoder, and emit packets
            launch {
                driver.incomingBytes.collect { bytes ->
                    _incomingBytes.emit(bytes)
                    val packets = slipDecoder.feed(bytes)
                    for (packet in packets) {
                        _incomingPackets.emit(packet)
                    }
                }
            }

            // Perform hardware connection
            val success = withContext(Dispatchers.IO) {
                driver.connect()
            }
            if (!success) {
                Log.e(TAG, "Driver connect call failed")
                _usbConnectionState.value = UsbConnectionState.ERROR
            }
        }
    }

    /**
     * Safely closes the active USB OTG driver and releases hardware resources.
     */
    fun disconnectUsbDevice() {
        usbConnectionJob?.cancel()
        usbConnectionJob = null
        activeDriver?.disconnect()
        activeDriver = null
        activeDevice = null
        _usbConnectionState.value = UsbConnectionState.DISCONNECTED
        updateNotification()
    }

    /**
     * Sends raw bytes out over the active USB serial interface.
     */
    fun sendBytes(data: ByteArray): Int {
        val driver = activeDriver ?: return -1
        if (_usbConnectionState.value != UsbConnectionState.CONNECTED) return -1
        return driver.write(data)
    }

    /**
     * Starts scanning for nearby Bluetooth LE Meshtastic devices.
     */
    @SuppressLint("MissingPermission")
    fun startBleScan() {
        _bleErrorMessage.value = null
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth not supported on this device")
            _bleConnectionState.value = BleConnectionState.ERROR
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "Bluetooth LE Scanner not available")
            return
        }

        _discoveredBleDevices.value = emptyList()
        _bleConnectionState.value = BleConnectionState.SCANNING

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, bleScanCallback)
        Log.d(TAG, "BLE scan started successfully")
    }

    /**
     * Stops any active Bluetooth LE device scanning.
     */
    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanner.stopScan(bleScanCallback)
        if (_bleConnectionState.value == BleConnectionState.SCANNING) {
            _bleConnectionState.value = BleConnectionState.DISCONNECTED
        }
        Log.d(TAG, "BLE scan stopped")
    }

    /**
     * Initiates a GATT connection to the specified [BluetoothDevice].
     */
    fun connectBleDevice(device: BluetoothDevice) {
        stopBleScan()
        disconnectBleDevice()
        _bleErrorMessage.value = null

        val client = BleClient(this, device)
        bleClient = client

        bleConnectionJob = serviceScope.launch {
            delay(500)

            launch {
                client.connectionState.collect { state ->
                    _bleConnectionState.value = state
                    updateNotification()
                }
            }

            launch {
                client.errorMessage.collect { error ->
                    _bleErrorMessage.value = error
                }
            }

            launch {
                client.incomingPackets.collect { packet ->
                    _incomingPackets.emit(packet)
                }
            }

            client.connect()
        }
    }

    /**
     * Closes the active Bluetooth LE client session.
     */
    fun disconnectBleDevice() {
        bleConnectionJob?.cancel()
        bleConnectionJob = null
        bleClient?.disconnect()
        bleClient = null
        _bleConnectionState.value = BleConnectionState.DISCONNECTED
        _bleErrorMessage.value = null
        updateNotification()
    }

    /**
     * Encodes a raw packet using SLIP and sends it over the active interface.
     *
     * For USB connections, it wraps the packet with the required Meshtastic
     * serial header before SLIP framing.
     *
     * @param packet The raw payload to transmit.
     * @return The number of bytes successfully sent, or -1 on failure.
     */
    fun sendPacket(packet: ByteArray): Int {
        val client = bleClient
        if (client != null && _bleConnectionState.value == BleConnectionState.CONNECTED) {
            val success = client.write(packet)
            return if (success) packet.size else -1
        }

        val driver = activeDriver
        if (driver != null && _usbConnectionState.value == UsbConnectionState.CONNECTED) {
            val size = packet.size
            val headerAndPayload = ByteArray(4 + size)
            headerAndPayload[0] = SERIAL_HEADER_1
            headerAndPayload[1] = SERIAL_HEADER_2
            headerAndPayload[2] = ((size shr 8) and 0xFF).toByte()
            headerAndPayload[3] = (size and 0xFF).toByte()
            System.arraycopy(packet, 0, headerAndPayload, 4, size)
            
            val framed = SlipFramer.encode(headerAndPayload)
            return sendBytes(framed)
        }

        return -1
    }

    private fun startServiceForeground() {
        val notification = createNotification("Sovereign Mesh Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val usbState = _usbConnectionState.value
        val bleState = _bleConnectionState.value
        val text = when {
            usbState == UsbConnectionState.CONNECTED && bleState == BleConnectionState.CONNECTED -> "Connected (USB & BLE)"
            usbState == UsbConnectionState.CONNECTED -> "Connected to USB Node"
            bleState == BleConnectionState.CONNECTED -> "Connected to BLE Node"
            usbState == UsbConnectionState.CONNECTING || bleState == BleConnectionState.CONNECTING -> "Connecting..."
            usbState == UsbConnectionState.ERROR || bleState == BleConnectionState.ERROR -> "Connection Error"
            else -> "Sovereign Mesh Active (Disconnected)"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sovereign Mesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Using standard system fallback icon
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Mesh Connection Service"
            val descriptionText = "Monitors off-grid hardware connections over USB-OTG and BLE."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
