package e7.gearscan.data

import e7.gearscan.opt.GearScore
import org.json.JSONArray
import org.json.JSONObject

enum class SellReference { POOL, EQUIPPED, SELECTED }
enum class SellSort { CHANCE, SCORE, INVENTORY, QUALITY }

/** These are saved search requirements, not an automatic sale policy. */
data class SellQuery(
    val score: GearScore.Model = GearScore.Model(),
    val maximumChance: Double = 5.0,
    val reforge: Boolean = true,
    val reference: SellReference = SellReference.POOL,
    val referenceHeroes: Set<Long> = emptySet(),
    val referenceMinimum: Int = 0,
    val matchMain: Boolean = false,
    val floorPercent: Double = 100.0,
    val floors: Map<Slot, Double> = emptyMap(),
    val slots: Set<Slot> = Slot.entries.toSet(),
    val sets: Set<String> = emptySet(),
    val mains: Set<GearStat> = emptySet(),
    val levels: Set<Int> = emptySet(),
    val minimumEnhance: Int = 0,
    val maximumEnhance: Int = 14,
    val bagOnly: Boolean = true,
    val includeLocked: Boolean = false,
    val protectPool: Boolean = true,
    val includeRare: Boolean = false,
    val includeUnknown: Boolean = false,
    val standardOnly: Boolean = true,
    val maximumCurrentScore: Double? = null,
    val maximumProcQuality: Double? = null,
    val maximumTotalQuality: Double? = null,
    val minimumProcs: Int = 0,
    val forecastMs: Int = 250,
    val sort: SellSort = SellSort.QUALITY,
    val descending: Boolean = false,
    val scoreFilter:Boolean=false,
    val keepStats:Map<GearStat,Double> = emptyMap(),
    val keepChance:Double=0.0,
    /** Per-slot game ES a finished piece should reach; a missing slot uses the median of your +15 pieces. */
    val esBars:Map<Slot,Int> = emptyMap(),
    /** Chance to reach the ES bar at which a piece counts as worth enhancing further. */
    val rollOn:Double=5.0,
) {
    fun error(): String? = when {
        !score.valid() -> "Use nonnegative stat weights, with diversity from 0 to 100%."
        !maximumChance.isFinite() || maximumChance !in 0.0..100.0 -> "Chance must be between 0 and 100%."
        !floorPercent.isFinite() || floorPercent !in 0.0..500.0 -> "Floor scale must be between 0 and 500%."
        floors.values.any { !it.isFinite() || it < 0 } -> "Floors must be nonnegative."
        esBars.values.any { it !in 0..300 } -> "ES bars must be between 0 and 300."
        !rollOn.isFinite()||rollOn !in 0.0..100.0 -> "The keep-rolling chance must be between 0 and 100%."
        keepStats.values.any { !it.isFinite()||it<=0 } || !keepChance.isFinite()||keepChance !in 0.0..100.0 -> "Choose valid stat safeguards and chance."
        slots.isEmpty() -> "Choose at least one slot."
        minimumEnhance !in 0..15 || maximumEnhance !in minimumEnhance..15 -> "The enhancement range is empty."
        referenceMinimum !in 0..15 -> "Choose a legal reference enhancement."
        maximumCurrentScore?.let { !it.isFinite() || it < 0 } == true -> "Choose a nonnegative current-score limit."
        listOfNotNull(maximumProcQuality, maximumTotalQuality).any { !it.isFinite() || it !in 0.0..200.0 } -> "Roll quality must be between 0 and 200%."
        minimumProcs !in 0..5 || forecastMs !in 25..5000 -> "Choose a valid roll count and forecast budget."
        else -> null
    }
    fun includes(item: Item, pool: Set<Long>): Boolean = item.slot in slots &&
        (sets.isEmpty() || item.set in sets) && (mains.isEmpty() || item.mainType in mains) &&
        (levels.isEmpty() || item.level in levels) && item.enhance in minimumEnhance..maximumEnhance &&
        (!bagOnly || item.equippedOn == null) && (includeLocked || !item.locked) &&
        (!protectPool || item.equippedOn !in pool) && (!scoreFilter || maximumCurrentScore == null || score.value(item) <= maximumCurrentScore + 1e-8)

    fun toJson() = JSONObject().put("rollBasis",e7.gearscan.opt.RollReview.BASIS).put("score", score.toJson()).put("maximumChance", maximumChance).put("reforge", reforge)
        .put("reference", reference.name).put("referenceHeroes", JSONArray(referenceHeroes.sorted())).put("referenceMinimum", referenceMinimum)
        .put("matchMain", matchMain).put("floorPercent", floorPercent).put("floors", JSONObject().apply { floors.forEach { (s,v)->put(s.key,v) } })
        .put("slots", JSONArray(slots.map { it.key }.sorted())).put("sets", JSONArray(sets.sorted())).put("mains", JSONArray(mains.map { it.key }.sorted()))
        .put("levels", JSONArray(levels.sorted())).put("minimumEnhance", minimumEnhance).put("maximumEnhance", maximumEnhance)
        .put("bagOnly", bagOnly).put("includeLocked", includeLocked).put("protectPool", protectPool).put("includeRare", includeRare)
        .put("includeUnknown", includeUnknown).put("standardOnly", standardOnly).put("maximumCurrentScore", maximumCurrentScore ?: JSONObject.NULL)
        .put("maximumProcQuality", maximumProcQuality ?: JSONObject.NULL).put("maximumTotalQuality", maximumTotalQuality ?: JSONObject.NULL)
        .put("scoreFilter",scoreFilter).put("keepStats",JSONObject().apply { keepStats.forEach { (s,v)->put(s.key,v) } }).put("keepChance",keepChance)
        .put("esBars",JSONObject().apply { esBars.forEach { (s,v)->put(s.key,v) } }).put("rollOn",rollOn)
        .put("minimumProcs", minimumProcs).put("forecastMs", forecastMs).put("sort", sort.name).put("descending", descending)
    companion object {
        fun of(j: JSONObject): SellQuery {
            fun strings(k: String) = j.optJSONArray(k)?.let { a->(0 until a.length()).map { a.get(it).toString() } }.orEmpty()
            fun optional(k: String) = if(j.isNull(k) || !j.has(k)) null else j.getDouble(k)
            val legacyPrimary=!j.has("rollBasis")&&j.has("scoreFilter")&&!j.optBoolean("scoreFilter",false)&&optional("maximumTotalQuality")==null
            val procLimit=optional("maximumProcQuality")
            val totalLimit=optional("maximumTotalQuality")
            val f=j.optJSONObject("floors");val ks=j.optJSONObject("keepStats");val eb=j.optJSONObject("esBars")
            val q=SellQuery(score=j.optJSONObject("score")?.let(GearScore.Model::of) ?: GearScore.Model(),
                maximumChance=j.optDouble("maximumChance",5.0),reforge=j.optBoolean("reforge",true),
                reference=runCatching { SellReference.valueOf(j.optString("reference")) }.getOrDefault(SellReference.POOL),
                referenceHeroes=strings("referenceHeroes").map(String::toLong).toSet(),referenceMinimum=j.optInt("referenceMinimum",0),
                matchMain=j.optBoolean("matchMain",false),floorPercent=j.optDouble("floorPercent",100.0),
                floors=f?.keys()?.asSequence()?.mapNotNull { k->Slot.of(k)?.let { it to f.getDouble(k) } }?.toMap().orEmpty(),
                slots=if(j.has("slots")) strings("slots").mapNotNull(Slot::of).toSet() else Slot.entries.toSet(),
                sets=strings("sets").toSet(),mains=strings("mains").mapNotNull(GearStat::of).toSet(),levels=strings("levels").map(String::toInt).toSet(),
                minimumEnhance=j.optInt("minimumEnhance",0),maximumEnhance=j.optInt("maximumEnhance",14),
                bagOnly=j.optBoolean("bagOnly",true),includeLocked=j.optBoolean("includeLocked",false),protectPool=j.optBoolean("protectPool",true),
                includeRare=j.optBoolean("includeRare",false),includeUnknown=j.optBoolean("includeUnknown",false),standardOnly=j.optBoolean("standardOnly",true),
                maximumCurrentScore=optional("maximumCurrentScore"),maximumProcQuality=if(legacyPrimary)null else procLimit,maximumTotalQuality=if(legacyPrimary)procLimit else totalLimit,
                minimumProcs=j.optInt("minimumProcs",0),forecastMs=j.optInt("forecastMs",250),
                sort=runCatching { SellSort.valueOf(j.optString("sort")) }.getOrDefault(SellSort.QUALITY).let { if(!j.optBoolean("scoreFilter",false)&&it in listOf(SellSort.SCORE,SellSort.CHANCE))SellSort.QUALITY else it },descending=j.optBoolean("descending",false),
                scoreFilter=j.optBoolean("scoreFilter",false),keepStats=ks?.keys()?.asSequence()?.mapNotNull { k->GearStat.of(k)?.let { it to ks.getDouble(k) } }?.toMap().orEmpty(),keepChance=j.optDouble("keepChance",0.0),
                esBars=eb?.keys()?.asSequence()?.mapNotNull { k->Slot.of(k)?.let { it to eb.getInt(k) } }?.toMap().orEmpty(),rollOn=j.optDouble("rollOn",5.0))
            require(q.error()==null) { q.error().orEmpty() };return q
        }
    }
}
