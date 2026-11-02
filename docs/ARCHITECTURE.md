# E7 Gear architecture

How the app is put together, and where each kind of code goes. Read this before adding a feature, a setting, a
screen or a background search. UI design rules are in [UI_COOKBOOK.md](UI_COOKBOOK.md); this file is about code.

The aim: the right way to write something is the only way that compiles, so a contributor follows the project's
rules without having to know them, and reviews stay about behaviour.

## Layers

```
 :core   pure Kotlin (JVM). Game model, content, gear math, optimizer, forecasts, file formats.
   ^      No Android, no Compose. Every rule about the game is here and unit-tested here.
   |
 :app    state and screens. One Feature per mode, built from a few shared primitives (state/).
   |      Services (overlay, VPN capture, background work) read the same features.
   v
 :kit    Compose design system. Frame, sheets, rows, grids, text roles, values, controls, messages.
          Screens are made of kit parts; the kit decides spacing, type, colour and fitting.
```

Dependencies point one way: `:app` depends on `:core` and `:kit`; `:kit` depends on `:core` only for value types
(`Chance`, `Gold`, `Stat`); `:core` depends on nothing but the Kotlin standard library and `org.json`.
Gradle enforces this: a `:core` file that imports `android.*` or a `:kit` file that reads a feature does not compile.

Package names stay `e7.gearscan.*` in every module, so moving a file between modules never changes its imports.

## :core

| Package | Holds |
|---|---|
| `data` | Model (`Item`, `Stat`, `Slot`, `OwnedHero`), `Box` (a scan), `Content` (heroes, sets, artifacts), queries (`PotentialQuery`, `SellQuery`), `UpgradeRecord`, `History`, presets and imports |
| `calc` | Hero stats from gear |
| `opt` | Optimizer (`Engine`, `Optimizer`, `PriceRun`, `Ghost`), forecasts (`Finish`, `Reforge`, `GearPotential`, `EsForecast`, `KeepCurve`, `GearBounds`), sell review (`SellSearch`, `RollReview`), `HeroUpgrades`, `UpgradeCheck` |
| `value` | Display values with one meaning each: `Chance`, `Gold`, `Count`, `StatAmount` (see "Values") |
| `net` | `Net` interface (requests that name the app), content and release checks |

Rules for `:core`:
- Functions take what they need and return a result; no global mutable state. (`Mirror.active`, `Problems` and
  `Http`'s user agent move into objects the app passes in.)
- `org.json` is `compileOnly`: Android provides it at run time, tests get it from Maven.
- Tests: `./gradlew :core:test`. They run in seconds and need no device. The synthetic box (`testing/SyntheticBox`)
  gives every test a deterministic account with gear at every level, so no test needs anyone's real data.

## :app

### The graph

`E7GearApp : Application` builds one `AppGraph` at start. It holds the shared services and every feature:

```kotlin
class AppGraph(app: Application) {
    val disk = Disk(app.filesDir)
    val store = Store(disk, version = STATE_VERSION, migrations = Migrations.all)
    val net: Net = AndroidNet(userAgent = "E7Gear/${BuildConfig.VERSION_NAME}")
    val messages = Messages()                 // toasts, explanations, confirmations
    val nav = Nav(start = Screen.Optimize)
    val work = WorkHost(app)                  // the one foreground notification for long searches
    val content = ContentFeature(this)        // game content, image base, mirror
    val appUpdate = AppUpdateFeature(this)
    val scan = ScanFeature(this)              // the box: scans, imports, export, backup, restore, erase
    val heroes = HeroesFeature(this)          // setups, tiers, presets, equip and record builds
    val marks = MarksFeature(this)            // cleared, kept and sold marks shared by the gear modes
    val optimize = OptimizeFeature(this)
    val pool = PoolFeature(this)
    val history = HistoryFeature(this)
    val sellReview = SellReviewFeature(this)
    val improveHeroes = ImproveHeroesFeature(this)
    val findGear = FindGearFeature(this)
    val tracker = TrackerFeature(this)        // upgrade tracker, shown in the overlay
    val checklist = ChecklistFeature(this)    // equip and pool checklists over the game
    val settings = SettingsFeature(this)
    val feedback = FeedbackFeature(this)
}
```

`MainActivity`, `OverlayService`, `WorkService` and `CaptureVpnService` all reach the graph through
`(application as E7GearApp).graph`. There are no other global objects with state. There is no dependency-injection
framework: the graph is plain code a newcomer can read top to bottom.

### A feature

A feature owns one mode's state and actions. It is the only place that changes that state.

```kotlin
class FindGearFeature(g: AppGraph) : Feature(g, "findGear") {
    var query by saved("query", PotentialQuery(), PotentialQuery.Codec)    // kept on disk, in backups, cleared by erase
        private set
    private val search = freshWork<Inputs, FindGearResult>("Find gear") {   // cancel, busy, late results and staleness handled
        Inputs(query, g.scan.scanId, g.tracker.recordedSteps)
    }
    val result get() = search.result
    val stale get() = search.stale
    val busy get() = search.busy

    fun edit(q: PotentialQuery) { query = q }
    fun find(): Outcome = search.start { key -> FindGearSearch.run(g.scan.items, key.query) }
    fun track(id: Long): Outcome = ...
    fun trackAll(): Outcome = if (stale) Outcome.Failed("Update the matches first") else ...
    override fun onBoxChanged(old: Box?, new: Box?) = Unit                   // the stale check covers a new scan
}
```

Rules:
- State a screen shows is `val` or `private set`; screens call actions, never assign.
- Actions return `Outcome`. The screen passes it to `messages.show(...)`, which toasts a message. Features keep no
  error-text fields.
- Anything that destroys the player's data takes a `Confirmed` argument (see "Primitives").
- Math lives in `:core`; a feature only gathers inputs, runs the search and keeps the result.

### Primitives (`state/`)

| Primitive | What it guarantees | Replaces |
|---|---|---|
| `saved(key, default, codec)` | One declaration puts a field in the state file, backups, restore and erase, and saves it 0.5 s after a change. A field marked `keepOnErase` (theme, proxy) survives erase. | `stateJson()`, `apply()`, `eraseAll()` lists and `touch()` calls |
| `savedFile(name, backup = true)` | The same for whole documents (history, results, progress); atomic writes. | hand-made files per feature |
| `Store` | Backup = every field and file; restore = every one (defaults for missing keys); erase = every one; numbered migrations for old state files. | per-field code in `AppModel` |
| `work(name, weight)` → `Work<R>` | `start { }` cancels the previous run; a cancelled run never publishes; `busy`, `progress`, `cancel()`; `foreground = true` shows the shared notification with Stop; `checkpoint(...)` and `resumeFrom` make long runs resumable. | job + generation counter + busy flag + `PoolService.start/stop` per feature |
| `freshWork(name) { inputs }` → `FreshWork<K, R>` | The result remembers the inputs it was computed from; `stale` compares them with today's. Every screen, button and action uses the same check. | hand-written comparisons in several places |
| `Outcome` | `Done`, `Note(text)`, `Failed(text)`; shown as a toast by `Messages`. | inline error fields |
| `Confirmed` | Only `Messages.confirm(title, body)` can create one (its constructor is internal to `:kit`). | per-button confirmation checks |
| `Nav` | A stack of `Entry(screen)`; `App` wraps each entry in a `SaveableStateProvider`, so `rememberSaveable` state (tabs, open sections, list length) survives leaving the screen and coming back. The back link's label is the previous entry's title. | screen state in `AppModel` or lost in `remember` |

Each primitive has its own JVM tests. Two generic tests cover every feature, present and future:
- every `saved` field and `savedFile` survives backup → restore with a non-default value;
- after `erase()` every field reads its default and every file is gone, except the ones marked `keepOnErase`.

### Screens

One package per mode: `features/findgear/` holds `FindGearFeature.kt`, `FindGearScreen.kt`, `FindGearSheets.kt`
(and `FindGearOverlay.kt` if it draws over the game). A screen is a function of the feature and its entry:

```kotlin
@Composable fun FindGearScreen(f: FindGearFeature) {
    var tab by rememberSaveable { mutableStateOf(Tab.SetUp) }      // survives navigation (Nav)
    Frame(title = "Find gear", kana = "サガス",
          primary = Action("Find gear", enabled = !f.busy) { f.find() }) {
        ...
    }
}
```

Screens, sheets and overlay pages are registered in sealed types (`Screen`, `Sheet`, `OverlayPage`); `App` routes
with an exhaustive `when`, so a new screen without a route or a title does not compile.

### Services

| Service | Job |
|---|---|
| `WorkService` | The foreground notification while any `Work` with `foreground = true` runs; Stop cancels it. Wake lock at most 1 h. |
| `OverlayService` | Draws the tracker and checklist pages over the game; reads and writes only `graph.tracker` and `graph.checklist`. |
| `CaptureVpnService` | Local VPN (hev-socks5-tunnel, native, from SocksTun) that reads the game's traffic during a scan. |

## :kit

The kit is the only module that touches Compose layout details. Screens compose kit parts; most kit internals are
`internal` to the module, so a screen can't reach past them.

| Part | Contract |
|---|---|
| `Frame(title, kana, primary, secondary)` | Draws the back link (none on the first screen), the title, the content and the action row. The back link comes from `LocalBack`, a small kit interface (`label`, `go()`) that `App` provides from `Nav`, so the kit never depends on the app. At most one pink button per screen: `primary` is a value, not a slot. |
| `SheetBody(title, primary)` / `TallSheet` | Sheets end at their content; only `TallSheet` may fill the screen. |
| `Panel` (overlay) | Sizes to its main page and keeps that height while one thing is shown; pages split themselves to fit. |
| Text roles: `Title`, `Heading`, `Body`, `Note`, `Value`, `Label` | Size, weight, colour and line rule per role. One-line roles shrink to fit or end in "…". |
| Values: `ChanceText`, `GoldText`, `CountText`, `StatAmount` | One way to write each kind of number: "25%", "1 in 12", "~730k gold", "1 piece / 2 pieces", icon + value. |
| Rows and grids: `RowCard`, `ListRow`, `CardGrid`, `Fold`, `Pill` | Even rows, fixed trailing slots, centred short last row. |
| Controls: `ValueSlider`, `Segmented`, `Toggle`, chips | Snapped stops; the value opens the keyboard only with `@OptIn(KeyboardInput::class)`. |
| Messages host | Toasts, tap-to-explain, the confirm dialog that issues `Confirmed`. |
| Theme | Light and dark palettes; colours only through roles. |

Discouraged parts need an explicit opt-in at the call site (Kotlin `@RequiresOptIn`), so each use is a visible
decision in review:

| Opt-in | For |
|---|---|
| `@OptIn(LinkStyle::class)` | `LinkText` (prefer a row, pill or fold) |
| `@OptIn(KeyboardInput::class)` | typed number fields (only the optimizer's stat minimums and slider values) |
| `@OptIn(FixedSize::class)` | a frame with a fixed height (say why in the call) |
| `@OptIn(Scrolling::class)` | scroll containers in overlay pages (they must page instead) |

## Checks that stay

Structure can't express everything. These stay as tests:
- `:core` — the gear math against brute force and recorded results.
- `SourceRulesTest` — fonts cover every character used; every icon exists in the image store; no game data in the
  APK; colours come from the palettes; requests go through `Net`.
- `ScreenAudit` (emulator) — every screen at 353 dp with large text and 411 dp, English and Russian: text cut or
  broken mid-word, past the edge, layout shift when a state flips, frames with large empty areas. It runs on the
  synthetic box by default (`./gradlew syntheticFixtures`), or on your own backup with `E7_FIXTURES`.

## How to

- **Add a setting:** `var x by saved("x", default)` in the feature that owns it. Backup, restore and erase follow.
- **Add a screen:** a `Screen` subtype with its title, a route in `App`, a `@Composable` in the feature package
  using `Frame`.
- **Add a background search:** `private val s = work<R>("name", Weight.Heavy)`, or `freshWork("name") { inputs }` if its result can go stale.
- **Show a number:** a value type from `:core/value` and its kit renderer; never format a number in a screen.
- **Delete or reset data:** an action that takes `Confirmed`; the screen calls `messages.confirm(...)` first.
