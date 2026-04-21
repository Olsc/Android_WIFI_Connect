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

## 🧱 项目架构

本库由四个核心组件协作完成自动化配网流程：

- **`WifiConnectManager`**：开发者的主要入口。为 Android 原生和 Unity 集成提供简洁的 API。
- **`WifiMainActivity`**：库的核心 Activity。负责权限管理、后台 Wi-Fi 扫描、初始化本地热点（LocalOnlyHotspot）以及托管 Web 配置门户的微型 HTTP 服务器（端口 8765）。
- **`WifiMonitorService`**：后台监控服务。实时监控系统的 Wi-Fi 状态，若连接丢失且设备未处于配网状态，则自动弹出配网界面。
- **`WifiAccessibilityService`**：可选但强烈建议开启的无障碍服务。用于自动点击系统连接确认弹窗（例如“允许此应用连接到建议的网络”）。
## 🤖 网络自动化 (无障碍服务)

在 Android 10 及更高版本中，出于安全考虑，系统在应用尝试连接 Wi-Fi 时会弹出对话框请求用户“允许”或“连接”。在 VR、TV 或车载系统中，这些弹窗可能出现在背景中或极难操作。

我们的 `WifiAccessibilityService` 专门为此设计，其工作机制如下：

- **智能识别**：服务专门监控 `com.android.settings`、`com.android.systemui` 和 `android.permissioncontroller` 等系统核心包。
- **关键字触发**：自动识别如 “允许”、“连接”、“确定”、“OK”、“建议的WLAN网络” 等中英文关键字。
- **零干预流程**：当用户在手机端（Web 门户）点击连接后，主设备端会自动处理所有系统级弹窗，实现真正的无感连接。

### 如何启用？
1. 在主设备（VR/TV）上运行应用。
2. 当界面提示“无障碍服务未启用”时，点击“去设置”。
3. 在辅助功能列表中找到 **WIFI Connect** 并将其开启。

> [!IMPORTANT]
> **安全声明**：本库的无障碍服务仅用于识别系统 Wi-Fi 连接相关的弹窗按钮。它不会收集任何用户输入、个人隐私或除辅助配网以外的任何数据。

## ⚙️ 技术细节

- **Web 服务器端口**：8765
- **强制门户跳转 (Captive Portal)**：自动拦截强制门户检测 URL（如 Google 的 `generate_204`），使配网页面在许多手机连接热点后能自动弹出。
- **凭据存储**：通过 Web 页面输入的 Wi-Fi 凭据会缓存至 `SharedPreferences` 中，以便在信号丢失时尝试自动重连。
- **智能连接技术**：采用 `WifiNetworkSuggestion` (Android 10+) 和 `WifiNetworkSpecifier`，根据系统能力提供最稳定的连接方式。

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
