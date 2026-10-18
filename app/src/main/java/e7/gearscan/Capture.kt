package e7.gearscan

import java.io.ByteArrayOutputStream

/** In-memory capture of the game's login connections, filled by CaptureProxy. */
object Capture {
    const val DIR_C2S = 0
    const val DIR_S2C = 1

    private val conns = mutableListOf<Conn>()
    private val log = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /** Connection events only (ports, byte counts, errors), never payload: safe to attach to a bug report. */
    @Synchronized fun log(line: String) {
        log += "${clock.format(java.util.Date())} $line"
        while (log.size > 300) log.removeFirst()
    }

    @Synchronized fun logText(): String = log.joinToString("\n")

    @Synchronized fun clearLog() = log.clear()

    @Synchronized fun open(): Conn = Conn().also { conns += it }

    @Synchronized fun count(): Int = conns.size

    @Synchronized fun totalBytes(): Long = conns.sumOf { it.bytes() }

    @Synchronized fun clear() = conns.clear()

    /**
     * Each run of same-direction data on a connection is one hex segment, which is what the
     * Fribbels scanner produces by grouping packets on their TCP ack number.
     */
    @Synchronized fun hexSegments(): List<String> = conns.flatMap { it.hexBursts() }

    class Conn {
        private val bursts = mutableListOf<ByteArrayOutputStream>()
        private var lastDir = -1

        @Synchronized fun write(dir: Int, buf: ByteArray, n: Int) {
            if (dir != lastDir) bursts += ByteArrayOutputStream()
            bursts.last().write(buf, 0, n)
            lastDir = dir
        }

        @Synchronized fun bytes(): Long = bursts.sumOf { it.size().toLong() }

        @Synchronized fun hexBursts(): List<String> = bursts.map { hex(it.toByteArray()) }
    }

    private const val HEX = "0123456789abcdef"

    private fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        b.forEachIndexed { i, v ->
            c[2 * i] = HEX[(v.toInt() shr 4) and 0xF]
            c[2 * i + 1] = HEX[v.toInt() and 0xF]
        }
        return String(c)
    }
}
