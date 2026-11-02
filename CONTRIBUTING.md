# Contributing to E7 Gear

Thanks for helping. Small fixes and new ideas are both welcome. For anything big, open an issue first so we agree on
the direction before you spend time on it.

## Setup

Follow "Building" in the [README](README.md): JDK 17, the Android SDK with NDK 29.0.14206865, and a clone with
`--recurse-submodules`. Then `./gradlew assembleDebug` should work with no other files.

## How the code is laid out

`:core` is pure Kotlin with the game rules, file formats and optimizer. `:kit` is the Compose design system.
`:app` holds the state and the screens. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before adding a feature;
it says where each kind of code goes.

## Tests and the audit

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:ktlintCheck
```

CI runs exactly this on every pull request. Run it before you push. `./gradlew :core:ktlintFormat` fixes most style
errors.

Screens are checked by the screen audit on an emulator. You do not need a real account; the audit runs on a
synthetic one. The steps are in the README under "Screen audit". Run it when you change anything a player sees.
Add new screens and states to `ScreenAudit` in the same change.

Do not call third-party services from tests, and do not commit game data, names or images. Game content is
downloaded from the project's own content site at test time.

## UI rules

Every screen follows [docs/UI_COOKBOOK.md](docs/UI_COOKBOOK.md). Read it before you design or change a screen.
Build screens from the parts in `:kit` rather than styling by hand.

## Commits and branches

- One branch per change. Pull requests are squash-merged, so the pull request title and description become the
  commit.
- Write the summary in the imperative, under 72 characters ("Fix clipped set names on small phones").
- Leave a blank line, then a short body that says why the change is needed, not what the diff already shows.
- Keep unrelated changes out of the same pull request.

## What a pull request needs

- The command above passes.
- Tests for new rules and for bugs you fix.
- For any UI change, screenshots at 353 dp with large text and at 411 dp, in light and dark.
- A description that says what changed and why.

## Releases

Releases and signing stay with the maintainer. The release key is never in the repository or in CI.
Do not bump the version or edit release notes in a pull request.
