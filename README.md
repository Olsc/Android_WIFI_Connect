# WIFI Connect Library

A powerful Android library designed to simplify the Wi-Fi connection process, especially for devices with limited input capabilities (e.g., VR headsets, Android TV, IoT devices). It provides an automated workflow to gather Wi-Fi credentials via a local hotspot and a web-based configuration portal.

## 🌟 Key Features

- **Automated Hotspot Setup**: Automatically creates a "Local Only Hotspot" (Android 8.0+) if the device is not connected to Wi-Fi.
- **Web Configuration Portal**: Launches a mini-HTTP server on the hotspot, allowing any device with a browser to set up the host device's Wi-Fi.
- **QR Code Integration**: Generates a QR code for the hotspot so helper devices can scan and connect instantly.
- **Modern Android Support**: Fully compatible with Android 10+ using `WifiNetworkSuggestion` and `WifiNetworkSpecifier`.
- **Unity Ready**: Includes a dedicated bridge for seamless integration into Unity projects.
- **Self-Contained UI**: Comes with a built-in Activity (`WifiMainActivity`) that handles permissions, scanning, and connection status.
## 🚀 How It Works

1. **Check**: The library checks if the device is already connected to Wi-Fi.
2. **Scan**: If not connected, it scans for nearby networks.
3. **Assist**: If no known networks are available, it starts a local hotspot and displays a QR code.
4. **Configure**: User scans the QR code with a phone, connects to the hotspot, and enters the target Wi-Fi credentials through a web page.
5. **Connect**: The host device receives the credentials, shuts down the hotspot, and connects to the target Wi-Fi.

## 🧱 Project Architecture

The library consists of four main components interacting in a coordinated workflow:

- **`WifiConnectManager`**: The primary entry point for developers. Provides a clean API for Android Native and Unity integrations.
- **`WifiMainActivity`**: The heart of the library. It manages permissions, performs background Wi-Fi scanning, initializes the `LocalOnlyHotspot`, and hosts the mini-HTTP server (port 8765) for the web config portal.
- **`WifiMonitorService`**: A background service that monitors the system's Wi-Fi state. If the connection is lost and the device isn't in a setup state, it automatically triggers the provisioning UI.
- **`WifiAccessibilityService`**: An optional but highly recommended service that automates the clicking of system confirmation dialogs (e.g., "Allow this app to connect to a suggested network").

## 🤖 Network Automation (Accessibility Service)

Starting from Android 10, the OS requires user permission via a dialog before an app can suggest or connect to a Wi-Fi network. On devices like **VR Headsets**, **Android TV**, or **Automotive systems**, these dialogs might appear in the background or be difficult to interact with.

Our `WifiAccessibilityService` is designed to solve this:

- **Targeted Monitoring**: Specifically watches for events from system packages like `com.android.settings`, `com.android.systemui`, and `com.android.permissioncontroller`.
- **Intelligent Recognition**: Automatically identifies and clicks buttons matching keywords like "Allow", "Connect", "OK", "Suggested WLAN", etc. (supports multiple languages).
- **Zero-Touch Flow**: Once the user submits credentials on their phone, the host device handles the entire connection process autonomously.

### How to Enable?
1. Open the app on your host device (VR/TV).
2. When prompted that "Accessibility Service is not enabled", click "Go to Settings".
3. Find **WIFI Connect** in the accessibility services list and toggle it **On**.

> [!IMPORTANT]
> **Privacy Statement**: This service is used exclusively for interacting with system Wi-Fi connection dialogs. It does **not** collect user input, personal data, or any information outside the scope of Wi-Fi provisioning.

## ⚙️ Technical Details

- **Web Server Port**: 8765
- **Redirection**: Automatically intercepts captive portal check URLs (like Google's `generate_204`) to make the setup page pop up instantly on many phones.
- **Credential Storage**: Credentials entered via the web portal are cached in `SharedPreferences`, allowing the library to attempt auto-reconnection if the signal is lost.
- **Smart Connection**: Uses `WifiNetworkSuggestion` (Android 10+) and `WifiNetworkSpecifier` to provide the most stable connection method based on system capabilities.

## 🛠 Installation

Add the `:wifi-lib` module to your Android project. Ensure you have the following dependencies (already included in the library's `build.gradle`):

- `com.google.zxing:core` & `journeyapps:zxing-android-embedded` (for QR codes)
- `androidx.appcompat`

## 📖 Usage

### Android Native

```java
import com.olsc.wifi.lib.WifiConnectManager;

WifiConnectManager.startWifiCheck(this, new WifiConnectManager.WifiConnectionListener() {
    @Override
    public void onWifiConnected() {
        // Proceed with your app's logic
        Toast.makeText(MainActivity.this, "Wi-Fi Connected!", Toast.LENGTH_SHORT).show();
    }
});
```

### Unity Integration

The library provides a specialized method to communicate back to Unity via `UnitySendMessage`.

```java
// In your Android plugin wrapper or Activity
WifiConnectManager.startWifiCheckUnity(currentActivity, "MyUnityObjectName", "OnWifiSuccess");
```

## 📋 Requirements

- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Permissions**:
    - `ACCESS_FINE_LOCATION`
    - `ACCESS_COARSE_LOCATION`
    - `NEARBY_WIFI_DEVICES` (Android 13+)
    - `CHANGE_WIFI_STATE`

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
