# Sceptrum

A free Wi-Fi universal remote for Android (Kotlin, Jetpack Compose, Material 3), designed for the Galaxy Z Fold 7 folded and unfolded.

- Network control only (no infrared).
- First target: Android TV / Google TV via the Android TV Remote protocol v2 (XGIMI Horizon S Max, TCL 75QM6K).
- Later: LG webOS, Samsung Tizen, Sony Bravia, PJLink projectors, Gree and Daikin air conditioners.

See [docs/SCEPTRUM_plan.md](docs/SCEPTRUM_plan.md) for the plan and [docs/REMOTE_research.md](docs/REMOTE_research.md) for background research.

## Build

Needs JDK 17 and the Android SDK (platform 36). Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file (`sdk.dir=...`, not committed).

```sh
./gradlew assembleDebug test           # debug APK + unit tests (Windows: gradlew.bat)
./gradlew recordRoborazziDebug         # screenshots -> app/build/outputs/roborazzi/
```

The APK lands in `app/build/outputs/apk/debug/`. GitHub Actions ("Android CI") runs the same steps on every push to `main` and every pull request, and uploads the `debug-apk` and `screenshots` artifacts.

## Modules

- `:app`: Android app (Compose UI, Keystore client certificate).
- `:driver:androidtv`: Android TV Remote protocol v2 (pairing on 6467, control session on 6466), pure Kotlin/JVM with Wire-generated protobuf.
- `:testing:fakes`: a scripted fake Android TV over local TLS sockets, and fixed certificate fixtures (`tools/atv_fixtures.py`).

Licensed under the Apache License 2.0. The Android TV protocol is ported from [androidtvremote2](https://github.com/tronikos/androidtvremote2) (Apache-2.0); see [NOTICE](NOTICE).
