# Sovereign Mesh (Android)

Sovereign Mesh is an independent, 100% open-source Android application designed to interface with Meshtastic mesh networking devices (such as the Heltec V3, T-Beam, and custom ESP32-S3 hardware). 

This project was born out of a fundamental need for privacy-first, off-grid communications. While excellent, centralized app store deployments often introduce unvetted tracking or dependencies. Sovereign Mesh is built from the ground up to eliminate the corporate app ecosystem entirely, providing a clean, transparent pipeline from your touchscreen to the local LoRa radio waves.

## 🛡️ Core Privacy & Security Philosophy

* **Zero Telemetry / Zero Analytics:** No background tracking, no crash-reporting phone-homes, and absolutely no connection to third-party data collection services.
* **Air-Gapped Ready:** Designed to be sideloaded and operated entirely offline without Google Play Services or network-dependent maps.
* **Hardware-Direct Connectivity:** Supports Bluetooth LE pairing as well as a direct **USB-OTG cable connection**, allowing you to physically wire your phone to your Heltec node and completely cut out local RF eavesdropping between your smart device and your transceiver.
* **Fully Auditable Architecture:** The hardware connection layer and data serialization lines are strictly decoupled from the UI, making it incredibly straightforward for security professionals to audit the raw codebase.

## 🏗️ Technical Stack

* **Language:** 100% Native Kotlin
* **UI Framework:** Jetpack Compose (Modern, transparent, declaration-driven UI)
* **Data Serialization:** Official Meshtastic Protocol Buffers (`meshtastic/protobufs`) compiled directly via JavaLite.
* **Hardware Interface:** Android USB Host API & Android Bluetooth LE Subsystem.

## 🤝 Contributing & Open Source

This is a community-driven project intended for anyone who values communication sovereignty. We actively encourage code audits, pull requests, and feedback. 

* **No Closed Binaries:** Every dependency used in this project is verified open-source.
* **F-Droid Targeted:** This repository is structured to comply directly with F-Droid compilation standards for future inclusion in the main F-Droid archive.
