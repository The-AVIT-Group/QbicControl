package au.com.theavitgroup.qbiccontrol

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

class BacklightMonitor(
    private val onChange: (Int) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastValue: Int = -1
    @Volatile private var running: Boolean = false

    fun start() {
        running = true
        thread = HandlerThread("BacklightMonitor").also { it.start() }
        handler = Handler(thread!!.looper)
        scheduleNext()
        Log.i(TAG, "Started — polling $ACTUAL_PATH")
    }

    fun stop() {
        running = false
        handler = null
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "Stopped")
    }

    private fun scheduleNext() {
        handler?.postDelayed({
            if (running) {
                poll()
                scheduleNext()
            }
        }, POLL_INTERVAL_MS)
    }

    private fun poll() {
        val actual = readInt(ACTUAL_PATH) ?: return
        val max    = readInt(MAX_PATH) ?: return
        if (max == 0) return
        val pct = (actual * 100 / max).coerceIn(0, 100)
        if (pct != lastValue) {
            lastValue = pct
            Log.d(TAG, "Backlight → $pct%")
            onChange(pct)
        }
    }

    private fun readInt(path: String): Int? = try {
        File(path).readText().trim().toInt()
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read $path: ${e.message}")
        null
    }

    companion object {
        private const val TAG              = "BacklightMonitor"
        private const val ACTUAL_PATH      = "/sys/class/backlight/backlight/actual_brightness"
        private const val MAX_PATH         = "/sys/class/backlight/backlight/max_brightness"
        private const val POLL_INTERVAL_MS = 500L
    }
}
