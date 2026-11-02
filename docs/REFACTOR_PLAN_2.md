# Refactor plan 2: explicit dependencies (proposed 2026-11-02, waits for the maintainer)

Written against `architecture` cb3a872 (audit and all 14 device suites green). Goal: a feature can be read and
tested on its own. Today every feature gets the whole `AppGraph` (`class XFeature(g: AppGraph)`), so any feature can
call any other, and some do it both ways. Same rule as plan 1: each step builds, passes every check and changes no
behaviour. A step that turns out to need a behaviour change stops and goes to the maintainer.

## Where we start (measured on cb3a872)

- 15 feature classes, each constructed with the whole graph; 100 screen functions take `graph: AppGraph`.
- Feature logic (screens excluded) connects to other features along 52 paths: 38 point down the layer order below,
  14 point up (34 references). `FeatureLayersTest` (step 0) checks this in the unit gate.
- `TrackerFeature.kt` is 803 lines and 47 functions: records, queue, editing, equip/sold marks, the upgrade check and
  the overlay in one class.
- Global objects with state: `Http` (`agent`, `blockThirdParty`), `Mirror.active`, `Themes` (`choice`, `systemDark`),
  `Ghost.nextId`. ARCHITECTURE.md says there are none (line 81) and shows an old `AppGraph` constructor (line 52).

## Layer order (low to high; a feature may use only features below it)

| Layer | Holds |
|---|---|
| 0 | Services in `AppGraph`: store, work, messages, nav, platform, disk, **rev** (new), **http** (new) |
| 1 | settings, appUpdate |
| 2 | content |
| 3 | feedback |
| 4 | scan |
| 5 | marks, **records** (new, the tracker's upgrade records) |
| 6 | heroes |
| 7 | history |
| 8 | optimize |
| 9 | pool |
| 10 | sellReview, improveHeroes, findGear |
| 11 | tracker |
| 12 | checklist, then overGame |
| UI | screens and sheets: may read any feature (decided: they keep taking the graph) |

## The 14 wrong-way connections and their fix

| From -> to | Where | Fix |
|---|---|---|
| scan -> sellReview, findGear, improveHeroes, heroes, tracker (8 calls) | ScanFeature.kt:96-140 (`adopt`, `import`, `restore`, `erase`) | Box events on `Feature`: `onBoxReplacing`, `onBoxChanged` (exists), `onBoxAdopted`, `onImported`, `onRestored`, `onErased`. `AppGraph` calls them in the order the calls run today (step 1). |
| content -> scan, settings -> scan (`scan.touch()`) | ContentFeature.kt:71, SettingsFeature.kt:20 | `rev` is an app-wide "inputs changed" counter, not a scan field: move it to `AppGraph.rev` (27 reads in 9 files, 33 `scan.touch()` calls become `rev.bump()`) (step 1). |
| feedback <-> scan (scan log) | FeedbackFeature.kt:44-46, ScanFeature.kt:222-226 | `saveScanLog(text)` takes the text; the Feedback screen passes the live log. Feedback no longer knows scan (step 2). |
| heroes -> pool (`pool.members()`) | HeroesFeature.kt:176 `inUse` | `inUse` is "gear worn by pool members": move it to `PoolFeature`; its two callers (improveHeroes, tracker) are above pool (step 3). |
| history -> pool, history -> optimize | OptimizeFeature.kt:114, HistoryFeature.kt:41-60 | History becomes the log below optimize and pool, so optimize -> history points down (entries, `record`, `restoreBuild`, `flag`). `openHistory` moves out: `pool.openFromHistory(e)` and `optimize.openFromHistory(e)`; the History screen picks one (step 3). |
| sellReview, improveHeroes, findGear -> tracker (records) | SellReviewFeature.kt:71,109,169,174; ImproveHeroesFeature.kt:131-147; FindGearFeature.kt:43,63,98 | Records (`records`, `recordedSteps`, `upgrades.json`, `syncMarks`) move into a new `RecordsFeature` at layer 5 (step 4). |
| same three -> tracker (`track*`) | ImproveHeroesFeature.kt:252-254 (pass-throughs), FindGearFeature.kt:102,116, SellReviewFeature.kt:157 | The tools return what to track; their screens call `graph.tracker.track...` (step 4). |

## Steps

| Step | What | Files (main) | Who |
|---|---|---|---|
| 0 | Characterisation tests on cb3a872, green before anything moves: what `adopt`/`import`/`restore`/`erase` leave behind (sell review and find gear results cleared, improve heroes reloaded, `afterImport` effects, marks synced, tracker reset); opening a pool and a single history entry; `inUse`; `rev` bumps and freshness. Seam commit: `Revision` and `AppGraph.rev`; the box-event hooks on `Feature`, raised by `AppGraph` and already used by `ScanFeature` (its 8 direct calls become 5 overrides; done here so step 4 does not collide with step 1 in the tracker). `FeatureLayersTest`: the table above as a unit test with a list of connections not yet fixed that may only shrink. | app/src/test, state/, AppGraph.kt, ScanFeature and the 5 features with hooks | me + 1 agent (tests) |
| 1 | `rev` move: `scan.rev`/`scan.touch()` become `g.rev.value`/`g.rev.bump()` | ScanFeature, ContentFeature, SettingsFeature and every `rev` reader and `touch()` caller | agent A |
| 2 | Feedback below scan | FeedbackFeature, ScanFeature (3 lines), Feedback screen | agent A |
| 3 | `inUse` to pool; history as a log; `openFromHistory` | HeroesFeature, PoolFeature, HistoryFeature, OptimizeFeature, HistoryScreen, ImproveHeroesFeature:155, TrackerFeature:668 | agent B |
| 4 | Tracker split: `RecordsFeature` (records, `recordedSteps`, `upgrades.json`, reload/reset/restore, `syncMarks`); `UpgradeCheckRun` (results, `expected`, `busy`, `error`, goals, `runCheck`/`begin`/`checkpoint`/`invalidate` and the derived counts) owned by the tracker; `TrackerFeature` keeps queue, selection, editing, equip/sold and the overlay (target under 450 lines). Tools read records; `track*` calls move to screens. | features/tracker, sellreview, improveheroes, findgear and their screens | agent C |
| 5 | Globals: `Http` becomes an instance from `AppGraph` passed to content, appUpdate, feedback and presets (`blockThirdParty` set by the test graph, not by class lookup); `Mirror.active` becomes a parameter of the URL builders in `:core`; `Themes` becomes a CompositionLocal fed from settings. `Ghost.nextId` stays: a private allocator of process-wide unique negative ids (a pool run shows several heroes' searches together, so per-search counters could repeat an id on one screen); documented as the one exception. ARCHITECTURE.md lines 52 and 81 corrected, layer table added. Audit: the ghost step warns and skips when the search has no ghost (tonight's false "no back link"). | core/data, core/opt/Ghost.kt, kit/ui/Theme.kt, AppFrame, docs, ScreenAudit | agent D |
| 6 | Constructors: `Feature(ctx: FeatureContext, id)` where `FeatureContext` holds the layer-0 services; each feature takes the features it uses as constructor parameters; `AppGraph` builds them in layer order, so the compiler rejects a reference upward. Tests can build one feature with fakes below it. | all 15 features, AppGraph, TestGraph | agent A |
| 7 | Verification (below) and merge to `architecture` | | me |

Waves: step 0 first (seam, so parallel work meets the same interfaces). Then steps 1-2 (A), 3 (B) and 4 (C) in
parallel, each in its own worktree from the seam commit; step 5 (D) starts when B, the smallest, is merged; files overlap only in `AppGraph` (one line each) and
`ScanFeature` (A only; C's `syncMarks` call goes through A's hook). I merge in the order 1-2, 3, 4, 5 and run the unit
gate after each merge. Step 6 runs alone after that, because it touches every feature.

## Acceptance (all must hold; checked by me, numbers in the merge commit)

1. `FeatureLayersTest`: its not-yet-fixed list is empty (14 at cb3a872, 9 after step 0); no feature constructor takes `AppGraph`.
2. No `object` holding a `var` in `:core`, `:kit` or `:app` main code except `Ghost.nextId` (today 4 objects, 6 vars).
3. `TrackerFeature.kt` under 450 lines; no file under `features/` over 800 lines.
4. The step 0 tests pass unchanged from cb3a872 to the end.
5. Unit gate: `./gradlew --rerun-tasks :core:test :core:syntheticFixtures :app:testDebugUnitTest :app:assembleDebug
   :app:assembleDebugAndroidTest :app:lintDebug :core:ktlintCheck`, 0 failed.
6. Device: `audit_chunks.sh` shows only the known Improve heroes row finding; `suites_chunks.sh` 14 of 14 pass.

## Not in this plan

- The 100 screen functions keep taking the graph: a screen reading its state holders is normal for Compose, and
  rewriting them is churn with visual risk.
- Find gear's redesign, text roles and value types (`Chance`, `Gold`, `StatAmount`) stay deferred as in plan 1.

## Cost

Plan 1 was 74 commits in about 6 hours. This is about 30 commits: about 1 hour for step 0, about 1.5 hours for the
parallel wave, about 1 hour for step 6 and about 45 minutes of device checks (emulator; it wedges when the Mac swaps,
the scripts restart it). Agents: at most 3 Sonnet subagents at once (A, B, C; then D in B's place), then 1 for step 6.
