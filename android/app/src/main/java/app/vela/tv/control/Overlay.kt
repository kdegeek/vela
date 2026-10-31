package app.vela.tv.control

import android.accessibilityservice.AccessibilityService
import android.graphics.Typeface
import app.vela.tv.VelaApp
import app.vela.tv.design.VelaPalettes
import app.vela.tv.design.VelaPaletteValues
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
        // Read on each presentation, including an already visible panel: appearance never leaks
        // through from another app, and changing HC takes effect on the next agent update.
        val colors = VelaPalettes.overlay((service.application as VelaApp).settings.settings.value.highContrast)
        p.background = background(colors, prompt != null)
        p.removeAllViews()
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.view.View(service).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors.agent.toInt()) }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(text(if (prompt == null) "Vela" else "Vela needs your OK", colors.ink.toInt(),
                if (prompt == null) 16f else 18f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAccessibilityHeading = true
            })
        }
        p.addView(header)
        for (l in lines.takeLast(3)) p.addView(text(l, colors.inkMuted.toInt(), 16f))
        if (prompt != null) {
            p.addView(text(prompt, colors.ink.toInt(), 20f))
            p.addView(text("OK to allow · Back to cancel", colors.accent.toInt(), 16f))
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
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val lp = WindowManager.LayoutParams(
            minOf(dp(520), (service.resources.displayMetrics.widthPixels - dp(96)).coerceAtLeast(dp(44))), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(48)
            y = dp(27)
        }
        wm.addView(p, lp)
        panel = p
        return p
    }

    private fun background(colors: VelaPaletteValues, prompt: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(if (colors.highContrast) colors.surface.toInt() else ((colors.surface and 0x00FFFFFF) or 0xF2000000).toInt())
        setStroke(dp(if (prompt) 2 else 1), (if (prompt) colors.agent else if (colors.highContrast) colors.lineStrong else colors.line).toInt())
    }

    private fun text(s: String, color: Int, size: Float) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(4), 0, dp(4))
        maxLines = 3
    }
}
