package e7.gearscan.testing

import e7.gearscan.TestContent
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** A made-up account in the formats the app imports (gear.txt) and restores (backup.json). Same seed, same text. */
object SyntheticBox {
    class Result(
        val gearText: String,
        val backupText: String,
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
                .put("state", state(heroes, items, wearers, setKeys, rnd))
                .put("box", JSONObject(gear.toString()))
                .put("history", JSONArray(history(heroes, items, wearers, rnd).map { it.toJson() }))
        return Result(gear.toString(), backup.toString())
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
                .put("ingameEquippedId", wearer?.toString() ?: "None")
                .put("main", JSONObject().put("type", main.key).put("value", main15))
                .put("op", JSONArray().put(JSONArray().put(main.key).put(if (pct) main15 / 500.0 else main15 / 5.0)))
                .put(
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
        val vals = types.map { rollOf(it, level, rnd, fast && it == GearStat.SPD) }.toMutableList()
        val rolls = types.map { 1 }.toMutableList()
        for (step in 1..enhance / 3) {
            if (rank == "Heroic" && step == 4) {
                val t = pool.filter { it !in types }.random(rnd)
                types += t
                vals += rollOf(t, level, rnd, false)
                rolls += 1
            } else {
                val i = if (fast) types.indexOf(GearStat.SPD) else rnd.nextInt(types.size)
                vals[i] += rollOf(types[i], level, rnd, fast && types[i] == GearStat.SPD)
                rolls[i] += 1
            }
        }
        if (fast) {
            val i = types.indexOf(GearStat.SPD)
            while (vals[i] < 20) vals[i] += 1
        }
        return Piece(
            id,
            slot,
            set,
            rank,
            level,
            enhance,
            main,
            main15(main, level),
            types.indices.map {
                Triple(types[it], vals[it], rolls[it])
            },
            locked,
        )
    }

    private fun rollOf(
        t: GearStat,
        level: Int,
        rnd: Random,
        high: Boolean,
    ): Int {
        val r = Finish.range(t, level)
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
    ): JSONObject {
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
    }
}
