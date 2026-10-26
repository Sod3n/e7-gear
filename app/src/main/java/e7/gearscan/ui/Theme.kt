package e7.gearscan.ui

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import e7.gearscan.R

/** Every colour the app paints; both palettes set every role (ThemeRulesTest keeps raw colours out of the screens). */
@Immutable
data class Palette(
    val ink: Color, val muted: Color, val faint: Color, val dash: Color, val slate: Color,
    val paper: Color, val paperTop: Color, val paperBottom: Color, val card: Color, val field: Color, val line: Color,
    val edge: Color, val shadow: Color, val onAccent: Color, val scrim: Color, val onTile: Color, val iconBg: Color,
    val pink: Color, val deepPink: Color, val lightPink: Color, val halftoneDot: Color, val hatchBand: Color, val seigaiha: Color,
    val blue: Color, val red: Color, val purple: Color,
    val ghostBg: Color, val ghostStripe: Color, val stripeA: Color, val stripeB: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    ink = Color(0xFF1D2149), muted = Color(0xFF5B5A72), faint = Color(0xFFB9B3C6), dash = Color(0xFF8F8CA3), slate = Color(0xFF3A3D5C),
    paper = Color(0xFFF2E9D8), paperTop = Color(0xFFF3E9D6), paperBottom = Color(0xFFECDFC6), card = Color(0xFFFBF7EF),
    field = Color(0xFFFFFDF6), line = Color(0xFFE6DCCA),
    edge = Color(0xFF1D2149), shadow = Color(0xFF1D2149), onAccent = Color(0xFF1D2149), scrim = Color(0x8C1D2149),
    onTile = Color(0xFFFBF7EF), iconBg = Color(0xFF1D2149),
    pink = Color(0xFFF0559B), deepPink = Color(0xFFC2367A), lightPink = Color(0xFFFDE3EF), halftoneDot = Color(0xFFF5B3D1),
    hatchBand = Color(0xFFF9D0E3), seigaiha = Color(0xFFFF4FA8),
    blue = Color(0xFF2B3F9C), red = Color(0xFFD23F4F), purple = Color(0xFF7A4FC2),
    ghostBg = Color(0xFFEEF1FA), ghostStripe = Color(0xFFDFE4F6), stripeA = Color(0xFFE9DCC6), stripeB = Color(0xFFF6EFE2),
    dark = false,
)

// The RTA helper's dark palette (webapps/draft/static/base.css): cream ink on night-navy paper, pink stays bright.
// Gear tiles (slate/onTile) and game icon circles (iconBg) stay dark in both, like the game's own.
val DarkPalette = Palette(
    ink = Color(0xFFF2E8D5), muted = Color(0xFFABA8C6), faint = Color(0xFF555A80), dash = Color(0xFF6F7398), slate = Color(0xFF2E3358),
    paper = Color(0xFF141831), paperTop = Color(0xFF161A35), paperBottom = Color(0xFF10132A), card = Color(0xFF1D2242),
    field = Color(0xFF262C50), line = Color(0xFF353B66),
    edge = Color(0xFFCFC6D9), shadow = Color(0xFF05060E), onAccent = Color(0xFF1D2149), scrim = Color(0xB303040C),
    onTile = Color(0xFFF2E8D5), iconBg = Color(0xFF0B0E1E),
    pink = Color(0xFFF0559B), deepPink = Color(0xFFFF8AC2), lightPink = Color(0xFF3A2147), halftoneDot = Color(0xFF7A3466),
    hatchBand = Color(0xFF4A2558), seigaiha = Color(0xFFFF4FA8),
    blue = Color(0xFF93A9FF), red = Color(0xFFFF7A86), purple = Color(0xFFB79BFF),
    ghostBg = Color(0xFF222850), ghostStripe = Color(0xFF2A3160), stripeA = Color(0xFF262B4C), stripeB = Color(0xFF1F2444),
    dark = true,
)

/** The saved choice (null = follow the phone) and the phone's own setting, both observed by every screen and the overlay. */
object Themes {
    private const val PREFS = "theme"
    private const val KEY = "dark"
    var choice by mutableStateOf<Boolean?>(null)
        private set
    var systemDark by mutableStateOf(false)
    val dark get() = choice ?: systemDark

    fun load(context: Context) {
        systemDark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        choice = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    }

    fun set(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, dark).apply()
        choice = dark
    }
}

object C {
    val p get() = if (Themes.dark) DarkPalette else LightPalette
    val ink get() = p.ink
    val muted get() = p.muted
    val faint get() = p.faint
    val dash get() = p.dash
    val slate get() = p.slate
    val paper get() = p.paper
    val card get() = p.card
    val field get() = p.field
    val line get() = p.line
    val edge get() = p.edge
    val shadow get() = p.shadow
    val onAccent get() = p.onAccent
    val onTile get() = p.onTile
    val iconBg get() = p.iconBg
    val scrim get() = p.scrim
    val pink get() = p.pink
    val deepPink get() = p.deepPink
    val lightPink get() = p.lightPink
    val blue get() = p.blue
    val red get() = p.red
    val purple get() = p.purple
    val ghostBg get() = p.ghostBg
    val ghostStripe get() = p.ghostStripe
    val stripeA get() = p.stripeA
    val stripeB get() = p.stripeB
}

/** Text and ticks on a fill: dark on the bright pink in both themes, ink elsewhere. */
fun textOn(fill: Color) = if (fill == C.pink) C.onAccent else C.ink

val Dela = FontFamily(Font(R.font.delagothicone_regular))
val Zen = FontFamily(
    Font(R.font.zenmarugothic_regular, FontWeight.Normal),
    Font(R.font.zenmarugothic_medium, FontWeight.Medium),
    Font(R.font.zenmarugothic_bold, FontWeight.Bold),
    Font(R.font.zenmarugothic_black, FontWeight.Black),
)

fun dela(size: TextUnit, color: Color = C.ink) = TextStyle(fontFamily = Dela, fontSize = size, color = color,
    lineHeight = size * 1.15)

fun zen(size: TextUnit, weight: FontWeight = FontWeight.Bold, color: Color = C.ink) =
    TextStyle(fontFamily = Zen, fontWeight = weight, fontSize = size, color = color, lineHeight = size * 1.4)

/** Hard offset shadow; a zero blur radius would disable the shadow entirely. */
fun TextStyle.pinkShadow(px: Float = 5f) = copy(shadow = Shadow(C.pink, Offset(px, px), 0.01f))

val TitleStyle get() = dela(22.sp).pinkShadow()
