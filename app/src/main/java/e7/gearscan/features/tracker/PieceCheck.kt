package e7.gearscan.features.tracker

import e7.gearscan.data.Box
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.data.Item
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.HeroUpgrades
import e7.gearscan.opt.Setup
import e7.gearscan.opt.UpgradeCheck

/** What the tracker's check says about one piece, worked out from the hero setups. */
internal class PieceCheck(private val contentFeature: ContentFeature, private val marks: MarksFeature, private val heroesFeature: HeroesFeature, private val minGain: Double) {
    private val content get() = contentFeature.content

    fun compareSlot(
        heroes: List<Setup>,
        current: Item,
        b: Box,
        rebuilds: Boolean,
    ): Map<Long, Pair<List<Pair<Long, String>>, List<Long>>> {
        val cleared = marks.cleared
        val others =
            b.items.filter {
                it.slot == current.slot && it.id != current.id && it.enhance < 15 && it.id !in cleared && UpgradeRecord.supported(it)
            }
        val res = HeroUpgrades().run(heroes, (listOf(current) + others).sortedBy { it.id })
        return heroes.mapNotNull { s -> rankSlot(s, current, b, rebuilds, res)?.let { s.hero.id to it } }.toMap()
    }

    private fun rankSlot(
        s: Setup,
        current: Item,
        b: Box,
        rebuilds: Boolean,
        res: HeroUpgrades.Result,
    ): Pair<List<Pair<Long, String>>, List<Long>>? {
        val base = res.bases[s.hero.id] ?: return null
        val ref = (if (rebuilds) base.value else base.worn) ?: return null
        val refBase = HeroUpgrades.Base(base.id, ref, emptyList(), false, base.unit, 0)

        fun counted(p: HeroUpgrades.Pick) =
            (if (rebuilds) p.values else p.swaps.map { it.second })
                .filter { it > ref + HeroUpgrades.MIN_GAIN * base.unit }.sortedDescending()
        val ranked =
            res.picks.filter { it.hero == s.hero.id }.map { p -> p to counted(p) }
                .map { (p, v) -> Triple(p, v, v.sumOf { (it - ref) / base.unit } / res.sims) }
                .filter { it.third > 0 || it.first.piece == current.id }.sortedByDescending { it.third }
        val mine = ranked.firstOrNull { it.first.piece == current.id }?.third ?: 0.0
        val lines =
            ranked.take(5).map { (p, v, _) ->
                val q = if (p.piece == current.id) current else b.itemsById.getValue(p.piece)
                val name = "${content.set(q.set)?.name ?: q.set.removeSuffix("Set")} ${q.slot.label} +${q.enhance}"
                val where = if (p.piece == current.id) " (this one)" else ""
                p.piece to
                    "$name$where · ${v.size * 100 / res.sims}%" +
                    (v.getOrNull(v.size / 2)?.let { " · usually ${heroesFeature.gainLabel(s.hero.id, refBase, it)}" } ?: "")
            }
        return lines to ranked.filter { it.first.piece != current.id && it.third > mine }.map { it.first.piece }
    }

    fun advice(
        pr: HeroUpgrades.PieceResult,
        rebuilds: Boolean,
    ): UpgradeCheck.HeroResult {
        // counted like Improve heroes: finishes that gain at least the "Showing" minimum
        val ref = if (rebuilds) pr.bar.value else pr.swapRef
        val hits = (if (rebuilds) pr.hits else pr.swapHits).filterValues { ref == null || it >= ref + minGain * pr.bar.unit }
        val values = hits.values.sortedDescending()
        val now = if (rebuilds) pr.now else pr.swapNow
        val nowValue = if (rebuilds) pr.bar.value else pr.swapNowValue
        val build = (if (rebuilds) pr.rebuildBuild else pr.swapBuild)?.sortedBy { it.slot.ordinal }
        val lines =
            build?.let { b ->
                // the check's own ids for the piece are negative
                val from = heroesFeature.buildFrom(pr.hero, b, Long.MIN_VALUE)
                b.mapIndexed { i, q -> "${q.slot.label}: ${content.set(q.set)?.name ?: q.set.removeSuffix("Set")} ${q.mainType.short} · ${from[i]}" } +
                    "Sets: ${HeroUpgrades.setsLabel(b, content)}"
            }.orEmpty()
        return UpgradeCheck.HeroResult(
            pr.hero, heroesFeature.heroName(pr.hero).orEmpty(), hits.keys.sorted(), now, false, pr.unknown,
            pr.provisional,
            if (now && nowValue != null) {
                (if (rebuilds) pr.without else pr.worn ?: pr.without).let { b -> b.value?.let { (nowValue - it) / b.unit } } ?: 1.0
            } else {
                null
            },
            if (rebuilds) pr.changes else pr.swapChanges, pr.total,
            (if (rebuilds) pr.minimal else pr.swapMinimal).map { q -> q.subs.joinToString(" · ") { "${it.type.short} ${it.type.format(it.value)}" } },
            values.getOrNull(values.size / 2)?.let { v ->
                heroesFeature.gainLabel(
                    pr.hero,
                    if (rebuilds) {
                        pr.bar
                    } else {
                        pr.worn?.let { w -> HeroUpgrades.Base(w.id, pr.swapRef, w.items, false, w.unit, 0, w.worn) } ?: pr.bar
                    },
                    v,
                )
            }.orEmpty(),
            if (now && nowValue != null) heroesFeature.gainLabel(pr.hero, if (rebuilds) pr.without else pr.worn ?: pr.without, nowValue) else "",
            pr.swapNow, lines,
        )
    }
}
