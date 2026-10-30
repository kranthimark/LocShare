# LocShare – offline location sharing on one Wi‑Fi router

Every phone with LocShare installed, on the same Wi‑Fi router or dongle, shares its
**Device ID, IP address, latitude and longitude** with every other phone.
No laptop, no server and no internet needed.

## How it works
- Each phone reads its GPS.
- Every 3 seconds it broadcasts a small message (UDP port 45454) to the whole Wi‑Fi network.
- Every phone listens on the same port and builds the list of devices.
- The IP shown for each device is taken from the network packet itself.
- A device is shown as OFFLINE after 20 seconds of silence and removed after 10 minutes.
- Sharing keeps running with the screen off (a notification shows while it runs).

## Build the APK (once, on a laptop with internet)
1. Install Android Studio: https://developer.android.com/studio
2. Unzip this folder, then in Android Studio: File → Open → select the `LocShare` folder.
3. Wait for "Gradle sync" to finish (first time downloads a few hundred MB).
4. Menu: Build → Build App Bundle(s) / APK(s) → Build APK(s).
5. Click "locate" in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`.

## Install on each phone
1. Copy `app-debug.apk` to the phone (USB cable, or the upload server from before).
2. Open it and allow "Install unknown apps" when asked.
3. Open LocShare → Allow location → type a device name → Save.

## Requirements on each phone
- Connected to the same router / dongle Wi‑Fi
- Location (GPS) turned ON
- Router setting "AP isolation / client isolation" must be OFF
- First GPS fix without internet can take 1–2 minutes; go near a window or outside

## Battery
Some phones (Xiaomi, Oppo, Vivo, Realme, Samsung) stop background apps.
Settings → Apps → LocShare → Battery → set to "Unrestricted / No restrictions".
