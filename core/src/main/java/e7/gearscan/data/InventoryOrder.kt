package e7.gearscan.data

import org.json.JSONObject
import kotlin.math.floor

/** The game's Equipment Score (ES), separate from the optimizer's substat-only score. Port of gear_cleanup/sort/game_sort.py. */
object InventoryOrder {
    private val power = doubleArrayOf(1.0, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.4, 2.6, 2.8, 3.0, 3.3, 3.6, 3.9, 4.2, 5.0)
    fun mainWeight(t: GearStat) = mainWeights[t] ?: .4
    private val mainWeights = mapOf(GearStat.ATK to .0498, GearStat.HP to .0093, GearStat.DEF to .0844, GearStat.SPD to .588, GearStat.CR to .435, GearStat.CD to .375)
    private val subWeights = mapOf(GearStat.ATK to .0972, GearStat.HP to .02, GearStat.DEF to .135, GearStat.SPD to 2.0, GearStat.CR to 1.6, GearStat.CD to 1.14)
    private val rawStats = mapOf("att" to GearStat.ATK, "att_rate" to GearStat.ATK_P, "max_hp" to GearStat.HP, "max_hp_rate" to GearStat.HP_P,
        "def" to GearStat.DEF, "def_rate" to GearStat.DEF_P, "speed" to GearStat.SPD, "cri" to GearStat.CR, "cri_dmg" to GearStat.CD, "acc" to GearStat.EFF, "res" to GearStat.RES)

    fun score(item: Item, raw: JSONObject? = null): Int {
        val op = raw?.optJSONArray("op")
        if (op != null && op.length() > 0) {
            val main = op.getJSONArray(0)
            val type = rawStats[main.optString(0)]
            if (type != null) {
                var sum = main.optDouble(1) * power[item.enhance.coerceIn(0, 15)] * (if (type.showsPercent) 100 else 1) * (mainWeights[type] ?: .4)
                // Preserve the raw accumulation and precision, including repeated roll entries.
                val subs = linkedMapOf<GearStat, Double>()
                for (i in 1 until op.length()) {
                    val s = op.getJSONArray(i)
                    rawStats[s.optString(0)]?.let { t -> subs[t] = (subs[t] ?: 0.0) + s.optDouble(1) }
                }
                subs.forEach { (t, v) -> sum += v * (if (t.showsPercent) 100 else 1) * (subWeights[t] ?: 1.0) }
                return floor(sum).toInt()
            }
        }
        val current = UpgradeRecord.fromScan(item)
        return floor(current.mainValue * (mainWeights[item.mainType] ?: .4) + item.subs.sumOf { it.value * (subWeights[it.type] ?: 1.0) }).toInt()
    }
}
