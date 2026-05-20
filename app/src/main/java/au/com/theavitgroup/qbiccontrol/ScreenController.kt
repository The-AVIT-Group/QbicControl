package au.com.theavitgroup.qbiccontrol

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log

class ScreenController(private val context: Context) {

  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

  @Suppress("DEPRECATION")
  private var wakeLock: PowerManager.WakeLock? = null

  /** Wakes the screen and holds it on until [sleep] or [release] is called. */
  fun wakeUp() {
    if (wakeLock?.isHeld == true) return
    @Suppress("DEPRECATION")
    wakeLock = powerManager.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "QbicControl:screen_on",
    ).also { it.acquire() }
    Log.d(TAG, "Screen woken, wake lock held")
  }

  /**
   * Puts the screen to sleep via DevicePolicyManager.lockNow().
   *
   * Requires device admin to be activated once after install:
   *   adb shell dpm set-active-admin \
   *       au.com.theavitgroup.qbiccontrol/.DeviceAdminReceiver
   *
   * Without device admin this logs a warning and does nothing — it will not
   * crash the service.
   */
  fun sleep() {
    releaseWakeLock()
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, DeviceAdminReceiver::class.java)
    if (dpm.isAdminActive(admin)) {
      dpm.lockNow()
      Log.d(TAG, "Screen locked via DevicePolicyManager")
    } else {
      Log.w(TAG, "Screen sleep skipped — device admin not active. " +
        "Run: adb shell dpm set-active-admin " +
        "au.com.theavitgroup.qbiccontrol/.DeviceAdminReceiver")
    }
  }

  fun release() = releaseWakeLock()

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  companion object {
    private const val TAG = "ScreenController"
  }
}
