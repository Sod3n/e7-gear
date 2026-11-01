package e7.gearscan.data

/** Where a running scan stands; the capture screen and the pill over the game both show it, so they never disagree. */
enum class ScanStep(val title: String, val hint: String, val pill: String) {
    WAITING("Scanning is on", "Open Epic Seven from its title screen and log in.", "Scanning ON · open the game and log in"),
    CONNECTED("Game connected", "Tap through the title screen and log in.", "Scanning ON · log in to reach the lobby"),
    LOGIN("Logging in…", "Keep going until the lobby. Your heroes and gear arrive there.", "Logging in… keep going to the lobby"),
    LOBBY("Heroes and gear arrived", "Come back to E7 Gear and import them.", "Got your box · tap to import"),
    LATE("No login seen", "If the game was already past its title screen, restart it: the scan only sees a fresh login.",
        "No login yet · already in the game? Tap to restart it"),
}

object ScanStatus {
    const val LOGIN_BYTES = 8 * 1024L
    /** The lobby's login reply carries the whole box, well over this. */
    const val LOBBY_BYTES = 100 * 1024L
    /** Long enough to tap through the title screen; past it with no login, the game was most likely already logged in. */
    const val LATE_MS = 30_000L

    fun of(conns: Int, bytes: Long, sinceStartMs: Long): ScanStep = when {
        bytes > LOBBY_BYTES -> ScanStep.LOBBY
        bytes > LOGIN_BYTES -> ScanStep.LOGIN
        sinceStartMs >= LATE_MS -> ScanStep.LATE
        conns > 0 -> ScanStep.CONNECTED
        else -> ScanStep.WAITING
    }
}
