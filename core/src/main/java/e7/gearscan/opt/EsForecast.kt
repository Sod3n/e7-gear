package e7.gearscan.opt

import e7.gearscan.calc.Reforge
import e7.gearscan.data.*
import org.json.JSONObject
import kotlin.math.floor

/** The game's Equipment Score now and at +15: substats use the game's weights, which GearScore's defaults equal. */
object EsForecast {
    const val VERSION = "es-v1"

    // itemenhance.exp: cumulative enhance XP to reach +1..+15, times equip_item.xp and itemgrade.need_xp_rate
    private val CUMULATIVE =
        doubleArrayOf(1.0, 2.25, 3.75, 5.75, 8.75, 12.75, 18.25, 25.25, 33.75, 44.25, 57.75, 74.75, 95.75, 122.25, 156.25)

    private fun xp(level: Int) = if (level >= 88) 400.0 else 350.0

    private fun rate(rank: String) = if (rank == "Heroic") 1.35 else 1.5

    private fun cumulative(e: Int) = if (e <= 0) 0.0 else CUMULATIVE[e.coerceAtMost(15) - 1]

    private val FULL = CUMULATIVE[14] * xp(85) * rate("Epic")

    /** Gold per XP: itemgrade.enchant_price (1 for Epic and Heroic) x sell_item_value 19, x0.6 when the XP comes from enhancement stones. */
    const val GOLD_PER_XP = 19 * 0.6

    /** Gold for one Epic Lv85 +0 -> +15 at the stone price. */
    val FULL_GOLD = FULL * GOLD_PER_XP

    /** Lowest and highest ES a +15 piece can have: weakest/strongest roll in all 9 roll slots, main at +15, reforge gains on top. */
    val esRange: IntRange by lazy {
        val w = GearScore.weights
        val lo = GearStat.entries.minOf { Finish.range(it, 85).first * w.getValue(it) }
        val hi =
            GearStat.entries.maxOf {
                maxOf(Finish.maximumRoll(it, 88).toDouble(), Finish.maximumRoll(it, 85) + Reforge.gain(it, 6) / 6) *
                    w.getValue(it)
            }
        val mains = GearStat.entries.map { Reforge.main(it, 0.0) * InventoryOrder.mainWeight(it) }.filter { it > 0 }
        (
            kotlin.math
                .floor(
                    (mains.minOrNull() ?: 20.0) * .9 + 9 * lo,
                ).toInt()
        )..(kotlin.math.ceil((mains.maxOrNull() ?: 27.0) + 9 * hi).toInt())
    }

    fun goldText(gold: Double): String =
        when {
            gold >= 1e9 -> String.format(java.util.Locale.US, "%.1fB", gold / 1e9)
            gold >= 1e6 -> String.format(java.util.Locale.US, "%.1fM", gold / 1e6)
            gold >= 1e3 -> "${(gold / 1e3).toInt()}k"
            else -> gold.toInt().toString()
        }

    /** Enhance cost from the piece's level to +15, as a share of one Epic Lv85 going +0 to +15. */
    fun finishShare(item: Item): Double =
        if (item.enhance >= 15) 0.0 else (cumulative(15) - cumulative(item.enhance)) * xp(item.level) * rate(item.rank) / FULL

    data class Es(
        val now: Int,
        val bar: Int?,
        val chance: Double,
        val typical: Double?,
        val best: Double?,
        val possible: Boolean,
        val supported: Boolean,
        val finishShare: Double,
    ) {
        val reached get() = bar != null && now >= bar

        fun worthRolling(minimumPercent: Double) = supported && bar != null && chance * 100 >= minimumPercent - 1e-9

        fun toJson() =
            JSONObject()
                .put("now", now)
                .put("bar", bar ?: JSONObject.NULL)
                .put("chance", chance)
                .put("typical", typical ?: JSONObject.NULL)
                .put("best", best ?: JSONObject.NULL)
                .put("possible", possible)
                .put("supported", supported)
                .put("finishShare", finishShare)

        companion object {
            fun of(j: JSONObject): Es {
                fun d(k: String): Double? = if (j.isNull(k)) null else j.getDouble(k)
                return Es(
                    j.getInt("now"),
                    if (j.isNull("bar")) null else j.getInt("bar"),
                    j.getDouble("chance"),
                    d("typical"),
                    d("best"),
                    j.getBoolean("possible"),
                    j.getBoolean("supported"),
                    j.getDouble("finishShare"),
                )
            }
        }
    }

    private val substatsOnly = GearScore.Model(mainPercent = 0)

    fun main15(
        item: Item,
        reforge: Boolean,
    ): Double {
        val raw = if (item.enhance == 15) item.mainValue else Finish.main15(item)
        val value = if (reforge && item.level == 85) Reforge.main(item.mainType, raw) else raw
        return value * InventoryOrder.mainWeight(item.mainType)
    }

    /** [bar] is a whole ES; the game floors ES, so reaching it means the unfloored total is at least [bar]. */
    fun of(
        item: Item,
        now: Int,
        bar: Int?,
        reforge: Boolean,
        reforgeAllowed: Boolean,
        supported: Boolean,
        budgetMs: Int,
    ): Es {
        val share = finishShare(item)
        val ref = reforge && reforgeAllowed && item.level == 85
        val main = main15(item, ref)
        val goal = GearScore.Goal(substatsOnly, (bar ?: 0) - main, reforge, reforgeAllowed, supported, budgetMs = budgetMs)
        val f = GearScore.forecast(item, goal)
        if (!f.supported) return Es(now, bar, 0.0, null, null, false, false, share)
        val chance = if (bar == null) 0.0 else f.chance
        return Es(
            now,
            bar,
            chance,
            f.expected?.let { floor(it + main) },
            f.best?.let { floor(it + main + 1e-9) },
            bar != null && f.possible,
            true,
            share,
        )
    }
}
