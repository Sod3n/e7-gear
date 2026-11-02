package e7.gearscan.data

import e7.gearscan.opt.GearPotential
import org.json.JSONArray
import org.json.JSONObject

data class PotentialQuery(
    val targets: Map<GearStat, String> = mapOf(GearStat.SPD to "20"),
    val slots: Set<Slot> = Slot.entries.toSet() - Slot.BOOTS,
    val sets: Set<String> = emptySet(),
    val mains: Set<GearStat> = emptySet(),
    val reforge: Boolean = true,
    val minimumChance: String = "0",
    /** 0: needs work, 1: unfinished only, 2: all, including pieces that match already. */
    val show: Int = 0,
    val rareOnly: Boolean = true,
) {
    fun error(): String? =
        when {
            targets.isEmpty() -> "Add at least one substat minimum."
            targets.size > 4 -> "A piece has at most four substats."
            targets.values.any {
                it.toDoubleOrNull()?.let { v ->
                    v.isFinite() && v > 0
                } != true
            } -> "Enter a positive minimum for every selected stat."
            targets.entries.any { (t, v) ->
                v.toDouble() >
                    e7.gearscan.opt.GearBounds
                        .target(t, reforge, show == 2)
                        .maximum
            } ->
                targets.entries
                    .first { (t, v) ->
                        v.toDouble() >
                            e7.gearscan.opt.GearBounds
                                .target(t, reforge, show == 2)
                                .maximum
                    }.let { (t, _) ->
                        "${t.short} is capped at ${fmtNum(
                            e7.gearscan.opt.GearBounds
                                .target(t, reforge, show == 2)
                                .maximum,
                        )} for this finish setting."
                    }
            slots.isEmpty() -> "Choose at least one slot."
            minimumChance.toDoubleOrNull()?.let {
                it.isFinite() && it in 0.0..100.0
            } != true -> "Minimum chance must be between 0 and 100%."
            else -> null
        }

    fun goal(allowed: Boolean = true) = GearPotential.Goal(targets.mapValues { it.value.toDouble() }, reforge, allowed)

    fun includes(item: Item) =
        item.slot in slots &&
            (sets.isEmpty() || item.set in sets) &&
            (mains.isEmpty() || item.mainType in mains) &&
            (show != 1 || item.enhance < 15)

    fun accepts(result: GearPotential.Outcome): Boolean =
        result.supported &&
            result.possible &&
            (show == 2 || !result.matchesNow) &&
            (result.chance > 0 || rareOnly) &&
            result.chance * 100 + 1e-9 >= minimumChance.toDouble()

    fun toJson() =
        JSONObject()
            .put("targets", JSONObject().apply { targets.forEach { (s, v) -> put(s.key, v) } })
            .put("slots", JSONArray(slots.map { it.key }))
            .put("sets", JSONArray(sets.toList()))
            .put("mains", JSONArray(mains.map { it.key }))
            .put("reforge", reforge)
            .put("minimumChance", minimumChance)
            .put("show", show)
            .put("rareOnly", rareOnly)

    companion object {
        fun of(j: JSONObject): PotentialQuery {
            fun strings(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            val t = j.optJSONObject("targets") ?: JSONObject().put(GearStat.SPD.key, "20")
            return PotentialQuery(
                t
                    .keys()
                    .asSequence()
                    .mapNotNull { k -> GearStat.of(k)?.let { it to t.getString(k) } }
                    .toMap(),
                if (j.has("slots")) strings("slots").mapNotNull(Slot::of).toSet() else Slot.entries.toSet() - Slot.BOOTS,
                strings("sets").toSet(),
                strings("mains").mapNotNull(GearStat::of).toSet(),
                j.optBoolean("reforge", true),
                j.optString("minimumChance", "0"),
                j.optInt("show").coerceIn(0, 2),
                j.optBoolean("rareOnly", true),
            )
        }
    }
}
