package e7.gearscan.value

import java.math.BigDecimal
import java.math.MathContext

@JvmInline
value class Chance(
    val p: Double,
) {
    // two significant digits; 0% and 100% only when certain, so a slim chance never reads as none
    val text: String
        get() {
            val pct = p * 100
            return when {
                !(p > 0) -> "0%"
                p >= 1 - 1e-10 -> "100%"
                pct >= 10 -> "${minOf(Math.round(pct), 99L)}%"
                pct < 0.01 -> "<0.01%"
                else -> BigDecimal(pct).round(MathContext(2)).stripTrailingZeros().toPlainString() + "%"
            }
        }
}
