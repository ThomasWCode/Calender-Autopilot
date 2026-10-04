package com.thomaswcode.calendareventtimers.outlook

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.thomaswcode.calendareventtimers.util.ScanLog

/**
 * A strip over the status bar saying what the scan is doing, with a Stop button. It keeps the
 * screen on while it shows, and sits where the automation never taps. Accessibility overlays need
 * no extra permission.
 */
class ScanOverlay(private val service: AccessibilityService, private val onStop: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var label: TextView? = null

    /** Call on the main thread. */
    @SuppressLint("SetTextI18n") // English-only app
    fun show(text: String) {
        if (root != null) return
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val height = windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars()).top.coerceAtLeast(dp(32))
        val width = service.resources.displayMetrics.widthPixels

        val message = TextView(service).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Keep clear of the centred camera cut-out.
            maxWidth = (width * 0.42).toInt()
        }
        val stop = TextView(service).apply {
            this.text = "STOP"
            setTextColor(0xFFFFB4AB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { onStop() }
        }
        val strip = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xF0202124.toInt())
            setPadding(dp(16), 0, 0, 0)
            // The message keeps to the left of the centred camera cut-out (its maxWidth), STOP to the right.
            addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(View(service), LinearLayout.LayoutParams(0, 0, 1f))
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        message.gravity = Gravity.CENTER_VERTICAL
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        try {
            windowManager.addView(strip, params)
            root = strip
            label = message
        } catch (e: Exception) {
            ScanLog.w("Couldn't show the scan overlay: ${e.message}")
        }
    }

    /** Safe from any thread. */
    fun post(text: String) {
        main.post { label?.text = text }
    }

    /** Call on the main thread. */
    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        label = null
    }
}
