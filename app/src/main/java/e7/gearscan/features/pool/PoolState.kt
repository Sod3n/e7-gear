package e7.gearscan.features.pool

import e7.gearscan.opt.Build
import e7.gearscan.opt.NaN2

/** Sheet hero id for the Advanced sheet editing every hero in the pool run at once. */
const val POOL_RULES = -2L

/** Time cap per hero in a pool run; top-1 searches usually finish far sooner. */
const val POOL_HERO_MS = 6_000L

/**
 * Pieces [taker] wanted from [holder], both in the run: [made] if they moved. A trade not made carries what it would have
 * done in match points ([takerPoints] for the taker, [holderPoints] for the holder); a made one reads its effect off the run's before/after.
 */
class Trade(
    val taker: Long,
    val holder: Long,
    val items: List<Long>,
    val made: Boolean,
    val takerPoints: Pair<Double, Double> = NaN2,
    val holderPoints: Pair<Double, Double> = NaN2,
    val moves: Int = 0,
    /** The player gave these pieces away, but the wearer has no full build at all without them. */
    val stuck: Boolean = false,
)

data class PoolState(
    val ids: List<Long>,
    val before: Map<Long, Int?>,
    val builds: Map<Long, Build?>,
    val after: Map<Long, Int?>,
    val current: Long?,
    val currentBest: Int?,
    val running: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
    /** Pieces one hero of the run wanted from another, made or not; the player may overrule each and re-run. */
    val trades: List<Trade> = emptyList(),
    /** Pass over the heroes; a later pass looks again at what earlier trades freed up. */
    val round: Int = 1,
    /** Heroes looked at in the results walk-through; kept with the run's history entry. */
    val reviewed: Set<Long> = emptySet(),
    /** Opened from history: the gear may have moved since; its checklist is flagged. */
    val fromHistory: Boolean = false,
    /** Each hero's setup as the run used it, so an edit made while reviewing can offer a re-run. */
    val setups: Map<Long, String> = emptyMap(),
) {
    val moves get() = builds.entries.sumOf { (id, b) -> b?.items?.count { it.equippedOn != id && !it.ghost } ?: 0 }
}
