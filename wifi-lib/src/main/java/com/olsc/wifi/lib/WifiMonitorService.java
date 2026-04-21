package com.olsc.wifi.lib;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 后台服务，负责实时监控 WiFi 状态。
 * 如果检测到 WiFi 断开且当前没有显示配网界面，则自动调起配网 Activity。
 */
public class WifiMonitorService extends Service {
    private static final String TAG = "WifiMonitorService";
    private BroadcastReceiver networkReceiver;
    private static boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "WiFi 监控服务已启动");
        isRunning = true;
        registerNetworkReceiver();
    }

    public static void startService(Context context) {
        if (!isRunning) {
            Intent intent = new Intent(context, WifiMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 如果需要前台服务，可以使用 startForegroundService，
                // 但为了简单，先使用普通服务，通常作为 Launcher 的应用进程级别较高不易被杀。
                context.startService(intent);
            } else {
                context.startService(intent);
            }
        }
    }

    private void registerNetworkReceiver() {
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 仅监听网络变化
                if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                    checkAndTriggerProvisioning(context);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, filter);
        
        // 初始检查
        checkAndTriggerProvisioning(this);
    }

    private void checkAndTriggerProvisioning(Context context) {
        if (!isWifiConnected(context)) {
            // 如果 WiFi 未连接，且 WifiMainActivity 不在前端，则启动它
            if (!WifiMainActivity.isActive) {
                Log.w(TAG, "检测到 WiFi 断开，正在启动自动配网界面...");
                Intent activityIntent = new Intent(context, WifiMainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                context.startActivity(activityIntent);
            }
        }
    }

    private boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            @SuppressWarnings("deprecation")
            android.net.NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return info != null && info.isConnected();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (networkReceiver != null) {
            unregisterReceiver(networkReceiver);
        }
        Log.d(TAG, "WiFi 监控服务已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
