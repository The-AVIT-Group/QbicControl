package au.com.theavitgroup.qbiccontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the WebSocket service automatically after the panel reboots. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      // Android 14+ blocks camera-type FGS from background (BOOT_COMPLETED) for non-system apps.
      // BootActivity presents a visible window first, satisfying the eligible-state requirement.
      context.startActivity(
        Intent(context, BootActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }
  }
}
