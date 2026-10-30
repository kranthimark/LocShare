package com.fleet.locshare;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main screen: my ID / IP / GPS, a name box, start/stop, and a live list of all devices on the Wi-Fi. */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final long OFFLINE_AFTER_MS = 20_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView myInfo, countView, emptyView;
    private EditText nameBox;
    private Button startStop;
    private LinearLayout listView;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (hasLocationPermission() && !DeviceStore.running) startSharing();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresher); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresher); }

    // ---------- UI ----------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable card(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F2F4F7"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(root);

        // My device card
        LinearLayout mine = new LinearLayout(this);
        mine.setOrientation(LinearLayout.VERTICAL);
        mine.setPadding(dp(14), dp(12), dp(14), dp(12));
        mine.setBackground(card(Color.WHITE));
        root.addView(mine);

        TextView h = new TextView(this);
        h.setText("This device");
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(16);
        mine.addView(h);

        myInfo = new TextView(this);
        myInfo.setTextSize(14);
        myInfo.setLineSpacing(0, 1.2f);
        myInfo.setPadding(0, dp(6), 0, dp(8));
        mine.addView(myInfo);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameBox = new EditText(this);
        nameBox.setHint("Device name (e.g. Truck 12)");
        nameBox.setSingleLine(true);
        nameBox.setText(Prefs.get(this).getString("name", ""));
        nameRow.addView(nameBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> {
            String n = nameBox.getText().toString().trim();
            if (n.length() > 40) n = n.substring(0, 40);
            Prefs.get(this).edit().putString("name", n).apply();
            Toast.makeText(this, "Name saved", Toast.LENGTH_SHORT).show();
        });
        nameRow.addView(save);
        mine.addView(nameRow);

        startStop = new Button(this);
        startStop.setOnClickListener(v -> {
            if (DeviceStore.running) stopSharing(); else startSharing();
        });
        mine.addView(startStop);

        // Devices header
        countView = new TextView(this);
        countView.setTextSize(18);
        countView.setTypeface(Typeface.DEFAULT_BOLD);
        countView.setPadding(dp(4), dp(18), 0, dp(8));
        root.addView(countView);

        emptyView = new TextView(this);
        emptyView.setText("No other devices yet. Make sure the other phones are on the same Wi-Fi "
                + "with LocShare open and GPS on.");
        emptyView.setTextColor(Color.parseColor("#666666"));
        emptyView.setPadding(dp(4), 0, dp(4), dp(8));
        root.addView(emptyView);

        listView = new LinearLayout(this);
        listView.setOrientation(LinearLayout.VERTICAL);
        root.addView(listView);

        setContentView(scroll);
    }

    private void refresh() {
        startStop.setText(DeviceStore.running ? "Stop sharing" : "Start sharing");

        String myId = Prefs.deviceId(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Device ID:  ").append(myId).append('\n');
        sb.append("IP address:  ").append(DeviceStore.myIp).append('\n');
        if (DeviceStore.myLat != null) {
            sb.append(String.format(Locale.US, "Latitude:  %.6f%nLongitude:  %.6f%nAccuracy:  ±%d m%n",
                    DeviceStore.myLat, DeviceStore.myLon, Math.round(DeviceStore.myAccuracy)));
        }
        sb.append("Status:  ").append(DeviceStore.running ? DeviceStore.gpsStatus : "Not sharing");
        myInfo.setText(sb.toString());

        List<DeviceStore.Device> all = DeviceStore.snapshot();
        List<DeviceStore.Device> others = new ArrayList<>();
        long now = System.currentTimeMillis();
        int online = 0;
        for (DeviceStore.Device d : all) {
            if (d.isMe) continue;
            others.add(d);
            if (now - d.lastSeen <= OFFLINE_AFTER_MS) online++;
        }
        countView.setText("Devices on this Wi-Fi: " + online
                + (others.size() > online ? "  (" + (others.size() - online) + " offline)" : ""));
        emptyView.setVisibility(others.isEmpty() ? TextView.VISIBLE : TextView.GONE);

        listView.removeAllViews();
        for (DeviceStore.Device d : others) listView.addView(deviceRow(d, now));
    }

    private TextView deviceRow(DeviceStore.Device d, long now) {
        boolean offline = now - d.lastSeen > OFFLINE_AFTER_MS;
        long ageS = Math.max(0, (now - d.lastSeen) / 1000);
        String age = ageS < 60 ? ageS + "s ago" : (ageS / 60) + "m ago";

        StringBuilder sb = new StringBuilder();
        sb.append(d.name == null || d.name.isEmpty() ? "(no name)" : d.name)
          .append(offline ? "   • OFFLINE" : "   • online").append('\n');
        sb.append("ID: ").append(d.id).append("     IP: ").append(d.ip).append('\n');
        sb.append(String.format(Locale.US, "Lat: %.6f     Long: %.6f%n", d.lat, d.lon));
        sb.append("±").append(Math.round(d.accuracy)).append(" m  ·  ").append(age);
        if (DeviceStore.myLat != null) {
            double m = DeviceStore.distanceMeters(DeviceStore.myLat, DeviceStore.myLon, d.lat, d.lon);
            sb.append("  ·  ").append(m < 1000 ? Math.round(m) + " m away"
                    : String.format(Locale.US, "%.2f km away", m / 1000));
        }

        TextView t = new TextView(this);
        t.setText(sb.toString());
        t.setTextSize(14);
        t.setLineSpacing(0, 1.2f);
        t.setTextColor(offline ? Color.parseColor("#888888") : Color.parseColor("#1A1A1A"));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(card(offline ? Color.parseColor("#E9E9E9") : Color.WHITE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------- Start / stop ----------

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startSharing() {
        if (!hasLocationPermission()) {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please turn ON Location (GPS)", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        Intent svc = new Intent(this, LocShareService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        DeviceStore.running = true;
    }

    private void stopSharing() {
        stopService(new Intent(this, LocShareService.class));
        DeviceStore.running = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) {
            if (hasLocationPermission()) startSharing();
            else Toast.makeText(this, "Location permission is needed to share position", Toast.LENGTH_LONG).show();
        }
    }
}
