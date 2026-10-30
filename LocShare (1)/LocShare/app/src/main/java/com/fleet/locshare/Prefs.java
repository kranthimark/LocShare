package com.fleet.locshare;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Saved settings: a permanent device ID and the device name typed by the user. */
public final class Prefs {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("locshare", Context.MODE_PRIVATE);
    }

    /** Created once on first launch, e.g. "DEV-7KQ2XM", and kept forever on this phone. */
    public static synchronized String deviceId(Context c) {
        SharedPreferences p = get(c);
        String id = p.getString("device_id", null);
        if (id == null) {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder("DEV-");
            for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
            id = sb.toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }
}
