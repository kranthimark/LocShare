package com.fleet.locshare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Shared, in-memory list of devices seen on the local network (plus this device's own status). */
public final class DeviceStore {

    public static final class Device {
        public String id;
        public String name;
        public String ip;
        public double lat;
        public double lon;
        public float accuracy;
        public long lastSeen; // System.currentTimeMillis() when last heard
        public boolean isMe;
    }

    /** All known devices by device ID (includes this device). */
    public static final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();

    // This device's live status, shown at the top of the screen
    public static volatile String myIp = "-";
    public static volatile String gpsStatus = "Waiting for GPS…";
    public static volatile boolean running = false;
    public static volatile Double myLat = null;
    public static volatile Double myLon = null;
    public static volatile float myAccuracy = 0f;

    private DeviceStore() {}

    /** Devices sorted: me first, then by name/ID. Drops devices silent for over 10 minutes. */
    public static List<Device> snapshot() {
        long now = System.currentTimeMillis();
        devices.values().removeIf(d -> !d.isMe && now - d.lastSeen > 10 * 60 * 1000);
        List<Device> list = new ArrayList<>(devices.values());
        Collections.sort(list, (a, b) -> {
            if (a.isMe != b.isMe) return a.isMe ? -1 : 1;
            String ka = (a.name == null || a.name.isEmpty()) ? a.id : a.name;
            String kb = (b.name == null || b.name.isEmpty()) ? b.id : b.name;
            return ka.compareToIgnoreCase(kb);
        });
        return list;
    }

    /** Straight-line distance in metres between two GPS points. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
