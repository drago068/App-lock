# Private App Lock for Android

An offline Android privacy barrier (Kotlin, minSdk 31, targetSdk 34) for protecting selected apps with a PIN or 3×3 pattern. It uses an Accessibility service to observe foreground package and window-class metadata only; it does not read window content. It has no network permission, accounts, analytics, ads, biometrics, or cloud recovery. This is not a device-security boundary and is not release-ready until the device checklist is completed.

## Build

Requirements: JDK 17 or newer and Android SDK Platform 35. The wrapper uses the owner-provided Gradle 8.9 ZIP at `D:\app lock\gradle-8.9-bin.zip` through a local `file:` URL in `gradle/wrapper/gradle-wrapper.properties`; keep the ZIP at that path or update the URL. Run `.\gradlew.bat assembleDebug` in PowerShell. This command resolves the Maven dependencies below, so arrange those downloads first if you want to keep dependency resolution offline.

Configured build plugins: Android Gradle Plugin 8.7.3 and Kotlin Android 2.0.21. Direct app dependencies: AndroidX Core KTX 1.15.0, Lifecycle Runtime KTX 2.8.7, DataStore Preferences 1.1.1, and Google Tink Android 1.15.0. Transitive artifacts are selected by those artifacts' Maven metadata.

The owner's secret dialer code is supplied through the Gradle property `privateLockSecretCode` in `gradle.properties`; it is injected into the manifest filter and is not an authentication bypass. Use the service's Settings entry as a fallback because some manufacturers do not forward secret-code broadcasts.

Release signing must be configured with the owner's private keystore; no key is included. Debug APKs must not be distributed. The debug launcher alias starts enabled for setup and is hidden after app selection; the app remains visible in Android Settings and Accessibility settings.

## Install and enable

Install the debug APK on an Android 12+ device. Choose a PIN or pattern, save the one-time recovery code, select protected apps, then enable Private App Lock in Accessibility settings. The dashboard provides service status and re-lock options. Device-specific behavior, including secret dialer code delivery and background activity starts, still needs verification on the owner's phone.

## Security posture

The app is intended as a privacy barrier against casual access, not a device security boundary. It cannot prevent uninstall, root/ADB access, service disabling, or Android settings changes. Keystore loss makes encrypted records unreadable; the app explains that the user must clear app data or reinstall. See [THREAT_MODEL.md](THREAT_MODEL.md) and [TEST_CHECKLIST.md](TEST_CHECKLIST.md).
