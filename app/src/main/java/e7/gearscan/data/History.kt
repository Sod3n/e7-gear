package e7.gearscan.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A piece of a stored build: the item, and the mod the build put on it if any. */
class PieceRef(val id: Long, val mod: Mod?) {
    fun toJson(): JSONObject = JSONObject().put("id", id).apply {
        mod?.let { put("mod", JSONObject().put("i", it.index).put("from", it.from.name).put("fromValue", it.fromValue).put("to", it.to.name).put("toValue", it.toValue)) }
    }

    companion object {
        fun of(it: Item) = PieceRef(it.id, it.mod)

        fun fromJson(o: JSONObject) = PieceRef(o.getLong("id"), o.optJSONObject("mod")?.let { m ->
            Mod(m.getInt("i"), GearStat.valueOf(m.getString("from")), m.getDouble("fromValue"), GearStat.valueOf(m.getString("to")), m.getDouble("toValue"))
        })
    }
}

/**
 * One finished optimization, kept to look back at. A pool run: one build per hero that changed, every hero's match before
 * and after, the score and the heroes the player reviewed. A one-hero search: its best builds in order.
 */
class HistoryEntry(
    val at: Long,
    val pool: Boolean,
    val title: String,
    val detail: String,
    val before: Int,
    val after: Int,
    val moves: Int,
    val builds: Map<Long, List<List<PieceRef>>>,
    val beforeMatch: Map<Long, Int?> = emptyMap(),
    val afterMatch: Map<Long, Int?> = emptyMap(),
    val reviewed: Set<Long> = emptySet(),
) {
    fun copy(reviewed: Set<Long>) = HistoryEntry(at, pool, title, detail, before, after, moves, builds, beforeMatch, afterMatch, reviewed)

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at); put("pool", pool); put("title", title); put("detail", detail)
        put("before", before); put("after", after); put("moves", moves)
        put("builds", JSONObject().apply { builds.forEach { (h, bs) -> put(h.toString(), JSONArray(bs.map { b -> JSONArray(b.map { it.toJson() }) })) } })
        fun ints(m: Map<Long, Int?>) = JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v ?: JSONObject.NULL) } }
        put("beforeMatch", ints(beforeMatch)); put("afterMatch", ints(afterMatch))
        put("reviewed", JSONArray(reviewed.toList()))
    }

    companion object {
        fun fromJson(o: JSONObject): HistoryEntry {
            fun ints(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associate { it.toLong() to (if (m.isNull(it)) null else m.getInt(it)) } } ?: emptyMap()
            val b = o.getJSONObject("builds")
            return HistoryEntry(o.getLong("at"), o.getBoolean("pool"), o.getString("title"), o.getString("detail"), o.getInt("before"), o.getInt("after"),
                o.getInt("moves"), b.keys().asSequence().associate { h ->
                    h.toLong() to b.getJSONArray(h).let { bs -> (0 until bs.length()).map { i -> bs.getJSONArray(i).let { a -> (0 until a.length()).map { PieceRef.fromJson(a.getJSONObject(it)) } } } }
                }, ints("beforeMatch"), ints("afterMatch"),
                o.optJSONArray("reviewed")?.let { a -> (0 until a.length()).map(a::getLong).toSet() } ?: emptySet())
        }
    }
}

/** Past optimizations, newest first, in their own file so the state file stays small. */
object History {
    /** Older entries drop off; a pool run is a few KB. */
    const val KEEP = 60

    fun load(f: File): List<HistoryEntry> = if (!f.exists()) emptyList() else runCatching {
        JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { runCatching { HistoryEntry.fromJson(a.getJSONObject(it)) }.getOrNull() } }
    }.getOrDefault(emptyList())

    fun save(f: File, entries: List<HistoryEntry>) {
        // whole-file replace, so a write cut short never leaves half a history
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray(entries.take(KEEP).map { it.toJson() }).toString())
        tmp.renameTo(f)
    }
}
