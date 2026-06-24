# Skill: Meshtastic Protocol Handler (Antigravity 2.0)

This skill package defines the exact procedures, implementation patterns, and references for managing Meshtastic node protocol interaction, protobuf serialization, and low-level hardware interface integration on Android.

---

## 1. Protobuf Pipeline (`protobuf-javalite`)

To serialize and deserialize messages communicating with Meshtastic hardware, the app compiles the official `.proto` files using Google's lightweight Protobuf library optimized for mobile.

### 1.1 Dependency Setup
Configure the Kotlin/Gradle build to use the Protobuf Gradle Plugin and compile protobufs into Java/Kotlin Lite classes:

```kts
plugins {
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
```

### 1.2 Compiling & Importing Schemas
* Raw `.proto` files are sourced directly from the official [meshtastic/protobufs](https://github.com/meshtastic/protobufs) repository.
* Place schemas under `app/src/main/proto/` (e.g., `mesh.proto`, `portnums.proto`, `telemetry.proto`, etc.).
* Run `./gradlew generateDebugProto` to compile the schemas.
* In Kotlin code, import the generated JavaLite types (e.g., `com.geeksville.mesh.MeshProtos.MeshPacket`).

### 1.3 Parsing & Building Messages
All deserialized protobuf data must be treated as immutable.
```kotlin
// Example: Parsing a raw byte payload from hardware
fun parseIncomingMeshPacket(data: ByteArray): MeshProtos.MeshPacket {
    return MeshProtos.MeshPacket.parseFrom(data)
}

// Example: Constructing an outbound text payload
fun createOutboundTextPacket(text: String, channel: Int): MeshProtos.MeshPacket {
    val payload = MeshProtos.Data.newBuilder()
        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
        .setPayload(ByteString.copyFromUtf8(text))
        .build()

    return MeshProtos.MeshPacket.newBuilder()
        .setDecrypted(payload)
        .setChannel(channel)
        .build()
}
```

---

## 2. Low-Level Android Hardware Interfaces

Hardware layer tasks should be isolated into background Android `Service` modules or distinct driver implementations, decoupled from the UI layer.

### 2.1 USB-OTG Serial Communication (`android.hardware.usb`)
Meshtastic hardware typically communicates using USB-to-UART bridge chips (e.g., CP210X, CH340, FTDI). The Android USB Host API is used to manage these raw byte streams without requiring root access.

* **Manager Acquisition:**
  ```kotlin
  val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
  ```
* **Device Discovery & Permission Flow:**
  - Retrieve the list of connected devices using `usbManager.deviceList`.
  - Check permission using `usbManager.hasPermission(device)`.
  - If permission is absent, request it asynchronously using `PendingIntent.getBroadcast` matching a custom action filter `ACTION_USB_PERMISSION`.
* **Interface Claiming:**
  ```kotlin
  val connection = usbManager.openDevice(device) ?: throw IOException("Failed to open USB device")
  val usbInterface = device.getInterface(0)
  connection.claimInterface(usbInterface, true)
  ```
* **Serial Thread / I/O Loop:**
  - Locate `UsbEndpoint` structures (typically an bulk-in endpoint and a bulk-out endpoint).
  - Spawn a dedicated background background thread for polling input via `connection.bulkTransfer(...)`.
  - Pass the resulting byte arrays directly to a decrypter/parser (e.g., SLIP encoding parser for Meshtastic framed packets).

### 2.2 Bluetooth Low Energy (BLE) Handshake (`android.bluetooth.le`)
For wireless, off-grid client operation, communication occurs over BLE.

* **Scanner Setup:**
  ```kotlin
  val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  val bluetoothAdapter = bluetoothManager.adapter
  val bleScanner = bluetoothAdapter.bluetoothLeScanner
  ```
* **Discovery:**
  - Define custom scan filters filtering for the Meshtastic Service UUID: `6ba1b088-7266-419b-a08b-9e4f02f5c760` (or similar service identifiers).
  - Start scanning with `bleScanner.startScan(filters, settings, scanCallback)`.
* **GATT Connection & Services:**
  - Connect via `device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)`.
  - Implement the `BluetoothGattCallback` to handle service discovery and notifications.
  - Subscribe to the Tx characteristic notification to receive packet streams from the node.
  - Write bytes to the Rx characteristic to transmit packet streams to the node.
