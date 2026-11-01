package e7.gearscan.overlay

import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import e7.gearscan.R
import e7.gearscan.data.ScanStep
import e7.gearscan.ui.LightPalette

/** The scan's status over the game, which hides the status bar and so the progress notification. Plain views: the scan service has no Compose lifecycle. */
class ScanPill(private val ctx: Context, private val onTap: PendingIntent) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var dot: View? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun show(step: ScanStep) {
        if (!Settings.canDrawOverlays(ctx) || OverlayState.appInFront) { hide(); return }
        if (root == null) create()
        if (root == null) return
        label!!.text = step.pill
        (dot!!.background as GradientDrawable).setColor(when (step) {
            ScanStep.LOBBY -> LightPalette.blue
            ScanStep.LATE -> LightPalette.red
            else -> LightPalette.pink
        }.toArgb())
        // windows added with WRAP_CONTENT can be measured at dialog width, so the width is set from the content
        val room = ctx.resources.displayMetrics.widthPixels - dp(32f).toInt()
        root!!.measure(View.MeasureSpec.makeMeasureSpec(room, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        params!!.width = root!!.measuredWidth
        params!!.height = root!!.measuredHeight
        runCatching { wm.updateViewLayout(root, params) }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; dot = null; label = null; params = null
    }

    private fun create() {
        val p = LightPalette
        val shadow = dp(2.5f).toInt()
        val radius = dp(999f)
        val card = GradientDrawable().apply { cornerRadius = radius; setColor(p.card.toArgb()); setStroke(dp(2f).toInt(), p.edge.toArgb()) }
        val back = GradientDrawable().apply { cornerRadius = radius; setColor(p.edge.toArgb()) }
        val bg = LayerDrawable(arrayOf(back, card)).apply {
            setLayerInset(0, shadow, shadow, 0, 0)
            setLayerInset(1, 0, 0, shadow, shadow)
        }
        val d = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.pink.toArgb()) }
            layoutParams = LinearLayout.LayoutParams(dp(9f).toInt(), dp(9f).toInt()).apply { marginEnd = dp(8f).toInt() }
        }
        val t = TextView(ctx).apply {
            setTextColor(p.ink.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = ResourcesCompat.getFont(ctx, R.font.zenmarugothic_bold) ?: Typeface.DEFAULT_BOLD
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(14f).toInt(), dp(7f).toInt(), dp(14f).toInt() + shadow, dp(7f).toInt() + shadow)
            addView(d); addView(t)
            setOnClickListener { runCatching { onTap.send() } }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(10f).toInt() }
        if (runCatching { wm.addView(r, lp) }.isFailure) return
        root = r; dot = d; label = t; params = lp
    }
}
