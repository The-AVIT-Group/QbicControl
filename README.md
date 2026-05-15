# QBIC SmartPanel Companion App

Companion Android app for the **QBIC SmartPanel Sensors** Q-SYS plugin.
Exposes ambient light and proximity sensors, MJPEG camera streaming, camera
privacy LED control, and browser foreground monitoring via WebSocket (port 9090)
and HTTP (port 9091).

> **Tested on:** QBIC TD-1070, firmware v2.12.4, Android 12.

**Package:** `au.com.theavitgroup.qbiccontrol`

> **Part of the QBIC SmartPanel system.**
> This app is a companion to the [QBIC SmartPanel Sensors Q-SYS plugin](https://github.com/The-AVIT-Group/q-sys-plugin-qbic-panel).
> The plugin communicates with this app over WebSocket (port 9090) and HTTP (port 9091).
> Install the Q-SYS plugin on your Core before commissioning panels with this app.

---

## Architecture

```text
Q-SYS Core
  └─ Plugin (runtime.lua)
       └─ WebSocket  ws://<panel-ip>:9090   ← this app
       └─ HTTP       http://<panel-ip>:9091  ← MJPEG / snapshot / screen

Browser / VLC / NVR
  └─ http://<panel-ip>:9091/              ← live MJPEG (~10 fps)
  └─ http://<panel-ip>:9091/snapshot      ← single JPEG frame
  └─ http://<panel-ip>:9091/screen        ← full-resolution PNG of the Android display
  └─ http://<panel-ip>:9091/screen?scale=3 ← scaled JPEG (e.g. ÷3 → 426×266)
```

The QBIC native REST API (`qjetty`, port 8080) does **not** expose sensors, camera,
or foreground app state. This app fills those gaps as a foreground service that
auto-starts after every reboot.

---

## Hardware

| Property | Value |
| --- | --- |
| **Panel** | QBIC SmartPanel (tested on TD-1070) |
| **SoC** | Rockchip RK3566 |
| **OS** | Android 12 (userdebug — `su` available) |
| **Firmware** | v2.12.4 |
| **Camera** | OV8856 (front-facing, 640×480 JPEG capture) |

### Sensors

| Sensor | Input device | ABS code | Range | Notes |
| --- | --- | --- | --- | --- |
| Ambient light | `/dev/input/event1` (`lightsensor-level`) | `0x001c` | 10–255 | On-change |
| Proximity | `/dev/input/event5` (`proximity`) | `0x0019` | 0–150 000 | Lower = closer |

Sensors are read via the Android `SensorManager` API (`SensorMonitor` class), which is
the permission-safe path on production firmware. `getevent` works in `adb shell` but is
blocked by SELinux in app context.

---

## Prerequisites

You need Android Platform Tools (`adb`) to commission a panel. The full Android SDK
is only required if you need to rebuild the app from source.

**Check if adb is installed:**

```powershell
Get-Command adb -ErrorAction SilentlyContinue
```

**Install via WinGet (if missing):**

```powershell
winget install Google.PlatformTools
```

---

## APK distribution

`QbicControl.apk` is committed to the repo alongside the commission script.
Field engineers only need **adb + the repo** — no Android SDK, no build step.

The commission script defaults to this file automatically:

```powershell
.\commission_panel.ps1          # uses android-app\QbicControl.apk
.\commission_panel.ps1 -Token "mysecret"
```

---

## Build (only needed when changing the app source)

Building requires the **full Android SDK** (Android Studio). Gradle reads the SDK
location from `local.properties`:

```
sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
```

`local.properties` is machine-specific and gitignored — create it on each build machine.

```powershell
cd android-app
.\build_android_app.ps1
```

This builds the APK and copies it to `android-app\QbicControl.apk`. Commit the updated
APK so others can commission without rebuilding:

```powershell
git add -f android-app/QbicControl.apk
git commit -m "chore: update QbicControl.apk"
```

> Build output is redirected to `C:\Temp` to avoid Windows Defender file-lock failures
> on Gradle task files inside OneDrive-synced directories.

---

## Install

### Enabling developer options (first time only)

On the panel:

- Settings → About device → Build number → press 7 times
- Settings → Developer options → USB debugging → enable

### ADB connect

```bash
adb devices
```

### First install

**Recommended — use the commission script** (handles priv-app install, permissions XML, grants, and device admin in one step):

```powershell
.\commission_panel.ps1
# or with a known token:
.\commission_panel.ps1 -Token "mysecret"
```

After the script completes, skip to [Verify](#verify) — device setup is already done.

> The commission script installs the app as a **system priv-app**
> (`/system/priv-app/QbicControl/`). After that, `adb install -r` no longer works for
> updates — use the [Updating the priv-app](#updating-the-priv-app-after-rebuild) steps
> instead.

**Manual install (no commission script):**

```bash
adb install android-app\QbicControl.apk
```

Then run the [Device setup](#device-setup-manual-run-once-after-each-apk-install) steps below.

---

## System permissions files

The commission script pushes two XML files to the device during setup.

### `privapp-permissions-qbiccontrol.xml` → `/system/etc/permissions/`

Whitelists two `signature|privileged` permissions for the priv-app:

| Permission | Purpose |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | `BootActivity` shows a transparent overlay to satisfy the QBIC firmware's camera foreground requirement |
| `INJECT_EVENTS` | `HomeBtn` injects the Android HOME keyevent via `InputManager` to dismiss the kiosk browser |

Android reads this at boot and grants both permissions automatically — no `pm grant` required.

### `qbiccontrol-permissions.xml` → `/system/etc/sysconfig/`

Exempts the `CAMERA` permission from Android's automatic permission revocation. Without
this, Android 12 can silently revoke `CAMERA` after the app has not been used in the
foreground for an extended period, breaking the always-on streaming service.

> **Single source of truth:** edit both XML files in this directory.
> The commission script reads them directly; there are no duplicate inline copies.

---

## Updating the priv-app (after rebuild)

Once the commission script has installed QbicControl as a system priv-app, subsequent
APK updates must replace it directly on the system partition. `adb install -r` will fail
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` if the debug signing key changed (which happens
when building on a new machine or after reinstalling Android Studio).

> **Preserve the signing key:** Copy `%USERPROFILE%\.android\debug.keystore` to the
> same path on any new build machine. If the same keystore is used, `adb install -r`
> continues to work and you can skip the steps below.

If the key has changed (or on any machine where `adb install -r` returns
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`):

```powershell
# 1. Restart adbd as root and remount the system partition read-write
adb root
adb remount

# 2. Replace the priv-app APK and both permissions XML files
adb shell rm -rf /system/priv-app/QbicControl
adb push android-app\QbicControl.apk /system/priv-app/QbicControl/QbicControl.apk
adb shell chmod 644 /system/priv-app/QbicControl/QbicControl.apk
adb push privapp-permissions-qbiccontrol.xml /system/etc/permissions/privapp-permissions-qbiccontrol.xml
adb shell chmod 644 /system/etc/permissions/privapp-permissions-qbiccontrol.xml
adb push qbiccontrol-permissions.xml /system/etc/sysconfig/qbiccontrol-permissions.xml
adb shell chmod 644 /system/etc/sysconfig/qbiccontrol-permissions.xml

# 3. Reboot to apply (the system scans priv-app and permissions on boot)
adb reboot
# Wait ~50 seconds, then:
adb wait-for-device
```

After reboot, re-run the [Device setup](#device-setup--manual-run-once-after-each-apk-install)
steps — runtime permissions (`CAMERA`, `WRITE_SECURE_SETTINGS`, `GET_USAGE_STATS`), the
accessibility service enable, and device admin must be re-granted whenever the APK signature
changes. `SYSTEM_ALERT_WINDOW` and `INJECT_EVENTS` are re-granted automatically from the
permissions XML. `QbicControlService` re-enables the accessibility service automatically on
startup if `WRITE_SECURE_SETTINGS` is already granted.

> **Token:** App data (including the stored auth token in `SharedPreferences`)
> survives a priv-app replacement via `adb push` as long as you do **not** run
> `pm clear` beforehand. If the token was cleared, re-enter the panel's `ANDROID_ID`
> in the Q-SYS plugin Password field to re-commission:
> ```bash
> adb shell settings get secure android_id
> ```

---

## Device setup — manual (run once after each APK install)

> **Skip this section if you ran `commission_panel.ps1`** — it performs all of the steps
> below automatically, including pushing both permissions XML files.

```bash
# 1. Confirm ADB connection
adb devices

# 2. Grant camera permission (required for MJPEG stream and snapshot)
adb shell pm grant au.com.theavitgroup.qbiccontrol android.permission.CAMERA

# 3. Grant secure settings permission (required for privacy LED toggle)
adb shell pm grant au.com.theavitgroup.qbiccontrol android.permission.WRITE_SECURE_SETTINGS

# 4. Grant usage stats access (required for browser foreground detection)
adb shell appops set au.com.theavitgroup.qbiccontrol GET_USAGE_STATS allow

# 5. Enable screen capture accessibility service (required for /screen endpoint)
adb shell settings put secure enabled_accessibility_services \
    au.com.theavitgroup.qbiccontrol/.ScreenCaptureService
adb shell settings put secure accessibility_enabled 1

# 6. Start the service immediately (or reboot — auto-starts via BootReceiver)
adb shell am start-foreground-service -n au.com.theavitgroup.qbiccontrol/.QbicControlService

# 7. Verify both ports are listening
adb shell ss -tlnp | grep -E "9090|9091"
```

> **Note:** Permissions must be granted *after* install. After each APK reinstall,
> always restart the service so it picks up the new APK:
> ```bash
> adb shell am force-stop au.com.theavitgroup.qbiccontrol
> adb shell am start-foreground-service -n au.com.theavitgroup.qbiccontrol/.QbicControlService
> ```

---

## Verify

```bash
# Both ports listening
adb shell ss -tlnp | grep -E "9090|9091"

# WebSocket test (requires wscat: npm install -g wscat)
adb forward tcp:9090 tcp:9090
wscat -c ws://localhost:9090/
> {"cmd":"sensor"}
< {"ok":true,"light":42,"proximity":5}
> {"cmd":"camera"}
< {"ok":true,"streaming":true,"port":9091}
> {"cmd":"camera_led"}
< {"ok":true,"enabled":true}
# Open/close the browser on the panel — you should see unsolicited pushes:
< {"ok":true,"browser":true}
< {"ok":true,"browser":false}
> {"cmd":"launch","package":"com.qbic.smilplayer","url":"http://192.168.1.100:9091/index.html"}
< {"ok":true}

# Camera preview / screen capture
adb forward tcp:9091 tcp:9091
# Open http://localhost:9091/         in a browser for live MJPEG
# Open http://localhost:9091/snapshot in a browser for a single camera frame
# Open http://localhost:9091/screen   in a browser for a PNG of the Android display
```

---

## Command reference

All messages are JSON. All responses: `{"ok":true,...}` or `{"ok":false,"error":"<reason>"}`.

| Command | Request | Response fields | Permission required |
| --- | --- | --- | --- |
| Sensor read (on-demand) | `{"cmd":"sensor"}` | `light`, `proximity` | — |
| Sensor push (unsolicited) | — | `light`, `proximity` | — |
| Browser push (unsolicited) | — | `browser` | `GET_USAGE_STATS` (appops) |
| LED control | `{"cmd":"led","location":"FRONT_SIDE","color":"FF0000"}` | — | — |
| Screen on/off | `{"cmd":"screen","state":"on\|off"}` | — | — |
| Launch app | `{"cmd":"launch","package":"..."}` | — | — |
| Launch app with URL | `{"cmd":"launch","package":"...","url":"..."}` | — | — |
| Home keyevent | `{"cmd":"home"}` | — | `su` (userdebug firmware) |
| Status | `{"cmd":"status"}` | `status`, `version` | — |
| Camera status | `{"cmd":"camera"}` | `streaming`, `port` | `CAMERA` |
| Camera on/off | `{"cmd":"camera","state":"on\|off"}` | `streaming`, `port` | `CAMERA` |
| Privacy LED status | `{"cmd":"camera_led"}` | `enabled` | `WRITE_SECURE_SETTINGS` |
| Privacy LED on/off | `{"cmd":"camera_led","state":"on\|off"}` | `enabled` | `WRITE_SECURE_SETTINGS` |
| Screen capture start | `{"cmd":"screen_capture","state":"on","interval":1,"scale":3}` | `running` | Accessibility service |
| Screen capture stop | `{"cmd":"screen_capture","state":"off"}` | `running` | Accessibility service |
| Screen capture push (unsolicited) | — | `screen` (base64 JPEG at selected scale) | Accessibility service |

LED `location` values: `FRONT`, `SIDE`, `FRONT_SIDE` (default).

`interval` is in seconds (float, minimum 0.2).

---

## Sensor push

`SensorMonitor` registers Android `SensorManager` listeners and broadcasts to all connected
WebSocket clients when a value changes. Broadcasts are throttled to ≥200 ms between sends
to avoid flooding on rapid changes.

```json
{"ok": true, "light": 42, "proximity": 5}
```

| Field | Type | Description |
| --- | --- | --- |
| `light` | integer or null | Ambient light level, 10–255 |
| `proximity` | integer or null | Proximity raw value, 0–150 000; lower = closer |

---

## Browser monitoring

`BrowserMonitor` polls `UsageStatsManager.queryEvents()` every second on a
`HandlerThread` and broadcasts to all connected WebSocket clients when the foreground
app changes. The event window covers the last 10 seconds of `MOVE_TO_FOREGROUND`
events, making detection reliable even under brief system UI interruptions.

```json
{"ok": true, "browser": true}
{"ok": true, "browser": false}
```

| Field | Type | Description |
| --- | --- | --- |
| `browser` | boolean | `true` when `com.qbic.smilplayer` is in the foreground, `false` otherwise |

**Required permission:** `GET_USAGE_STATS` (appops grant — not a standard `pm grant`):

```bash
adb shell appops set au.com.theavitgroup.qbiccontrol GET_USAGE_STATS allow
```

If the permission is not granted the monitor logs a warning and skips polling — all
other features continue to work.

---

## Screen capture push

`ScreenMonitor` runs a background loop that captures the Android display via
`ScreenCaptureService` (AccessibilityService) and broadcasts to all connected WebSocket
clients when the screen content changes.

Enable/disable via the `screen_capture` command:

```json
{"cmd": "screen_capture", "state": "on", "interval": 1, "scale": 3}
{"cmd": "screen_capture", "state": "off"}
```

| Field | Type | Description |
| --- | --- | --- |
| `state` | `"on"` / `"off"` | Start or stop the capture loop |
| `interval` | float (seconds) | Minimum time between pushes; minimum 0.2 s |
| `scale` | integer 1–6 | Divide each dimension by this value before encoding; 1 = native resolution (default 3) |

Frames are compressed as **JPEG quality 60** at the scaled dimensions. The scale can be
changed at any time by re-sending the command with `"state":"on"` — the monitor adopts
the new value immediately without restarting.

The monitor compares a CRC32 hash of each frame and only pushes when the content has
changed — frames that are identical to the last pushed frame are silently dropped.

Pushed event (unsolicited, only when content changes):

```json
{"ok": true, "screen": "<base64-encoded JPEG>"}
```

The Q-SYS plugin receives this push and passes the base64 string directly to an
`IconData`-type control for display as a live panel preview. Because Q-SYS Lua
WebSocket splits large messages across multiple `ws.Data` events, the plugin buffers
incoming data and parses JSON only once a complete message is received.

The polling HTTP endpoint (`GET /screen`) is still available for one-shot captures.

---

## Camera stream

The camera stream is served on port 9091 using Android Camera2 API (front-facing OV8856,
640×480 JPEG). Frames are captured into an `ImageReader` and served over plain HTTP.

| Endpoint | Description |
| --- | --- |
| `GET /` | Live MJPEG stream, ~10 fps (`multipart/x-mixed-replace`) |
| `GET /snapshot` | Single JPEG frame (`image/jpeg`); 503 if no frame ready yet |
| `GET /screen` | Full-resolution PNG of the Android display (`image/png`); 503 if accessibility service not connected |
| `GET /screen?scale=N` | Scaled JPEG (÷N each dimension, N = 1–6); `scale=1` is equivalent to no parameter (returns PNG) |

The Q-SYS plugin polls `/snapshot` every second and only updates the preview control when
the JPEG content changes. Browsers and VLC can consume the live MJPEG stream directly.

### `/screen` — Android display capture

`/screen` uses `AccessibilityService.takeScreenshot()` (API 30+) rather than `screencap`.
`screencap` requires `CAPTURE_VIDEO_OUTPUT`, which is `signature`-only on Rockchip Android 12
firmware and cannot be granted to third-party APKs even as a priv-app.

The accessibility service approach requires no signature permission. `QbicControlService`
enables `ScreenCaptureService` automatically on startup via `WRITE_SECURE_SETTINGS`. No
manual Settings UI interaction is needed.

**Implementation note:** `ScreenshotResult` changed its bitmap accessor method across Android
versions. The app resolves the correct method at runtime via reflection:

| API level | Method | Return type |
| --- | --- | --- |
| 30–31 | `getHardwareBitmap()` | `Bitmap` (hardware-backed) |
| 32–33 | `getHardwareBuffer()` | `HardwareBuffer` |
| 34+ | `getAcquirableBuffer()` | `HardwareBuffer` |

The camera privacy LED is the Android 12 hardware privacy indicator — it lights automatically
when the camera is opened and turns off when it closes. The `camera_led` command controls
this independently via `Settings.Global.camera_mic_icons_disabled`:

- `enabled: true` → LED follows camera hardware state (default Android behaviour)
- `enabled: false` → LED suppressed even while camera is open

---

## Authentication

Disabled by default (`Config.TOKEN = ""`), appropriate for an isolated AV network.
To enable, set `TOKEN` in `Config.kt` and rebuild, then append `?token=<TOKEN>` to the
WebSocket URL in the Q-SYS plugin `runtime.lua`.

---

## Auto-start behaviour

`BootReceiver` starts `QbicControlService` automatically after every reboot.

> **Android restriction:** The app must have been launched at least once manually after
> install for `BootReceiver` to fire after reboots (Android direct-boot restriction).
> Tap the launcher icon once after first install, then reboots will auto-start it.

---

## Troubleshooting

### Ports not listening after install

Run the device setup steps above. Confirm with:

```bash
adb shell ss -tlnp | grep -E "9090|9091"
```

### Camera streaming stays false

1. Confirm `CAMERA` permission is granted:

   ```bash
   adb shell pm list permissions -g au.com.theavitgroup.qbiccontrol | grep CAMERA
   ```

2. Restart service after granting:

   ```bash
   adb shell am force-stop au.com.theavitgroup.qbiccontrol
   adb shell am start-foreground-service -n au.com.theavitgroup.qbiccontrol/.QbicControlService
   ```

3. Check logcat:

   ```bash
   adb logcat -s CameraStreamServer:* -v time
   ```

### Privacy LED toggle returns error

`WRITE_SECURE_SETTINGS` has not been granted. Run:

```bash
adb shell pm grant au.com.theavitgroup.qbiccontrol android.permission.WRITE_SECURE_SETTINGS
```

Then restart the service.

### Browser status not updating / always shows Closed

1. Confirm `GET_USAGE_STATS` has been granted:

   ```bash
   adb shell appops get au.com.theavitgroup.qbiccontrol GET_USAGE_STATS
   ```

   Expected output: `GET_USAGE_STATS: allow`

2. If not granted, run:

   ```bash
   adb shell appops set au.com.theavitgroup.qbiccontrol GET_USAGE_STATS allow
   ```

3. Restart the service:

   ```bash
   adb shell am force-stop au.com.theavitgroup.qbiccontrol
   adb shell am start-foreground-service -n au.com.theavitgroup.qbiccontrol/.QbicControlService
   ```

4. Check logcat:

   ```bash
   adb logcat -s BrowserMonitor:* -v time
   ```

### Sensor values not updating

`SensorMonitor` fires only on value change. Cover/uncover the sensor to confirm it is
working. Rapid changes within the 200 ms throttle window will be dropped on the send side.

### App not auto-starting after reboot

Tap the launcher icon once on the panel, then reboot. See auto-start note above.

### `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

The app is installed as a system priv-app and the signing key has changed. Use the
[Updating the priv-app](#updating-the-priv-app-after-rebuild) steps above.

The fastest way to avoid this on future machines: copy
`%USERPROFILE%\.android\debug.keystore` from the original build machine.

### `adb uninstall` fails with `DELETE_FAILED_DEVICE_POLICY_MANAGER`

The app is registered as a device admin, which Android prevents being uninstalled
through normal means. Options:

**Option A — remove via the panel UI (safest, preserves app data):**

Settings → Security → Device admins → QbicControl → Deactivate → then `adb uninstall`

**Option B — clear app data first (wipes stored token), then uninstall:**

```bash
adb shell "su 0 pm clear au.com.theavitgroup.qbiccontrol"
adb uninstall au.com.theavitgroup.qbiccontrol
```

Note: if the app is a priv-app, `adb uninstall` removes the user-space override but the
system copy remains. Use the priv-app replacement steps instead.

**Option C — nuclear (use only when the above fail and the app is a priv-app):**

If device admin persists even after `pm clear`, the registration is stuck in the system
device policy file. Delete it and reboot — this clears ALL device admin registrations
on the panel:

```bash
adb shell "su 0 sh -c 'rm /data/system/device_policies.xml'"
adb reboot
# After reboot, run the full device setup steps to restore permissions and device admin
```

### `/screen` returns 503

The `ScreenCaptureService` accessibility service is not connected. Check:

```bash
adb shell settings get secure enabled_accessibility_services
# Expected: au.com.theavitgroup.qbiccontrol/.ScreenCaptureService
```

If missing, re-enable it:

```bash
adb shell settings put secure enabled_accessibility_services \
    au.com.theavitgroup.qbiccontrol/.ScreenCaptureService
adb shell settings put secure accessibility_enabled 1
adb shell am force-stop au.com.theavitgroup.qbiccontrol
adb shell am start-foreground-service -n au.com.theavitgroup.qbiccontrol/.QbicControlService
```

`QbicControlService` also re-enables the service automatically on startup via
`WRITE_SECURE_SETTINGS`. Check that permission is still granted:

```bash
adb shell pm list permissions -g au.com.theavitgroup.qbiccontrol | grep WRITE_SECURE
```

Check logcat for confirmation: `adb logcat -s ScreenCaptureService QbicControlService`

### WebSocket not connecting from Q-SYS

1. Confirm service is running: `adb shell ss -tlnp | grep 9090`
2. Confirm panel IP is reachable from Q-SYS Core
3. Check logcat: `adb logcat -s QbicWS CommandHandler SensorMonitor -v time`
