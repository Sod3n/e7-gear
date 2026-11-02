package e7.gearscan.data

import org.json.JSONArray
import org.json.JSONObject

/** A manual observation. These never overwrite a scan: a new scan is the source of truth. */
class UpgradeRecord(val scan: Long, val history: List<Item>) {
    val item get() = history.last()

    fun record(enhance: Int, main: Double, values: List<Double>, added: Pair<GearStat, Double>? = null, correction: Boolean = false): UpgradeRecord {
        val old = item
        require(main.isFinite() && main > 0) { "Enter the main stat shown in the game." }
        require(values.size == old.subs.size && values.all { it.isFinite() && it > 0 && it == kotlin.math.floor(it) }) { "Enter every substat as a positive whole number." }
        val next = ((old.enhance / 3 + 1) * 3).coerceAtMost(15)
        require(enhance in 0..15 && if (correction) enhance == old.enhance else old.enhance < 15 && enhance == next) { "Record the next +3 step." }
        require(e7.gearscan.opt.GearBounds.main(this, enhance).contains(main)) { "The main stat must match this enhancement level." }
        values.forEachIndexed { i, v ->
            if (correction || v != old.subs[i].value) require(e7.gearscan.opt.GearBounds.existingLine(old, i, enhance, correction).contains(v)) { "${old.subs[i].type.short} is outside this step's in-game roll bounds." }
        }
        val reveal = old.rank == "Heroic" && old.subs.size == 3 && enhance >= 12
        require((added != null) == reveal) { "Choose the new fourth substat and enter its value." }
        added?.let { (type, value) ->
            require(type in allowedNew(old) && e7.gearscan.opt.GearBounds.newLine(type, old.level).contains(value) && value.isFinite() && value > 0 && value == kotlin.math.floor(value)) { "Enter a legal fourth substat." }
        }
        if (!correction) {
            require(main >= old.mainValue && values.indices.all { values[it] >= old.subs[it].value }) { "A stat went down. Use Correct if the saved values are wrong." }
            require(values.indices.count { values[it] > old.subs[it].value } == if (reveal) 0 else 1) { "One substat changes at each +3 step; enter the totals shown in the game." }
        }
        val subs = old.subs.mapIndexed { i, s -> Substat(s.type, values[i], s.rolls + if (!correction && values[i] > s.value) 1 else 0, s.modified) } +
            listOfNotNull(added?.let { Substat(it.first, it.second, 1, false) })
        val observed = Item(old.id, old.slot, old.set, old.rank, old.level, enhance, old.mainType, main, subs, old.locked, old.equippedOn,
            main15 = if (enhance == 15) main else old.main15)
        return UpgradeRecord(scan, history + observed)
    }

    /** The game's reforge of a finished level 85 piece: the level 90 main stat, and each substat its bonus for its rolls. */
    fun reforged(): UpgradeRecord {
        val old = item
        require(old.level == 85 && old.enhance == 15) { "Only a finished level 85 piece can be reforged." }
        val main = e7.gearscan.calc.Reforge.main(old.mainType, old.mainValue)
        val subs = old.subs.map { Substat(it.type, it.value + e7.gearscan.calc.Reforge.gain(it.type, it.rolls), it.rolls, it.modified) }
        return UpgradeRecord(scan, history + Item(old.id, old.slot, old.set, old.rank, 90, 15, old.mainType, main, subs, old.locked, old.equippedOn, main15 = main))
    }

    fun undo() = UpgradeRecord(scan, if (history.size > 1) history.dropLast(1) else history)
    fun toJson() = JSONObject().put("scan", scan).put("history", JSONArray(history.map(::itemJson)))

    companion object {
        /** Fribbels normalizes main.value to +15. op carries the base; restore the current game's main stat. */
        fun mainAt(it: Item, enhance: Int): Double {
            if (!it.main15.isFinite()) return it.mainValue
            val power = when { enhance == 15 -> 5.0; enhance <= 10 -> 1 + enhance * 0.2; else -> 3 + (enhance - 10) * 0.3 }
            val value = it.main15 * power / 5
            return if (it.mainType.showsPercent) kotlin.math.round(value * 10) / 10 else kotlin.math.floor(value + 1e-8)
        }

        fun fromScan(it: Item) = Item(it.id, it.slot, it.set, it.rank, it.level, it.enhance, it.mainType, mainAt(it, it.enhance),
            it.subs, it.locked, it.equippedOn, main15 = it.main15)

        fun supported(it: Item) = it.level in listOf(85, 88) && it.rank in listOf("Epic", "Heroic") &&
            (it.enhance == 15 || it.main15.isFinite() && it.main15 >= it.mainValue) &&
            it.subs.size == (if (it.rank == "Heroic" && it.enhance < 12) 3 else 4) &&
            it.subs.map { s -> s.type }.distinct().size == it.subs.size && it.subs.all { s -> s.type != it.mainType && s.value > 0 }

        fun allowedNew(it: Item) = GearStat.entries.filter { t -> t != it.mainType && it.subs.none { s -> s.type == t } &&
            !(it.slot == Slot.WEAPON && t.stat == Stat.DEF) && !(it.slot == Slot.ARMOR && t.stat == Stat.ATK) }

        fun fromJson(o: JSONObject): UpgradeRecord {
            val a = o.getJSONArray("history")
            val h = (0 until a.length()).map { Box.parseItem(a.getJSONObject(it))!! }
            require(h.isNotEmpty() && h.all { it.id == h.first().id })
            return UpgradeRecord(o.getLong("scan"), h)
        }

        fun itemJson(it: Item) = JSONObject().put("id", it.id).put("gear", it.slot.key).put("set", it.set).put("rank", it.rank)
            .put("level", it.level).put("enhance", it.enhance).put("l", it.locked).put("ingameEquippedId", it.equippedOn?.toString() ?: "None")
            .put("main", JSONObject().put("type", it.mainType.key).put("value", it.mainValue))
            .put("substats", JSONArray(it.subs.map { s -> JSONObject().put("type", s.type.key).put("value", s.value).put("rolls", s.rolls).put("modified", s.modified) }))
            .apply { if (it.main15.isFinite()) put("op", JSONArray().put(JSONArray().put(it.mainType.key).put(it.main15 / if (it.mainType.showsPercent) 500 else 5))) }
    }
}
