# E7 Gear UI cookbook

Read this before designing or changing any screen, sheet or overlay page. It is built from the maintainer's corrections
(quoted). Examples show how a principle was applied once; they are not the rule. When a new case comes up, apply the
principle, not the nearest example.

## Principles

### 1. One glance, one decision
A player looks at a card for a second or two to decide what to do with it. Each card, row or tile answers one question,
and the fact that decides it is the biggest thing there. Everything else is secondary, folded, or one tap away.
- Said: "too much info with same weight"; "it requires too much to read to understand what this procent mean";
  "still doesnt feel understandable from glance. Needs real comprehension".
- Ask: what is the player deciding here? What single fact decides it? Is that fact the most visible thing?
- Example: the Sell review card leads with ES now → usual at +15 and one chance chip; the roll history moved to details.
- Prefer a visual (bar, chip, icon, colour) over a sentence that says the same.
- Two numbers that mean different things never sit side by side on one row. Said, on an Improve heroes row showing
  "best ~63%" next to an "89% now" chip: "this one kind still not very clear". The row kept the chance; the hero's
  current match moved to its own page.

### 2. Every word must earn its place
If a player would skip a text, it is noise and it hides what matters. Explanations are for the player who asks: fold them
or put them behind a tap.
- Said: "Remove ALL text that doesnt provide ACTUALLY important information and what user will dont just ignore as noise";
  "this text is reduntant"; "something user would ignore"; "too much text".
- Ask: if this line disappeared, would a player miss it? Does another element already say it?
- Said (2026-11-03), on "ES 47 → 80" in Sell review: "It is not very clear what exactly ES 47 -> in sell review means", then, on
  my fix that added "now · usually 80 at +15" under it: "it doesnt mean that you can just add text and expect it to help. Stop this
  \"lazy\" UX decisions that leads to text overwhelming". An unclear number is fixed by how it is shown (the game's own
  conventions, position, colour) or by removing it, never by a caption.
- Example: a chance repeated next to a bar that already showed it was removed; explanations of the keep-rolling tiles live behind a tap.
- Said (2026-11-03): "keep the rare-roll fold, but for curious users add description why so". The why is the first line inside
  the opened fold, not on the list.

### 3. Speak the game, in the player's units
Use the game's own terms and quantities (ES, gold, rolls, +3 steps, pieces), not our model's internals or ratios against
an internal baseline.
- Said: "games equipment score will feel more native"; "what means 22% more cost should mean for player?" → gold;
  "enchancment filter does make sense only on upgrade steps, not for each level".
- Said (2026-11-03): "can you show percents instead of \"1 in x\" metric? Everywhere in app." Chances are percents
  through `Chance` (two significant digits; 0% and 100% only when certain); SourceRulesTest bans "1 in N".
- Said (2026-11-03): "group batch reviews by set -> slot -> level, so you dont need to jump back and forward". A walk
  over the game follows the game's own filters: set filter, slot tab, level filter, then up the ES grid
  (`InventoryOrder.walk`). Then: "add this grouping to all gear screens, just on sell view only \"on\" by default". Every
  gear list has an order switch ending in "By set"; on, the list shows a header per set/slot/level and the walk follows
  the list as shown.
- Ask: could a player check this number against something in the game? Is the unit one they already think in? Does a
  walk over the game visit pieces in the order the game's filters find them?
- Example: GS became the game's Equipment Score; cost became gold; enhancement filters step +0/+3/…/+15.

### 4. Numbers are honest
Every number on a player screen is computed from that player's data. The player can tell how far to trust it:
exact, estimated, unknown or out of date. No verdict ("best", "safe", "worth it") unless a calculation shows it.
- Said: "when to keep rolling does not computed from user inventory?"; "we also need show that it is aproximate values";
  "it isnt feels like it actually best balance tho".
- Ask: where does this number come from, for this player? Is its precision visible? Would I defend the verdict word?
- Said (2026-11-03), over the game on a weapon the forecast called worth rolling ("11% reach 90", blue) under a pink
  "Sold ›": "are you sure that is something that I should sell?", then: "It is was +6 piece that I upgraded to +9 when saw
  4% chance to end >90gs". The pink button is a verdict too. It follows the forecast: Sold when the chip says sell, Record
  when it says roll on, Keep when it is at the bar, none without a forecast (`esKeeps`, EsVerdictTest).
- Example: keep-rolling tiles are computed live from the review list, marked as estimates, and explain themselves on tap.
  A placeholder or test value is never shipped looking like real data; if a build has one, the delivery message says so.

### 5. Touch the thing you mean
Controls do exactly what they look like, on exactly what was tapped. A tap on a setting opens that setting, not a page of
all settings. Buttons look like buttons; link-style text is rare. One main action per screen.
- Said: "clicking on different filters opened general window with all filters... I expected it to open only editing of
  filter on which I clicked"; "you generaly overuse link style buttons"; "why there this 3 links on bottom?";
  "having two main action buttons on screen feels strange"; "Window for ES value selection is BS. Just scrollbar with
  minimum and maximum".
- Link-style text is a third-level action, below outlined buttons: next to any other element it loses its weight, so
  it is only for actions that genuinely don't matter (a rare escape, "Report this"). An isolated link with space around
  it (the "‹ Back" at a screen's top) still reads. Anything the player needs (a recovery, cancel or stop, a main way
  in, a required permission) is a button, a row or a pill. Said (2026-11-04): link style is "even not secondary, but
  'thirdry' action that is very not important. It loses a lot of 'value' near any other element".
- The pink button follows the order of the work: review each hero's changes, then change gear in the game. Said
  (2026-11-04): "first review all heroes changes, and only after go to in game changing". A screen or sheet nothing can
  open is deleted, not kept ("Why they still in app?").
- Ask: what is the one thing a player does next here? Is it the only pink button? Does every tappable thing look tappable,
  and lead only where its label says? Is every link an action the player could do without?
- Said (2026-11-03), on a small "By set" pill put on each list's heading: "this toggle is kinda hard to see and feels out
  of place?" A pill reads as a tag, and it sat somewhere different on each screen. How a list is ordered is chosen with
  the app's switch for views (`Segmented`, like "By hero | By piece"), directly above the list it orders, the same way on
  every screen.
- Said (2026-11-03), on weapons that could still end at 83–93 in the Sell review list: "but my filters are set to not sell
  >90gs gear at all?" The ES bar and the worth-rolling chance only coloured chips; they never took a piece out of the
  list. A setting that says what not to sell removes those pieces from the sell list and the walk over the game (behind
  their own pill, like "kept by safeguards"), and a changed value updates the list at once.
- Said (2026-11-03), after I put per-slot ES bars in "More filters" for "move per piece ... ES bar filter be in 'advance'":
  "I think instead it should be near overall bar?" then "just folded, that was I mean initialy". The finer version of a
  setting folds right under that setting; "advanced" never means another screen.
- Example: settings are rows that open their own sheet; a value is a slider over the game's range, tap the number to type.

### 6. Nothing moves unless the player moved it
Toggling, loading, errors, empty states and async results never shift the content the player is looking at. State the
player expects to find again (a tab, an open section, a filter) is still there when they come back.
- Said: "make layout not shift if possible. We can make panels fixed size"; "Why going back from kept pieces windows opens
  matches instead of set up tab?".
- Ask: what changes on this screen after the player acts or data arrives? Does anything above the player's focus change
  height? Does state survive leaving and coming back?
- Example: the keep-rolling tile area has a fixed height whether or not numbers exist; errors from actions are toasts;
  the Sell review tab lives in AppModel, not in `remember`. The rail over the game keeps one height for a whole walk: a
  three-substat piece is measured as four, and a record step's pages keep the piece's height, so Sold, Keep and Back
  stay under the thumb (RailFitScreens checks it).

### 7. Reachable from where the player is
A mode the player uses often has a front door they can see, with its state on it. Tools are not buried under data or
settings screens, and flows don't depend on cross-links.
- Said: "Currently flow to this screens is complete bs"; "kept pieces should be its own full screen with filters/search".
- Ask: how does a player who just opened the app get here? What do they see before entering (status, count)?
- Example: the Gear tab has one raised card per mode.
- Said (2026-11-04), on the Gear tab: "All tab state chips looks stale or useless" and "Over the game: Continue tracking
  button is useless". The chips reported what the app held in memory ("Not checked yet", "Out of date" after any restart),
  not the player's gear, and the row opened a screen with nothing for a running walk. A front door shows state only when
  it is true for the player's data and worth acting on; the walk's own controls live on the rail (undo takes back the last
  mark or recorded +3, the bubble's dismiss target stops it).

### 8. Built for one thumb on a small phone
Large-enough targets, few precise drags, no keyboard unless typing is the point. Assume 353 dp width, large font, Russian
text lengths.
- Said: "I would make next/previous button bigger"; "slidebars a little hard to use for some values, because there is too
  much values"; "+/- buttons adds overhead to UI".
- Ask: can every control be hit with a thumb at 353 dp and 1.3x font? How many values does a slider span?
- Example: sliders snap to meaningful stops (upgrade steps, 22 chances), and the far-left stop means off.
- Said (2026-11-04, the rail over the game): "some buttons kinda small on overlay?", "recording new substat is not very
  convenient", "Hide/undo/back buttons full wide", "make whole overlay less wider", "make text for artifact numbers
  larger". I read "full wide" as one filled row and built it; the owner: "you completely didnt understand what I was
  suggesting", and placed the pieces themselves in a placement page (cache/e7gear_mock/rail-editor). Their layout is
  the spec: 84 dp rail; undo and hide side by side on top; count; tile, score and a narrow less-rounded verdict chip in
  the left column, stats at 11 sp on the right; Sold ›, Keep ›, Record full width; "<" back to the app full width at
  the bottom. When a layout request has two readings, mock both or hand over a placement page before building.
  A walk moves on with the buttons that decide (Sold ›, Keep ›), not separate arrows; a value is picked on a vertical
  slider with every value marked, and lifting the finger picks it. The rail
  measures the piece before choosing sizes: big buttons only while the piece keeps its cell, then compact buttons, then
  smaller numbers; text pages are the last resort. Check it at the owner's 792 x 353 dp landscape, not a portrait box.
  It may cover the game's first inventory column's icons, never its substats ("it is fine, because I still can see
  substats of this items").

### 9. Safe to explore
A player can tap around without losing work: resets and anything irreversible ask first; marks can be undone; nothing
stale is acted on silently.
- Said: "add confirmation window for reset buttons. For ALL, it should be default behaviour".
- Ask: what is lost if this is tapped by accident? Can it be undone or does it confirm?
- Example: `askReset` everywhere (enforced by SourceRulesTest); Undo after Sold/Keep; stale lists ask for an update first.

### 10. Looks like the game app it is
Depth, alignment and rhythm matter: raised cards with hard shadows, recessed blocks, aligned label/value columns, even
grids with a centred short last row, every stat with its icon, both themes.
- Said: "can you add more depth to design?"; "now this feels too flat"; "can you then at least align it properly?";
  "grid is uneven"; "can you center second raw?"; "Why main stat dont have icon?".
- Every frame ends at its content: sheets, cards and the floating panel over the game. A fixed-size frame with a large
  empty area reads as something missing. Said, on the overlay panel that stayed two-thirds empty on a piece: "submenus
  dont fit height by content". Keep a size fixed only while one thing is shown, so buttons don't jump between its pages.
- Rows of one list share one label width, and value grids keep the same columns from row to row (a target sits above
  the value it asks for). Said (2026-11-04) of "What it looks for": "nightmare of perfectionist".
- No text is cut with "…": a label that can be long wraps or shrinks (FitText). A " · " line breaks between claims, never
  inside one (`claims()`, enforced by SourceRulesTest). Said: "I thinked clipping text is banned?"; "It is more convenient
  to move whole claim to new line".
- A gear tile is the game's inventory cell: level, +N, set at the bottom right and, for a piece another hero wears, that
  hero's face at the bottom left (`GearTile(wearer = heroes.faceOf(id))`). Said: "add hero icon if somebody currently
  uses this piece, like in game", then "search for other places where gear shows 'on X hero'".
- Ask: does it look like a finished screen next to the existing ones, in light and dark? Does any frame have empty
  space its content doesn't use?

## Before showing anything

The maintainer is not the reviewer. A round reaches them only after:
1. **Self-review against the principles**: answer each "Ask" for every screen touched, on the real renders (353 dp big
   font and 411 dp, light and dark).
2. **Independent review**: a fresh subagent gets only the screenshots and this file and lists everything the maintainer would
   object to. Fix those first.
3. **Machine checks clean, sized to the step**. Said (2026-11-03): "your audits after each change takes too much time each
   time, can you optimize workflow?" (I had run unit gate + lint + audit + suites + an emulator reboot after every tweak.)
   - each change, ~1 min, emulator already up: ONE gradle call with the touched unit tests and the one androidTest that
     renders the touched screen, then read its screenshot:
     `ANDROID_SERIAL=emulator-5554 E7_FIXTURES=<fixtures> ./gradlew -q :app:testDebugUnitTest --tests '<package>.*'
     connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=e7.gearscan.<Screens>[#test]`
     (e.g. SellFiltersScreens ~35 s, HeroUpgradeScreens#quickRender ~20 s). No lint, no full suite, no audit here.
   - renders for review come from a saved result, not a fresh computation: Improve heroes'
     `HeroUpgradeScreens#quickRender` reads E7_FIXTURES/hero_upgrades.json (21 s with the emulator already up). Keep the
     emulator running through a design session.
   - before a build goes to the maintainer's phone: all unit tests once, then ScreenAudit for the touched screens at the
     hardest setups without repeating them (`AUDIT_UNIT=0 AUDIT_ONLY='sell*' AUDIT_SETUPS='353-big-en,411-big-ru'
     E7_FIXTURES=<fixtures> tools/audit.sh`, ~3 min) and the androidTest suites that touch the change. Lint runs in
     release.sh.
   - before release: the full audit (`E7_FIXTURES=<fixture folder> tools/audit.sh`, ~8 min, emulator only) and every
     androidTest suite that touches the change.
   - New screens and states are added to ScreenAudit in the same change, including the states where the player decides
     something (with results, not only empty or intro states).
4. **Only real product choices go to the maintainer**, each with what it changes for the player.

When a correction comes in anyway: fix it, find the principle it falls under (or add one here, in the maintainer's words), and
if it can be checked by machine, add the check to ScreenAudit or SourceRulesTest in the same change.

## How the app does it

- Screen: `Frame(model, tab, actions = { PinkButton(...) }) { BackToPrevious(model); ScreenTitle(...); ... }`. The actions
  row holds the one primary action. Register the screen in `Screen` (AppModel.kt), `screenLabel`, and `App.kt` routing.
- Settings: `RowCard(label, onClick, labelWidth = …)` per setting, one shared label width per screen; each opens its own
  sheet (`model.sheet = Sheet(SheetKind.X, id)`, rendered with `SheetBody`). Sheets end at their content unless listed in
  `tall`.
- Lists: `ListRow` with `ChevronSlot`/`Slot` so every row keeps the same trailing slots; long lists show 20 with
  "Show 20 more" (no LazyColumn in the app; a composed list of hundreds of rows ran the app out of memory).
- Grids: `CardGrid` only (centres a short last row; hand-built grids fail SourceRulesTest).
- Text that must stay one line: `FitText(text, style, short = …)` (shrinks, then switches to the short form; never "…").
- Values: `ValueSlider` / `SliderTrack` with snapped stops; `TypedNumber` opens the keyboard only where SourceRulesTest
  allows it.
- Stats: `StatIcon` / `StatValue`, never a bare stat name in a value position.
- Feedback: `model.showToast` for results of actions; `model.explain(title, body)` for tap-to-explain; `model.askReset`
  for anything destructive.
- Colours: palette roles in `ui/Theme.kt` only (ThemeRulesTest); check both themes.
- State the player returns to lives in AppModel, not in `remember`.
- Network in tests: `Http.open` blocks Fribbels' hosts under instrumentation; tests use the fixture `builds/` cache.
