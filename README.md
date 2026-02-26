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
