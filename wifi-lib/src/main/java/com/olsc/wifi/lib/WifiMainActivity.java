package com.olsc.wifi.lib;

import android.Manifest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;
import android.content.SharedPreferences;

public class WifiMainActivity extends Activity {

    private TextView tvStatus, tvIpLink, tvHotspotInfo;
    private ImageView ivQrCode, ivWebQrCode;
    private View cardConnection, cardHome, cardAccessibility, cardWebStep;
    private Button btnAction, btnSetHome, btnSetAccessibility;

    public static boolean isActive = false;

    private WifiManager wifiManager;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ServerSocket serverSocket;
    private boolean isServerRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isStartingHotspot = false;
    private Runnable ipPollRunnable;
    private static final int IP_POLL_INTERVAL = 1000;
    private static final int PERMISSIONS_REQUEST_CODE = 1001;
    private static final int WIFI_PANEL_REQUEST_CODE = 1002;
    private List<ScanResult> cachedScanResults = new ArrayList<>();
    private boolean isScanning = false;
    private boolean isCheckingConnection = false;

    private int countdownSeconds = 10;
    private Runnable countdownRunnable;
    private Runnable hotspotMonitorRunnable;
    private Runnable connectionTimeoutRunnable; // 10秒连接超时检测
    private static final int HOTSPOT_CHECK_INTERVAL = 5000;
    private String lastReportedIp = "127.0.0.1";
    private static final String PREFS_NAME = "WifiConfigPrefs";
    private static final String KEY_WIFI_LIST = "saved_wifi_list";

    // 扫描结果接收器
    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            isScanning = false;
            updateScanResults();
            
            if (countdownSeconds <= 0 && !isWifiConnected() && hotspotReservation == null && !isStartingHotspot) {
                mainHandler.post(WifiMainActivity.this::startHotspotAndServer);
            }
        }
    };

    // 网络状态变化接收器
    private final BroadcastReceiver networkChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isWifiConnected() && isCheckingConnection) {
                onWifiSuccess();
            }
        }
    };

    private void updateScanResults() {
        @SuppressLint("MissingPermission")
        List<ScanResult> results = wifiManager.getScanResults();
        if (results != null && !results.isEmpty()) {
            cachedScanResults = new ArrayList<>(results);
            final String statusText = getString(R.string.wifi_found_networks, results.size());
            mainHandler.post(() -> {
                tvStatus.setText(statusText);
                Log.d("WIFI_SCAN", "扫描完成，找到 " + results.size() + " 个网络。并已更新缓存。");
            });
        } else {
            Log.d("WIFI_SCAN", "本次扫描结果为空，保持上一版本扫描到的 " + cachedScanResults.size() + " 个网络。");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(wifiScanReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(networkChangeReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(wifiStateReceiver);
        } catch (Exception ignored) {}
        
        if (hotspotReservation != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hotspotReservation.close();
            }
            hotspotReservation = null;
        }
        stopHttpServer();
        stopCountdown();
        stopHotspotMonitor();
        stopConnectionTimeout();
    }

    // Wi-Fi 状态变化接收器
    private final BroadcastReceiver wifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                Log.d("WIFI_LIB", "Wi-Fi 已开启，继续流程");
                startProcess();
            }
        }
    };

    private void stopConnectionTimeout() {
        if (connectionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectionTimeoutRunnable);
            connectionTimeoutRunnable = null;
        }
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            mainHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void stopHotspotMonitor() {
        if (hotspotMonitorRunnable != null) {
            mainHandler.removeCallbacks(hotspotMonitorRunnable);
            hotspotMonitorRunnable = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_main);

        tvStatus = findViewById(R.id.tv_status);
        tvIpLink = findViewById(R.id.tv_ip_link);
        tvHotspotInfo = findViewById(R.id.tv_hotspot_info);
        ivQrCode = findViewById(R.id.iv_qrcode);
        ivWebQrCode = findViewById(R.id.iv_web_qrcode);
        cardConnection = findViewById(R.id.card_connection);
        cardWebStep = findViewById(R.id.card_web_step);
        btnAction = findViewById(R.id.btn_action);
        cardHome = findViewById(R.id.card_home);
        btnSetHome = findViewById(R.id.btn_set_home);
        btnSetHome.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            startActivity(intent);
        });
        cardAccessibility = findViewById(R.id.card_accessibility);
        btnSetAccessibility = findViewById(R.id.btn_set_accessibility);
        btnSetAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        IntentFilter scanFilter = new IntentFilter();
        scanFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, scanFilter);

        IntentFilter networkFilter = new IntentFilter();
        networkFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkChangeReceiver, networkFilter);

        IntentFilter stateFilter = new IntentFilter();
        stateFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiStateReceiver, stateFilter);

        btnAction.setOnClickListener(v -> checkPermissionsAndStart());

        checkAccessibilityStatus();
        
        // 启动后台 WiFi 监控服务
        WifiMonitorService.startService(this);
        
        mainHandler.postDelayed(this::checkPermissionsAndStart, 800);
    }

    @Override
    protected void onStart() {
        super.onStart();
        isActive = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        isActive = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkLauncherStatus();
        checkAccessibilityStatus();
    }

    private void checkAccessibilityStatus() {
        if (!isAccessibilityServiceEnabled()) {
            cardAccessibility.setVisibility(View.VISIBLE);
        } else {
            cardAccessibility.setVisibility(View.GONE);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + WifiAccessibilityService.class.getName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e("WIFI_LIB", "获取无障碍状态失败: " + e.getMessage());
        }
        android.text.TextUtils.SimpleStringSplitter mStringColonSplitter = new android.text.TextUtils.SimpleStringSplitter(':');

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue);
                while (mStringColonSplitter.hasNext()) {
                    String accessibilityService = mStringColonSplitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void checkLauncherStatus() {
        if (!isDefaultLauncher()) {
            cardHome.setVisibility(View.VISIBLE);
        } else {
            cardHome.setVisibility(View.GONE);
        }
    }

    /**
     * 检测当前应用是否为默认桌面 (Launcher)
     */
    private boolean isDefaultLauncher() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        final android.content.pm.ResolveInfo res = getPackageManager().resolveActivity(intent, 0);
        if (res != null && res.activityInfo != null) {
            return getPackageName().equals(res.activityInfo.packageName);
        }
        return false;
    }

    /**
     * 检查并请求必要的权限
     */
    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissions = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                permissions.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            if (!permissions.isEmpty()) {
                requestPermissions(permissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
                return;
            }
        }
        startProcess();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startProcess();
            } else {
                Toast.makeText(this, "权限被拒绝，无法继续。", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void startProcess() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, R.string.wifi_location_required, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        if (!wifiManager.isWifiEnabled()) {
            tvStatus.setText(R.string.wifi_enabling);
            boolean success = false;
            try {
                success = wifiManager.setWifiEnabled(true);
            } catch (Exception e) {
                Log.e("WIFI_LIB", "开启 Wi-Fi 出错", e);
            }
            
            if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                startActivityForResult(panelIntent, WIFI_PANEL_REQUEST_CODE);
            }
            return;
        }

        if (isWifiConnected()) {
            tvStatus.setText(R.string.wifi_already_connected);
            onWifiSuccess();
        } else {
            startCountdown();
        }
    }

    private void startCountdown() {
        stopCountdown();
        countdownSeconds = 8;
        isCheckingConnection = true;
        
        // 尝试自动连接已保存网络
        tryAutoConnectToSavedWifi();
        startWifiScan();
        
        countdownRunnable = new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                if (isWifiConnected()) {
                    onWifiSuccess();
                    return;
                }
                
                if (countdownSeconds > 0) {
                    tvStatus.setText(getString(R.string.wifi_waiting_auto_connect, countdownSeconds));
                    countdownSeconds--;
                    mainHandler.postDelayed(this, 1000);
                    
                    if (countdownSeconds % 4 == 0) {
                        startWifiScan();
                    }
                } else {
                    if (!isWifiConnected() && hotspotReservation == null && !isStartingHotspot) {
                        Log.w("WIFI_SCAN", "等待超时，启动热点以辅助配网");
                        startHotspotAndServer();
                    }
                }
            }
        };
        mainHandler.post(countdownRunnable);
    }

    private void saveWifiCredentials(String ssid, String password) {
        if (ssid == null || ssid.trim().isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String data = prefs.getString(KEY_WIFI_LIST, "{}");
        try {
            JSONObject json = new JSONObject(data);
            json.put(ssid, password);
            prefs.edit().putString(KEY_WIFI_LIST, json.toString()).apply();
            Log.d("WIFI_LIB", "已保存凭据: " + ssid);
        } catch (Exception e) {
            Log.e("WIFI_LIB", "保存凭据出错", e);
        }
    }

    private void tryAutoConnectToSavedWifi() {
        if (!wifiManager.isWifiEnabled()) return;
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String data = prefs.getString(KEY_WIFI_LIST, "{}");
        try {
            JSONObject json = new JSONObject(data);
            if (json.length() == 0) return;
            
            Log.d("WIFI_LIB", "发现已保存网络，尝试建议连接...");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
                java.util.Iterator<String> keys = json.keys();
                while(keys.hasNext()) {
                    String ssid = keys.next();
                    String pass = json.getString(ssid);
                    suggestions.add(new WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .setWpa2Passphrase(pass)
                        .build());
                }
                wifiManager.addNetworkSuggestions(suggestions);
            }
        } catch (Exception e) {
            Log.e("WIFI_LIB", "自动建议连接出错", e);
        }
    }

    private void startHotspotMonitor() {
        stopHotspotMonitor();
        hotspotMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (hotspotReservation == null && !isWifiConnected() && !isStartingHotspot && countdownSeconds <= 0) {
                    Log.d("WIFI_MONITOR", "监控: 热点丢失，尝试重新启动");
                    startHotspotAndServer();
                }
                mainHandler.postDelayed(this, HOTSPOT_CHECK_INTERVAL);
            }
        };
        mainHandler.postDelayed(hotspotMonitorRunnable, HOTSPOT_CHECK_INTERVAL);
    }

    @SuppressLint("SetTextI18n")
    private void onWifiSuccess() {
        stopConnectionTimeout(); // 成功连接，取消超时检测
        tvStatus.setText(R.string.wifi_status_connected);
        WifiAccessibilityService.pendingSsid = null;

        mainHandler.postDelayed(() -> {
            Log.d("WIFI_LIB", "配网成功，清理资源...");
            
            if (hotspotReservation != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    hotspotReservation.close();
                }
                hotspotReservation = null;
            }
            stopHttpServer();
            stopPolling();
            
            WifiConnectManager.notifySuccess();
            
            try {
                Class<?> unityClass = Class.forName("com.unity3d.player.UnityPlayerActivity");
                Log.d("WIFI_LIB", "准备返回 Unity 界面");
                Intent intent = new Intent(this, unityClass);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } catch (ClassNotFoundException e) {
                Log.e("WIFI_LIB", "未找到 UnityPlayerActivity");
            }
            
            finish();
        }, 1200);
    }

    private void startWifiScan() {
        if (isScanning) return;
        isScanning = true;
        @SuppressLint("MissingPermission")
        boolean success = wifiManager.startScan();
        if (!success) {
            isScanning = false;
            Log.e("WIFI_SCAN", "系统扫描请求失败");
            if (countdownSeconds <= 0) {
                startHotspotAndServer();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WIFI_PANEL_REQUEST_CODE) {
            new Handler(Looper.getMainLooper()).postDelayed(this::startProcess, 1000);
        }
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        } else {
            try {
                int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
                return mode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                return info != null && info.isConnected();
            }
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void startHotspotAndServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isStartingHotspot || hotspotReservation != null) {
                return;
            }
            
            isStartingHotspot = true;
            try {
                wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                    @SuppressLint("SetTextI18n")
                    @Override
                    public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                        super.onStarted(reservation);
                        isStartingHotspot = false;
                        hotspotReservation = reservation;
                        
                        String ssid = "";
                        String password = "";

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            SoftApConfiguration config = reservation.getSoftApConfiguration();
                            ssid = config.getSsid();
                            password = config.getPassphrase();
                        } else {
                            @SuppressWarnings("deprecation")
                            WifiConfiguration config = reservation.getWifiConfiguration();
                            if (config != null) {
                                ssid = config.SSID;
                                password = config.preSharedKey;
                            }
                        }

                        final String fSsid = ssid;
                        final String fPass = password;
                        new Thread(() -> {
                            String wifiQrContent = "WIFI:S:" + fSsid + ";T:WPA;P:" + fPass + ";;";
                            Bitmap qrBitmap = generateQrCode(wifiQrContent);
                            
                            String ip = getHotspotIpAddress();
                            String webUrl = "http://" + ip + ":8765/";
                            Bitmap webQrBitmap = generateQrCode(webUrl);

                            mainHandler.post(() -> {
                                if (qrBitmap != null) ivQrCode.setImageBitmap(qrBitmap);
                                if (webQrBitmap != null) ivWebQrCode.setImageBitmap(webQrBitmap);
                                cardConnection.setVisibility(View.VISIBLE);
                                cardWebStep.setVisibility(View.VISIBLE);
                                tvStatus.setText(getString(R.string.wifi_hotspot_active, fSsid));
                                tvHotspotInfo.setText("名称: " + fSsid + "\n密码: " + fPass);
                                tvIpLink.setText(webUrl);
                                startHttpServer();
                            });
                        }).start();

                        startHotspotMonitor();
                        startIpAndClientPolling(ssid, password);
                    }

                    @Override
                    public void onFailed(int reason) {
                        super.onFailed(reason);
                        isStartingHotspot = false;
                        mainHandler.post(() -> {
                            tvStatus.setText("热点启动失败: " + reason);
                            btnAction.setVisibility(View.VISIBLE);
                        });
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (IllegalStateException e) {
                isStartingHotspot = false;
                Log.e("WIFI_HOTSPOT", "请求热点异常", e);
            }
        }
    }

    private void startHttpServer() {
        if (isServerRunning) return;
        isServerRunning = true;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8765, 50, InetAddress.getByName("0.0.0.0"));
                Log.d("WIFI_HTTP", "服务器已在 8765 端口启动");
                while (isServerRunning) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                if (isServerRunning) Log.e("WIFI_HTTP", "服务器异常", e);
            }
        }).start();
    }

    private void startIpAndClientPolling(String ssid, String password) {
        stopPolling();
        ipPollRunnable = new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                String ip = getHotspotIpAddress();
                if (!ip.equals("127.0.0.1") && !ip.equals(lastReportedIp)) {
                    lastReportedIp = ip;
                    String webUrl = "http://" + ip + ":8765/";
                    Log.d("WIFI_IP", "热点 IP 已就绪: " + ip);
                    
                    mainHandler.post(() -> {
                        tvIpLink.setText(webUrl);
                    });

                    new Thread(() -> {
                        Bitmap webQrBitmap = generateQrCode(webUrl);
                        mainHandler.post(() -> {
                            if (webQrBitmap != null) {
                                ivWebQrCode.setImageBitmap(webQrBitmap);
                            }
                        });
                    }).start();
                }
                mainHandler.postDelayed(this, IP_POLL_INTERVAL);
            }
        };
        mainHandler.post(ipPollRunnable);
    }

    private void stopPolling() {
        if (ipPollRunnable != null) {
            mainHandler.removeCallbacks(ipPollRunnable);
            ipPollRunnable = null;
        }
    }

    private Bitmap generateQrCode(String content) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            return barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 400, 400);
        } catch (Exception e) {
            Log.e("WIFI_QR", "生成二维码失败", e);
            return null;
        }
    }

    private String getHotspotIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getName();
                if (name.contains("wlan") || name.contains("ap") || name.contains("swlan") || name.contains("hotspot")) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("WIFI_IP", "获取热点 IP 失败", e);
        }
        return "127.0.0.1";
    }

    private void stopHttpServer() {
        isServerRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    @SuppressLint({"MissingPermission", "SetTextI18n"})
    private void handleClient(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            Log.d("WIFI_HTTP", "收到请求: " + requestLine);
            
            String path = "/";
            String method = "GET";
            if (requestLine.contains(" ")) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    method = parts[0].toUpperCase();
                    path = parts[1];
                }
            }

            // Portal 跳转逻辑
            if (path.contains("generate_204") || path.contains("hotspot-detect") || 
                path.contains("success.html") || path.contains("canonical.html") || 
                path.contains("connecttest") || path.contains("kindle-wifi")) {
                
                String ip = getHotspotIpAddress();
                String response = "HTTP/1.1 302 Found\r\nLocation: http://" + ip + ":8765/\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Log.d("WIFI_HTTP", "执行 Portal 引导跳转");
                return;
            }

            int contentLength = 0;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                }
            }
            
            String css = "body { margin: 0; font-family: -apple-system, sans-serif; background: #f6f9fc; min-height: 100vh; display: flex; align-items: center; justify-content: center; } " +
                         ".container { width: 90%; max-width: 420px; background: #fff; padding: 40px 32px; border-radius: 24px; box-shadow: 0 20px 40px rgba(0,0,0,0.05); } " +
                         "h2 { margin: 0 0 12px 0; color: #1a1a1a; } " +
                         "input, select { margin-bottom: 20px; padding: 16px; width: 100%; box-sizing: border-box; border: 1.5px solid #eee; border-radius: 14px; } " +
                         "button { background: #007aff; color: white; border: none; padding: 18px; border-radius: 16px; width: 100%; font-weight: 600; }";

            if (path.equals("/") || path.startsWith("/?")) {
                List<ScanResult> results = cachedScanResults;
                StringBuilder options = new StringBuilder();
                Set<String> ssids = new HashSet<>();
                for (ScanResult sr : results) {
                    if (sr.SSID != null && !sr.SSID.isEmpty()) ssids.add(sr.SSID);
                }
                
                if (ssids.isEmpty()) {
                    options.append("<option value=\"\">未发现网络</option>");
                } else {
                    for (String s : ssids) {
                        options.append("<option value=\"").append(s).append("\">").append(s).append("</option>\n");
                    }
                }
                
                String js = "<script>" +
                            "function updateSsid(val) { " +
                            "  if(val) document.getElementsByName('manual_ssid')[0].value = val; " +
                            "}" +
                            "</script>";

                String htmlBody = "<div class=\"container\"><h2>配置网络</h2><p>请选择 Wi-Fi 并输入密码。</p>" +
                        "<form method=\"POST\" action=\"/connect\">" +
                        "<select name=\"ssid\" onchange=\"updateSsid(this.value)\"><option value=\"\">-- 请选择 --</option>" + options + "</select>" +
                        "<input type=\"text\" name=\"manual_ssid\" placeholder=\"Wi-Fi 名称\">" +
                        "<input type=\"password\" name=\"password\" placeholder=\"在此输入密码\">" +
                        "<button type=\"submit\">开始配网</button></form></div>" + js;

                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" +
                        "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><style>" + css + "</style></head><body>" + htmlBody + "</body></html>";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
            } else if (path.startsWith("/connect")) {
                String body = "";
                if (method.equals("POST") && contentLength > 0) {
                    char[] bodyChars = new char[contentLength];
                    reader.read(bodyChars, 0, contentLength);
                    body = new String(bodyChars);
                }
                
                String ssid = "", manualSsid = "", password = "";
                String[] params = body.split("&");
                for (String param : params) {
                    String[] kv = param.split("=");
                    if (kv.length == 2) {
                        String key = URLDecoder.decode(kv[0], "UTF-8");
                        String value = URLDecoder.decode(kv[1], "UTF-8");
                        if (key.equals("ssid")) ssid = value;
                        else if (key.equals("manual_ssid")) manualSsid = value;
                        else if (key.equals("password")) password = value;
                    }
                }
                
                String finalSsid = manualSsid.isEmpty() ? ssid : manualSsid;
                final String finalPassword = password;
                String htmlResponse = "<div class=\"container\"><h2>收到凭据</h2><p>正在尝试连接 " + finalSsid + "...</p></div>";
                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" +
                        "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><style>" + css + "</style></head><body>" + htmlResponse + "</body></html>";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
                mainHandler.postDelayed(() -> {
                    showSuccessDialog(finalSsid);
                    connectToWifi(finalSsid, finalPassword);
                }, 1000);
            }
        } catch (Exception e) {
            Log.e("WIFI_HTTP", "处理连接异常", e);
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
    
    private void showSuccessDialog(String ssid) {
        View view = getLayoutInflater().inflate(R.layout.dialog_success, null);
        TextView tvTitle = view.findViewById(R.id.tv_dialog_title);
        TextView tvMsg = view.findViewById(R.id.tv_dialog_msg);
        tvTitle.setText(R.string.wifi_dialog_title);
        tvMsg.setText(getString(R.string.wifi_dialog_subtitle, ssid));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(view).setCancelable(false).create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
        mainHandler.postDelayed(() -> { if (dialog.isShowing()) dialog.dismiss(); }, 4000);
    }

    private void connectToWifi(String ssid, String password) {
        if (hotspotReservation != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hotspotReservation.close();
            hotspotReservation = null;
        }
        stopHttpServer();
        stopHotspotMonitor();

        Log.d("WIFI_CONNECT", "执行连接任务: " + ssid);
        tvStatus.setText("正在连接: " + ssid);
        WifiAccessibilityService.pendingSsid = ssid;

        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        if (password != null && !password.isEmpty()) saveWifiCredentials(ssid, password);

        startConnectionTimeout(); // 开启 20 秒异常连接检测
        mainHandler.postDelayed(() -> executeWifiConnection(ssid, password), 1500);
    }

    private void startConnectionTimeout() {
        stopConnectionTimeout();
        connectionTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWifiConnected()) {
                    Log.w("WIFI_CONNECT", "10秒内未检测到网络连接，尝试引导至系统设置界面");
                    openSystemWifiAndReturn();
                }
            }
        };
        mainHandler.postDelayed(connectionTimeoutRunnable, 10000); // 10秒超时
    }

    private void openSystemWifiAndReturn() {
        try {
            // 1. 进入系统 WiFi 设置页面
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            
            // 2. 10 秒后自动跳回本应用
            mainHandler.postDelayed(() -> {
                Log.d("WIFI_CONNECT", "正在从系统设置自动跳回配网应用");
                Intent backIntent = new Intent(this, WifiMainActivity.class);
                backIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(backIntent);
            }, 10000); // 系统设置页停留 10 秒
        } catch (Exception e) {
            Log.e("WIFI_CONNECT", "跳转系统设置失败", e);
        }
    }

    private void executeWifiConnection(String ssid, String password) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { wifiManager.removeNetworkSuggestions(new ArrayList<>()); } catch (Exception ignored) {}
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder().setSsid(ssid).setWpa2Passphrase(password).setIsAppInteractionRequired(false).build();
            List<WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(suggestion);
            wifiManager.addNetworkSuggestions(list);
            
            try {
                android.net.wifi.WifiNetworkSpecifier specifier = new android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build();
                android.net.NetworkRequest request = new android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build();
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(@NonNull android.net.Network network) {
                            cm.bindProcessToNetwork(network); 
                            mainHandler.post(() -> onWifiSuccess());
                        }
                    });
                }
            } catch (Exception e) { Log.e("WIFI_CONNECT", "连接异常", e); }
            tvStatus.setText(getString(R.string.wifi_wait_system_dialog));
        }
    }
}
