# PLAN_001: Project Scaffolding & Offline Map Integration

This implementation plan details the scaffolding of the Sovereign Mesh Android project (Phase 1) including Gradle build scripts, dependency configurations, and project initialization steps.

---

## 1. User Review Required

> [!IMPORTANT]
> **Sovereignty & Decoupling Guard:** 
> * We are requesting zero internet connectivity permissions in `AndroidManifest.xml` to guarantee by design that no data can leak over standard cellular/Wi-Fi channels. Maps will load purely offline from the device filesystem or custom cached sources.
> * We are reusing local Gradle assets from `~/AndroidStudioProjects/utc-alarm` to avoid remote wrapper downloads, aligning with our self-hosted, reproducible builds.

---

## 2. Proposed Changes

We will create the core files to support a Kotlin + Jetpack Compose + Protobuf JavaLite + OSMDroid Android application.

### Root Project Configuration

#### [NEW] [settings.gradle.kts](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/settings.gradle.kts)
Configures the project name, repository permissions, plugin management, and includes the `:app` module.
* Repositories: Strictly restricted to Google Maven and Maven Central.

#### [NEW] [build.gradle.kts](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/build.gradle.kts)
Top-level build script registering plugins (Android Application, Kotlin Android, Kotlin Compose, Protobuf).

#### [NEW] [gradle.properties](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/gradle.properties)
Configures AndroidX, Jetpack Compose optimization flags, and sets custom JVM memory limits for compilations.

#### [NEW] [libs.versions.toml](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/gradle/libs.versions.toml)
Defines all plugin and library versions, specifically including:
* Android Gradle Plugin: `8.13.2`
* Kotlin: `2.0.21`
* OSMDroid: `6.1.18` (verifiable open-source offline mapping library)
* Protobuf JavaLite: `3.25.1`
* Protobuf Gradle Plugin: `0.9.4`

### App Module Configuration

#### [NEW] [build.gradle.kts](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/app/build.gradle.kts)
App-level build configurations targeting SDK 34/36, defining namespace `com.sovereignmesh.android`, and enabling the Protobuf JavaLite task generation pipeline.

#### [NEW] [AndroidManifest.xml](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/app/src/main/AndroidManifest.xml)
Minimal manifest configuration requesting zero network permissions, declaring the background service and main activity.

#### [NEW] [MainActivity.kt](file:///home/james/AndroidStudioProjects/sovereign-mesh-android/app/src/main/java/com/sovereignmesh/android/MainActivity.kt)
Main entry point UI displaying basic Compose layouts and checking for required USB / BLE runtime permissions.

---

## 3. Cryptographic & Privacy Impact

* **No Analytics:** No dependency in `libs.versions.toml` maps to any analytical platform.
* **No Network Permission:** Manifest declares no `<uses-permission android:name="android.permission.INTERNET" />`. This physically guarantees that the app cannot leak messages or keys, even if compromised.

---

## 4. Verification Plan

### Compilation Verification
We will run `./gradlew assembleDebug` using Java 21 to verify that:
1. Gradle initializes successfully with the copied wrapper.
2. Dependencies resolve correctly.
3. The build is reproducible.
