package e7.gearscan.opt

import e7.gearscan.calc.GS
import e7.gearscan.calc.NS
import e7.gearscan.calc.PEN_MULT
import e7.gearscan.data.SetDef
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A goal over final stats. Must be nondecreasing in every stat it uses: bounds rely on it. */
interface Objective {
    val uses: IntArray

    /** For the product bound: 1 = damage (atk x cd), 2 = EHP (hp x def), 0 = none. */
    val product: Int get() = 0

    /** Set for "closest to targets": value = -sum k[i] * max(0, t[i] - s[idx[i]]) + eps * gs. */
    val targets: TargetSpec? get() = null

    fun eval(
        s: DoubleArray,
        dmgMult: Double,
        pen: Double,
    ): Double
}

class TargetSpec(
    val idx: IntArray,
    val t: DoubleArray,
    val k: DoubleArray,
    val eps: Double,
)

/** [changed]: not the hero's current piece in that slot (or a modded one), counted against maxChanged. [cost]: taken off the value, >= 0. */
class Candidate(
    val ref: Int,
    val vec: DoubleArray,
    val set: Int,
    val ghost: Boolean,
    val changed: Boolean = false,
    val cost: Double = 0.0,
)

class Entry(
    val value: Double,
    val picks: IntArray,
    val root: Int = -1,
)

/**
 * Exact top-K gear search by branch and bound. Bounds: per-slot stat maxima, every legal final
 * set pattern (one 4-piece set plus pairs), and for damage/EHP a linear bound over per-slot
 * Pareto fronts. [fixed] and [setBase] are already final-multiplied for atk/hp/def.
 */
class Engine(
    slots: List<List<Candidate>>,
    private val fixed: DoubleArray,
    private val setBase: DoubleArray,
    private val sets: List<SetDef>,
    private val obj: Objective,
    private val mins: DoubleArray,
    private val maxs: DoubleArray,
    private val setAlts: List<IntArray>,
    private val maxGhost: Int,
    private val k: Int,
    startThreshold: Double = Double.NEGATIVE_INFINITY,
    private val maxChanged: Int = 6,
    private val setDamage: Boolean = false,
    /** A slot (0..5) whose pieces each get their own best build, instead of the top K builds overall; searched first. */
    private val distinctSlot: Int = -1,
    /** Distinct slot: any build above the threshold is enough, so each piece stops at its first one. */
    private val distinctFirst: Boolean = false,
    /** Distinct slot: a value each piece (by ref) is already known to reach; only better builds are looked for. */
    rootSeeds: Map<Int, Double> = emptyMap(),
) {
    private val nSets = sets.size
    private val st: Array<Array<Candidate>>
    private val order: IntArray
    private val sufMax = Array(7) { DoubleArray(NS) }
    private val sufMin = Array(7) { DoubleArray(NS) }
    private val front = arrayOfNulls<Array<DoubleArray>>(6)
    private val heap = PriorityQueue<Entry>(compareBy { it.value })

    @Volatile private var thr = startThreshold
    val nodes = AtomicLong()

    @Volatile var cancelled = false

    @Volatile var timedOut = false

    /** A feasibility check: the first build found ends the search. */
    @Volatile var stopAtFirst = false
    var deadline = Long.MAX_VALUE
    val keptPerSlot: IntArray
    private var rootBest: java.util.concurrent.atomic.AtomicLongArray? = null
    private var rootEntry: Array<Entry?> = emptyArray()
    private var settled = BooleanArray(0)

    private val hasMin = mins.any { !it.isNaN() }
    private val hasMax = maxs.any { !it.isNaN() }

    // stat-relevant sets and the legal final patterns over them
    private val statSets = sets.indices.filter { sets[it].hasStats }.toIntArray()
    private val patterns = ArrayList<IntArray>()
    private val memo = ConcurrentHashMap<Long, Array<DoubleArray>>()

    // shortfall bound: max(0, x) >= x on any subset of target stats, so for every subset the
    // linear miss minus the best the remaining slots can add to it bounds the true miss
    private val spec = obj.targets
    private val subsets: Array<IntArray>
    private val subMax: Array<DoubleArray>

    // joint minimums: per-stat best pieces often differ, so for every subset of minimums the summed relative
    // shortfall must fit in what one piece per remaining slot adds to that same sum
    private val minIdx = (0 until 8).filter { !mins[it].isNaN() && mins[it] > 0 }
    private val minSubs: Array<IntArray> =
        (1 until (1 shl minIdx.size))
            .map { m ->
                minIdx.indices
                    .filter { m shr it and 1 == 1 }
                    .map { minIdx[it] }
                    .toIntArray()
            }.filter { it.size >= 2 }
            .toTypedArray()

    // each subset twice: stats scaled by the minimum, and by the stat's typical piece
    private val minScale = Array(2) { DoubleArray(8) { 1.0 } }
    private val minSubMax: Array<Array<DoubleArray>>

    // per set alternative: the sets it asks for, and per depth a bitmask of those sets the slot has pieces in
    private val altSets: Array<IntArray> = setAlts.map { a -> a.indices.filter { a[it] > 0 }.toIntArray() }.toTypedArray()
    private val supply: Array<IntArray>

    init {
        val four = statSets.filter { sets[it].pieces == 4 }
        val two = statSets.filter { sets[it].pieces == 2 }

        fun pairs(
            p: IntArray,
            used: Int,
            from: Int,
        ) {
            patterns += p.clone()
            if (used + 2 > 6) return
            for (i in from until two.size) {
                p[two[i]] += 2
                pairs(p, used + 2, i)
                p[two[i]] -= 2
            }
        }
        pairs(IntArray(nSets), 0, 0)
        for (f in four) {
            val p = IntArray(nSets)
            p[f] = 4
            pairs(p, 4, 0)
        }

        // one best build per piece of the distinct slot: elsewhere a dominated piece is never needed (K = 1)
        val filtered = slots.mapIndexed { s, li -> if (s == distinctSlot) li else filterDominated(li, if (distinctSlot >= 0) 1 else k) }
        val rest = (0 until 6).filter { it != distinctSlot }.sortedBy { filtered[it].size }
        val ord = if (distinctSlot >= 0) listOf(distinctSlot) + rest else rest
        order = ord.toIntArray()
        st = Array(6) { d -> filtered[ord[d]].toTypedArray() }
        keptPerSlot = IntArray(6) { d -> st[d].size }
        if (distinctSlot >= 0) {
            rootBest =
                java.util.concurrent.atomic.AtomicLongArray(st[0].size).also { a ->
                    for (i in st[0].indices) a.set(i, (rootSeeds[st[0][i].ref] ?: Double.NEGATIVE_INFINITY).toRawBits())
                }
            rootEntry = arrayOfNulls(st[0].size)
            settled = BooleanArray(st[0].size)
        }
        supply =
            Array(setAlts.size) { a ->
                IntArray(6) { d ->
                    var m = 0
                    for ((p, set) in altSets[a].withIndex()) if (st[d].any { it.set == set }) m = m or (1 shl p)
                    m
                }
            }
        for (d in 5 downTo 0) {
            for (x in 0 until NS) {
                var mx = Double.NEGATIVE_INFINITY
                var mn = Double.POSITIVE_INFINITY
                for (c in st[d]) {
                    mx = maxOf(mx, c.vec[x])
                    mn = minOf(mn, c.vec[x])
                }
                if (st[d].isEmpty()) {
                    mx = 0.0
                    mn = 0.0
                }
                sufMax[d][x] = sufMax[d + 1][x] + mx
                sufMin[d][x] = sufMin[d + 1][x] + mn
            }
        }
        val n = spec?.idx?.size ?: 0
        subsets = Array(if (n == 0) 0 else (1 shl n) - 1) { m -> (0 until n).filter { (m + 1) shr it and 1 == 1 }.toIntArray() }
        subMax =
            Array(subsets.size) { si ->
                val sub = subsets[si]
                val suf = DoubleArray(7)
                for (d in 5 downTo 0) {
                    var mx = if (st[d].isEmpty()) 0.0 else Double.NEGATIVE_INFINITY
                    // a slot's best shortfall cut net of its piece's cost: with costs, the bound also counts what the rest will pay
                    for (c in st[d]) {
                        var v = -c.cost
                        for (i in sub) v += spec!!.k[i] * c.vec[spec.idx[i]]
                        mx = maxOf(mx, v)
                    }
                    suf[d] = suf[d + 1] + mx
                }
                suf
            }
        for (x in minIdx) {
            minScale[0][x] = mins[x]
            minScale[1][x] = maxOf(1e-6, (sufMax[0][x] - sufMin[0][x]) / 6)
        }
        minSubMax =
            Array(2) { w ->
                Array(minSubs.size) { si ->
                    val sub = minSubs[si]
                    val suf = DoubleArray(7)
                    for (d in 5 downTo 0) {
                        var mx = if (st[d].isEmpty()) 0.0 else Double.NEGATIVE_INFINITY
                        for (c in st[d]) {
                            var v = 0.0
                            for (x in sub) v += c.vec[x] / minScale[w][x]
                            mx = maxOf(mx, v)
                        }
                        suf[d] = suf[d + 1] + mx
                    }
                    suf
                }
            }
        if (obj.product != 0) {
            val px = if (obj.product == 2) 1 else 0
            val py = if (obj.product == 2) 2 else 5
            for (d in 0 until 6) front[d] = paretoFront(st[d], px, py)
        }
    }

    /** Drop an item when k others of the same slot and set are at least as good in every stat that matters. */
    private fun filterDominated(
        li: List<Candidate>,
        k: Int,
    ): List<Candidate> {
        val rel = (obj.uses.toList() + mins.indices.filter { !mins[it].isNaN() }).distinct()
        val eq = maxs.indices.filter { !maxs[it].isNaN() }
        // with a change cap, a new piece must not knock out the hero's own one, so they compete only among themselves
        val bySet = li.groupBy { Triple(it.set, it.ghost, maxChanged < 6 && it.changed) }
        val out = ArrayList<Candidate>()
        for ((_, group) in bySet) {
            for ((i, a) in group.withIndex()) {
                var c = 0
                for ((j, b) in group.withIndex()) {
                    if (i == j) continue
                    var dom = b.cost <= a.cost
                    var strict = b.cost < a.cost
                    if (dom) {
                        for (r in rel) {
                            if (b.vec[r] < a.vec[r]) {
                                dom = false
                                break
                            }
                            if (b.vec[r] > a.vec[r]) strict = true
                        }
                    }
                    if (dom) {
                        for (r in eq) {
                            if (b.vec[r] != a.vec[r]) {
                                dom = false
                                break
                            }
                        }
                    }
                    if (dom && (strict || j < i)) {
                        if (++c >= k) break
                    }
                }
                if (c < k) out += a
            }
        }
        return out
    }

    private fun paretoFront(
        items: Array<Candidate>,
        px: Int,
        py: Int,
    ): Array<DoubleArray> {
        val pts =
            items
                .map { doubleArrayOf(it.vec[px], it.vec[py]) }
                .sortedWith(compareByDescending<DoubleArray> { it[0] }.thenByDescending { it[1] })
        val f = ArrayList<DoubleArray>()
        var bestY = Double.NEGATIVE_INFINITY
        for (p in pts) {
            if (p[1] > bestY) {
                f += p
                bestY = p[1]
            }
        }
        return f.toTypedArray()
    }

    /** Bonus of final counts; [optimistic] drops negative parts (bound) instead of applying them. */
    private fun bonusOf(
        c: IntArray,
        out: DoubleArray,
        optimistic: Boolean,
    ) {
        out.fill(0.0, 0, 8)
        var dmg = 1.0
        var pen = 1.0
        for (i in statSets) {
            val s = sets[i]
            val act = c[i] / s.pieces
            if (act == 0) continue

            fun add(
                x: Int,
                v: Double,
            ) {
                if (!optimistic || v > 0) out[x] += act * v
            }
            add(0, s.atkPct / 100 * setBase[0])
            add(1, s.hpPct / 100 * setBase[1])
            add(2, s.defPct / 100 * setBase[2])
            add(3, s.spdPct / 100 * setBase[3])
            add(4, s.cr)
            add(5, s.cd)
            add(6, s.eff)
            add(7, s.res)
            if (setDamage && (s.dmg > 0 || !optimistic)) dmg += act * s.dmg / 100
            if (s.pen) pen = PEN_MULT
        }
        out[8] = dmg
        out[9] = pen
    }

    private fun bonusVectors(
        c: IntArray,
        rem: Int,
    ): Array<DoubleArray> {
        var key = rem.toLong()
        for (i in statSets) key = key * 7 + c[i]
        memo[key]?.let { return it }
        val fc = IntArray(nSets)
        val vs = ArrayList<DoubleArray>()
        for (p in patterns) {
            var need = 0
            for (i in statSets) need += maxOf(0, p[i] - c[i])
            if (need > rem) continue
            for (i in statSets) fc[i] = maxOf(c[i], p[i])
            vs += DoubleArray(10).also { bonusOf(fc, it, true) }
        }
        val outl = ArrayList<DoubleArray>()
        for ((ai, a) in vs.withIndex()) {
            var dominated = false
            for ((bi, b) in vs.withIndex()) {
                if (ai == bi) continue
                var ge = true
                var gt = false
                for (x in 0 until 10) {
                    if (b[x] < a[x]) {
                        ge = false
                        break
                    }
                    if (b[x] > a[x]) gt = true
                }
                if (ge && (gt || bi < ai)) {
                    dominated = true
                    break
                }
            }
            if (!dominated) outl += a
        }
        return outl.toTypedArray().also { memo[key] = it }
    }

    /** Slots after [d] can still complete some alternative: Hall's condition over the sets it still lacks. */
    private fun altsFeasible(
        cnt: IntArray,
        d: Int,
    ): Boolean {
        if (setAlts.isEmpty()) return true
        for ((a, alt) in setAlts.withIndex()) {
            val ids = altSets[a]
            var need = 0
            var short = 0
            for ((p, set) in ids.withIndex()) {
                val n = alt[set] - cnt[set]
                if (n > 0) {
                    need += n
                    short = short or (1 shl p)
                }
            }
            if (need > 5 - d) continue
            var ok = true
            var m = short
            while (m != 0 && ok) {
                var demand = 0
                for ((p, set) in ids.withIndex()) if (m shr p and 1 == 1) demand += alt[set] - cnt[set]
                var slots = 0
                for (j in d + 1 until 6) if (supply[a][j] and m != 0) slots++
                if (demand > slots) ok = false
                m = (m - 1) and short
            }
            if (ok) return true
        }
        return false
    }

    @Synchronized private fun offerDistinct(
        v: Double,
        picks: IntArray,
        root: Int,
    ) {
        val best = rootBest!!
        if (v <= thr || v <= Double.fromBits(best.get(root))) return
        rootEntry[root]?.let { heap.remove(it) }
        val e = Entry(v, picks, root)
        heap.add(e)
        rootEntry[root] = e
        best.set(root, (if (distinctFirst) Double.POSITIVE_INFINITY else v).toRawBits())
        if (stopAtFirst) cancelled = true
        if (heap.size > k) heap.poll()!!.let { rootEntry[it.root] = null }
        if (heap.size >= k) thr = maxOf(thr, heap.peek()!!.value)
    }

    /** Distinct-slot refs whose best build the search may not have seen (it stopped before finishing them). */
    @Synchronized fun unsettled(): List<Int> =
        if (distinctSlot < 0 || (done && !stopped)) emptyList() else st[0].indices.filter { !settled[it] }.map { st[0][it].ref }

    @Synchronized private fun offer(
        v: Double,
        picks: IntArray,
    ) {
        if (v <= thr) return
        heap.add(Entry(v, picks))
        if (stopAtFirst) cancelled = true
        if (heap.size > k) heap.poll()
        if (heap.size >= k) thr = maxOf(thr, heap.peek()!!.value)
    }

    /** Current best entries, best first; picks are candidate refs in slot order 0..5. */
    @Synchronized fun results(): List<Entry> = heap.sortedByDescending { it.value }

    private inner class Worker {
        val acc = DoubleArray(NS)
        val sa = DoubleArray(NS)
        val bon = DoubleArray(10)
        val cnt = IntArray(nSets)
        val pick = IntArray(6)
        var ghosts = 0
        var changes = 0
        var cost = 0.0

        /** The value a build must beat: in distinct mode also its own piece's best so far. */
        fun lim(): Double {
            val rb = rootBest ?: return thr
            return maxOf(thr, Double.fromBits(rb.get(pick[0])))
        }

        fun minsOk(s: DoubleArray): Boolean {
            if (!hasMin) return true
            for (x in 0 until 8) if (!mins[x].isNaN() && s[x] < mins[x]) return false
            return true
        }

        /** With set bonus [v] and slots after [d] still open: can the minimums be met together? */
        fun minsJointly(
            d: Int,
            v: DoubleArray,
        ): Boolean {
            for (w in 0 until 2) {
                for ((si, sub) in minSubs.withIndex()) {
                    var need = 0.0
                    for (x in sub) need += (mins[x] - fixed[x] - acc[x] - v[x]) / minScale[w][x]
                    if (need > minSubMax[w][si][d + 1] + 1e-9) return false
                }
            }
            return true
        }

        fun bound(
            d: Int,
            rem: Int,
        ): Double {
            var best = Double.NEGATIVE_INFINITY
            for (v in bonusVectors(cnt, rem)) {
                for (x in 0 until 8) sa[x] = fixed[x] + acc[x] + sufMax[d + 1][x] + v[x]
                sa[GS] = acc[GS] + sufMax[d + 1][GS]
                if (!minsOk(sa) || !minsJointly(d, v)) continue
                best = maxOf(best, obj.eval(sa, v[8], v[9]))
            }
            if (best == Double.NEGATIVE_INFINITY || best <= lim()) return best
            if (spec != null) return minOf(best, targetBound(d, rem))
            if (obj.product == 0) return best
            return minOf(best, productBound(d, rem))
        }

        fun targetBound(
            d: Int,
            rem: Int,
        ): Double {
            val sp = spec!!
            val gsHi = sp.eps * (acc[GS] + sufMax[d + 1][GS])
            var best = Double.NEGATIVE_INFINITY
            for (v in bonusVectors(cnt, rem)) {
                if (hasMin) {
                    for (x in 0 until 8) sa[x] = fixed[x] + acc[x] + sufMax[d + 1][x] + v[x]
                    if (!minsOk(sa) || !minsJointly(d, v)) continue
                }
                var miss = 0.0
                for ((si, sub) in subsets.withIndex()) {
                    var lb = -subMax[si][d + 1]
                    for (i in sub) lb += sp.k[i] * (sp.t[i] - fixed[sp.idx[i]] - acc[sp.idx[i]] - v[sp.idx[i]])
                    if (lb > miss) miss = lb
                }
                best = maxOf(best, -miss + gsHi)
            }
            return best
        }

        /** Linear upper bound of atk x cd (or hp x def) through the tangent at the optimistic point. */
        fun productBound(
            d: Int,
            rem: Int,
        ): Double {
            var best = Double.NEGATIVE_INFINITY
            for (v in bonusVectors(cnt, rem)) {
                val hiX: Double
                val hiY: Double
                val loY: Double
                val partX: Double
                val partY: Double
                if (obj.product == 1) {
                    partX = fixed[0] + acc[0] + v[0]
                    partY = fixed[5] + acc[5] + v[5]
                    hiX = partX + sufMax[d + 1][0]
                    loY = partY + sufMin[d + 1][5]
                    val crHi = minOf(fixed[4] + acc[4] + sufMax[d + 1][4] + v[4], 100.0) / 100
                    if (fixed[3] + acc[3] + sufMax[d + 1][3] + v[3] <
                        (mins[3].takeUnless { it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                    ) {
                        continue
                    }
                    val wA = (1 - crHi) + crHi * loY / 100
                    val wC = crHi * hiX / 100
                    var lin = wA * partX + wC * partY
                    for (j in d + 1 until 6) {
                        var m = Double.NEGATIVE_INFINITY
                        for (p in front[j]!!) m = maxOf(m, wA * p[0] + wC * p[1])
                        lin += m
                    }
                    best = maxOf(best, (lin - crHi * hiX * loY / 100) * v[8] * v[9])
                } else {
                    partX = fixed[1] + acc[1] + v[1]
                    partY = fixed[2] + acc[2] + v[2]
                    hiX = partX + sufMax[d + 1][1]
                    loY = partY + sufMin[d + 1][2]
                    val wH = 1 + loY / 300
                    val wD = hiX / 300
                    var lin = wH * partX + wD * partY
                    for (j in d + 1 until 6) {
                        var m = Double.NEGATIVE_INFINITY
                        for (p in front[j]!!) m = maxOf(m, wH * p[0] + wD * p[1])
                        lin += m
                    }
                    best = maxOf(best, lin - hiX * loY / 300)
                }
            }
            return best
        }

        fun maxsOk(d: Int): Boolean {
            if (!hasMax) return true
            bonusOf(cnt, bon, false)
            for (x in 0 until 8) {
                if (maxs[x].isNaN()) continue
                // only bonuses already earned are certain; Torrent-type penalties could still apply, so skip hp
                val lo = fixed[x] + acc[x] + sufMin[d + 1][x] + if (x == 1) 0.0 else maxOf(0.0, bon[x])
                if (x != 1 && lo > maxs[x]) return false
            }
            return true
        }

        fun leaf() {
            bonusOf(cnt, bon, false)
            for (x in 0 until 8) sa[x] = fixed[x] + acc[x] + bon[x]
            sa[GS] = acc[GS]
            if (!minsOk(sa)) return
            if (hasMax) for (x in 0 until 8) if (!maxs[x].isNaN() && sa[x] > maxs[x]) return
            if (setAlts.isNotEmpty()) {
                var ok = false
                for (alt in setAlts) {
                    var all = true
                    for (i in alt.indices) {
                        if (cnt[i] < alt[i]) {
                            all = false
                            break
                        }
                    }
                    if (all) {
                        ok = true
                        break
                    }
                }
                if (!ok) return
            }
            val v = obj.eval(sa, bon[8], bon[9]) - cost
            if (v > lim()) {
                val p = IntArray(6)
                for (dd in 0 until 6) p[order[dd]] = st[dd][pick[dd]].ref
                if (rootBest != null) offerDistinct(v, p, pick[0]) else offer(v, p)
            }
        }

        // children's bounds per depth, so the most promising is searched first
        val bufB = Array(6) { DoubleArray(st[it].size) }
        val bufI = Array(6) { IntArray(st[it].size) }

        /** Puts piece [i] in slot depth [d]; false when it is not allowed or its sets can no longer be completed. */
        fun push(
            d: Int,
            i: Int,
        ): Boolean {
            val c = st[d][i]
            if (c.ghost && ghosts >= maxGhost) return false
            if (c.changed && changes >= maxChanged) return false
            cnt[c.set]++
            if (!altsFeasible(cnt, d)) {
                cnt[c.set]--
                return false
            }
            for (x in 0 until NS) acc[x] += c.vec[x]
            if (c.ghost) ghosts++
            if (c.changed) changes++
            cost += c.cost
            pick[d] = i
            return true
        }

        fun pop(
            d: Int,
            i: Int,
        ) {
            val c = st[d][i]
            cost -= c.cost
            if (c.ghost) ghosts--
            if (c.changed) changes--
            for (x in 0 until NS) acc[x] -= c.vec[x]
            cnt[c.set]--
        }

        // costs left to pay are >= 0, so the stat bound minus what is paid so far still bounds the value
        fun childBound(d: Int): Double = if (maxsOk(d)) bound(d, 5 - d) - cost else Double.NEGATIVE_INFINITY

        fun visit(
            d: Int,
            i: Int,
        ) {
            if (!push(d, i)) return
            if (d == 5) {
                leaf()
            } else if (childBound(d) > lim()) {
                dfs(d + 1)
            }
            pop(d, i)
        }

        fun dfs(d: Int) {
            if ((nodes.incrementAndGet() and 0x3FFF) == 0L && System.nanoTime() > deadline) timedOut = true
            if (cancelled || timedOut) return
            val row = st[d]
            if (d == 5) {
                for (i in row.indices) {
                    if (cancelled || timedOut) return
                    visit(d, i)
                }
                return
            }
            val bs = bufB[d]
            val ix = bufI[d]
            var n = 0
            for (i in row.indices) {
                if (!push(d, i)) continue
                val b = childBound(d)
                pop(d, i)
                if (b > lim()) {
                    bs[n] = b
                    ix[n] = i
                    n++
                }
            }
            sortDesc(bs, ix, n)
            for (t in 0 until n) {
                if (cancelled || timedOut || bs[t] <= lim()) return
                val i = ix[t]
                push(d, i)
                dfs(d + 1)
                pop(d, i)
            }
        }
    }

    /** Shell sort of [ix] by [bs] descending, both in step, over the first [n]. */
    private fun sortDesc(
        bs: DoubleArray,
        ix: IntArray,
        n: Int,
    ) {
        var gap = n / 2
        while (gap > 0) {
            for (i in gap until n) {
                val b = bs[i]
                val x = ix[i]
                var j = i
                while (j >= gap && bs[j - gap] < b) {
                    bs[j] = bs[j - gap]
                    ix[j] = ix[j - gap]
                    j -= gap
                }
                bs[j] = b
                ix[j] = x
            }
            gap /= 2
        }
    }

    val stopped get() = cancelled || timedOut

    @Volatile var done = false
        private set

    fun run(threads: Int) {
        try {
            runAll(threads)
        } finally {
            done = true
        }
    }

    private fun runAll(threads: Int) {
        if (st.any { it.isEmpty() }) {
            settled.fill(true)
            return
        }
        // first pieces in order of their bounds, the same as every depth below
        val rootB = DoubleArray(st[0].size)
        val root = IntArray(st[0].size)
        var n = 0
        Worker().run {
            for (i in st[0].indices) {
                if (!push(0, i)) {
                    if (settled.isNotEmpty()) settled[i] = true
                    continue
                }
                rootB[n] = childBound(0)
                root[n] = i
                n++
                pop(0, i)
            }
        }
        sortDesc(rootB, root, n)
        val next = AtomicInteger()
        val task =
            Runnable {
                val w = Worker()
                while (!stopped) {
                    val t = next.getAndIncrement()
                    if (t >= n || rootB[t] <= thr) break
                    w.visit(0, root[t])
                    if (settled.isNotEmpty() && !stopped) settled[root[t]] = true
                }
            }
        if (threads <= 1) {
            task.run()
        } else {
            val ex = Executors.newFixedThreadPool(threads)
            try {
                (0 until threads).map { ex.submit(task) }.forEach { it.get() }
            } finally {
                ex.shutdownNow()
            }
        }
        if (settled.isNotEmpty()) synchronized(this) { for (t in 0 until n) if (rootB[t] <= thr) settled[root[t]] = true }
    }
}
