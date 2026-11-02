package e7.gearscan

sealed class Screen {
    data object ScanIntro : Screen()
    data object ScanData : Screen()
    data object Capture : Screen()
    data object ScanResult : Screen()
    data object Optimize : Screen()
    data object Heroes : Screen()
    data object Gear : Screen()
    data object Results : Screen()
    data object GhostBuild : Screen()
    data class Compare(val index: Int, val poolHero: Long? = null) : Screen()
    data class HeroDetail(val id: Long) : Screen()
    data class DiffItems(val kind: Int) : Screen()
    data object OverlayIntro : Screen()
    data class Feedback(val error: String? = null) : Screen()
    data object PoolRun : Screen()
    data object Upgrade : Screen()
    data object GearPotential : Screen()
    data object SellFilters : Screen()
    data object SellKept : Screen()
    /** Pieces that could make heroes with a setup better once finished: every hero, or the pieces for [heroId]. */
    data class HeroUpgrades(val heroId: Long? = null) : Screen()
    /** A pool run's results walked hero by hero, with reviewed marks. */
    data class PoolReview(val id: Long) : Screen()
    /** One pool hero's full setup; [setup]: walk the heroes with no build, else the ready ones. */
    data class PoolHero(val id: Long, val setup: Boolean) : Screen()
}

enum class SheetKind { PRESET, TARGETS, ADVANCED, BONUS, SETS, MAINS, GOALS, SHOW, HERO_PICK, ARTIFACT, POOL_PICK, POOL_WORSE, POOL_LOW, POOL_HEROES, POOL_LIST, POOL_TRADES, SCAN_RESTART, POTENTIAL_HELP, POTENTIAL_TARGETS, POTENTIAL_SLOTS, POTENTIAL_SETS, POTENTIAL_MAINS, POTENTIAL_OPTIONS, POTENTIAL_PIECE, SELL_SCORE, SELL_WEIGHTS, SELL_FLOORS, SELL_CUSTOMS, SELL_HEROES, SELL_INVENTORY, SELL_SLOTS, SELL_SETS, SELL_MAINS, SELL_LEVELS, SELL_QUALITY, SELL_DETAILS, SELL_HELP, SELL_SAFEGUARDS, SELL_EXTRA, SELL_WORTH, HERO_UPGRADE_PIECE, HERO_UPGRADE_HELP, HERO_UPGRADE_SETTINGS, HERO_UPGRADE_BUILD }

/** [back] is the sheet to return to when this one finishes; null closes. */
data class Sheet(val kind: SheetKind, val heroId: Long, val back: SheetKind? = null)
