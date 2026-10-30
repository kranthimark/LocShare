package com.fleet.locshare;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs in the background (foreground service with a notification):
 *  - reads GPS
 *  - every few seconds broadcasts {id, name, lat, lon} to every device on the same Wi-Fi
 *  - listens for the same broadcasts from other devices and records their IP + location
 * No server and no internet needed: all phones on the same router/dongle find each other.
 */
public class LocShareService extends Service {

    public static final int PORT = 45454;            // UDP port used by all devices
    public static final long SEND_EVERY_MS = 3000;   // how often each device announces itself
    private static final String TAG = "LocShare";
    private static final String CHANNEL_ID = "locshare";

    private LocationManager locationManager;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ScheduledExecutorService sender;
    private Thread listenerThread;
    private volatile DatagramSocket listenSocket;
    private volatile boolean stopping = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { onNewLocation(loc); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {
            if (LocationManager.GPS_PROVIDER.equals(p)) DeviceStore.gpsStatus = "GPS is OFF – turn on Location";
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("locshare");   // needed to receive broadcasts on many phones
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "locshare:run");
        wakeLock.acquire();

        useWifiEvenWithoutInternet();
        startGps();
        startListener();

        sender = Executors.newSingleThreadScheduledExecutor();
        sender.scheduleWithFixedDelay(this::announce, 500, SEND_EVERY_MS, TimeUnit.MILLISECONDS);
        DeviceStore.running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    // ---------- Foreground notification ----------

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("LocShare is sharing location")
                .setContentText("Visible to devices on the same Wi-Fi")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- Keep traffic on Wi-Fi (router may have no internet) ----------

    /**
     * If mobile data is on and the Wi-Fi has no internet, Android sends traffic over mobile data.
     * Binding the app to the Wi-Fi network keeps our local broadcasts on the router.
     */
    private void useWifiEvenWithoutInternet() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                connectivityManager.bindProcessToNetwork(network);
                Log.i(TAG, "Bound to Wi-Fi network");
            }
            @Override public void onLost(Network network) {
                connectivityManager.bindProcessToNetwork(null);
            }
        };
        try {
            connectivityManager.requestNetwork(req, wifiCallback);
        } catch (Exception e) {
            Log.w(TAG, "Could not request Wi-Fi network", e);
            wifiCallback = null;
        }
    }

    // ---------- GPS ----------

    @SuppressLint("MissingPermission") // permission is requested in MainActivity before starting
    private void startGps() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        2000, 0, locationListener, Looper.getMainLooper());
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) onNewLocation(last);
                DeviceStore.gpsStatus = "Searching GPS… (go near a window / outside)";
            } else {
                DeviceStore.gpsStatus = "GPS is OFF – turn on Location";
            }
            // Network location helps indoors when available; it is optional.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        5000, 0, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            DeviceStore.gpsStatus = "Location permission not given";
        } catch (Exception e) {
            DeviceStore.gpsStatus = "GPS error: " + e.getMessage();
        }
    }

    private void onNewLocation(Location loc) {
        // Prefer accurate fixes: ignore a worse fix that arrives soon after a better one
        if (DeviceStore.myLat != null && loc.getAccuracy() > DeviceStore.myAccuracy * 3
                && DeviceStore.myAccuracy > 0 && !LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
            return;
        }
        DeviceStore.myLat = loc.getLatitude();
        DeviceStore.myLon = loc.getLongitude();
        DeviceStore.myAccuracy = loc.getAccuracy();
        DeviceStore.gpsStatus = "GPS OK (" + loc.getProvider() + ")";
        updateMyEntry();
    }

    // ---------- Sending ----------

    private void announce() {
        try {
            Set<InetAddress> targets = new LinkedHashSet<>();
            String myIp = findLocalIpAndBroadcasts(targets);
            DeviceStore.myIp = myIp;
            updateMyEntry();

            if (DeviceStore.myLat == null) return; // nothing to share until GPS has a fix

            SharedPreferences p = Prefs.get(this);
            JSONObject msg = new JSONObject();
            msg.put("app", "locshare");
            msg.put("v", 1);
            msg.put("id", Prefs.deviceId(this));
            msg.put("name", p.getString("name", ""));
            msg.put("lat", DeviceStore.myLat);
            msg.put("lon", DeviceStore.myLon);
            msg.put("acc", DeviceStore.myAccuracy);
            msg.put("ts", System.currentTimeMillis());
            byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);

            targets.add(InetAddress.getByName("255.255.255.255"));
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (InetAddress t : targets) {
                    try {
                        s.send(new DatagramPacket(data, data.length, t, PORT));
                    } catch (Exception e) {
                        Log.w(TAG, "send to " + t + " failed: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "announce failed", e);
        }
    }

    /** Finds this device's local IPv4 address and the broadcast address of each local network. */
    private static String findLocalIpAndBroadcasts(Set<InetAddress> broadcasts) {
        String best = "-";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                boolean wifiLike = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
                        || n.startsWith("eth") || n.startsWith("softap");
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (ia.getBroadcast() != null) broadcasts.add(ia.getBroadcast());
                    if (wifiLike || "-".equals(best)) best = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    private void updateMyEntry() {
        if (DeviceStore.myLat == null) return;
        String id = Prefs.deviceId(this);
        DeviceStore.Device me = DeviceStore.devices.get(id);
        if (me == null) { me = new DeviceStore.Device(); me.id = id; me.isMe = true; }
        me.name = Prefs.get(this).getString("name", "");
        me.ip = DeviceStore.myIp;
        me.lat = DeviceStore.myLat;
        me.lon = DeviceStore.myLon;
        me.accuracy = DeviceStore.myAccuracy;
        me.lastSeen = System.currentTimeMillis();
        DeviceStore.devices.put(id, me);
    }

    // ---------- Receiving ----------

    private void startListener() {
        final String myId = Prefs.deviceId(this);
        listenerThread = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (!stopping) {
                try {
                    if (listenSocket == null || listenSocket.isClosed()) {
                        DatagramSocket s = new DatagramSocket(null);
                        s.setReuseAddress(true);
                        s.setBroadcast(true);
                        s.bind(new InetSocketAddress(PORT));
                        listenSocket = s;
                    }
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    listenSocket.receive(pkt);
                    String text = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    JSONObject m = new JSONObject(text);
                    if (!"locshare".equals(m.optString("app"))) continue;
                    String id = m.optString("id");
                    if (id.isEmpty() || id.equals(myId)) continue; // ignore our own broadcast

                    DeviceStore.Device d = new DeviceStore.Device();
                    d.id = id.length() > 40 ? id.substring(0, 40) : id;
                    String name = m.optString("name");
                    d.name = name.length() > 40 ? name.substring(0, 40) : name;
                    d.ip = pkt.getAddress().getHostAddress(); // real sender IP from the network
                    d.lat = m.getDouble("lat");
                    d.lon = m.getDouble("lon");
                    d.accuracy = (float) m.optDouble("acc", 0);
                    d.lastSeen = System.currentTimeMillis();
                    DeviceStore.devices.put(d.id, d);
                } catch (Exception e) {
                    if (stopping) break;
                    Log.w(TAG, "receive error: " + e.getMessage());
                    if (listenSocket != null) listenSocket.close();
                    listenSocket = null;
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "locshare-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    // ---------- Shutdown ----------

    @Override
    public void onDestroy() {
        stopping = true;
        DeviceStore.running = false;
        if (sender != null) sender.shutdownNow();
        if (listenSocket != null) listenSocket.close();
        if (locationManager != null) locationManager.removeUpdates(locationListener);
        if (wifiCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
            connectivityManager.bindProcessToNetwork(null);
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
