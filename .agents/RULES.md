# Sovereign Mesh Agent Rules (Antigravity 2.0)

This ruleset governs the operational boundaries, constraints, and mandatory workflows for all AI sub-agents executing tasks in the Sovereign Mesh Android project.

---

## 1. Core Principles

### 1.1 Zero-Telemetry Constraint
* **No Analytics:** Do not introduce, import, or configure any analytics, telemetry, crash reporting, tracking, or user behavior analysis libraries (e.g., Firebase Analytics, Google Play Services SDKs, Mixpanel, Segment, Sentry, or App Center).
* **Strict Open-Source Dependencies:** Every dependency added to `build.gradle.kts` must be strictly verifiable open-source. No proprietary SDKs, closed binary blobs, or cloud-coupled tracking modules.
* **F-Droid Compliance:** The application must remain 100% offline-first and buildable without proprietary maven repositories or Google APIs.

### 1.2 Architecture Decoupling
* **Layer Separation:** The codebase must maintain strict separation between the Hardware layer (USB Host API, Bluetooth LE Services) and the Jetpack Compose User Interface.
* **No Direct Leaks:** UI components must never instantiate hardware connections directly or have references to low-level hardware structures. Communication must flow through clean interfaces and domain repositories, enabling independent security auditing of the cryptography and data pathways.

---

## 2. Mandatory Review Guardrail (CRITICAL)

Before any code is altered, a new feature is implemented, or any architectural shift is made, the active agent **MUST** follow this protocol:

1. **Write a Plan:** Create a standalone implementation plan file in the `.agents/reviews/` directory.
   - Filename template: `.agents/reviews/PLAN_<three_digit_sequence>_<descriptive_snake_case>.md` (e.g., `PLAN_001_initial_gradle_setup.md`).
   - The plan must detail:
     - The exact target files to be created or modified.
     - The logic, serialization, or interface changes proposed.
     - The dependency changes (if any) and their open-source licenses.
     - Cryptographic, hardware, or privacy implications of the changes.
2. **Obtain Approval:** The agent must halt execution and present this plan to the user. No application source code files (`.kt`, `.xml`, etc.) may be created, edited, or deleted until the user has explicitly approved the plan.

---

## 3. Technology Stack & Coding Standards
* **Language:** Idiomatic Kotlin with Coroutines and Flow for asynchronous operations.
* **UI:** Jetpack Compose using modern, high-contrast, premium, responsive layouts.
* **Protobuf:** `com.google.protobuf:protobuf-javalite` (JavaLite version for Android efficiency).
* **Hardware API:**
  - Android USB Host API (`android.hardware.usb.UsbManager`) for direct serial communication.
  - Android Bluetooth LE (`android.bluetooth.le`) APIs.
* **Offline Maps:** OSMDroid for completely offline peer mapping capability.
