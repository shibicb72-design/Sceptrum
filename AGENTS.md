# AGENTS.md - Sceptrum (Android TV / Google TV remote)

Primary tool: Claude Code (config in `.claude/`). The free OpenCode models are handled by Marcus, not by this file.
Retro and lessons from every PR: `docs/lessons.md` (moved there verbatim; read only the part your ticket touches). Plan and research: `docs/`.

## Ground rules
- **Phone:** never `adb install`, uninstall, or clear/change app data on Shibi's phone or devices. Install only after Shibi's explicit yes for that specific install. When a build is ready, stop and report the CI APK artifact. Read-only adb only when truly needed; prefer Settings > Debugging / Error log.
- **PRs:** one branch per PR (`opus/<topic>`), stacked on the previous PR's branch until `main` has the app; say which base in the PR. The PR body starts with "Do not merge without Shibi's OK." Never merge, never push to `main`, never force-push.
- **Work in progress:** check `git status` first. If the tree is dirty or on a feature branch with local commits, use `git worktree add` instead of switching branches.
- Never kill `claude.exe` or `opencode.exe`. Never commit secrets (pairing keys, certificates, `keystore.properties`).
- **Debug keystore:** `app/debug.keystore` is committed on purpose: a DEBUG-ONLY, NON-SECRET key (android / androiddebugkey) so every CI APK has the same signature and updates in place. Never point a release signingConfig at it. CI versionCode = 100 + `GITHUB_RUN_NUMBER` (3 locally).

## Project map
- `:app` (Compose UI, view models, `SessionManager`, DataStore + Tink stores), `:core:model`, `:driver:androidtv` (Android TV Remote v2 protocol, Kotlin/JVM, no Android imports), `:testing:fakes` (fake TV and test doubles).
- Pure logic stays free of Android so plain JUnit can test it.
- Dependencies only through `gradle/libs.versions.toml`. User-visible text in `app/src/main/res/values/strings.xml`.
- Code ported from androidtvremote2 (Apache-2.0) is credited in `NOTICE`; GPL/LGPL/EPL code is never translated.

## Build and test (always via the wrapper)
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gradle-brief.ps1 assembleDebug testDebugUnitTest
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gradle-brief.ps1 assembleDebug test recordRoborazziDebug
```
- `testDebugUnitTest` skips the JVM modules; use `test` before a PR (it also runs `:driver:androidtv`).
- Prints `BUILD OK` plus up to 5 `w:` lines, or the first failure lines with context. Full log: `%TEMP%\gradle-last.log` (Grep it; never read it whole). Exit code = Gradle's.
- If the shared `~/.gradle` cache has broken locks, add `-g .gradle/user-home` (git-ignored).

## Definition of done
- `assembleDebug test recordRoborazziDebug` green with zero `^w:` lines (CI "Android CI" enforces the warning gate).
- Logic changes come with tests. Report the actual result.
- Add one retro line to `docs/lessons.md` after every PR.

## Design rules
- No dark-on-dark text: every painted surface takes its content colour from its own container (`ContrastSurface`).
- Works folded and unfolded (single column folded, side panel unfolded); check both in screenshots.
- No visible volume sliders: no `Slider(` anywhere in the app UI (`NoSliderTest`); volume is buttons only.
- The carousel nav (`ui/nav/NavCarousel`) stays. Spinners go through `BusyIndicator` so screenshots reach idle.

## Delegation (token budget)
- Builds and tests -> `gradle-runner` (Haiku).
- Screenshot review -> `screenshot-reviewer` (Haiku, failing `*_compare.png` only).
- Code search -> `Explore` (Haiku, read-only).
- Planned implementation -> `android-implementer` (Sonnet). Planning stays in the main conversation (`opusplan`).
- The main conversation never reads PNGs, full Gradle logs or files under `build/`.
- `/clear` between tickets. How to measure: `docs/token-budget.md`.
