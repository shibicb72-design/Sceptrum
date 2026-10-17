# Proposals from the Android skills (PR 6, not done)

PR 6 ("skills hardening") applied Google's Android agent skills where they fit
the house rules. The items below are what the skills suggest that this PR
deliberately did **not** do. Each one needs a decision, a dependency or a
device check first.

## navigation-event

- **Predictive-back animations.** All back handling now goes through
  `NavigationBackHandler` (`ui/BackHandlers.kt`). `NavigationEventState` exposes
  gesture progress and swipe edge, so a closing card or panel could scale or
  slide with the finger. Not done, because behaviour had to stay unchanged.
  Suggested scope: the folded Apps panel and keyboard bar, and the pairing and
  edit cards on Devices. Scale 1 → 0.95 and offset up to 16 dp, read in
  `graphicsLayer {}` so it stays in the draw phase. Add a Roborazzi shot at
  progress 0.5.
- `android:enableOnBackInvokedCallback` is not set. At targetSdk 36 it defaults
  to on, which predictive back needs. Leave it unset.

## jetpack-compose-performance

- **`Modifier.holdable` → `Modifier.Node`.** It is a `@Composable` modifier
  factory (`remember` + `rememberUpdatedState` + `pointerInput`). That is
  correct, but each of the ~10 hold keys builds its own modifier chain on every
  recomposition. A `ModifierNodeElement` that delegates to
  `SuspendingPointerInputModifierNode` and an indication node would remove that
  cost. The gain is small now that the action holders are remembered (PR 6).
  Measure first, with a release build + R8 + Macrobenchmark.
- **Apps grid stays a plain `Column` of `Row`s.** There are at most 24 tiles,
  and the skill says not to use lazy layouts for small bounded lists. If the
  grid ever loads the TV's real app list (`docs/apps-list-options.md`), switch to
  `LazyVerticalGrid(GridCells.Adaptive(96.dp))` with `key = { it.link }` and
  `contentType = { "app" }`.
- **Baseline profile and R8 for release.** The skill's prerequisites (release
  build, R8, baseline profiles) are not in place: `isMinifyEnabled = false`.
  Do a release-only R8 trial first (Wire, Tink and DataStore ship consumer
  rules). Then verify pairing, TLS and the encrypted store against
  `FakeAndroidTvServer` and one real TV.

## edge-to-edge

- The IME fix uses `Modifier.fitInside(WindowInsetsRulers.Ime.current)` once,
  at `SceptrumScaffold`, as the skill prefers. Remaining check on the device:
  the Fold 7 floating or split keyboard on the inner screen, which reports a
  smaller IME inset. `ImeInsetsTest` covers 400 dp and 610 dp, not 900 dp.

## android-permissions-security

- Remove the unused `ACCESS_WIFI_STATE` in the targetSdk 37 PR, after one
  discovery check on the Fold 7 (`docs/security-audit.md` F3).
- At targetSdk 37: do the `ACCESS_LOCAL_NETWORK` runtime flow (check →
  rationale → request → settings after a permanent denial) and stop caching
  `lanAccess()` in `RemoteRoute` (`docs/security-audit.md`, "Local network
  permission").
- Optional: mark the **Copy all** clip as sensitive
  (`ClipDescription.EXTRA_IS_SENSITIVE`).

## Not adopted (house rules)

- Hilt (testing-setup), Navigation 3 (adaptive and navigation-3), Dropshots or
  Compose Preview screenshot tests, and the Styles API (needs compileSdk 37 and
  an alpha). No dependency, AGP or compileSdk bumps in this PR.
