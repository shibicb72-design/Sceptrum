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
