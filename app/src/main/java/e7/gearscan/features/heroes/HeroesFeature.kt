package e7.gearscan.features.heroes

import androidx.compose.runtime.mutableStateMapOf
import e7.gearscan.AppGraph
import e7.gearscan.calc.Calc
import e7.gearscan.data.Box
import e7.gearscan.data.GameHero
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Item
import e7.gearscan.data.Match
import e7.gearscan.data.Mod
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Preset
import e7.gearscan.data.PresetCache
import e7.gearscan.data.PresetTabs
import e7.gearscan.data.Presets
import e7.gearscan.data.Request
import e7.gearscan.data.Role
import e7.gearscan.data.SavedBuild
import e7.gearscan.data.Stat
import e7.gearscan.data.fmtNum
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.Build
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.HeroUpgrades
import e7.gearscan.opt.Mods
import e7.gearscan.opt.Objectives
import e7.gearscan.opt.Setup
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.ui.Confirmed
import e7.gearscan.ui.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HeroesFeature(g: AppGraph) : Feature(g, "heroes") {
    var tiers by saved("tiers", emptyMap(), TIERS)
    var team by saved("team", emptyList(), Codecs.longList)
    private var storedConfigs by saved("configs", emptyMap(), CONFIGS)
    val builds = savedDir("builds")

    val presets = mutableStateMapOf<String, PresetTabs?>()
    val presetLoading = mutableStateMapOf<String, Boolean>()
    val presetsOnline = mutableSetOf<String>()
    var presetNetwork = true

    internal val presetCache =
        object : PresetCache {
            override fun read(name: String) = builds.read(name)

            override fun modified(name: String) = builds.modified(name)

            override fun write(name: String, text: String) = builds.write(name, text)

            override fun delete(name: String) = builds.deleteFile(name)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val presetIo = Dispatchers.IO.limitedParallelism(2)

    val configs: MutableMap<Long, HeroConfig>
        get() {
            val s = storedConfigs
            return if (s is HashMap) s else HashMap(s).also { storedConfigs = it }
        }

    private val content get() = g.content.content
    private val box get() = g.scan.box

    fun hero(id: Long): OwnedHero? = box?.hero(id)

    fun gameHero(h: OwnedHero): GameHero? = content.heroes[h.code]

    fun heroName(h: OwnedHero) = h.name.ifEmpty { content.heroes[h.code]?.name ?: h.code }

    fun heroName(id: Long): String? = hero(id)?.let(::heroName)

    fun face(h: OwnedHero) = content.face(h.code)

    fun config(id: Long): HeroConfig = configs.getOrPut(id) { HeroConfig() }

    fun request(id: Long): Request {
        val cfg = config(id)
        return cfg.request ?: Request().also { cfg.request = it }
    }

    fun setup(id: Long, excluded: Set<Long> = emptySet(), canTakeFrom: (Long) -> Boolean = { tier(it) < tier(id) }): Setup? {
        val b = box ?: return null
        val h = b.hero(id) ?: return null
        val game = gameHero(h) ?: return null
        return Setup(h, game, config(id), request(id), b, content, canTakeFrom, excluded)
    }

    fun tier(id: Long) = tiers[id] ?: 1

    fun setTier(id: Long, t: Int) {
        tiers = tiers + (id to t.coerceIn(1, 5))
        g.store.saveNow()
    }

    fun currentBuild(id: Long): Build? = setup(id)?.let { s -> s.build(s.current().values.toList()) }

    fun pickHero(id: Long) {
        team = listOf(id) + team.drop(1).filter { it != id }
        ensurePresets(id)
        g.store.saveNow()
    }

    fun setupKey(id: Long): String = request(id).toJson().apply { remove("reviewed") }.toString() + config(id).artifact

    fun ensurePresets(id: Long, force: Boolean = false) {
        val h = hero(id) ?: return
        if (presetLoading[h.code] == true) return
        if (!force && presets.containsKey(h.code) && h.code in presetsOnline) {
            autoPreset(id)
            return
        }
        presetLoading[h.code] = true
        g.scope.launch {
            val tabs = withContext(presetIo) { Presets.load(presetCache, content, h.code, heroName(h), allowNetwork = presetNetwork) }
            presets[h.code] = tabs
            presetsOnline += h.code
            presetLoading[h.code] = false
            autoPreset(id)
        }
    }

    fun loadPresetsQuietly(id: Long, network: Boolean) {
        val h = hero(id) ?: return
        if (presetLoading[h.code] == true) return
        if (presets.containsKey(h.code) && (!network || h.code in presetsOnline)) return
        presetLoading[h.code] = true
        g.scope.launch {
            val tabs = withContext(presetIo) { Presets.load(presetCache, content, h.code, heroName(h), allowNetwork = network && presetNetwork) }
            presets[h.code] = tabs
            if (network) presetsOnline += h.code
            presetLoading[h.code] = false
        }
    }

    fun forgetPreset(code: String) {
        builds.deleteFile("rta_$code.json")
        builds.deleteFile("rta_$code.none")
    }

    fun hasBuild(id: Long): Boolean? {
        val h = hero(id) ?: return false
        if (config(id).request?.targets?.any { !it.isNaN() } == true) return true
        if (!presets.containsKey(h.code) || presetLoading[h.code] == true) return null
        val t = presets[h.code] ?: return false
        return t.mostUsed.isNotEmpty() || t.rta.isNotEmpty()
    }

    private fun missingSets(b: Build, req: Request) = Match.missingSets(b.items.groupingBy { it.set }.eachCount(), req.setAlts) { content.set(it) }

    fun mainsOnly(id: Long, req: Request): ((Stat) -> Double)? {
        val s = setup(id) ?: return null
        val f = Calc.final(s.hb, Ghost.mainsOnly(req), req.reforged, content)
        return { f[it] }
    }

    fun rolls(id: Long): ((Stat) -> Double)? {
        val s = setup(id) ?: return null
        val r = Objectives.rollSize(s.hb)
        return { r[it.ordinal] }
    }

    private fun worn(id: Long): Set<Long> = box?.gearOf(id)?.values.orEmpty().map { it.id }.toSet()

    /** Pieces worn by heroes of the pool run stay where they are; everything else is spare. */
    fun inUse(b: Box): Set<Long> {
        val members = g.pool.members().map { it.id }.toSet()
        return b.items.filter { it.equippedOn in members }.map { it.id }.toSet()
    }

    /** Every hero with a setup, each free to use its own gear and the spares. */
    fun gearSetups(b: Box, inUse: Set<Long>): List<Setup> =
        b.heroes.mapNotNull { h ->
            val game = gameHero(h) ?: return@mapNotNull null
            val cfg = configs[h.id] ?: return@mapNotNull null
            val req = cfg.request?.copy() ?: return@mapNotNull null
            if (req.goal == Goal.TARGETS && req.targets.all { it.isNaN() }) return@mapNotNull null
            req.lowerPriorityOnly = false
            req.keepCurrent = false
            req.maxChanges = 6
            req.mods = false
            Setup(h, game, cfg, req, b, content, { true }, inUse - worn(h.id))
        }

    /** Where each piece of [items] comes from for [heroId]: this piece, stays, from the bag, or from another hero. */
    fun buildFrom(heroId: Long, items: List<Item>, pieceId: Long): List<String> {
        val worn = box?.gearOf(heroId).orEmpty()
        return items.map {
            when {
                it.id == pieceId || it.id < 0 -> "This piece, upgraded"
                worn[it.slot]?.id == it.id -> "Stays"
                it.equippedOn == null -> "From the bag"
                else -> "From ${heroName(it.equippedOn!!) ?: "a hero not in the scan"}"
            }
        }
    }

    /** A build value over [base] as the player reads it: match points on targets, else the goal's own unit. */
    fun gainLabel(id: Long, base: HeroUpgrades.Base, value: Double): String {
        val v0 = base.value ?: return "makes a build"
        val req = configs[id]?.request ?: return ""
        val d = value - v0

        fun one(x: Double) = fmtNum(Math.round(x * 10) / 10.0)
        return when (req.goal) {
            Goal.TARGETS ->
                mainsOnly(id, req)?.let { floor ->
                    rolls(id)?.let { roll ->
                        Match.start(floor, req.targets, req.weights, roll).takeIf { it > 0 }?.let { "+${one(100 * d / it)} match" }
                    }
                } ?: "+${one(d / base.unit)} rolls"
            Goal.SPEED -> "+${one(d)} speed"
            Goal.GEAR_SCORE -> "+${one(d)} score"
            Goal.DAMAGE -> "+${one(100 * d / kotlin.math.abs(v0))}% damage"
            Goal.EHP -> "+${one(100 * d / kotlin.math.abs(v0))}% EHP"
            Goal.CP -> "+${one(100 * d / kotlin.math.abs(v0))}% CP"
        }
    }

    fun matchFor(id: Long): Match.Result? {
        val h = hero(id) ?: return null
        val cur = currentBuild(id) ?: return null
        val req = config(id).request?.takeIf { r -> r.targets.any { !it.isNaN() } }
        if (req != null) {
            return Match.of(
                { cur.final[it] }, mainsOnly(id, req) ?: return null, req.targets, req.weights,
                req.presetLabel?.substringAfter("· ") ?: "your targets", rolls(id) ?: { 1.0 }, missingSets(cur, req),
            )
        }
        val p = presets[h.code]?.mostUsed?.firstOrNull() ?: return null
        return matchFor(id, p)
    }

    fun presetRequest(id: Long, p: Preset): Request? {
        val game = hero(id)?.let { gameHero(it) } ?: return null
        val cfg = config(id)
        return Presets.toRequest(p, Calc.heroBase(game, cfg, cfg.artifact ?: p.artifact, content), null)
    }

    fun matchFor(id: Long, p: Preset): Match.Result? {
        val cur = currentBuild(id) ?: return null
        val r = presetRequest(id, p) ?: return null
        return Match.of({ cur.final[it] }, mainsOnly(id, r) ?: return null, r.targets, r.weights, p.label, rolls(id) ?: { 1.0 }, missingSets(cur, r))
    }

    fun matchOf(id: Long, b: Build): Match.Result? {
        val req = config(id).request?.takeIf { r -> r.targets.any { !it.isNaN() } } ?: return null
        val base = mainsOnly(id, req) ?: return null
        return Match.of(
            { b.final[it] }, base, req.targets, req.weights, req.presetLabel?.substringAfter("· ") ?: "", rolls(id) ?: { 1.0 },
            missingSets(b, req),
        )
    }

    private fun autoPreset(id: Long) {
        val cfg = config(id)
        val h = hero(id) ?: return
        cfg.request?.let { r -> if (!r.isBlank()) return }
        val t = presets[h.code] ?: return
        val all =
            listOf("Most used" to t.mostUsed, "RTA" to t.rta, "Top ranked" to t.topRanked, "Fastest" to t.fastest)
                .flatMap { (tab, ps) -> ps.map { tab to it } }
        val (tab, p) = all.maxByOrNull { (_, p) -> matchFor(id, p)?.pct ?: -1 } ?: return
        applyPreset(id, p, tab, auto = true)
    }

    fun applyPreset(id: Long, p: Preset, tab: String = "Most used", auto: Boolean = false) {
        val h = hero(id) ?: return
        val game = gameHero(h) ?: return
        val cfg = config(id)
        val hb = Calc.heroBase(game, cfg, cfg.artifact ?: p.artifact, content)
        cfg.request = Presets.toRequest(p, hb, cfg.request, tab, auto)
        g.scan.touch()
        g.store.saveNow()
    }

    fun applyRole(id: Long, role: Role) {
        val h = hero(id) ?: return
        val game = gameHero(h) ?: return
        val cfg = config(id)
        cfg.request = role.request(Calc.heroBase(game, cfg, cfg.artifact, content), cfg.request)
        g.scan.touch()
        g.store.saveNow()
    }

    fun presetReset(id: Long): ((Confirmed) -> Unit)? {
        val label = config(id).request?.presetLabel ?: return null
        val tab = label.substringBefore(" · ")
        val name = label.substringAfter(" · ")
        if (tab == "Role") return Role.entries.firstOrNull { it.label == name }?.let { r -> { _: Confirmed -> applyRole(id, r) } }
        val t = hero(id)?.let { presets[it.code] } ?: return null
        val p = t.names.indexOf(tab).takeIf { it >= 0 }?.let { i -> t.tab(i).firstOrNull { it.label == name } } ?: return null
        return { _: Confirmed -> applyPreset(id, p, tab) }
    }

    fun clearTargets(id: Long, confirmed: Confirmed) {
        val req = request(id)
        req.targets.fill(Double.NaN); req.mins.fill(Double.NaN); req.maxs.fill(Double.NaN); req.weights.fill(0); req.fromPreset.fill(false)
        g.scan.touch(); g.store.saveNow()
    }

    fun equip(heroId: Long, build: Build): Map<Long, Long?> {
        val b = box ?: return emptyMap()
        val prev = LinkedHashMap<Long, Long?>()
        for (it in build.items) {
            if (it.ghost) continue
            val item = b.itemsById[it.id] ?: it
            b.gearOf(heroId)[it.slot]?.takeIf { cur -> cur.id != it.id }?.let { cur ->
                prev.putIfAbsent(cur.id, cur.equippedOn)
                b.setEquipped(cur, null)
            }
            prev.putIfAbsent(item.id, item.equippedOn)
            b.setEquipped(item, heroId)
        }
        g.scan.commit()
        return prev
    }

    fun recordBuild(heroId: Long, b: Build, reforge: List<Long> = emptyList(), mods: List<Pair<Long, Mod>>? = null): Outcome {
        val prev = equip(heroId, b)
        val before = recordReforges(reforge)
        val modded = recordMods(mods ?: plannedMods(b))
        val name =
            request(heroId).presetLabel?.let { "${it.substringAfter("· ").replace("Community ", "Preset ")} build" }
                ?: "Build ${config(heroId).saved.size + 1}"
        if (config(heroId).saved.none { it.items.toSet() == b.items.filter { i -> !i.ghost }.map { i -> i.id }.toSet() }) saveBuild(heroId, b, name)
        g.messages.showToast(
            "Equipped in this app and saved to ${hero(heroId)?.let(::heroName) ?: "the hero"}'s builds" + reforgedNote(before) +
                moddedNote(modded, planned = mods == null),
            "Undo",
        ) {
            undoWrites(before + modded)
            undoEquip(prev)
        }
        return Outcome.Done
    }

    fun plannedMods(b: Build) = b.items.mapNotNull { i -> i.mod?.let { i.id to it } }

    fun undoEquip(prev: Map<Long, Long?>) {
        val b = box ?: return
        for ((id, owner) in prev) b.itemsById[id]?.let { b.setEquipped(it, owner) }
        g.scan.commit()
    }

    fun saveBuild(heroId: Long, build: Build, name: String) {
        val cfg = config(heroId)
        cfg.saved += SavedBuild(name, build.items.filter { !it.ghost }.map { it.id })
        g.scan.touch()
        g.store.saveNow()
    }

    fun lockBuild(build: Build) {
        val b = box ?: return
        build.items.filter { !it.ghost }.forEach { b.setLocked(it, true) }
        g.scan.touch()
        g.scan.saveBox()
    }

    fun savedItems(s: SavedBuild): List<Item> = s.items.mapNotNull { box?.itemsById?.get(it) }

    fun recordReforges(ids: List<Long>): List<Pair<Long, JSONObject>> {
        val b = box ?: return emptyList()
        val before =
            ids.distinct().mapNotNull { id ->
                val it = b.itemsById[id]?.takeIf { x -> x.level == 85 && x.enhance == 15 && b.canReforge(x.id) } ?: return@mapNotNull null
                val reforged = UpgradeRecord(g.scan.lastScan, listOf(UpgradeRecord.fromScan(it))).reforged().item
                b.writeStats(reforged)?.let { j -> id to j }
            }
        if (before.isNotEmpty()) {
            g.scan.replaceBox(Box(b.json))
            g.scan.commit()
        }
        return before
    }

    fun recordMods(mods: List<Pair<Long, Mod>>): List<Pair<Long, JSONObject>> {
        val b = box ?: return emptyList()
        val before = mods.mapNotNull { (id, m) -> b.itemsById[id]?.let { b.writeSubs(Mods.apply(it, m)) }?.let { id to it } }
        if (before.isNotEmpty()) {
            g.scan.replaceBox(Box(b.json))
            g.scan.commit()
        }
        return before
    }

    fun undoWrites(before: List<Pair<Long, JSONObject>>) {
        val b = box ?: return
        if (before.isEmpty()) return
        before.asReversed().forEach { (id, j) -> b.restore(id, j) }
        g.scan.replaceBox(Box(b.json))
        g.scan.commit()
    }

    fun reforgedNote(before: List<Pair<Long, JSONObject>>) = if (before.isEmpty()) "" else " · ${before.size} reforged"

    fun moddedNote(before: List<Pair<Long, JSONObject>>, planned: Boolean) =
        if (before.isEmpty()) "" else " · ${before.size} modified" + if (planned) " (planned values; rescan for the rolls)" else ""

    override fun onImported() {
        afterImport()
    }

    fun afterImport() {
        g.store.saveNow()
        team.firstOrNull()?.let { ensurePresets(it) }
    }

    override fun onBoxChanged(old: Box?, new: Box?) {
        configs.values.forEach { cfg ->
            cfg.request?.let { r ->
                r.setAlts =
                    r.setAlts
                        .map { alt -> alt.mapNotNull { (k, n) -> content.set(k)?.takeIf { n >= it.pieces }?.let { k to (n / it.pieces) * it.pieces } }.toMap() }
                        .filter { it.isNotEmpty() }
            }
        }
        if (new == null) {
            presets.clear()
            return
        }
        if (team.isEmpty() || team.any { new.hero(it) == null }) team = listOfNotNull(defaultHero(new))
        tiers = tiers.filterKeys { new.hero(it) != null }
    }

    private fun defaultHero(b: Box): Long? =
        b.heroes.maxWithOrNull(compareBy({ tier(it.id) }, { h -> b.items.filter { it.equippedOn == h.id }.sumOf { it.score } }))?.id

    private companion object {
        val TIERS: Codec<Map<Long, Int>> =
            Codecs.obj(
                { m -> JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v) } } },
                { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } },
            )
        val CONFIGS: Codec<Map<Long, HeroConfig>> =
            Codecs.obj(
                { m -> JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v.toJson()) } } },
                { o ->
                    val out = HashMap<Long, HeroConfig>()
                    o.keys().forEach { k -> runCatching { out[k.toLong()] = HeroConfig.fromJson(o.getJSONObject(k)) } }
                    out
                },
            )
    }
}
