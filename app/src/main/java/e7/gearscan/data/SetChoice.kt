package e7.gearscan.data

/**
 * Sets as the Sets sheet edits them. With [four] picked: any one of them plus one pair from any of the [two] keys.
 * Without: exactly the [two] counts in pairs (Health 3 = three Health sets), the rest of the gear free.
 * [mix] keeps alternatives the tiles can't show (a role's "any 3 pairs from these") until the player edits.
 */
class SetChoice(val four: List<String>, val two: Map<String, Int>, val mix: List<Map<String, Int>>? = null) {
    fun toAlts(): List<Map<String, Int>> {
        mix?.let { return it }
        if (four.isEmpty()) return listOf(two.filterValues { it > 0 }.mapValues { 2 * it.value }).filter { it.isNotEmpty() }
        val partners = two.keys.map { mapOf(it to 2) }.ifEmpty { listOf(emptyMap()) }
        return four.flatMap { f -> partners.map { p -> mapOf(f to 4) + p } }.distinct()
    }

    /** Next state after a tap on a 2-piece tile: partner on/off beside a 4-piece pick, else ×1 → ×2 → ×3 → off within 3 pairs. */
    fun tapTwo(key: String): SetChoice {
        val cur = two[key] ?: 0
        if (four.isNotEmpty() && mix == null) {
            // a second tap asks for two of it, which leaves no room for a 4-piece set
            return if (cur == 0) SetChoice(four, two + (key to 1)) else SetChoice(emptyList(), mapOf(key to 2))
        }
        val base = if (mix != null) emptyMap() else two
        val c = base[key] ?: 0
        val next = if (c >= 3 || base.values.sum() + 1 > 3) 0 else c + 1
        return SetChoice(emptyList(), (base + (key to next)).filterValues { it > 0 })
    }

    /** A 4-piece pick turns 2-piece counts back into partner picks. */
    fun tapFour(key: String): SetChoice {
        val f = if (key in four) four - key else four + key
        return SetChoice(f, (if (mix != null) emptyMap() else two).mapValues { 1 })
    }

    companion object {
        /** Any [n] pairs from [keys], repeats allowed: for roles that accept a mix. */
        fun anyPairs(keys: List<String>, n: Int): SetChoice {
            val out = ArrayList<Map<String, Int>>()
            fun go(start: Int, left: Int, acc: Map<String, Int>) {
                if (left == 0) { out += acc; return }
                for (i in start until keys.size) go(i, left - 1, acc + (keys[i] to (acc[keys[i]] ?: 0) + 2))
            }
            go(0, n, emptyMap())
            return SetChoice(emptyList(), keys.associateWith { 1 }, out)
        }

        fun from(alts: List<Map<String, Int>>, content: Content): SetChoice {
            if (alts.isEmpty()) return SetChoice(emptyList(), emptyMap())
            val four = LinkedHashSet<String>(); val two = LinkedHashMap<String, Int>()
            for (alt in alts) for ((k, n) in alt) when (content.set(k)?.pieces) {
                4 -> four += k
                2 -> two[k] = maxOf(two[k] ?: 0, n / 2)
            }
            val c = if (four.isEmpty()) SetChoice(emptyList(), two) else SetChoice(four.toList(), two.mapValues { 1 })
            return if (c.toAlts().toSet() == alts.toSet()) c else SetChoice(four.toList(), two, alts)
        }
    }
}
