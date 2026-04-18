package com.olsc.wifi.lib;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/**
 * 无障碍服务，用于自动点击系统 WiFi 连接过程中的特定弹窗按钮。
 */
public class WifiAccessibilityService extends AccessibilityService {
    private static final String TAG = "WifiAccessibility";
    
    // 静态变量，用于存储当前正在尝试连接的 SSID，以便在选择列表中匹配
    public static String pendingSsid = null;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        
        // 处理窗口状态变化（弹窗出现）和内容变化（列表加载完成）
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            CharSequence packageName = event.getPackageName();
            if (packageName == null) return;

            String pkgStr = packageName.toString();
            // 扩展支持的包名，增加建议连接和权限控制相关的包
            if (pkgStr.equals("android") || 
                pkgStr.equals("com.android.settings") || 
                pkgStr.equals("com.android.systemui") || 
                pkgStr.equals("com.android.permissioncontroller") ||
                pkgStr.equals("com.google.android.gms") ||
                pkgStr.contains("networkstack")) {
                
                checkAndClickDialog();
            }
        }
    }

    private void checkAndClickDialog() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // 1. 尝试匹配特定的请求/错误文本
        String[] keywords = {
            "出了点问题", 
            "已取消选择设备的请求", 
            "是否允许系统连接到建议的WLAN网络",
            "建议的WLAN网络",
            "连接到建议的",
            "允许此应用连接到",
            "连接到您的设备"
        };

        boolean foundKeyword = false;
        for (String keyword : keywords) {
            if (!rootNode.findAccessibilityNodeInfosByText(keyword).isEmpty()) {
                foundKeyword = true;
                Log.d(TAG, "检测到匹配关键字: " + keyword);
                break;
            }
        }

        // 2. 如果有待处理的 SSID，尝试在当前窗口寻找并点击它（处理 WifiNetworkSpecifier 的选择列表）
        if (pendingSsid != null) {
            if (findAndClickNodeByExactText(rootNode, pendingSsid)) {
                Log.d(TAG, "在列表中找到了目标 SSID 并点击: " + pendingSsid);
                // 找到 SSID 后，可能还会出现确认弹窗，所以不立即清空 pendingSsid
            }
        }

        // 3. 如果触发了关键字或 SSID，寻找确认按钮
        if (foundKeyword || pendingSsid != null) {
            // 尝试点击各种可能的“允许”或“连接”按钮
            String[] buttons = {"允许", "确认", "确定", "OK", "好的", "连接", "同意", "始终允许"};
            for (String btnText : buttons) {
                if (findAndClickButton(rootNode, btnText)) {
                    Log.d(TAG, "点击了确认按钮: " + btnText);
                    break;
                }
            }
        }

        rootNode.recycle();
    }

    private boolean findAndClickNodeByExactText(AccessibilityNodeInfo rootNode, String text) {
        List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(text);
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                if (text.equals(node.getText())) {
                    if (performClick(node)) return true;
                }
            }
        }
        return false;
    }

    private boolean findAndClickButton(AccessibilityNodeInfo rootNode, String buttonText) {
        List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(buttonText);
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || "android.widget.Button".equals(node.getClassName())) {
                    if (performClick(node)) return true;
                }
            }
        }
        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            boolean result = performClick(parent);
            parent.recycle();
            return result;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "WifiAccessibilityService interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "WifiAccessibilityService connected");
    }
}
