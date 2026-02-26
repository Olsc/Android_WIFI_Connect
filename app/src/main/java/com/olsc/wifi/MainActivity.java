package com.olsc.wifi;

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
import android.graphics.Color;
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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
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

public class MainActivity extends Activity {

    private TextView tvStatus, tvIpLink, tvCount;
    private ImageView ivQrCode;
    private View cardConnection;
    private Button btnAction;
    private WifiManager wifiManager;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ServerSocket serverSocket;
    private boolean isServerRunning = false;
    private Thread serverThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isStartingHotspot = false;
    private Runnable ipPollRunnable;
    private static final int IP_POLL_INTERVAL = 1000; // 1 second
    private static final int PERMISSIONS_REQUEST_CODE = 1001;
    private static final int WIFI_PANEL_REQUEST_CODE = 1002;
    private List<ScanResult> cachedScanResults = new ArrayList<>();
    private boolean isScanning = false;

    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            isScanning = false;
            updateScanResults();
            
            if (!isWifiConnected() && hotspotReservation == null && !isStartingHotspot) {
                mainHandler.post(MainActivity.this::startHotspotAndServer);
            }
        }
    };

    private void updateScanResults() {
        @SuppressLint("MissingPermission")
        List<ScanResult> results = wifiManager.getScanResults();
        if (results != null) {
            cachedScanResults = new ArrayList<>(results);
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(results.size()).append(" networks:");
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty()) {
                    sb.append("\n• ").append(result.SSID);
                }
                if (sb.length() > 200) {
                    sb.append("\n...");
                    break;
                }
            }
            final String statusText = sb.toString();
            mainHandler.post(() -> {
                tvStatus.setText(statusText);
                Log.d("WIFI_SCAN", "Scan finished, found " + results.size() + " networks");
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(wifiScanReceiver);
        } catch (Exception e) {}
        if (hotspotReservation != null) {
            hotspotReservation.close();
            hotspotReservation = null;
        }
        stopHttpServer();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvIpLink = findViewById(R.id.tv_ip_link);
        tvCount = findViewById(R.id.tv_count);
        ivQrCode = findViewById(R.id.iv_qrcode);
        cardConnection = findViewById(R.id.card_connection);
        btnAction = findViewById(R.id.btn_action);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, intentFilter);

        btnAction.setOnClickListener(v -> checkPermissionsAndStart());

        mainHandler.postDelayed(this::checkPermissionsAndStart, 800);
    }

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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
                Toast.makeText(this, "Permissions denied. Cannot proceed.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startProcess() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "Please enable Location (GPS) first!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        if (!wifiManager.isWifiEnabled()) {
            tvStatus.setText("Enabling Wi-Fi...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                startActivityForResult(panelIntent, WIFI_PANEL_REQUEST_CODE);
            } else {
                wifiManager.setWifiEnabled(true);
            }
            return;
        }

        if (isWifiConnected()) {
            tvStatus.setText("Already connected to Wi-Fi.");
            Toast.makeText(this, "WIFI is already connected!", Toast.LENGTH_SHORT).show();
        } else {
            tvStatus.setText("Scanning for nearby Wi-Fi...");
            startWifiScan();
            
            mainHandler.postDelayed(() -> {
                if (hotspotReservation == null && !isStartingHotspot && !isWifiConnected()) {
                    Log.w("WIFI_SCAN", "Scan timeout, starting hotspot anyway");
                    startHotspotAndServer();
                }
            }, 5000);
        }
    }

    private void startWifiScan() {
        if (isScanning) return;
        isScanning = true;
        @SuppressLint("MissingPermission")
        boolean success = wifiManager.startScan();
        if (!success) {
            isScanning = false;
            Log.e("WIFI_SCAN", "startScan() failed, starting hotspot immediately");
            startHotspotAndServer();
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
                @SuppressWarnings("deprecation")
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
                Toast.makeText(this, "Hotspot is already starting or running...", Toast.LENGTH_SHORT).show();
                return;
            }
            
            isStartingHotspot = true;
            try {
                wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                    @Override
                    public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                        super.onStarted(reservation);
                        isStartingHotspot = false;
                        hotspotReservation = reservation;
                        
                        String ssid = "";
                        String password = "";

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            SoftApConfiguration config = reservation.getSoftApConfiguration();
                            if (config != null) {
                                ssid = config.getSsid();
                                password = config.getPassphrase();
                            }
                        } else {
                            @SuppressWarnings("deprecation")
                            WifiConfiguration config = reservation.getWifiConfiguration();
                            if (config != null) {
                                ssid = config.SSID;
                                password = config.preSharedKey;
                            }
                        }

                        String wifiQrContent = "WIFI:S:" + ssid + ";T:WPA;P:" + password + ";;";
                        Bitmap qrBitmap = generateQrCode(wifiQrContent);
                        if (qrBitmap != null) {
                            ivQrCode.setImageBitmap(qrBitmap);
                        }
                        
                        cardConnection.setVisibility(View.VISIBLE);
                        tvStatus.setText("Hotspot active: " + ssid);
                        startHttpServer();
                        
                        startIpAndClientPolling(ssid, password);
                    }

                    @Override
                    public void onStopped() {
                        super.onStopped();
                        isStartingHotspot = false;
                        mainHandler.post(() -> {
                            tvStatus.setText("Hotspot Stopped.");
                            cardConnection.setVisibility(View.GONE);
                            stopPolling();
                        });
                        stopHttpServer();
                    }

                    @Override
                    public void onFailed(int reason) {
                        super.onFailed(reason);
                        isStartingHotspot = false;
                        mainHandler.post(() -> tvStatus.setText("Hotspot Failed: " + reason));
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (IllegalStateException e) {
                isStartingHotspot = false;
                Toast.makeText(this, "Hotspot request already active.", Toast.LENGTH_LONG).show();
                Log.e("WIFI_HOTSPOT", "Caller already has an active LocalOnlyHotspot request", e);
            }
        } else {
            Toast.makeText(this, "Hotspot requires Android 8.0+", Toast.LENGTH_SHORT).show();
        }
    }

    private void startHttpServer() {
        if (isServerRunning) return;
        isServerRunning = true;
        
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8765, 50, InetAddress.getByName("0.0.0.0"));
                while (isServerRunning) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                if (isServerRunning) e.printStackTrace();
            }
        });
        serverThread.start();
    }

    private void startIpAndClientPolling(String ssid, String password) {
        stopPolling();
        ipPollRunnable = new Runnable() {
            @Override
            public void run() {
                String ip = getHotspotIpAddress();
                if (!ip.equals("127.0.0.1") && !ip.equals("192.168.43.1") || Build.VERSION.SDK_INT < 26) {
                    tvIpLink.setText("HTTP Link: http://" + ip + ":8765");
                }
                
                updateClientCount();
                
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

    private void updateClientCount() {
        int count = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream("/proc/net/arp")))) {
            String line;
            br.readLine();
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4 && parts[3].matches("..:..:..:..:..:..") && !parts[3].equals("00:00:00:00:00:00")) {
                    count++;
                }
            }
        } catch (Exception ignored) {}
        final int finalCount = count;
        mainHandler.post(() -> tvCount.setText("Connected Devices: " + finalCount));
    }

    private Bitmap generateQrCode(String content) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            return barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 400, 400);
        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    private void stopHttpServer() {
        isServerRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
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
            
            int contentLength = 0;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                }
            }
            
            String css = "body { font-family: system-ui, -apple-system, sans-serif; padding: 20px; background-color: #f5f5f7; color: #1d1d1f; } " +
                         ".container { max-width: 400px; margin: 0 auto; background: white; padding: 24px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); } " +
                         "h2 { margin-top: 0; color: #000; font-size: 24px; } " +
                         "label { font-size: 14px; font-weight: 500; color: #86868b; display: block; margin-bottom: 4px; } " +
                         "input, select { margin-bottom: 16px; padding: 12px; font-size: 16px; width: 100%; box-sizing: border-box; border: 1px solid #d2d2d7; border-radius: 8px; background-color: #fff; } " +
                         "button { background-color: #0071e3; color: white; border: none; cursor: pointer; padding: 14px; border-radius: 8px; font-size: 16px; font-weight: 600; width: 100%; transition: background-color 0.2s; } " +
                         "button:hover { background-color: #0077ed; }";

            if (requestLine.startsWith("GET / ")) {
                List<ScanResult> results = cachedScanResults;
                Set<String> ssids = new HashSet<>();
                if (results != null) {
                    for (ScanResult sr : results) {
                        if (sr.SSID != null && !sr.SSID.isEmpty()) {
                            ssids.add(sr.SSID);
                        }
                    }
                }
                
                StringBuilder options = new StringBuilder();
                if (ssids.isEmpty()) {
                    options.append("<option value=\"\">No networks found. Type manually below.</option>");
                } else {
                    for (String s : ssids) {
                        options.append("<option value=\"").append(s).append("\">").append(s).append("</option>\n");
                    }
                }
                
                String js = "<script>" +
                            "function updateSsid(val) { document.getElementsByName('manual_ssid')[0].value = val; }" +
                            "</script>";

                String html = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" +
                        "<!DOCTYPE html><html><head>" +
                        "<meta charset=\"UTF-8\">" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                        "<style>" + css + "</style></head><body>" +
                        "<div class=\"container\">" +
                        "<h2>连接设备到 Wi-Fi</h2>" +
                        "<form method=\"POST\" action=\"/connect\">" +
                        "<label>选择网络：</label>" +
                        "<select name=\"ssid\" onchange=\"updateSsid(this.value)\"><option value=\"\">-- 请选择 --</option>" + options.toString() + "</select>" +
                        "<label>或手动输入 SSID：</label>" +
                        "<input type=\"text\" name=\"manual_ssid\" placeholder=\"Wi-Fi 名称\">" +
                        "<label>密码：</label>" +
                        "<input type=\"password\" name=\"password\" placeholder=\"请输入密码\">" +
                        "<button type=\"submit\">连接</button></form></div>" +
                        js + "</body></html>";
                out.write(html.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
            } else if (requestLine.startsWith("POST /connect")) {
                char[] bodyChars = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int result = reader.read(bodyChars, read, contentLength - read);
                    if (result == -1) break;
                    read += result;
                }
                String body = new String(bodyChars);
                
                String[] params = body.split("&");
                String ssid = "";
                String manualSsid = "";
                String password = "";
                for (String param : params) {
                    String[] kv = param.split("=");
                    if (kv.length == 2) {
                        String key = URLDecoder.decode(kv[0], "UTF-8");
                        String value = URLDecoder.decode(kv[1], "UTF-8");
                        if (key.equals("ssid")) ssid = value;
                        if (key.equals("manual_ssid")) manualSsid = value;
                        if (key.equals("password")) password = value;
                    }
                }
                
                String finalSsid = (manualSsid != null && !manualSsid.trim().isEmpty()) ? manualSsid.trim() : ssid;
                
                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" +
                        "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>" + css + "</style></head><body>" +
                        "<div class=\"container\"><h2>信息已接收！</h2><p>设备正在尝试连接到 <b>" + finalSsid + "</b></p></div></body></html>";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
                String finalConfigSsid = finalSsid;
                String finalConfigPass = password;
                
                mainHandler.post(() -> {
                    tvStatus.setText("Connecting to " + finalConfigSsid + "...");
                    connectToWifi(finalConfigSsid, finalConfigPass);
                });
            } else {
                String response = "HTTP/1.1 404 Not Found\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            Log.e("WIFI_HTTP", "Client error", e);
        } finally {
            try {
                socket.close();
            } catch (Exception e) {}
        }
    }
    
    private void connectToWifi(String ssid, String password) {
        if (hotspotReservation != null) {
            hotspotReservation.close();
            hotspotReservation = null;
        }
        stopHttpServer();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build();
            List<WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(suggestion);
            wifiManager.addNetworkSuggestions(list);
            tvStatus.setText("Saved suggestion for " + ssid + ". System will connect to it shortly if it's in range.");
            Toast.makeText(this, "Network added. Connecting...", Toast.LENGTH_LONG).show();
        } else {
            @SuppressWarnings("deprecation")
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = String.format("\"%s\"", ssid);
            wifiConfig.preSharedKey = String.format("\"%s\"", password);
            
            @SuppressWarnings("deprecation")
            int netId = wifiManager.addNetwork(wifiConfig);
            
            @SuppressWarnings("deprecation")
            boolean d = wifiManager.disconnect();
            
            @SuppressWarnings("deprecation")
            boolean e = wifiManager.enableNetwork(netId, true);
            
            @SuppressWarnings("deprecation")
            boolean r = wifiManager.reconnect();
            
            tvStatus.setText("Requested connection to " + ssid);
        }
    }
}
