package e7.gearscan.testing

import e7.gearscan.TestContent
import e7.gearscan.calc.Calc
import e7.gearscan.calc.Reforge
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.PieceRef
import e7.gearscan.data.Request
import e7.gearscan.data.SavedBuild
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.opt.Finish
import e7.gearscan.opt.Objectives
import e7.gearscan.opt.Setup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** A made-up account in the formats the app imports (gear.txt) and restores (backup.json). Same seed, same text. */
object SyntheticBox {
    class Result(
        val gearText: String,
        val backupText: String,
        val builds: Map<String, String> = emptyMap(),
    )

    const val PIECES_PER_SLOT = 50
    const val HEROES = 40
    private const val FULL_HEROES = 24
    private const val FIRST_ITEM_ID = 7_000_000L
    private const val FIRST_HERO_ID = 3_000_000L
    private const val NOW = 1_760_000_000_000L
    private val levels = listOf(85, 88, 90)

    private val mains =
        mapOf(
            Slot.WEAPON to listOf(GearStat.ATK),
            Slot.HELMET to listOf(GearStat.HP),
            Slot.ARMOR to listOf(GearStat.DEF),
            Slot.NECKLACE to listOf(GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.CR, GearStat.CD),
            Slot.RING to listOf(GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.EFF, GearStat.RES),
            Slot.BOOTS to listOf(GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.SPD),
        )

    private val rawKeys =
        mapOf(
            GearStat.ATK to "att",
            GearStat.ATK_P to "att_rate",
            GearStat.HP to "max_hp",
            GearStat.HP_P to "max_hp_rate",
            GearStat.DEF to "def",
            GearStat.DEF_P to "def_rate",
            GearStat.SPD to "speed",
            GearStat.CR to "cri",
            GearStat.CD to "cri_dmg",
            GearStat.EFF to "acc",
            GearStat.RES to "res",
        )

    fun mainsOf(slot: Slot) = mains.getValue(slot)

    fun generate(seed: Long): Result {
        val rnd = Random(seed)
        val raw = JSONObject(TestContent.text)
        val setKeys = raw.getJSONArray("sets").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("key") } }
        val heroes = pickHeroes(raw, rnd)
        val items = makeItems(setKeys, rnd)
        val wearers = wear(items, heroes.size)
        val heroJson =
            heroes.mapIndexed { i, h ->
                JSONObject()
                    .put("id", FIRST_HERO_ID + i)
                    .put("code", h.code)
                    .put("name", h.name)
                    .put("stars", 6)
            }
        val itemJson = items.map { it.json(wearers[it.id]?.let { h -> FIRST_HERO_ID + h }) }
        val gear = JSONObject().put("items", JSONArray(itemJson)).put("heroes", JSONArray(heroJson))
        val backup =
            JSONObject()
                .put("app", "e7gear")
                .put("version", 1)
                .put("state", state(heroes, items, wearers, setKeys, rnd, gear.toString()))
                .put("box", JSONObject(gear.toString()))
                .put("history", JSONArray(history(heroes, items, wearers, rnd).map { it.toJson() }))
        return Result(gear.toString(), backup.toString(), builds(gear.toString(), heroes))
    }

    private fun ownRequest(
        box: Box,
        content: e7.gearscan.data.Content,
        index: Int,
        artifact: String?,
        r: Request,
    ) {
        val owned = box.allHeroes[index]
        val setup =
            Setup(
                owned,
                content.heroes.getValue(owned.code),
                HeroConfig().also { it.artifact = artifact },
                Request(),
                box,
                content,
                { true },
            )
        val worn = box.gearOf(owned.id)
        val final = setup.build(worn.values.toList()).final.stats
        r.setAlts =
            Calc.setCounts(worn.values, content).associate { (d, n) -> d.key to n }.let { if (it.isEmpty()) emptyList() else listOf(it) }
        r.mains = listOf(Slot.NECKLACE, Slot.RING, Slot.BOOTS).associateWith { setOf(worn.getValue(it).mainType) }
        r.capCrit = false
        for (x in listOf(Stat.SPD, Stat.CR, Stat.CD)) r.targets[x.ordinal] = Math.floor(final[x.ordinal])
        r.mins[Stat.SPD.ordinal] = Math.floor(final[Stat.SPD.ordinal]) - 5
        r.mins[Stat.RES.ordinal] = maxOf(0.0, Math.floor(final[Stat.RES.ordinal]) - 5)
        r.mins[Stat.CR.ordinal] = Double.NaN
    }

    private fun builds(
        gearText: String,
        heroes: List<Hero>,
    ): Map<String, String> {
        val content = TestContent.content
        val box = Box.parse(gearText)
        val sets = content.sets.map { it.key }

        fun game(counts: Map<String, Int>) =
            JSONObject().also { o -> counts.forEach { (k, n) -> o.put(content.set(k)!!.game, n.toString()) } }
        return heroes.indices.associate { i ->
            val owned = box.allHeroes[i]
            val gh = content.heroes.getValue(owned.code)
            val setup = Setup(owned, gh, HeroConfig().also { it.artifact = heroes[i].artifact }, Request(), box, content, { true })
            val worn = box.gearOf(owned.id).toMutableMap()
            for (slot in Slot.entries) {
                if (slot !in
                    worn
                ) {
                    box.items
                        .filter { it.slot == slot && it.equippedOn == null }
                        .maxByOrNull { it.enhance }
                        ?.let { worn[slot] = it }
                }
            }
            val final =
                setup
                    .build(worn.values.toList())
                    .final.stats
                    .copyOf()
            val roll = Objectives.rollSize(setup.hb)
            val floor = setup.hb.fixed.copyOf()
            floor[0] += 525 * setup.hb.mult[0]
            floor[1] += 2835 * setup.hb.mult[1]
            floor[2] += 310 * setup.hb.mult[2]
            val kept = listOf(Slot.NECKLACE, Slot.RING, Slot.BOOTS).mapNotNull { worn[it]?.mainType?.stat?.ordinal }.toSet()
            val common = setOf(0, 1, 2)
            val rolls = if (kept.any { it !in common }) 8 else 13
            for (x in 0 until 8) {
                final[x] =
                    when {
                        x !in kept -> minOf(final[x], floor[x] + 3 * roll[x])
                        x in common -> floor[x] + rolls * roll[x]
                        else -> maxOf(final[x], floor[x] + 13 * roll[x])
                    }
            }
            val own = Calc.setCounts(worn.values, content).associate { (d, n) -> d.key to n }
            val four = listOf("SpeedSet", "DestructionSet", "AttackSet", "CounterSet", "ImmunitySet").filter { it in sets }
            val two = listOf("HitSet", "CriticalSet", "HealthSet", "DefenseSet", "ResistSet").filter { it in sets }
            val groups =
                listOf(
                    own,
                    mapOf(four[i % four.size] to 4, two[i % two.size] to 2),
                    mapOf(four[(i + 2) % four.size] to 4, two[(i + 3) % two.size] to 2),
                )
            val shape = listOf(doubleArrayOf(1.0, 1.0, 1.0, 0.0), doubleArrayOf(0.97, 1.03, 5.0, 5.0), doubleArrayOf(1.05, 0.96, 3.0, 10.0))
            val rows = JSONArray()
            for (r in 0 until 12) {
                val g = if (r < 6) 0 else (r - 6) / 3 + 1
                val lead = r == 0 || r == 6 || r == 9
                val sh = shape[g]
                val jitter = if (lead) 0.0 else 1.0 + (r % 3)
                rows.put(
                    JSONObject()
                        .put("artifactCode", heroes[i].artifact ?: JSONObject.NULL)
                        .put("atk", Math.round(final[0] * sh[0] - jitter * 10))
                        .put("hp", Math.round(final[1] * sh[1] - jitter * 60))
                        .put("def", Math.round(final[2] - jitter * 8))
                        .put("spd", Math.round(final[3] + sh[2] - 10 * jitter))
                        .put("chc", Math.round(final[4] + sh[3] - jitter))
                        .put("chd", Math.round(final[5] - jitter))
                        .put("eff", Math.round(final[6] - jitter))
                        .put("efr", Math.round(final[7] - jitter))
                        .put("gs", 450 + (if (lead) 20 else 0) + r % 5)
                        .put("createDate", "2026-01-${10 + r}")
                        .put("sets", game(groups[g]))
                        .put("unitCode", owned.code)
                        .put("unitName", owned.name),
                )
            }
            owned.code to JSONObject().put("data", rows).toString()
        }
    }

    private class Hero(
        val code: String,
        val name: String,
        val artifact: String?,
    )

    private fun pickHeroes(
        raw: JSONObject,
        rnd: Random,
    ): List<Hero> {
        val arts = raw.getJSONArray("artifacts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val all =
            raw.getJSONArray("heroes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter {
                it.optInt("rarity") == 5 && !it.isNull("name") && it.optString("name").isNotEmpty() && it.has("skills")
            }
        return all.sortedBy { it.getString("code") }.shuffled(rnd).take(HEROES).map { h ->
            val role = h.optString("role")
            val art = arts.filter { it.optInt("rarity", 5) == 5 && (it.optString("role").isEmpty() || it.optString("role") == role) }
            Hero(h.getString("code"), h.getString("name"), art.takeIf { it.isNotEmpty() }?.random(rnd)?.getString("code"))
        }
    }

    private class Piece(
        val id: Long,
        val slot: Slot,
        val set: String,
        val rank: String,
        val level: Int,
        val enhance: Int,
        val main: GearStat,
        val main15: Int,
        val subs: List<Triple<GearStat, Int, Int>>,
        val events: List<Pair<GearStat, Int>>,
        val locked: Boolean,
    ) {
        fun json(wearer: Long?): JSONObject {
            val pct = main.showsPercent
            return JSONObject()
                .put("id", id)
                .put("gear", slot.key)
                .put("set", set)
                .put("rank", rank)
                .put("level", level)
                .put("enhance", enhance)
                .put("l", locked)
                .put("mainStatId", "cra6_${slot.icon}_m")
                .put("ingameEquippedId", wearer?.toString() ?: "None")
                .put("main", JSONObject().put("type", main.key).put("value", main15))
                .put(
                    "op",
                    JSONArray().put(JSONArray().put(rawKeys.getValue(main)).put(if (pct) main15 / 500.0 else main15 / 5.0)).also { a ->
                        events.forEach { (t, v) ->
                            a.put(JSONArray().put(rawKeys.getValue(t)).put(if (t.showsPercent) v / 100.0 else v.toDouble()))
                        }
                    },
                ).put(
                    "substats",
                    JSONArray(
                        subs.map { (t, v, r) ->
                            JSONObject()
                                .put("type", t.key)
                                .put("value", v)
                                .put("rolls", r)
                                .put("modified", false)
                        },
                    ),
                )
        }
    }

    private fun main15(
        main: GearStat,
        level: Int,
    ): Int {
        val flat = mapOf(GearStat.ATK to 500, GearStat.HP to 2700, GearStat.DEF to 300)
        val scale =
            if (level == 85) {
                1.0
            } else if (level == 88) {
                1.1
            } else {
                1.15
            }
        flat[main]?.let { return (it * scale / 5).toInt() * 5 }
        return when (main) {
            GearStat.CR -> 55
            GearStat.SPD -> 45
            else -> 65
        }
    }

    private fun makeItems(
        setKeys: List<String>,
        rnd: Random,
    ): List<Piece> {
        val out = ArrayList<Piece>()
        var id = FIRST_ITEM_ID
        for ((s, slot) in Slot.entries.withIndex()) {
            val slotMains = mainsOf(slot)
            for (k in 0 until PIECES_PER_SLOT) {
                val enhance = (k * 7 + s) % 16
                val level = levels[(k * 5 + s + k / 16) % levels.size]
                val main = slotMains[k % slotMains.size]
                val set = setKeys[(k + s * 5) % setKeys.size]
                val heroic = rnd.nextInt(3) == 0
                val fast = enhance == 15 && main != GearStat.SPD && level >= 88
                out += piece(id++, slot, set, if (heroic) "Heroic" else "Epic", level, enhance, main, fast, rnd.nextInt(10) == 0, rnd)
            }
        }
        return out
    }

    private fun piece(
        id: Long,
        slot: Slot,
        set: String,
        rank: String,
        level: Int,
        enhance: Int,
        main: GearStat,
        fast: Boolean,
        locked: Boolean,
        rnd: Random,
    ): Piece {
        val pool =
            GearStat.entries.filter {
                it != main && !(slot == Slot.WEAPON && it.stat == Stat.DEF) && !(slot == Slot.ARMOR && it.stat == Stat.ATK)
            }
        val types = ArrayList<GearStat>()
        if (fast) types += GearStat.SPD
        types += pool.filter { it !in types }.shuffled(rnd).take((if (rank == "Heroic") 3 else 4) - types.size)
        val events = ArrayList<Pair<GearStat, Int>>()
        for (t in types) events += t to rollOf(t, level, rnd, fast && t == GearStat.SPD)
        for (step in 1..enhance / 3) {
            if (rank == "Heroic" && step == 4) {
                val t = pool.filter { it !in types }.random(rnd)
                types += t
                events += t to rollOf(t, level, rnd, false)
            } else {
                val t = if (fast) GearStat.SPD else types[rnd.nextInt(types.size)]
                events += t to rollOf(t, level, rnd, fast && t == GearStat.SPD)
            }
        }
        val subs =
            types.map { t ->
                val of = events.filter { it.first == t }
                Triple(t, of.sumOf { it.second } + (if (level == 90) Reforge.gain(t, of.size).toInt() else 0), of.size)
            }
        return Piece(id, slot, set, rank, level, enhance, main, main15(main, level), subs, events, locked)
    }

    private fun rollOf(
        t: GearStat,
        level: Int,
        rnd: Random,
        high: Boolean,
    ): Int {
        val r = Finish.range(t, if (level == 90) 85 else level)
        return if (high) r.last else rnd.nextInt(r.first, r.last + 1)
    }

    private fun wear(
        items: List<Piece>,
        heroCount: Int,
    ): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (slot in Slot.entries) {
            val best = items.filter { it.slot == slot }.sortedWith(compareByDescending<Piece> { it.enhance }.thenBy { it.id })
            val take = best.take(heroCount)
            for ((h, p) in take.withIndex()) {
                val partial = h >= FULL_HEROES && (h + slot.ordinal) % 3 != 0
                if (!partial) out[p.id] = h
            }
        }
        return out
    }

    private fun state(
        heroes: List<Hero>,
        items: List<Piece>,
        wearers: Map<Long, Int>,
        setKeys: List<String>,
        rnd: Random,
        gearText: String,
    ): JSONObject {
        val content = TestContent.content
        val box = Box.parse(gearText)
        val four =
            listOf("SpeedSet", "DestructionSet", "AttackSet", "LifestealSet", "CounterSet", "ImmunitySet", "RageSet").filter {
                it in
                    setKeys
            }
        val two = listOf("HitSet", "CriticalSet", "HealthSet", "DefenseSet", "ResistSet", "UnitySet").filter { it in setKeys }
        val configs = JSONObject()
        val tiers = JSONObject()
        for ((i, h) in heroes.withIndex()) {
            val hid = FIRST_HERO_ID + i
            tiers.put(hid.toString(), 5 - i % 5)
            val cfg = HeroConfig()
            cfg.artifact = h.artifact
            val r = Request()
            val speedy = i % 3 != 2
            r.setAlts = listOf(mapOf(four[i % four.size] to 4, two[(i / 2) % two.size] to 2))
            r.mains =
                mapOf(Slot.NECKLACE to setOf(GearStat.CR, GearStat.CD), Slot.BOOTS to setOf(if (speedy) GearStat.SPD else GearStat.HP_P))
            r.goal = Goal.TARGETS
            r.targets[Stat.SPD.ordinal] = if (speedy) 190.0 + rnd.nextInt(8) * 5 else 130.0 + rnd.nextInt(5) * 5
            r.targets[Stat.CR.ordinal] = 85.0 + rnd.nextInt(3) * 5
            r.targets[Stat.CD.ordinal] = 180.0 + rnd.nextInt(5) * 10
            r.mins[Stat.RES.ordinal] = 20.0 + rnd.nextInt(3) * 10
            r.weights[Stat.SPD.ordinal] = 3
            r.weights[Stat.CD.ordinal] = 2
            cfg.request = r
            val worn = items.filter { wearers[it.id] == i }
            if (worn.size == Slot.entries.size) ownRequest(box, content, i, h.artifact, r)
            if (worn.size == Slot.entries.size) cfg.saved += SavedBuild("Current", worn.sortedBy { it.slot.ordinal }.map { it.id })
            configs.put(hid.toString(), cfg.toJson())
        }
        return JSONObject()
            .put("consent", true)
            .put("proxyOn", false)
            .put("proxy", "")
            .put("lastScan", NOW - 3_600_000)
            .put("decodeSec", 12)
            .put("tiers", tiers)
            .put("team", JSONArray(listOf(FIRST_HERO_ID)))
            .put("configs", configs)
            .put("poolTiers", JSONArray(listOf(5)))
    }

    private fun history(
        heroes: List<Hero>,
        items: List<Piece>,
        wearers: Map<Long, Int>,
        rnd: Random,
    ): List<HistoryEntry> {
        fun build(
            hero: Int,
            swap: Boolean,
        ): List<PieceRef> =
            Slot.entries.map { slot ->
                val worn = items.first { wearers[it.id] == hero && it.slot == slot }
                val other = items.filter { it.slot == slot && it.enhance >= 12 && it.id != worn.id }.random(rnd)
                PieceRef(if (swap) other.id else worn.id, null)
            }
        val full = (0 until FULL_HEROES).filter { h -> Slot.entries.all { s -> items.any { wearers[it.id] == h && it.slot == s } } }
        val poolHeroes = full.take(6)
        val pool =
            HistoryEntry(
                NOW - 86_400_000,
                true,
                "Whole pool",
                "6 heroes, safe, balanced",
                71,
                78,
                9,
                poolHeroes.associate { (FIRST_HERO_ID + it) to listOf(build(it, true)) },
                poolHeroes.associate { (FIRST_HERO_ID + it) to 60 + rnd.nextInt(20) },
                poolHeroes.associate { (FIRST_HERO_ID + it) to 80 + rnd.nextInt(20) },
            )
        val single =
            HistoryEntry(
                NOW - 7_200_000,
                false,
                heroes[full[0]].name,
                "closest to targets",
                64,
                91,
                4,
                mapOf((FIRST_HERO_ID + full[0]) to List(3) { build(full[0], true) }),
            )
        return listOf(single, pool)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0]).apply { mkdirs() }
        val r = generate(args.getOrNull(1)?.toLong() ?: 1L)
        File(dir, "gear.txt").writeText(r.gearText)
        File(dir, "backup.json").writeText(r.backupText)
        File(dir, "content.json").writeText(TestContent.text)
        File(dir, "builds").apply { mkdirs() }.let { b -> r.builds.forEach { (code, text) -> File(b, "$code.json").writeText(text) } }
    }
}
