package e7.gearscan.data

/**
 * How close gear is to a build's targets: the search's own score (weighted shortfall below each target, in rolls)
 * scaled so mains and sets with no substats are 0% and every target met is 100%. Same order as the search ranks builds.
 */
object Match {
    class Row(val stat: Stat, val now: Double, val target: Double, val weight: Int) {
        val met get() = now >= target - 0.5
    }

    /** [missing]: effect sets the build asks for that the gear lacks; stats can't show them, so each costs a share. */
    class Result(val pct: Int, val rows: List<Row>, val label: String, val missing: List<SetDef> = emptyList())

    /** Share of the match a missing effect set costs per piece: a 4-piece set 30%, a 2-piece 15%. */
    const val MISSING_SET_PER_PIECE = 0.075

    /** Sets of the closest set choice that [have] (pieces by set key) doesn't complete and stats can't show; stat sets already show. */
    fun missingSets(have: Map<String, Int>, setAlts: List<Map<String, Int>>, set: (String) -> SetDef?): List<SetDef> =
        setAlts.map { alt -> alt.filter { (k, n) -> (have[k] ?: 0) < n }.keys.mapNotNull(set).filter { !it.showsInStats } }
            .minByOrNull { m -> m.sumOf { it.pieces } } ?: emptyList()

    fun of(now: (Stat) -> Double, floor: (Stat) -> Double, targets: DoubleArray, weights: IntArray, label: String,
           roll: (Stat) -> Double = { 1.0 }, missing: List<SetDef> = emptyList()): Result? {
        val rows = Stat.entries.filter { !targets[it.ordinal].isNaN() }
            .map { Row(it, now(it), targets[it.ordinal], weights[it.ordinal].coerceAtLeast(1)) }
            .sortedByDescending { it.weight }
        if (rows.isEmpty()) return null
        var miss = 0.0; var start = 0.0
        for (r in rows) {
            miss += r.weight * maxOf(0.0, r.target - r.now) / roll(r.stat)
            start += r.weight * maxOf(0.0, r.target - floor(r.stat)) / roll(r.stat)
        }
        var pct = if (start <= 0) 100.0 else (100 * (1 - miss / start)).coerceIn(0.0, 100.0)
        for (m in missing) pct *= 1 - MISSING_SET_PER_PIECE * m.pieces
        return Result(Math.round(pct).toInt(), rows, label, missing)
    }
}
