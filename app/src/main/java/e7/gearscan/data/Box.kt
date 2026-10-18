package e7.gearscan.data

import org.json.JSONArray
import org.json.JSONObject

/** The user's heroes and gear, kept in Fribbels' gear.txt format so export is lossless. */
class Box(val json: JSONObject) {
    val items: List<Item>
    val allHeroes: List<OwnedHero>
    private val rawItems = HashMap<Long, JSONObject>()

    init {
        val ia = json.optJSONArray("items") ?: JSONArray()
        items = (0 until ia.length()).mapNotNull { i ->
            val o = ia.getJSONObject(i)
            parseItem(o)?.also { rawItems[it.id] = o }
        }
        val ha = json.optJSONArray("heroes") ?: JSONArray()
        allHeroes = (0 until ha.length()).map { ha.getJSONObject(it) }.map { o ->
            OwnedHero(o.optLong("id"), o.optString("code"), o.optString("name", o.optString("code")),
                o.optInt("stars", o.optInt("g", 0)))
        }
    }

    /** Heroes worth gearing: 6-star units and anything that wears gear. */
    val heroes: List<OwnedHero> by lazy {
        val wearing = items.mapNotNullTo(HashSet()) { it.equippedOn }
        allHeroes.filter { it.stars >= 6 || it.id in wearing }
    }

    val itemsById: Map<Long, Item> by lazy { items.associateBy { it.id } }

    fun hero(id: Long) = allHeroes.firstOrNull { it.id == id }

    fun gearOf(heroId: Long): Map<Slot, Item> =
        items.filter { it.equippedOn == heroId }.associateBy { it.slot }

    fun setEquipped(item: Item, heroId: Long?) {
        item.equippedOn = heroId
        rawItems[item.id]?.put("ingameEquippedId", heroId?.toString() ?: "None")
    }

    fun setLocked(item: Item, locked: Boolean) {
        item.locked = locked
        rawItems[item.id]?.put("l", locked)
    }

    fun toText(): String = json.toString()

    companion object {
        fun parse(text: String) = Box(JSONObject(text))

        fun parseItem(o: JSONObject): Item? {
            val slot = Slot.of(o.optString("gear")) ?: return null
            val main = o.optJSONObject("main") ?: return null
            val mainType = GearStat.of(main.optString("type")) ?: return null
            val sa = o.optJSONArray("substats") ?: JSONArray()
            val subs = (0 until sa.length()).mapNotNull { j ->
                val s = sa.getJSONObject(j)
                GearStat.of(s.optString("type"))?.let {
                    Substat(it, s.optDouble("value", 0.0), s.optInt("rolls", 1), s.optBoolean("modified", false))
                }
            }
            val eq = o.optString("ingameEquippedId", "None").toLongOrNull()?.takeIf { it != 0L }
            return Item(o.optLong("id", o.optLong("ingameId")), slot, o.optString("set"), o.optString("rank", "Epic"),
                o.optInt("level", 85), o.optInt("enhance", 0), mainType, main.optDouble("value", 0.0), subs,
                o.optBoolean("l", false), eq)
        }
    }
}

/** What changed between two scans. */
class ScanDiff(
    val newItems: List<Long>,
    val removedItems: List<Long>,
    val enhanced: List<Long>,
    val moved: List<Long>,
    val newHeroes: List<String>,
) {
    fun toJson() = JSONObject()
        .put("newItems", JSONArray(newItems)).put("removedItems", JSONArray(removedItems))
        .put("enhanced", JSONArray(enhanced)).put("moved", JSONArray(moved)).put("newHeroes", JSONArray(newHeroes))

    companion object {
        fun between(old: Box?, new: Box): ScanDiff {
            if (old == null) return ScanDiff(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            val o = old.itemsById
            val n = new.itemsById
            val enhanced = n.values.filter { it.id in o && (it.enhance > o[it.id]!!.enhance || it.level > o[it.id]!!.level) }
            val moved = n.values.filter { it.id in o && it.equippedOn != o[it.id]!!.equippedOn }
            val oldCodes = old.heroes.mapTo(HashSet()) { it.code }
            return ScanDiff(n.keys.filter { it !in o }, o.keys.filter { it !in n }, enhanced.map { it.id },
                moved.map { it.id }, new.heroes.filter { it.code !in oldCodes }.map { it.name }.distinct())
        }

        fun fromJson(j: JSONObject?): ScanDiff? = j?.let {
            fun longs(k: String) = it.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getLong) } ?: emptyList()
            val nh = it.optJSONArray("newHeroes")
            ScanDiff(longs("newItems"), longs("removedItems"), longs("enhanced"), longs("moved"),
                (0 until (nh?.length() ?: 0)).map { i -> nh!!.getString(i) })
        }
    }
}
