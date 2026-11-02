package app.vela.tv.player

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as MediaUi
import app.vela.tv.design.VelaPaletteValues
import app.vela.tv.design.VelaPalettes
import app.vela.tv.design.VelaSize
import app.vela.tv.design.VelaSpace
import app.vela.tv.design.VelaType
import kotlin.math.roundToInt

/** Visual adapter only. Media3 retains its views, focus navigation, listeners and enabled states. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayerChrome(
    private val context: Context,
    highContrast: Boolean,
    private val reducedMotion: Boolean,
) {
    private val colors: VelaPaletteValues = VelaPalettes.overlay(highContrast)
    private val density = context.resources.displayMetrics.density
    private val focused = intArrayOf(android.R.attr.state_focused)
    private val pressed = intArrayOf(android.R.attr.state_pressed)
    private val disabled = intArrayOf(-android.R.attr.state_enabled)
    private val resting = intArrayOf()

    fun applyTo(player: PlayerView) {
        // No controller replacement, key interception, focus listener or player listener.
        player.setControllerAnimationEnabled(!reducedMotion && !colors.highContrast)
        val controller = player.findViewById<PlayerControlView>(MediaUi.id.exo_controller) ?: return
        (controller.layoutParams as? ViewGroup.MarginLayoutParams)?.let { margins ->
            margins.setMargins(px(VelaSpace.SafeHorizontal), px(VelaSpace.SafeVertical),
                px(VelaSpace.SafeHorizontal), px(VelaSpace.SafeVertical))
            controller.layoutParams = margins
        }
        // The native video surface and subtitles are deliberately outside this adapter.
        controller.findViewById<View>(MediaUi.id.exo_controls_background)?.setBackgroundColor(
            if (colors.highContrast) Color.TRANSPARENT else
                (colors.canvas.toInt() and 0x00FFFFFF) or (153 shl 24))
        controller.findViewById<View>(MediaUi.id.exo_center_controls)?.background = panel()
        controller.findViewById<View>(MediaUi.id.exo_bottom_bar)?.background = panel()
        controller.findViewById<View>(MediaUi.id.exo_extra_controls_scroll_view)?.background = panel()

        for (id in intArrayOf(MediaUi.id.exo_play_pause, MediaUi.id.exo_prev, MediaUi.id.exo_next,
            MediaUi.id.exo_rew, MediaUi.id.exo_ffwd, MediaUi.id.exo_subtitle, MediaUi.id.exo_settings,
            MediaUi.id.exo_audio_track, MediaUi.id.exo_playback_speed, MediaUi.id.exo_repeat_toggle,
            MediaUi.id.exo_shuffle, MediaUi.id.exo_overflow_show, MediaUi.id.exo_overflow_hide,
            MediaUi.id.exo_fullscreen, MediaUi.id.exo_minimal_fullscreen, MediaUi.id.exo_vr)) {
            controller.findViewById<View>(id)?.let { button ->
                styleControl(button)
                (button as? ImageView)?.imageTintList = controlInk()
            }
        }
        // Media3 draws the seek arrow in these TextViews' backgrounds, with a live seconds label.
        // Keep that drawable and its layout/padding; tint it independently of our stateful fill.
        for (id in intArrayOf(MediaUi.id.exo_rew_with_amount, MediaUi.id.exo_ffwd_with_amount)) {
            controller.findViewById<TextView>(id)?.let { amount ->
                val arrow = amount.background?.mutate()
                styleControl(amount)
                if (arrow != null) {
                    arrow.setTintList(controlInk())
                    val padding = intArrayOf(amount.paddingLeft, amount.paddingTop, amount.paddingRight, amount.paddingBottom)
                    amount.background = LayerDrawable(arrayOf(amount.background, arrow))
                    amount.setPadding(padding[0], padding[1], padding[2], padding[3])
                }
                styleText(amount, VelaType.caption)
                amount.setTextColor(controlInk())
                amount.fontFeatureSettings = "tnum"
            }
        }
        controller.findViewById<ViewGroup>(MediaUi.id.exo_time)?.let { time ->
            for (index in 0 until time.childCount) {
                (time.getChildAt(index) as? TextView)?.let { label ->
                    styleText(label, VelaType.numeric)
                    label.fontFeatureSettings = "tnum"
                    label.setTextColor(if (label.id == MediaUi.id.exo_position) colors.ink.toInt() else colors.inkMuted.toInt())
                }
            }
        }
        controller.findViewById<DefaultTimeBar>(MediaUi.id.exo_progress)?.apply {
            setPlayedColor(colors.accent.toInt())
            setScrubberColor(colors.accent.toInt())
            setBufferedColor(colors.inkMuted.toInt())
            setUnplayedColor(colors.lineStrong.toInt())
            background = StateListDrawable().apply {
                addState(focused, shape(colors.surfaceFocused, radius = 0f, stroke = ringWidth(), line = colors.focusRing))
                addState(resting, shape(colors.surface, radius = 0f, stroke = if (colors.highContrast) px(1f) else 0))
            }
            defaultFocusHighlightEnabled = false
        }
        player.findViewById<ProgressBar>(MediaUi.id.exo_buffering)?.indeterminateTintList = ColorStateList.valueOf(colors.accent.toInt())
        // This remains Media3's error view; the activity still owns all error reporting and dialogs.
        player.findViewById<TextView>(MediaUi.id.exo_error_message)?.apply {
            styleText(this, VelaType.body)
            setTextColor(colors.ink.toInt())
            background = panel()
            setPadding(px(VelaSpace.M), px(VelaSpace.S), px(VelaSpace.M), px(VelaSpace.S))
            layoutParams = layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        }
    }

    fun dialog(title: String, message: String, error: Boolean = false): AlertDialog.Builder {
        // A public custom-title API avoids private framework alertTitle IDs. The message stays in
        // AlertDialog's native scroll container and all action/cancel callbacks stay at the caller.
        val heading = TextView(context).apply {
            text = title
            styleText(this, VelaType.title)
            setTextColor(colors.ink.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            isAccessibilityHeading = true
            setPadding(px(VelaSpace.L), px(VelaSpace.L), px(VelaSpace.L), px(VelaSpace.S))
            if (error) {
                val icon = context.getDrawable(android.R.drawable.ic_dialog_alert)?.mutate()
                icon?.setTint(colors.danger.toInt())
                icon?.setBounds(0, 0, px(VelaSize.GlyphList), px(VelaSize.GlyphList))
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = px(VelaSpace.S)
            }
        }
        return AlertDialog.Builder(context).setTitle(title).setCustomTitle(heading).setMessage(message)
    }

    fun show(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(panel())
            window.setElevation(0f)
            val available = context.resources.displayMetrics.widthPixels - 2 * px(VelaSpace.SafeHorizontal)
            window.setLayout(minOf(px(VelaSize.DialogWidth), available.coerceAtLeast(1)), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            styleText(this, VelaType.body)
            setTextColor(colors.inkMuted.toInt())
        }
        for (which in intArrayOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                styleControl(this)
                styleText(this, VelaType.label)
                setTextColor(controlInk())
                isAllCaps = false
                setSingleLine(false)
                maxLines = Int.MAX_VALUE
                ellipsize = null
                setPadding(px(VelaSpace.M), px(VelaSpace.S), px(VelaSpace.M), px(VelaSpace.S))
                layoutParams = layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            }
        }
        // Retry and Play next never receive initial focus. Native Back/cancel handling is unchanged.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun styleText(view: TextView, style: TextStyle) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        view.setLineHeight(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.lineHeight.value,
            context.resources.displayMetrics).roundToInt())
        view.typeface = Typeface.create(Typeface.SANS_SERIF, style.fontWeight?.weight ?: 400, false)
    }

    private fun styleControl(view: View) {
        val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        view.backgroundTintList = null
        view.background = StateListDrawable().apply {
            addState(disabled, shape(colors.surfaceRaised))
            addState(pressed, shape(colors.accentStrong, stroke = ringWidth(), line = colors.focusRing))
            addState(focused, shape(colors.accent, stroke = ringWidth(), line = colors.focusRing))
            addState(resting, shape(colors.surfaceRaised))
        }
        view.setPadding(padding[0], padding[1], padding[2], padding[3])
        view.foreground = null // Remove the old ripple; state fill/ring covers focus and press.
        view.stateListAnimator = null
        view.elevation = 0f
        view.translationZ = 0f
        view.defaultFocusHighlightEnabled = false
        view.minimumHeight = maxOf(view.minimumHeight, px(VelaSize.MinTarget))
        view.minimumWidth = maxOf(view.minimumWidth, px(VelaSize.MinTarget))
    }

    private fun controlInk() = ColorStateList(arrayOf(disabled, pressed, focused, resting),
        intArrayOf(colors.inkSubtle.toInt(), colors.onAccent.toInt(), colors.onAccent.toInt(), colors.ink.toInt()))

    private fun panel() = shape(colors.surface, radius = 20f)

    // Native layout managers clip child overhang. Keep the ring inside the existing hit target;
    // changing parent clipping/geometry would interfere with Media3's overflow/hide animations.
    private fun shape(fill: Long, radius: Float = 999f,
        stroke: Int = if (colors.highContrast) px(1f) else 0, line: Long = colors.lineStrong) = GradientDrawable().apply {
        cornerRadius = radius * density
        setColor(fill.toInt())
        if (stroke > 0) setStroke(stroke, line.toInt())
    }

    private fun ringWidth() = px(if (colors.highContrast) VelaSize.HighContrastRing else VelaSize.ControlRing)
    private fun px(dp: Dp) = px(dp.value)
    private fun px(dp: Float) = (dp * density).roundToInt()
}
