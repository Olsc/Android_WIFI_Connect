# WIFI Connect 辅助连接库

这是一款功能强大的 Android 库，旨在简化 Wi-Fi 连接流程。特别适用于交互受限的设备（如 VR 头显、安卓电视盒、IoT 设备等）。它通过自动创建本地热点并提供 Web 配置界面，让用户能够通过手机轻松完成主设备的网络配置。

## 🌟 核心功能

- **自动化热点配置**：如果设备未连接 Wi-Fi，自动开启“仅限本地热点 (Local Only Hotspot)”（支持 Android 8.0+）。
- **Web 配置门户**：在热点上启动微型 HTTP 服务器，允许任何带有浏览器的设备（如手机）访问配置页面并输入 Wi-Fi 凭据。
- **二维码快速连接**：自动为热点生成二维码，方便辅助设备扫码即连。
- **现代化 Android 支持**：完美适配 Android 10+，使用 `WifiNetworkSuggestion` 和 `WifiNetworkSpecifier` 进行智能连接。
- **Unity 深度集成**：内置专用桥接方法，可轻松集成到 Unity 项目中。
- **自包含 UI 流程**：内置 `WifiMainActivity` 处理所有权限请求、网络扫描和连接状态展示。

## 🚀 工作原理

1. **检查**：库首先检查当前设备是否已连接 Wi-Fi。
2. **扫描**：若未连接，则扫描周围可用的 Wi-Fi 网络。
3. **辅助**：若无可用已知网络，则启动本地热点并显示二维码。
4. **配置**：用户使用手机扫码连接热点，并在自动弹出的（或手动访问的）网页中选择/输入目标 Wi-Fi 信息。
5. **连接**：主设备接收到凭据后，关闭热点并尝试连接至目标 Wi-Fi。

## 🛠 安装

将 `:wifi-lib` 模块引入您的 Android 项目。确保引入了以下依赖（已在库的 `build.gradle` 中配置）：

- `com.google.zxing:core` 和 `journeyapps:zxing-android-embedded`（用于生成二维码）
- `androidx.appcompat`

## 📖 使用示例

### Android 原生使用

```java
import com.olsc.wifi.lib.WifiConnectManager;

WifiConnectManager.startWifiCheck(this, new WifiConnectManager.WifiConnectionListener() {
    @Override
    public void onWifiConnected() {
        // 连接成功后的逻辑
        Toast.makeText(MainActivity.this, "Wi-Fi 已连接!", Toast.LENGTH_SHORT).show();
    }
});
```

### Unity 项目集成

该库提供了专门的方法，通过 `UnitySendMessage` 将结果返回给 Unity。

```java
// 在您的 Android 插件封装类或 Activity 中调用
WifiConnectManager.startWifiCheckUnity(currentActivity, "Unity对象名", "成功回调方法名");
```

## 📋 系统要求

- **最低 SDK 版本**：24 (Android 7.0)
- **目标 SDK 版本**：34 (Android 14)
- **必要权限**：
    - `ACCESS_FINE_LOCATION` (位置权限)
    - `ACCESS_COARSE_LOCATION`
    - `NEARBY_WIFI_DEVICES` (Android 13+ 附近 Wi-Fi 设备权限)
    - `CHANGE_WIFI_STATE`

## 📝 许可证

本项目采用 MIT 许可证 - 详情请参阅 [LICENSE](LICENSE) 文件。
