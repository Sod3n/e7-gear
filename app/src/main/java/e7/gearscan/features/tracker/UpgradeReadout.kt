package e7.gearscan.features.tracker

import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.value.Chance
import e7.gearscan.features.sellreview.SellReviewFeature
import e7.gearscan.data.GearStat
import e7.gearscan.data.InventoryOrder
import e7.gearscan.data.Item
import e7.gearscan.opt.EsForecast
import e7.gearscan.opt.GearPotential
import e7.gearscan.opt.GearScore
import e7.gearscan.opt.RollReview

class UpgradeReadout(private val t: TrackerFeature, private val scan: ScanFeature, private val sellReview: SellReviewFeature) {
    val reviewRolls get(): List<RollReview.Roll>? {
        val entry = t.queue.firstOrNull { it.id == t.selected } ?: return null
        var rolls = entry.reviewRolls
        val history = t.record?.history.orEmpty().drop((entry.reviewHistory - 1).coerceAtLeast(0))
        for ((a, b) in history.zipWithNext()) rolls = RollReview.append(rolls, a, b)
        return rolls
    }
    val reviewSafeguards get(): List<RollReview.Safeguard> {
        val item = t.record?.item ?: return emptyList()
        val q = t.scoreQuery ?: return emptyList()
        val e = t.queue.firstOrNull { it.id == t.selected } ?: return emptyList()
        return q.keepStats.map { (stat, value) -> RollReview.safeguard(item, stat, value, q.reforge, e.reviewAllowed, e.reviewCoverage) }
    }
    val reviewProtected get() = reviewSafeguards.any { it.active(t.scoreQuery?.keepChance ?: 0.0) }
    val reviewStatText get(): String {
        val item = t.record?.item ?: return ""
        val q = t.scoreQuery ?: return ""
        val e = t.queue.firstOrNull { it.id == t.selected } ?: return ""
        return reviewSafeguards.joinToString("\n") { g ->
            "${g.stat.short} ${g.stat.format(g.minimum)}: " +
                when {
                    g.now -> "already reached"
                    !g.known -> "future unknown"
                    g.chance > 0 -> Chance(g.chance).text
                    g.possible -> "rare possibility, odds unknown"
                    else -> "not reachable"
                }
        } + "\n\n" +
            item.subs.sortedBy { if (it.type == GearStat.SPD) 0 else 1 }.joinToString("\n") { sub ->
                val line = RollReview.line(item, sub.type, q.reforge, e.reviewAllowed, e.reviewCoverage)
                "${sub.type.short} ${sub.type.format(sub.value)} → " +
                    (line.maximum?.let { "up to ${sub.type.format(it)} at +15${if (q.reforge) " & eligible reforge" else ""}" } ?: "finish unknown")
            }
    }
    val goalOutcome get() =
        t.record?.item?.let { item ->
            t.scoreGoal?.let { GearScore.forecast(item, it).outcome(it.floor) } ?: t.goal?.let { GearPotential.forecast(item, it) }
        }
    val title get() =
        when {
            t.sellQueueComplete -> "List complete"
            t.isRollReview && reviewProtected -> "Stat safeguard reached"
            t.isRollReview -> reviewRolls?.let { rolls -> "${RollReview.overallWeakCount(rolls)} of ${rolls.size} rolls below midrange" } ?: "Upgrade history unavailable"
            t.scoreGoal != null ->
                goalOutcome?.let { out ->
                    when {
                        !out.supported -> "Catch-up chance unavailable"
                        out.matchesNow -> "Comparison reached"
                        t.record?.item?.enhance == 15 && out.chance == 1.0 -> "Reforge to catch up"
                        out.rareOnly -> "Needs rare rolls to catch up"
                        out.possible -> "${out.label()} chance to catch up"
                        else -> "Cannot reach comparison"
                    }
                } ?: "Choose a piece"
            t.goal != null ->
                goalOutcome?.let { out ->
                    when {
                        out.matchesNow -> "Targets reached"
                        t.record?.item?.enhance == 15 && out.chance == 1.0 -> "Reforge to meet targets"
                        out.rareOnly -> "Rare rolls needed"
                        out.possible -> "${out.label()} chance to meet targets"
                        else -> "Targets no longer reachable"
                    }
                } ?: "Choose a piece"
            t.checkRun.busy -> "Checking builds · ${t.checkRun.results.size}/${t.checkRun.expected}"
            t.error != null -> "Check needs attention"
            t.checkRun.expected < 0 -> "Ready to check saved setups"
            t.checkRun.expected == 0 -> "Add hero setups to get advice"
            t.worn.equippedOn != null -> "Equipped on ${t.worn.equippedOn}"
            t.record?.item?.enhance == 15 ->
                if (t.checkRun.equipHeroes.isNotEmpty()) {
                    "Finished · upgrade for ${t.checkRun.equipHeroes.first().name}"
                } else if (t.checkRun.hasPotential) {
                    "Finished · potential upgrade"
                } else {
                    "Finished · no upgrade found"
                }
            t.checkRun.hasFuture && t.checkRun.results.any { it.provisional && it.hits.isNotEmpty() } -> "Potential upgrade · check incomplete"
            t.checkRun.hasFuture ->
                (t.checkRun.chance?.let { "Continue · $it% chance to upgrade" } ?: "Continue · potential upgrade") +
                    (t.checkRun.results.filter { it.hits.isNotEmpty() }.maxByOrNull { it.hits.size }?.better?.size?.takeIf { it > 0 }?.let { " · $it better ${if (it == 1) "bet" else "bets"}" } ?: "")
            t.checkRun.uncertain -> "Undecided · check incomplete"
            t.checkRun.results.any { it.current } -> "Pause · useful as it is"
            else -> "Pause · no upgrade found"
        }

    fun esFor(item: Item): EsForecast.Es? {
        val q = t.scoreQuery ?: return null
        val b = scan.box
        val ctx = sellReview.context(q)
        if (b == null || ctx == null) return null
        return EsForecast.of(
            item,
            InventoryOrder.score(item, if (item.enhance == b.itemsById[item.id]?.enhance) ctx.raw[item.id] else null),
            ctx.esBar(item.slot), q.reforge, b.canReforge(item.id), ctx.coverage(item), q.forecastMs,
        )
    }
}
