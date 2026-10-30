package app.vela.tv.control

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small panel drawn over whatever app is on screen: what the agent is doing, and "may I?" prompts. It never takes
 * focus or input, so the app underneath keeps working; the remote answers prompts through [HandsService.onKeyEvent].
 */
class Overlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private val hideLater = Runnable { hide() }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    /** Shows [lines] (most recent last); [prompt] highlights a question for the owner. Hides itself after [hideAfterMs] if set. */
    fun show(lines: List<String>, prompt: String? = null, hideAfterMs: Long? = null) = main.post {
        val p = panel ?: create()
        p.removeAllViews()
        for (l in lines.takeLast(4)) p.addView(text(l, 0xCCF6F1E7.toInt(), 15f))
        if (prompt != null) {
            p.addView(text(prompt, 0xFFFFFFFF.toInt(), 18f))
            p.addView(text("OK to allow · Back to cancel", 0xFFF0C46A.toInt(), 15f))
        }
        main.removeCallbacks(hideLater)
        hideAfterMs?.let { main.postDelayed(hideLater, it) }
    }

    fun hide() = main.post {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun create(): LinearLayout {
        val p = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xE60E1024.toInt())
                setStroke(dp(1), 0x33F6F1E7)
            }
        }
        val lp = WindowManager.LayoutParams(
            dp(520), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(32)
            y = dp(32)
        }
        wm.addView(p, lp)
        panel = p
        return p
    }

    private fun text(s: String, color: Int, size: Float) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(3), 0, dp(3))
        maxLines = 3
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
}
