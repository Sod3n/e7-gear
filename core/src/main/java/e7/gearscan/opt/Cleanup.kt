package e7.gearscan.opt

import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Stat

/**
 * Pieces that can go to make room, none of them worn by a hero in [inUse]'s owners.
 *
 * Finished pieces: only exact duplicates, another piece of the same slot, set, main and level at least as good in every
 * stat the game shows. Every other finished piece is the best of its kind at something.
 *
 * Pieces below +12: for each kind (slot, set, main) and each pair of stats a piece could grow into, the [keep] best
 * pieces of that kind stay, finished ones included and unfinished ones by their expected finish. A piece in none of
 * those lists can go, unless [FinishCheck] shows it can still make one of the player's heroes better.
 */
object Cleanup {
    class Pick(
        val item: Item,
        val twin: Item? = null,
        val pair: List<Stat> = emptyList(),
        val better: List<Item> = emptyList(),
    )

    private val pairs = Stat.entries.flatMapIndexed { i, a -> Stat.entries.drop(i + 1).map { b -> listOf(a, b) } }

    /** A substat in average rolls; flat attack, health and defense count half (they add less than the % lines). */
    private fun units(
        t: GearStat,
        v: Double,
    ): Double {
        val avg =
            when (t) {
                GearStat.SPD -> 3.0
                GearStat.CR -> 4.0
                GearStat.CD -> 5.5
                GearStat.ATK -> 39.5
                GearStat.HP -> 179.5
                GearStat.DEF -> 31.0
                else -> 6.0
            }
        return v / avg * if (t.percentOfBase || t.stat.pct || t == GearStat.SPD) 1.0 else 0.5
    }

    /** Rolls per stat now, or for a piece below +12 as expected at +15 (its remaining upgrades spread over its lines). */
    private fun profile(
        it: Item,
        expected: Boolean,
    ): DoubleArray {
        val out = DoubleArray(Stat.entries.size)
        if (it.subs.isEmpty()) return out
        val left = if (!expected) 0 else (15 - it.enhance) / 3 - if (it.rank == "Heroic" && it.subs.size < 4 && it.enhance < 12) 1 else 0
        for (s in it.subs) out[s.type.stat.ordinal] += units(s.type, s.value) + left.toDouble() / it.subs.size
        return out
    }

    fun ticket(it: Item) = it.enhance < 12 && it.level >= 85 && (it.rank == "Epic" || it.rank == "Heroic")

    fun duplicates(
        box: Box,
        inUse: Set<Long>,
    ): List<Pick> {
        fun beats(
            q: Item,
            p: Item,
        ) = q.mainValue >= p.mainValue &&
            p.subs.all { s -> q.subs.any { it.type == s.type && it.value >= s.value } }
        val out = ArrayList<Pick>()
        box.items
            .filter { it.enhance == 15 && it.id !in inUse }
            .groupBy { listOf(it.slot, it.set, it.mainType, it.level) }
            .values
            .forEach { grp ->
                val kept = ArrayList<Item>()
                // the piece that stays: locked, then worn, then the higher gear score
                for (p in grp.sortedWith(
                    compareByDescending<Item> { it.locked }
                        .thenByDescending { it.equippedOn != null }
                        .thenByDescending { it.score }
                        .thenBy { it.id },
                )) {
                    val twin = kept.firstOrNull { beats(it, p) }
                    if (twin != null) out += Pick(p, twin = twin) else kept += p
                }
            }
        return out
    }

    /** Pieces below +12 that are in no kind's top [keep] for any stat pair, with the pieces that stay for their best pair. */
    fun stock(
        box: Box,
        inUse: Set<Long>,
        keep: Int = 2,
    ): List<Pick> {
        class E(
            val item: Item,
            val ticket: Boolean,
            val p: DoubleArray,
        )
        val tickets = box.items.filter { ticket(it) && it.id !in inUse }
        val finished = box.items.filter { it.enhance >= 12 && it.id !in inUse }
        val byKind = LinkedHashMap<List<Any>, ArrayList<E>>()
        for (it in tickets) byKind.getOrPut(listOf(it.slot, it.set, it.mainType)) { ArrayList() } += E(it, true, profile(it, true))
        for (it in finished) byKind.getOrPut(listOf(it.slot, it.set, it.mainType)) { ArrayList() } += E(it, false, profile(it, false))
        val out = ArrayList<Pick>()
        for (grp in byKind.values) {
            val top = pairs.associateWith { (a, b) -> grp.sortedByDescending { it.p[a.ordinal] + it.p[b.ordinal] }.take(keep) }
            val stays = HashSet<Long>()
            for ((pr, t) in top) for (e in t) if (e.ticket && e.p[pr[0].ordinal] + e.p[pr[1].ordinal] > 0) stays += e.item.id
            for (e in grp) {
                if (!e.ticket || e.item.id in stays) continue
                val best = pairs.maxBy { (a, b) -> e.p[a.ordinal] + e.p[b.ordinal] }
                out += Pick(e.item, pair = best, better = top.getValue(best).map { it.item })
            }
        }
        return out
    }
}
