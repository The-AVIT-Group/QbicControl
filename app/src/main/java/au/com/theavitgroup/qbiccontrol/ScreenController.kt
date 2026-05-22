package au.com.theavitgroup.qbiccontrol

import android.content.Context
import android.os.PowerManager
import android.util.Log

class ScreenController(private val context: Context) {

  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

  @Suppress("DEPRECATION")
  private var wakeLock: PowerManager.WakeLock? = null

  /**
   * Wakes the screen and holds it at full brightness until [sleep] or [release] is called.
   * ACQUIRE_CAUSES_WAKEUP turns the display on even if it is currently off or dimmed by
   * the panel firmware's native sleep.
   */
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
   * Releases the screen wake lock so the panel's native display management resumes —
   * the display will dim and eventually sleep on its own schedule.
   *
   * Does NOT call DevicePolicyManager.lockNow(): that creates a keyguard that cannot
   * be dismissed programmatically, leaving the panel permanently blank until rebooted.
   * It also breaks the panel firmware's own brightness-based sleep/wake cycle.
   */
  fun sleep() {
    releaseWakeLock()
    Log.d(TAG, "Screen wake lock released — display follows native power management")
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
