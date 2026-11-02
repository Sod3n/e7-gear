package e7.gearscan.data

/** Data gaps noticed while running (unknown set, failed image); read by the screen audit and attached to reports. */
object Problems {
    private val seen = LinkedHashSet<String>()

    @Synchronized fun report(what: String) { if (seen.size < 200) seen += what }

    @Synchronized fun drain(): List<String> = seen.toList().also { seen.clear() }

    @Synchronized fun peek(): List<String> = seen.toList()
}
