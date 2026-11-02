# Refactor plan: from AppModel to features

How to get from today's code to [ARCHITECTURE.md](ARCHITECTURE.md), in steps that each build, pass every check and
change no behaviour except the bugs listed. Written 2026-11-02 against `gear-modes` d413d10.

## Decisions in force (assumed 2026-11-02, the maintainer may revise)

- Steps 0–5 come before release 1.1; the release itself (step 6) and any push wait for the maintainer.
- One Heavy search at a time (pool, Sell review, Improve heroes check, Optimize's pool); the tracker's upgrade check is Yielding.
- "Erase all data" erases every account field and file, history included; theme, proxy and overlay opacity are `keepOnErase`.
- Make room is removed (dropped on 2026-11-01); its cleared marks move to `MarksFeature`.
- Step 5 moves Find gear onto the new structure without its visual redesign; the redesign follows the maintainer's calls.
- Work happens on branch `architecture` (worktree `../e7-gear-arch`), never pushed until the maintainer says so.

## Where we start

| Layer | Lines | State |
|---|---|---|
| `opt/`, `calc/`, `data/` (39 files) | ~4,900 | Pure Kotlin already: no Android imports, no Compose state, no UI references. 156 unit tests. Moves into `:core` as it is. |
| `AppModel.kt` | 2,546 | 18 sections, one per feature, sharing ~60 fields. Every feature re-implements saving, erasing, cancelling, freshness and errors. |
| `ui/`, `overlay/` | ~8,600 | A good kit exists but is optional; screens bypass it. |
| Global objects with state | 8 | `OverlayState` (23 fields), `Capture`, `PoolService` companion, `Mirror`, `Http`, `GameImages`, `Themes`, `Problems`. |

### Bugs the restructure removes (they come from the structure, so each is fixed by a step, not by a patch)

| Bug | Cause | Fixed in |
|---|---|---|
| "Erase all data" keeps the history and ~15 settings, and running work keeps going | erase list written by hand; `touch()` re-saves leftovers | step 4 |
| Two long searches fight over one notification: starting one overwrites the other's Stop; any stop removes the notification for all | `PoolService` has one `onStop` and one `title` | step 7 |
| Import, restore and erase leave a pool run, a search, a cleanup or a hero-upgrade check running on the old box | each feature cancels by hand; most were never added | step 7 |
| Import doesn't drop kept / cleared / sold marks for pieces no longer in the box (only a restart does) | filtering only in `apply()` | step 8 |
| "Is something else running?" differs per feature (pool checks one other search, cleanup three, Find gear none) | ad-hoc guards | step 7 |
| Find gear's list goes stale when any piece is tracked | freshness compared by hand in three places | step 5 |
| Errors shown inline, on one tab, and kept | per-feature error fields | steps 5, 8–12 |
| Improve heroes forgets By hero / By piece; Find gear forgets its tab | screen state in `remember` | step 3 (Nav) |
| "1 pieces", three ways to write a chance | numbers formatted in screens | steps 5, 13 |
| Overlay panel two-thirds empty | fixed 280 dp height | step 10 |
| Leaving the app with Back leaves Sell review, Find gear and hero-upgrade runs working for a discarded model, so their results are lost | state lives in an Activity's ViewModel; `onCleared` cancels the pool and the tracker check only | steps 3 and 7 (state and work live in the Application) |

## Target layout

```
settings.gradle.kts            include(":core", ":kit", ":app")
gradle/libs.versions.toml      one place for versions
core/                          kotlin("jvm")
  src/main/java/e7/gearscan/{data,calc,opt,value,net}/
  src/test/java/...          today's unit tests + testing/SyntheticBox.kt
kit/                           com.android.library + Compose
  src/main/java/e7/gearscan/kit/
    Theme.kt  Type.kt  Frame.kt  Sheets.kt  Rows.kt  Grid.kt  Controls.kt  Values.kt  Messages.kt  OptIns.kt
app/
  src/main/java/e7/gearscan/
    E7GearApp.kt  AppGraph.kt  MainActivity.kt  App.kt (routing)
    state/        Store.kt  Codecs.kt  Migrations.kt  Work.kt  Fresh.kt  Feature.kt  Nav.kt
    services/     WorkService.kt  OverlayService.kt  CaptureVpnService.kt  CaptureProxy.kt
    features/
      scan/          ScanFeature.kt + screens (scan flow, import, export, backup/restore/erase)
      content/       ContentFeature.kt (game content, images base, mirror), AppUpdateFeature.kt
      heroes/        HeroesFeature.kt (setups, tiers, presets, equip/record build) + hero screens
      optimize/      OptimizeFeature.kt (one-hero search, rescue, ghost) + screens
      pool/          PoolFeature.kt + screens
      history/       HistoryFeature.kt + screen
      marks/         MarksFeature.kt (cleared, kept, sold marks; shared by sell review and tracker)
      sellreview/    SellReviewFeature.kt + screens
      improveheroes/ ImproveHeroesFeature.kt + screens
      findgear/      FindGearFeature.kt + screens
      tracker/       TrackerFeature.kt (upgrade tracker), ChecklistFeature.kt, overlay pages
      settings/      SettingsFeature.kt (consent, proxy, theme, rating), FeedbackFeature.kt
  src/main/jni/                unchanged (hev-socks5-tunnel)
```

Package names stay `e7.gearscan.*` and source folders keep their `java` names; files move with `git mv` so history follows them.

## Primitives: exact API

These are written first (step 3), with their own JVM tests, before any feature moves.

```kotlin
// state/Codecs.kt — how a saved value is written to and read from JSON
interface Codec<T> { fun write(value: T): Any?; fun read(json: Any?): T? }   // read null → the field's default
object Codecs {
    val bool: Codec<Boolean>; val int: Codec<Int>; val long: Codec<Long>; val double: Codec<Double>; val string: Codec<String>
    fun <E : Enum<E>> enum(values: Array<E>): Codec<E>
    val longSet: Codec<Set<Long>>; val intSet: Codec<Set<Int>>; val longList: Codec<List<Long>>
    fun <T> obj(write: (T) -> JSONObject, read: (JSONObject) -> T): Codec<T>    // PotentialQuery, SellQuery, …
}

// state/Store.kt — every saved field and file of the app, in one registry
class Store(disk: Disk, val version: Int, migrations: List<Migration>, scope: CoroutineScope) {
    fun <T> field(key: String, default: T, codec: Codec<T>, keepOnErase: Boolean = false): SavedField<T>
    fun file(name: String, backup: Boolean, keepOnErase: Boolean = false): SavedFile
    fun load()                         // once at start: state.json (migrated) → every field
    fun backup(): JSONObject           // { app, version, fields, files }
    fun restore(json: JSONObject)      // migrates old backups (v1 = today's format), then every field and backed-up file
    fun erase()                        // every field to its default, every file deleted, except keepOnErase
}
class SavedField<T> : ReadWriteProperty<Any?, T>   // snapshot state inside; a set schedules an atomic save in 0.5 s
class SavedFile { val exists: Boolean; fun read(): String?; fun write(text: String); fun delete() }   // atomic tmp + rename
class Migration(val from: Int, val apply: (JSONObject) -> JSONObject)

// state/Work.kt — background work with one owner
enum class Weight { Light, Heavy, Yielding }  // Heavy: one at a time; Yielding: cancelled when a Heavy starts
class WorkHost(app: Application, scope: CoroutineScope) {
    val active: List<Work<*>>                  // snapshot state; WorkService shows the Heavy one with its own Stop
    fun cancelAll()                            // import, restore, erase and onCleared call this
}
class Work<R>(val name: String, host: WorkHost, val weight: Weight, progress: SavedFile? = null) {
    val busy: Boolean; val fraction: Float?
    fun start(block: suspend WorkScope.() -> R): Outcome   // Failed("Finish or stop <name> first") when a Heavy one runs
    fun cancel()
}
interface WorkScope : CoroutineScope {
    fun progress(done: Int, total: Int)        // throttled to 1/s for the notification
    suspend fun checkpoint(state: JSONObject)  // written to the progress file; resumable runs read resumeFrom
    val resumeFrom: JSONObject?
    fun onCancel(action: () -> Unit)           // e.g. SearchRun.cancel() for the CPU-bound engine
}

// state/Fresh.kt — a result that knows its inputs
class FreshWork<K, R>(name: String, host: WorkHost, weight: Weight, inputs: () -> K) {
    val result: R?; val busy: Boolean
    val stale: Boolean                         // result's key != inputs() now
    fun start(block: suspend WorkScope.(key: K) -> R): Outcome
    fun cancel()
}

// kit/Messages.kt
sealed interface Outcome { data object Done : Outcome; data class Note(val text: String) : Outcome; data class Failed(val text: String) : Outcome }
class Messages { fun show(o: Outcome); fun explain(title: String, body: String); suspend fun confirm(title: String, body: String, action: String): Confirmed? }
class Confirmed internal constructor()     // only Messages.confirm can make one

// state/Nav.kt
sealed interface Screen { val title: String; val kana: String? get() = null }
class Entry(val id: Long, val screen: Screen)
class Nav(start: Screen) { val stack: List<Entry>; val sheet: Sheet?; fun go(s: Screen); fun replace(s: Screen); fun open(s: Sheet); fun back(): Boolean }
// App.kt wraps each entry in rememberSaveableStateHolder().SaveableStateProvider(entry.id) and removes the state on pop.

// state/Feature.kt
abstract class Feature(protected val g: AppGraph, val id: String) {
    protected fun <T> saved(key: String, default: T, codec: Codec<T>, keepOnErase: Boolean = false) = g.store.field("$id.$key", default, codec, keepOnErase)
    protected fun savedFile(name: String, backup: Boolean = true) = g.store.file("$id/$name", backup)
    protected fun <R> work(name: String, weight: Weight = Weight.Light, progress: SavedFile? = null) = Work<R>(name, g.work, weight, progress)
    protected fun <K, R> freshWork(name: String, weight: Weight = Weight.Light, inputs: () -> K) = FreshWork<K, R>(name, g.work, weight, inputs)
    open fun onBoxChanged(old: Box?, new: Box?) {}   // after a scan, import, restore or erase, with all work already cancelled
}
```

Generic tests that cover every feature, present and future (step 3–4):
- every registered field and backed-up file survives `backup()` → `erase()` → `restore()` with a non-default value;
- after `erase()` every field reads its default and every file is gone, except `keepOnErase`;
- a v1 state file and a v1 backup (today's format) load with every value in place (synthetic fixtures in the repo;
  your real backup locally through `E7_BACKUP`);
- `Work`: a cancelled run never publishes; a second Heavy start is refused with the first one's name; `cancelAll`
  stops everything; a Yielding run is cancelled when a Heavy one starts;
- `FreshWork`: stale flips when an input changes and only then.

## Where everything in AppModel goes

| AppModel section (lines) | New home | Saved (`saved` / `savedFile`) | Work | Notes |
|---|---|---|---|---|
| navigation, sheet, toast, confirm, explain (256–305, 720–729) | `Nav`, `Messages` | — | — | `askReset` → `messages.confirm`; `sheetUnder` → `Nav` |
| content, mirror, images base (308–414) | `ContentFeature` | `checkedAt`, `mirror` | content check (Light) | `contentStatus` → `Outcome`; `GameImages.base` → `LocalImageBase` |
| app update (316–376) | `AppUpdateFeature` | — | download (Light) | `updateProblem` → `Outcome` |
| box, scan flow, capture (205–244, 745–861) | `ScanFeature` | `box.json` as `savedFile`, `lastScan`, `lastDiff`, `decodeSec` | import (Light) | `Capture` object → `CaptureSession` owned here; import calls `g.work.cancelAll()` then every `onBoxChanged` |
| heroes, setups, presets, build actions (433–566, 679–744) | `HeroesFeature` | `configs`, `tiers`, `team`; `builds/` cache files | preset loads (Light, 2 at a time) | `matchFor`, `matchOf`, `setupKey` live here; equip / record / undo |
| search (567–678) | `OptimizeFeature` | — | search, rescue, ghost (Light; engine cancel via `onCancel`) | `search` state stays in memory |
| whole pool (862–1076, 2034–2135) | `PoolFeature` | `tiers`, `extra`, `skip`, `losses`, `effort`, `secondsPerHero`, `pendingPool`, `lastPool` | pool run (Heavy, foreground) | `poolKeep/Give` in memory; checklist loading → `ChecklistFeature` |
| history (2137–2198) | `HistoryFeature` | `history.json` | — | erased by erase (today it is not) |
| cleared / kept / sold marks (1557–1573) | `MarksFeature` | `cleared`, `kept`, `sold` | — | `onBoxChanged` drops marks for pieces no longer in the box |
| sell filters (1077–1194) | `SellReviewFeature` | `query`; `sell_progress.jsonl` as progress file | review (Heavy, resumable), curve (Light) | freshness from `sellKey()`; `sellTab` → `rememberSaveable`; `sellError` → `Outcome` |
| gear potential (1195–1287) | `FindGearFeature` | `query` | search (Light) | inputs = query, scan, recorded steps only |
| upgrade observations (1288–1552) | `TrackerFeature` | `upgrades.json` | upgrade check (Yielding, foreground) | `UpgradeTracker` callbacks become feature calls; `error`/`notice` → `Outcome` |
| clean up (1553–1717) | removed (Make room was dropped on 11-01); its marks go to `MarksFeature` | — | — | `FinishCheck` stays in `:core` if Sell review uses it, else removed |
| improve heroes (1718–2033) | `ImproveHeroesFeature` | `minGain`, `rebuilds`; `hero_upgrades.json`, progress file | check (Heavy, resumable), final build (Light) | key = `heroUpgradeKey()` as today |
| over the game (2199–2271) | `TrackerFeature` + `SettingsFeature` | `overGameNative`, `nativeSeen`, `overlaySeen` (`keepOnErase`) | — | `floating` in memory |
| reports, crash, files (2272–2380) | `FeedbackFeature`, `ScanFeature` | — | report flush (Light) | `lastError`, `lastCrash` stay report-only |
| settings (215–247) | `SettingsFeature` | `consent`, `proxyOn`, `proxy`, `showRating`, theme (`keepOnErase`) | — | `Themes` object → here |
| `OverlayState` (23 fields) | `ChecklistFeature` (build, done, order, recorded, `onFinish` required; `checklist.json`), `TrackerFeature` (upgrade), `OverlayWindow` in `services/` (opacity, tab, bubble position, dragging) | checklist progress; opacity (`keepOnErase`) | — | `load(build, onFinish)` without a default |
| `PoolService` companion | `WorkHost` + `WorkService` | — | — | one notification per Heavy run, its own Stop |
| `Http`, `Problems`, `Mirror` | `AppGraph.net`, `AppGraph.problems`, `ContentFeature` | — | — | tests pass a fake `Net`; the "refuse amazonaws under instrumentation" switch goes |

## Steps

Each step is one branch and one pull request, squash-merged. Gate for every step: `./gradlew :core:test
:app:testDebugUnitTest assembleDebug lint`, the filtered ScreenAudit for the screens it touches, and the full audit
plus every screen suite at steps 2, 4, 7, 10 and 12. Size: S under an hour, M about a session, L one to two sessions.

| # | Step | Size | Done when |
|---|---|---|---|
| 0 | No-reply author address for the repo; Gradle Kotlin DSL; `libs.versions.toml`; `.editorconfig` + ktlint | S | build unchanged, ktlint runs |
| 1 | `:core` module: `git mv` `opt/`, `calc/`, `data/` and their tests; `org.json` compileOnly | S | APK identical in behaviour; `:core:test` runs in seconds |
| 1b | Format `:core` with ktlint (its own commit, so `git blame` skips it) | S | no logic diff |
| 2 | `:kit` module: Theme, text, Frame, sheets, rows, grids, controls, values, messages hosts | M | full audit clean |
| 3 | Primitives (`state/`) + `AppGraph` + `E7GearApp` + `Nav` with per-entry saved state; the ~15 screen states the player returns to (tabs, open sections, list lengths) switch to `rememberSaveable`; JVM tests above | M | tests pass; AppModel still in charge; Improve heroes and Find gear keep their tabs after visiting another screen |
| 4 | `Store` takes over persistence: all 32 fields to `saved`, files to `savedFile`, state v2 with a v1 migration, backups v1 readable; erase complete | M | generic store tests; your real backup restores on the emulator; "edits survive a restart" passes |
| 5 | Find gear on the new structure, with its redesign (your 4 calls): `FindGearFeature`, screens with `Frame(primary)`, `Chance`/`Count`/`StatAmount`, `Outcome` | L | Find gear suite + filtered audit + quick render + fresh reviewer |
| 6 | Release 1.1 (see "Commit history") | S | release on GitHub |
| 7 | `WorkHost` + `WorkService` take over every long run; Heavy / Yielding policy; cancel on import, restore, erase, close | M | full audit; a two-search test shows the second refused with the first's name |
| 8 | `MarksFeature` + `SellReviewFeature` | M | Sell review suites + audit |
| 9 | `ImproveHeroesFeature` | M | HeroUpgradeScreens + audit |
| 10 | `TrackerFeature`, `ChecklistFeature`, overlay `Panel`; `OverlayState` deleted | L | overlay suites (Queue, TrackPiece, Upgrade, UpgradeWindow, NoScroll) + audit |
| 11 | `HeroesFeature`, `OptimizeFeature`, `PoolFeature`, `HistoryFeature` | L | pool and search audit steps; `PoolBackupTest` on your backup |
| 12 | `ScanFeature`, `ContentFeature`, `AppUpdateFeature`, `SettingsFeature`, `FeedbackFeature`; `AppModel.kt` deleted | M | full audit + all suites |
| 13 | Lock-down: `PinkButton` private to the kit, opt-ins for `LinkText` / keyboard / fixed size / overlay scrolling, text roles screen by screen; delete the five source checks that became structure | M | compile errors fixed; `SourceRulesTest` down to its five file checks |
| 14 | Open source: CI, synthetic fixtures for the audit, CONTRIBUTING, templates, README screenshots | M | CI green on a pull request from a fork |

## Open-source setup (step 14)

- **CI** (`.github/workflows/ci.yml`, every pull request): checkout with submodules, JDK 17, Android SDK + NDK r29
  (cached), `./gradlew :core:test :app:testDebugUnitTest lint assembleDebug`. A second workflow, run by hand or
  nightly, boots an emulator (`reactivecircus/android-emulator-runner`) and runs the ScreenAudit on the synthetic box.
- **Synthetic fixtures**: `SyntheticBox.generate(seed)` builds a deterministic account (heroes with setups, gear at
  every level including +15, every set and slot) and writes `backup.json`, `gear.txt` and `content.json` for the
  emulator tests. Nobody needs a real account to run any test; your own backup still works through `E7_FIXTURES`.
- **Docs**: README (screenshots, building with the submodule and NDK, what leaves the phone), ARCHITECTURE.md,
  CONTRIBUTING.md (setup, tests, UI rules, commit style, what a pull request needs: screenshots at 353 dp with large
  text), UI_COOKBOOK.md with "owner" changed to "maintainer", issue and pull-request templates.
- **Releases** stay with the maintainer: `tools/release.sh` signs with the key in your Keychain; CI never sees it.
- **Dependency updates**: Dependabot on `libs.versions.toml` and the GitHub Actions versions.

## Commit history

### What is public

`origin/main` has 126 commits: 112 from SocksTun, the VPN app E7 Gear started from, and 14 of E7 Gear's own, with
release tags v1.0.0–v1.0.3. Checked on 2026-11-02:
- no game data or game images were ever committed;
- no release key or password (signing reads `E7_RELEASE_PASSWORD` from the environment); the only keystore is
  SocksTun's public debug key;
- every E7 Gear commit is authored with the GitHub no-reply address; no phone serials, account files or home paths;
- 1 star, 0 forks.

The messages read like a changelog. **Keep it as it is**: rewriting would move the release tags the in-app updater
reads, for no gain.

### What is not public yet

`gear-modes` holds all unpublished work (`next`, `hero-upgrades`, `known-issues` and `scan-status` are inside it):
63 commits since 1.0.3, in design-round steps. They can't be pushed as they are:
- 8 of them are authored with a personal email address instead of the no-reply one;
- the "Snapshot: uncommitted work on next" commit mixes four features and contains a path to a private fixture.

### Plan

1. **Step 0:** `git config user.email 59008922+Sod3n@users.noreply.github.com` for this repository (worktrees share
   it), so no new commit carries a personal address.
2. **At 1.1 (step 6):** a `release/1.1` branch from `origin/main` with a short series of commits written fresh, each
   building and passing tests on its own:
   1. `Gear tab: Sell review, Improve heroes and Find gear as the three gear modes`
   2. `Upgrade tracker over the game: +3 steps, queues, sold and keep marks`
   3. `Scan status line and floating pill`
   4. `Feedback: already-known issues card`
   5. `Tests run offline and never call third-party services`
   6. the architecture steps 0–5, one commit each
   7. `1.1.0`
   Before the push: `git log -p release/1.1 ^origin/main` is searched for personal addresses, device serials and
   home-directory paths.
3. **Archive locally:** tag the old branches (`archive/gear-modes-2026-11-02`, …) and never push them; the reasons live
   on in HANDOFF and the new commit bodies.
4. **After 1.1:** one branch per change, a pull request into `main`, squash-merged; summary line in the imperative,
   body says why. Branch protection on `main`: CI must pass.
