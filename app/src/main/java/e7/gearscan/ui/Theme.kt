package e7.gearscan.ui

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

object C {
    val ink = Color(0xFF1D2149)
    val muted = Color(0xFF5B5A72)
    val card = Color(0xFFFBF7EF)
    val field = Color(0xFFFFFDF6)
    val paper = Color(0xFFF2E9D8)
    val pink = Color(0xFFF0559B)
    val deepPink = Color(0xFFC2367A)
    val lightPink = Color(0xFFFDE3EF)
    val blue = Color(0xFF2B3F9C)
    val line = Color(0xFFE6DCCA)
    val red = Color(0xFFD23F4F)
    val purple = Color(0xFF7A4FC2)
    val slate = Color(0xFF3A3D5C)
    val ghostBg = Color(0xFFEEF1FA)
    val ghostStripe = Color(0xFFDFE4F6)
    val faint = Color(0xFFB9B3C6)
    val dash = Color(0xFF8F8CA3)
    val stripeA = Color(0xFFE9DCC6)
    val stripeB = Color(0xFFF6EFE2)
    val scrim = Color(0x8C1D2149)
}

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
