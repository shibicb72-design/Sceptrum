# Sceptrum house rules

- Dependencies only through `gradle/libs.versions.toml`.
- User-visible text in `app/src/main/res/values/strings.xml`.
- Definition of done: `assembleDebug test recordRoborazziDebug` green with zero `^w:` lines (CI enforces the warning gate).
- Pure logic stays free of Android so plain JUnit can test it: protocol code lives in `:driver:androidtv` (Kotlin/JVM, no Android imports), test doubles in `:testing:fakes`.
- No `Slider(` anywhere in the app UI (`NoSliderTest`): volume is buttons only.
- Every painted surface takes its content colour from its own container (`ContrastSurface`).
- Code ported from androidtvremote2 (Apache-2.0) is credited in `NOTICE`; GPL/LGPL/EPL code is never translated.
- Add a retro line below after every PR.

## Retro

- PR 2 (protocol core): Windows aborts a TLS 1.3 connection with a TCP reset when the server refuses the client certificate, so the `bad_certificate` alert never arrives. "Needs pairing" therefore also triggers after 3 silent closes in a row, not only on an SSL error.
- PR 3 (discovery and pairing): an indeterminate spinner never lets Roborazzi reach idle; Robolectric then runs out of heap in `ShadowTrace`. Spinners go through `BusyIndicator`, which is still when the animator scale is 0, and screenshot tests set that scale. DataStore allows one live instance per file, so reopen tests cancel the first store's scope.
- PR 4 (usable remote): hold timing lives in `RemoteViewModel`, not in gestures, so it is tested in virtual time; the UI only reports press and release. `SessionManager` is pure Kotlin (network events behind an interface) and is tested against the fake TV.
- PR 4b (pairing fix): a real XGIMI projector showed "TV refused pairing" at once and never a code. Every TLS failure was labelled "refused", and the client key was an Android Keystore key, which most likely could not sign the RSA-PSS CertificateVerify that TLS 1.3 needs (Conscrypt asks it for raw RSA). The client identity is now a software RSA key in the encrypted store, as androidtvremote2 does; failures are split into Rejected (polo status), Handshake (TLS) and Dropped (no status), and every error card copies a diagnostic report. Replies are parsed field by field so a status outside the enum still shows its number.
- PR 5 (Google TV MVP): on Windows, back-to-back DataStore writes can fail to rename over a file the virus scanner still holds, so view-model tests that write often use `TestStores.memoryRepository()`; on-disk round trips stay in `DeviceRepositoryTest`. The keyboard sends the whole field value after 150 ms of quiet and clears with `KEYCODE_DEL`, because androidtvremote2 refuses an empty edit.

## Lessons for agents

- Adding a field to a data class changes its `equals()`: search the tests for `assertEquals` on that type and update them. A `Throwable` field never compares equal to a freshly built one, so compare `value.copy(error = null)` and check the exception separately (PR 5b, `LinkState.Retrying.error`).
- Compose UI test: `assertExists()` and `assertDoesNotExist()` are member functions of `SemanticsNodeInteraction`; importing them fails with "Unresolved reference". Only extension assertions and actions (`assertIsSelected`, `assertIsNotEnabled`, `performClick`, `performScrollTo`) are imported (PR 5b).
