# E7 Gear

An Android gear optimizer for Epic Seven. It reads your heroes and gear straight from the game (no PC, no root),
then finds builds for one hero or optimizes your whole roster at once without making anyone worse.

E7 Gear stands on [Fribbels' Epic 7 Optimizer](https://github.com/fribbels/Fribbels-Epic-7-Optimizer): its server
decodes every scan, and much of the app follows its formats and rules (see Credits). Thank you, Fribbels.

## Install

1. Download the latest `E7-Gear-x.y.z.apk` from [Releases](https://github.com/Sod3n/e7-gear/releases/latest) on your phone.
2. Open it and allow installs from that source once.
3. Later versions install from inside the app: it offers **Update** when one is out.

Game data (heroes, artifacts, sets, icons) is downloaded on first start and refreshed by the app itself, so new heroes
arrive without a new APK.

## What leaves your phone

- **Scanning:** the app runs a VPN for Epic Seven only and records the game's login. That data, which includes your
  account id and current session, is sent to [Fribbels](https://github.com/fribbels/Fribbels-Epic-7-Optimizer)' server
  to decode your heroes and gear. Your gear then stays on the phone.
- **No account data** goes anywhere else. The app also fetches game data and icons from its content site
  (`e7-gear-content.pages.dev`), community builds by hero name from Fribbels' hero library and the
  [RTA draft helper](https://rta-draft-helper.pages.dev), hero portraits from GitHub, and checks GitHub for new versions.
- **Bug reports** are sent only when you send one, with the phone model, window size and the last error.

## Disclaimer

E7 Gear is a fan project, not affiliated with or endorsed by Smilegate. Reading the game's network traffic may go
against Epic Seven's terms of service; use the scan at your own risk.

## Credits and licenses

- [Fribbels' Epic 7 Optimizer](https://github.com/fribbels/Fribbels-Epic-7-Optimizer) by Fribbels:
  - scans are decoded by its server, the same way its auto-importer does it;
  - gear is kept, imported and exported in its `gear.txt` format;
  - hero and artifact data (names, elements, exclusive equipment) and set piece counts come from its data files;
  - community builds come from its hero library;
  - gear score, substat modification (reforge stones) and the penetration assumption follow its rules.
  E7 Gear is a separate project and isn't made or supported by Fribbels.
- VPN capture: built on [sockstun](https://github.com/heiher/sockstun) and
  [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT).
- Fonts: Zen Maru Gothic and Dela Gothic One (SIL Open Font License, `app/src/main/assets/licenses`).
- E7 Gear's own code: MIT, see `LICENSE`.

## Building

Android Studio or `./gradlew assembleDebug` (JDK 17). The app downloads its game data at runtime, so a debug build
works without any data files. Tests that need game data download a copy on first run.
