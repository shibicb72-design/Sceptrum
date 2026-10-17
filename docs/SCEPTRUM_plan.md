# SCEPTRUM plan: a Wi-Fi remote for Google TV first, then other brands

Opus, 2026-10-17. Planning only. No code, repo or git action was taken. This file is the only file written.
Inputs: `REMOTE_research.md` (same folder, prior research), `SPECTACULA_plan.md` and the `local_music_player` repo (read-only, for the design language).
Items marked **[UNVERIFIED]** could not be confirmed from a primary source during this research.

---

## Executive summary

**What Sceptrum is**
- Sceptrum is a new standalone Android app: a remote control that works over the home Wi-Fi. No infrared, and no cloud account.
- **First shippable feature: Google TV / Android TV.** This covers both must-work devices:
  - the XGIMI Horizon S Max projector (2024, **Android TV 11**, confirmed by Shibi);
  - the TCL 75QM6K (Google TV; ships on Android 12, with an Android 14 update reported for its chip family).

  Both are driven by Google's own "Android TV Remote Service" protocol v2, the same one the official Google TV phone app uses.
- **How the protocol works (verified against [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2), Apache-2.0):**
  - TLS with a client certificate, and protobuf messages prefixed with a varint length.
  - Port **6467** for one-time pairing, using a 6-character hex code shown on the TV.
  - Port **6466** for keys, volume state, power state, keyboard text and app launch links.
- **Order of work:**
  - **Google TV MVP in PRs 1-5.** The remote is usable from PR 4, and the full MVP lands at PR 5.
  - Then: LG webOS, Samsung Tizen, Sony BRAVIA, PJLink, Gree AC, Daikin AC.
  - Every protocol is a separate "driver" behind one interface, so the UI never changes when a brand is added.

**Design**
- Same look and rules as Spectacula:
  - pastel backgrounds and a dark theme;
  - `ContrastSurface`, so text never sits dark-on-dark;
  - folded and unfolded Galaxy Z Fold 7 layouts;
  - **no volume slider anywhere.** Volume is two rocker buttons with hold-to-repeat, and the level is shown only as a number.

**Platform choices**
- Kotlin, Jetpack Compose, Material 3.
- `applicationId com.shibi.sceptrum`, **minSdk 31, targetSdk 36** now. Move to targetSdk 37 in a dedicated PR.
- Credentials are stored with **DataStore + Tink, keys in the Android Keystore**. `EncryptedSharedPreferences` is deprecated.
- Discovery uses `NsdManager` for `_androidtvremote2._tcp`, with manual IP entry as the fallback.

**Correction to the earlier research**
- Google's local-network page says apps targeting 36 or lower should **not** declare or request `ACCESS_LOCAL_NETWORK` ([docs](https://developer.android.com/privacy-and-security/local-network-permission)).
- So Sceptrum adds the permission in the same PR that moves to targetSdk 37, not earlier.
- The permission UI is still designed now.

**Biggest risks**
1. **Power-on from standby.** The XGIMI Horizon 20 Max is Wi-Fi only (RTINGS lists no Ethernet: [RTINGS](https://www.rtings.com/projector/tools/compare/xgimi-horizon-20-max-vs-xgimi-horizon-20-pro/115774/120492)), so Wake-on-LAN is unlikely to work. Power-on will depend on the projector keeping its Wi-Fi alive in standby. The checklist in §15 tests this today.
2. **Keyboard text** only works while a text field is focused on the TV, and it reuses counters the TV sends.
3. **Android 17's local-network permission** applies once Sceptrum targets API 37.

**What you can do now:** run the 10-minute XGIMI checklist in §15. It settles the model, pairing, ports, mDNS, standby and PJLink questions before any code is written.

---

## 1. Project setup

| Item | Recommendation | Why |
|---|---|---|
| Repo | Existing public repo **`shibicb72-design/Sceptrum`** (created by the owner; default branch `main`; public, so nightly.link APK links work) | Standalone app, separate from Spectacula |
| applicationId / namespace | `com.shibi.sceptrum` | Permanent once installed or published (see Spectacula plan §1.2) |
| Language/UI | Kotlin 2.4.x, Jetpack Compose (BOM as in Spectacula, `2026.06.01` or newer), Material 3 | Same toolchain as `local_music_player` (AGP 9.4.0, Kotlin 2.4.10, Roborazzi 1.76.0, Robolectric 4.17) |
| **minSdk** | **31** (Android 12) | <ul><li>The Fold 7 runs Android 16/17, so it is not limited by this.</li><li>31 gives dynamic colour, modern Keystore (RSA-PSS for TLS 1.3), `HapticFeedbackConstants.CONFIRM/REJECT` (API 30+) and `PendingIntent` mutability defaults.</li><li>It still covers most phones a household would use.</li></ul> |
| **targetSdk** | **36** for PRs 1-5; **37** in PR 6 | <ul><li>Play requires 36 since 2026-08-31 ([Play](https://developer.android.com/google/play/requirements/target-sdk)).</li><li>At 36 the app gets an implicit local-network grant on Android 17.</li><li>Moving to 37 adds the `ACCESS_LOCAL_NETWORK` runtime prompt (§8).</li><li>It also removes the large-screen orientation opt-out ([Android 17 changes](https://developer.android.com/about/versions/17/behavior-changes-17)). That is fine, because Sceptrum is fully resizable anyway.</li></ul> |
| compileSdk | 36 in PR 1; 37 when PR 6 lands (needed for `FLAG_SHOW_PICKER` and the permission constant) | Whether AGP 9.4 supports compileSdk 37 is [UNVERIFIED]. If it does not, use the permission string literal. |
| Protobuf | **Square Wire** (`com.squareup.wire`, latest **7.1.0** per [Maven Central](https://repo1.maven.org/maven2/com/squareup/wire/wire-runtime/maven-metadata.xml)) generating Kotlin from the `.proto` files | <ul><li>Pure Kotlin and small.</li><li>Works in plain JVM modules, so protocol tests run without Robolectric.</li><li>The alternative is `protobuf-javalite` 4.36.2.</li></ul> |
| Crypto | `com.google.crypto.tink:tink-android` **1.24.0** ([Maven](https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/maven-metadata.xml)) | §6 |
| Adaptive | `androidx.compose.material3.adaptive` **1.3.0** stable ([Google Maven](https://dl.google.com/android/maven2/androidx/compose/material3/adaptive/adaptive/maven-metadata.xml)), `androidx.window` **1.5.1** | Window size classes and posture (§2.4) |

House rules are copied from Spectacula's `AGENTS.md` into a new `AGENTS.md`:
- dependencies only through `libs.versions.toml`;
- user text in `strings.xml`;
- `clean assembleDebug test` green, with zero `^w:` lines;
- pure logic kept free of Android so plain JUnit can test it;
- a retro line after every PR.

---

## 2. Design language (inherited from Spectacula)

### 2.1 Themes
- **Backgrounds:** port `domain/AppBackground.kt`, `PastelColor.kt` and the swatch catalogue as they are. That includes:
  - 16 solids: blush, peach, butter, mint, sage, sky, periwinkle, lavender, lilac, rose, coral, apricot, seafoam, powder_blue, mauve, cream;
  - 8 blends: cotton_candy, sunrise, mermaid, sorbet, twilight, peachy_keen, lilac_mist, mint_cream.
- **Dark theme toggle:** paint `appBackground.shownWith(darkTheme)`. Never overwrite the stored pastel choice (Spectacula retro "carousel v3").
- **Content colours:** content roles come from the backdrop (`backgroundContentScheme`), and accents come from `chrome.accentText`. Never use a fixed lilac on pastel (retro "opus review").
- **Settings:** Settings > Appearance offers Dark on/off, a pastel grid and an optional Material You dynamic colour.

### 2.2 ContrastSurface: no dark-on-dark text
- **Port the code:** copy `BackgroundContrast` (WCAG maths, `rolesForContainer(containerArgb, behindArgb)`) and `ui/background/ContrastSurface.kt` almost line for line.
- **Every painted surface derives its content colour from its own container** (composited over the window sample). This covers:
  - remote buttons;
  - the d-pad disc;
  - the rockers;
  - the device cards;
  - the pairing sheet;
  - status chips.
- **A root `Surface(color = background, contentColor = onBackground)`** goes in `SceptrumTheme` (Spectacula retro V1). Transparent Scaffolds and TopAppBars pass `contentColor`, `titleContentColor` and `navigationIconContentColor` explicitly.
- **Contrast unit test:** for every solid, every blend, Dark on and off, and every button container role, assert `isReadable(content, container) >= 4.5`. Icon-only glyphs must reach ≥ 3:1. Decorative rings need about 40% white on dark (Spectacula lesson).

### 2.3 Remote-specific visual rules
- **Shapes:**
  - the d-pad is a filled disc;
  - each rocker is a tall pill split into + and −;
  - other keys are 56-64dp rounded squares (the 48dp minimum touch target is exceeded everywhere).
- **Glass:** buttons use the Spectacula "glass" fill composited over `pageFill`, so they stay opaque and never show through.
- **No volume slider, anywhere:**
  - Volume is **VOL + / VOL − buttons** with hold-to-repeat, plus an optional swipe strip (§3.3).
  - The level shows only as a transient number chip, "Vol 23 / 100", read from the TV's `remote_set_volume_level`.
  - There is no bar and no `Slider` composable in the app. Add a lint-style unit test that greps `ui/` for `Slider(` and fails if it finds one.
- **Motion:** M3 Expressive springs, as in Spectacula's carousel. Animator scale 0 means reduce motion: snap, with no ripples beyond the default.

### 2.4 Galaxy Z Fold 7: folded, unfolded, tabletop
- **Size classes:** use `currentWindowAdaptiveInfo().windowSizeClass`.
  - Width **Compact** < 600dp: the folded cover screen, about 411dp wide (Spectacula's `w411dp-h891dp-xxhdpi`).
  - Width **Expanded** ≥ 840dp: unfolded. The inner screen is about **984×1092dp** at default display size ([yesviz](https://yesviz.com/devices/samsung-z-fold7/)) [UNVERIFIED: depends on the One UI display-size setting].
  - Medium: split-screen windows.
- **Posture:** read `FoldingFeature` from Jetpack WindowManager (`WindowInfoTracker`). `HALF_OPENED` + `HORIZONTAL` means **tabletop**. `calculatePosture()` is internal in adaptive 1.2+, per the earlier research.
- **Activity setup:**
  - `MainActivity` gets `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`, so a fold or unfold does not drop the TLS session.
  - The session lives in a ViewModel or service either way.
  - Tests still cover the `recreate()` path (Spectacula's `TwoPaneNavigationTest` pattern).

---

## 3. Remote UI

### 3.1 Controls and Google TV mapping
Key codes in `RemoteKeyCode` mirror `android.view.KeyEvent` numbers (androidtvremote2's `remotemessage.proto`). Exact enum names are confirmed when the proto is vendored in PR 2.

| Control | Gesture | Sends |
|---|---|---|
| D-pad ▲▼◀▶ | tap; hold repeats (400 ms delay, then every 120 ms) | `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT`, `SHORT` |
| OK (centre) | tap / long-press | `KEYCODE_DPAD_CENTER` `SHORT`; long-press sends `START_LONG` on press and `END_LONG` on release (opens context menus) |
| Swipe pad (toggle on the d-pad) | fling up/down/left/right; tap = OK | same DPAD keys; one step per 48dp of travel |
| Back / Home | tap; Home long-press | `KEYCODE_BACK` / `KEYCODE_HOME` (long-press via `START_LONG`/`END_LONG`) |
| **Volume rocker** (button pair) | tap; hold repeats | `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` |
| Mute | tap | `KEYCODE_VOLUME_MUTE` |
| **Channel rocker** (button pair) | tap | `KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN` (hidden unless the device reports a live-TV input; the TCL has tuners, the projector does not) |
| Power | tap | `KEYCODE_POWER` while connected; otherwise the power-on flow (§7.3) |
| Input | tap | `KEYCODE_TV_INPUT` (opens the TV's input picker) |
| Media row | tap | `MEDIA_REWIND`, `MEDIA_PREVIOUS`, `MEDIA_PLAY_PAUSE`, `MEDIA_NEXT`, `MEDIA_FAST_FORWARD` |
| Extra keys (overflow ⋯ sheet) | tap | `KEYCODE_SETTINGS`, `KEYCODE_GUIDE`, `KEYCODE_INFO`, `KEYCODE_CAPTIONS`, digits 0-9, colour keys |
| Keyboard ⌨ | opens the text bar | IME protocol (§3.2) |
| Apps ▦ | grid of shortcuts | `remote_app_link_launch_request` (§3.4) |

**Haptics** use Compose `LocalHapticFeedback`, falling back to `View.performHapticFeedback`:
- `VirtualKey` on every key down;
- `SegmentFrequentTick` on each auto-repeat (as Spectacula's carousel does);
- `Confirm` when pairing succeeds;
- `Reject` on a send failure or a disconnect.

A Settings toggle "Vibrate on key press" is on by default.

### 3.2 Keyboard text (Google TV IME protocol)
Verified in androidtvremote2's `remote.py`.
1. **Negotiate the IME feature.** During `remote_configure` the client requests feature bits:
   - always PING=1, KEY=2, POWER=32, VOLUME=64 and APP_LINK=512;
   - **plus IME=4**.

   The total is `615`, ANDed with the TV's mask.
2. **Wait for a focused field.** When a text field on the TV gets focus, the TV sends a `remote_ime_batch_edit` (field 21) with `ime_counter` and `field_counter`. It also sends a `remote_ime_key_inject` whose `app_info.app_package` names the foreground app.
3. **Send text.** The client sends `remote_ime_batch_edit { ime_counter, field_counter (both echoed from the last incoming batch edit), edit_info { insert: 1, text_field_status { start: len-1, end: len-1, value: <whole text> } } }`.

   Sceptrum sends the **whole field value** after each change (debounced 150 ms), not single characters, so backspace is simply a shorter value.
4. **Enter:** send `KEYCODE_ENTER` as a key. **Clear:** send an empty value [UNVERIFIED: androidtvremote2 rejects empty text; test on the device, and fall back to repeated `KEYCODE_DEL`].

**UX**
- The ⌨ button opens a bottom text bar with the system keyboard. It shows "Type on TV" with the foreground app name.
- If no batch edit has arrived yet ("no text field focused"), the bar says "Select a search box on the TV first".
- A Settings toggle, "Fallback: type with key presses", maps a-z and 0-9 to `KEYCODE_A..Z` and `KEYCODE_0..9`.
- A community report shows that single letter keycodes do not always type into fields ([HA community](https://community.home-assistant.io/t/androidtv-remote-v2/818996)), so the fallback is labelled "may not work in every app".
- Voice (`remote_voice_*`) is out of scope (it needs `RECORD_AUDIO`; open question Q7).

### 3.3 Volume without a slider
- **Hold-to-repeat** on the rocker buttons, with `SegmentFrequentTick` haptics on each step.
- **Optional swipe strip:** Settings > "Volume swipe on right edge", default off.
  - A vertical drag on a 24dp invisible strip along the right edge sends one `VOLUME_UP/DOWN` per 32dp of travel.
  - It only appears in the compact layout, and its feedback is the same number chip.
- **Hardware volume keys:** while the remote screen is in the foreground, the phone's own volume keys control the TV (`onKeyDown` in the activity consumes `KEYCODE_VOLUME_*`). Settings toggle, default on.
- **Chip:** "Vol 23/100 · Muted" fades after 1 s. The data comes from `remote_set_volume_level` (`volume_level`, `volume_max`, `volume_muted`). There is **no slider and no bar**.

### 3.4 App shortcuts (app_link)
- **Format:** `remote_app_link_launch_request.app_link` (field 90). A URL with a scheme is sent as is. A bare package id becomes `market://launch?id=<package>` (androidtvremote2 `send_launch_app_command`).
- **Default tiles** (user-editable; each one tested on both devices) [UNVERIFIED per device]:
  - YouTube `https://www.youtube.com`
  - Netflix `https://www.netflix.com/title`
  - Prime Video `https://app.primevideo.com`
  - Disney+ `https://www.disneyplus.com`
  - Plex `plex://`
  - Settings `market://launch?id=com.android.tv.settings`
- **Adding tiles:**
  - "+ Add app" accepts a package name or a URL.
  - "Add current app" uses the package from the last `remote_ime_key_inject`, if one was seen.
- **Icons:** monochrome glyphs or initials in a `GeneratedArtTile`-style badge. No brand logos, to stay clear of trademarks (Play impersonation policy).
- **Later:** launcher app shortcuts ("Power", "Netflix on Projector") through `ShortcutManagerCompat`, and a Quick Settings tile (PR 15).

### 3.5 Layout sketches

**Folded (cover screen, about 411 × 891dp, Compact width):**
```
┌─────────────────────────────────┐
│ ● XGIMI Projector  ▾        ⏻   │  device chip (status dot, tap = switch device), Power
│ [⇄ Input]  [▦ Apps]  [⌨]  [⋯]   │  quick row
│                                 │
│           ╭───────────╮         │
│           │     ▲     │         │  d-pad disc; ⤧ icon toggles swipe pad
│           │ ◀   OK  ▶ │         │
│           │     ▼     │         │
│           ╰───────────╯         │
│     ⟲ Back     ⌂ Home     ☰     │
│  ╭─────╮                ╭─────╮ │
│  │  +  │     🔇 Mute     │  ▲  │ │  VOL rocker (buttons)   CH rocker (hidden if no tuner)
│  │ VOL │  [Vol 23/100]   │ CH  │ │  transient chip, never a bar
│  │  −  │                 │  ▼  │ │
│  ╰─────╯                ╰─────╯ │
│   ⏪    ⏮    ⏯    ⏭    ⏩       │  media row
└─────────────────────────────────┘
```

**Unfolded (inner screen, about 984 × 1092dp, Expanded width), three panes:**
```
┌───────────────┬─────────────────────────────┬─────────────────────┐
│ Devices       │ ● TCL 75QM6K   Living room ⏻│ Apps                │
│ ● XGIMI  ✓    │                             │ [YT] [NF] [PV] [D+] │
│ ○ TCL 75QM6K  │        ╭───────────╮        │ [PL] [⚙] [+ Add]    │
│ + Add device  │        │     ▲     │        │─────────────────────│
│               │        │ ◀   OK  ▶ │        │ Keyboard            │
│ Status:       │        │     ▼     │        │ [ Type on TV…     ] │
│ Connected     │        ╰───────────╯        │  Netflix · search   │
│ Vol 23 · On   │    ⟲ Back   ⌂ Home   ☰      │─────────────────────│
│               │ ╭───╮  🔇  [⇄ Input]  ╭───╮ │ Numbers             │
│               │ │ + │                 │ ▲ │ │  1 2 3 / 4 5 6 …    │
│               │ │VOL│                 │CH │ │                     │
│               │ │ − │                 │ ▼ │ │                     │
│               │ ╰───╯                 ╰───╯ │                     │
│               │   ⏪  ⏮  ⏯  ⏭  ⏩          │                     │
└───────────────┴─────────────────────────────┴─────────────────────┘
   0.24 width             0.46 width                0.30 width
```
- **Medium width** (split screen): two panes, devices hidden behind the chip, and Apps/Keyboard as a right pane.
- **Expanded landscape** (`w1092dp-h984dp`): same three panes.

**Tabletop (half-folded, hinge horizontal):**
```
 upper half:  device · power state · current app · "Vol 23/100" · media row (large)
 ─────────────── hinge ───────────────
 lower half:  d-pad + Back/Home + VOL/CH rockers (thumb zone)
```

**Navigation:** a three-icon `NavigationSuiteScaffold` (Remote, Devices, Settings), which becomes a rail when unfolded.
- The Spectacula glass carousel is **not** reused here, because a remote needs maximum thumb space.
- The tiles keep the same glass look. Open question Q9 asks whether a small 3-item carousel is wanted.

---

## 4. Device discovery

### 4.1 mDNS via NsdManager
- **Service type** `_androidtvremote2._tcp`, as used in Home Assistant's `androidtv_remote` manifest (see the earlier research). The TXT record and the advertised port point to 6466 [UNVERIFIED: the port value comes from the TXT/SRV record; check with §15 step 5].
- **API 34+:** `discoverServices(type, PROTOCOL_DNS_SD, listener)`, then for each found service `registerServiceInfoCallback(info, executor, ServiceInfoCallback)`. This gives continuous `onServiceUpdated` (IP changes) and `onServiceLost`, and replaces the deprecated one-shot `resolveService()` ([ServiceInfoCallback](https://developer.android.com/reference/android/net/nsd/NsdManager.ServiceInfoCallback)).
  - Read addresses with `getHostAddresses()`. Prefer IPv4, keep IPv6 as a fallback.
- **API 31-33:** `resolveService()` through a **single-flight queue**, because a parallel resolve fails with `FAILURE_ALREADY_ACTIVE` [UNVERIFIED as an official statement].
- **API 37 (after PR 6):** an optional "Pick from system list" button uses `DiscoveryRequest.Builder(type).setFlags(FLAG_SHOW_PICKER)`. The chosen device can be reached without `ACCESS_LOCAL_NETWORK` ([local network permission](https://developer.android.com/privacy-and-security/local-network-permission)). That suits a user who denies the broad permission.
- **Scan window:** 8 s when the Devices screen opens or on pull-to-refresh, then stop. Keep remembered devices and refresh their IPs passively.

### 4.2 Multicast lock
- Recent `NsdManager` docs state that a Wi-Fi multicast lock is not needed for foreground discovery ([NsdManager reference](https://developer.android.com/reference/android/net/nsd/NsdManager)). The exact SDK level is [UNVERIFIED].
- Sceptrum still declares `CHANGE_WIFI_MULTICAST_STATE`, a normal permission that SSDP needs later.
- It acquires a `WifiManager.MulticastLock` (`setReferenceCounted(false)`) **only during the scan window** and releases it in `finally`. This is harmless on new OS versions and needed on some OEM builds ([MulticastLock](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock)).

### 4.3 Manual IP fallback
- **Fields:** "Add by IP" takes an IPv4 address and an optional name.
- **Probe:** TCP connect to 6467 with a 2 s timeout, then read the server certificate.
  - The certificate CN looks like `atvremote/<x>/<y>/<model>/<MAC>`. androidtvremote2 reads both the **name and the MAC** from it (`async_get_name_and_mac`).
  - This gives the friendly name and the Wake-on-LAN MAC with no extra permission.
- **Same-subnet check:** read the phone's subnet from `ConnectivityManager.getLinkProperties()` (no location or SSID permission needed). Warn if the entered IP is outside it ("Different network? Guest Wi-Fi often blocks this").
- **Router tip:** suggest a DHCP reservation in the router, shown once.

### 4.4 Device identity
A device is keyed by **MAC from the certificate CN**, falling back to the server certificate fingerprint. A new IP for the same key updates the stored host silently.

---

## 5. Pairing UX and protocol

### 5.1 Wire format (verified)
- **Framing:** each message is a **varint length** followed by the protobuf bytes, in both directions. The receiver buffers until the whole message has arrived ([base.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/base.py)).
- **`polo.proto` `OuterMessage`:**
  - `protocol_version`=1 (value 2);
  - `status`=2 (`STATUS_OK` 200, `STATUS_ERROR` 400, `STATUS_BAD_CONFIGURATION` 401, `STATUS_BAD_SECRET` 402);
  - `pairing_request`=10, `pairing_request_ack`=11, `options`=20, `configuration`=30, `configuration_ack`=31, `secret`=40, `secret_ack`=41.

### 5.2 Pairing sequence (port 6467)
```
Phone (TLS client, presents client cert)                  TV
  ── PairingRequest{service_name:"atvremote", client_name:"Sceptrum (Fold7)"} ──▶
  ◀── PairingRequestAck ─────────────────────────────────────────────────
  ── Options{input_encodings:[{HEXADECIMAL, symbol_length 6}], preferred_role: INPUT} ──▶
  ◀── Options ───────────────────────────────────────────────────────────
  ── Configuration{encoding:{HEXADECIMAL,6}, client_role: INPUT} ─────────▶
  ◀── ConfigurationAck  →  TV shows 6-char code, e.g. "4F A1 C9" ─────────
  [user types code]
  ── Secret{secret: SHA-256(...)} ───────────────────────────────────────▶
  ◀── SecretAck  →  paired; close; connect to 6466 with the same cert ────
```
Each step has a **10 s timeout** (androidtvremote2 `PAIRING_TIMEOUT`).

### 5.3 Secret computation (verified in [pairing.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/pairing.py))
```
code        = 6 hex chars from the TV, uppercased, spaces removed
h = SHA-256 over, in order:
  hexbytes(clientModulus as uppercase hex)
  hexbytes("0" + clientExponent hex)          // 65537 -> "010001" -> 01 00 01
  hexbytes(serverModulus as uppercase hex)    // from the TV's TLS peer certificate
  hexbytes("0" + serverExponent hex)
  hexbytes(code[2..6])                        // last 4 chars = 2 bytes
check: h[0] == parseHex(code[0..2])  → else "Wrong code" (do NOT send)
send:  Secret{ secret = h (all 32 bytes) }
```
**Kotlin pitfalls** (written into the PR 2 ticket):
- `BigInteger.toByteArray()` adds a sign byte. Build the hex string with `toString(16).uppercase()` and decode it, matching Python's `bytes.fromhex(f"{n:X}")`.
- Python raises an error on an odd-length hex string. Left-pad with `0` so a TV with an odd-length modulus still works, and log it.
- The local first-byte check catches about 255/256 typos before anything is sent, so "wrong code" is instant and does not use up a TV attempt.

### 5.4 Client certificate
- **What androidtvremote2 generates:** self-signed **RSA-2048**, e=65537, SHA-256, CN = client name, 10-year validity, BasicConstraints CA, SAN = name ([certificate_generator.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/certificate_generator.py)).
- **Sceptrum's version:**
  - Generate one RSA-2048 key in the **Android Keystore** (alias `sceptrum_atv_client`): `KeyGenParameterSpec` with `PURPOSE_SIGN`, digests `NONE, SHA256, SHA384, SHA512`, paddings `PKCS1` and `PSS` (so both TLS 1.2 and 1.3 work), `setCertificateSubject(CN=Sceptrum)` and a 10-year validity. Keystore creates the self-signed certificate.
  - A custom `X509ExtendedKeyManager` returns that alias. The `SSLContext("TLS")` uses that key manager plus a **capture-then-pin trust manager**: during pairing it accepts the TV's self-signed certificate and records its SHA-256 fingerprint, and afterwards it pins it (§6).
  - **Fallback** [UNVERIFIED need]: if a TV rejects the Keystore-backed handshake, generate the key in software and store the PKCS#8 bytes inside the Tink-encrypted DataStore. This sits behind a debug flag and is tested in PR 3 on both devices.
- **One client identity for all TVs**, like the official app. A non-exportable key means a new phone has to re-pair, which is acceptable.

### 5.5 Pairing UX states
```
Choose device ─▶ "Connecting…" ─▶ "Enter the code shown on <name>" [ _ _ _ _ _ _ ] ─▶ "Paired ✓" ─▶ Remote
```
- **Code field:** 6 boxes, hex keyboard (0-9 and A-F as an on-screen keypad, since the system keyboard has no hex mode), auto-uppercase, paste support, and auto-submit on the 6th character.
- **Errors** (each a `ContrastSurface` card with one action):

| State | Cause | Message / action |
|---|---|---|
| `WrongCodeLocal` | first-byte check failed | "That code doesn't match. Check the TV and try again." The field stays open and nothing is sent. |
| `BadSecret` | TV `STATUS_BAD_SECRET` 402 | Same message. Restart from step 1, because the TV shows a new code. |
| `Rejected` | `STATUS_ERROR`/`BAD_CONFIGURATION`, or the TV closed the socket | "The TV refused pairing." Retry. |
| `Timeout` | no ack within 10 s | "No answer from the TV." Retry. Hint: "Is it on and on the same Wi-Fi?" |
| `Unreachable` | TCP connect failed or timed out | "Can't reach <IP>:6467." Link to the Wi-Fi checklist. On Android 17 with targetSdk 37, check the permission first (§8). |
| `NoRemoteService` | 6467 closed but 8008/8009 open (Cast only) | "This device doesn't offer Google TV remote control." This happens on Fire TV or on a device whose Remote Service is too old (version 5 or newer is needed [UNVERIFIED]; source: [atvremote Go docs](https://beta.pkg.go.dev/github.com/drosocode/atvremote)). |
| `NeedsRepair` | an SSL error on 6466 after pairing (androidtvremote2 maps it to `InvalidAuth("Need to pair again")`) | "<name> forgot this phone." Pair again. |

---

## 6. Credential storage

- **Do not use `EncryptedSharedPreferences`.** Every API in `security-crypto` is deprecated ([release notes](https://developer.android.com/jetpack/androidx/releases/security)).
- **Store:** one `DataStore<DeviceStore>`, a Wire/proto message holding a list of `PairedDevice`. Each entry has:
  - `id` (MAC or fingerprint), `driver` ("androidtv"), `name`, `host`, `port`, `mac`;
  - `serverCertSha256` (the pin);
  - `pairedAt`, `lastSeenAt`;
  - `appShortcuts`;
  - `features` (the last negotiated mask);
  - `hasTuner`;
  - a `driverSecrets` map, used later for the LG client-key, Samsung token, Sony PSK and PJLink password.
- **Encryption:**
  - Use a Tink `Aead` (AES256-GCM keyset) from `AndroidKeysetManager.Builder().withSharedPref(…).withKeyTemplate(AES256_GCM).withMasterKeyUri("android-keystore://sceptrum_master")`.
  - `androidx.datastore:datastore-tink` is only at **1.3.0-rc01** ([Google Maven](https://dl.google.com/android/maven2/androidx/datastore/datastore-tink/maven-metadata.xml)). So PR 3 writes a ~30-line `AeadSerializer` over `datastore` 1.2.1, with associated data `"sceptrum-devices-v1"`, and swaps to the official one when 1.3.0 is stable.
  - Decryption errors become `CorruptionException`. Recovery: wipe the store and show "Pair your devices again".
- **Not stored:** the TLS private key lives only in the Keystore.
- **Backup:** `android:allowBackup="false"` plus `dataExtractionRules` excluding everything. Keystore keys cannot restore, so a restored store would only be junk.
- **Server pin:** on every 6466 connect, compare the peer certificate's SHA-256 with `serverCertSha256`.
  - A mismatch means the TV was factory-reset or something is impersonating it. Do not send keys. Show "<name>'s identity changed. Pair again?"
  - The pin is only trusted for private-range IPs (RFC 1918 / link-local).

---

## 7. Session: connect, keepalive, reconnect, power

### 7.1 Control-channel handshake (port 6466)
Verified in [remote.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py).
1. The TV sends `remote_configure` (field 1). It contains `code1` (the TV's feature mask) and `device_info` (model, vendor).
2. The phone replies with `remote_configure { code1 = 615 & tvMask, device_info { unknown1 = 1, unknown2 = "1", package_name = "com.shibi.sceptrum", app_version = "<versionName>" } }`.
   - androidtvremote2 sends `package_name="atvremote"`. Whether TVs check this value is [UNVERIFIED]. Use our own package name, and fall back to `atvremote` behind a debug toggle if a TV misbehaves.
3. The TV sends `remote_set_active`. The phone replies `remote_set_active { active = negotiatedMask }`.
4. The TV sends `remote_start { started }` (power state) and `remote_set_volume_level` (level, max, muted). Then it sends `remote_ime_*` as focus changes.
5. Keys: `remote_key_inject { key_code, direction }` (field 10), with directions `SHORT`=3, `START_LONG`=1 and `END_LONG`=2.

### 7.2 Keepalive and reconnect
- **Ping:** the TV sends `remote_ping_request { val1 }` about every 5 s while idle. Reply at once with `remote_ping_response { val1 }`.
- **Idle watchdog:** if **16 s** pass with nothing received and nothing sent, close and reconnect. Both values come from androidtvremote2.
- **Reconnect:**
  - Exponential backoff starting at **0.1 s, doubling, capped at 30 s**, reset on success (androidtvremote2 `_async_reconnect`).
  - Retry on connect failure or connection loss.
  - Stop and show `NeedsRepair` on an SSL error.
- **Lifecycle** (`SessionManager`, an app-scoped coroutine owner, not a foreground service):
  - Connect when the remote screen starts.
  - Keep the connection for **60 s after the app is backgrounded**, so switching to another app and back is instant, then close.
  - Reconnect immediately on resume and on `ConnectivityManager.NetworkCallback.onAvailable` / `onLinkPropertiesChanged` for Wi-Fi.
  - No background service, so no notification and no battery drain. Widgets and the QS tile (PR 15) connect on demand.
- **UI status dot** on the device chip:
  - green: connected;
  - amber, pulsing: connecting or reconnecting (with the backoff countdown);
  - grey: TV off or unreachable;
  - red: pairing needed.

  Keys pressed while reconnecting are **not queued**, except Power. A `Reject` haptic plus a toast-style chip says "Not connected".

### 7.3 Power state, power-on and Wake-on-LAN
- **State:** `remote_start.started` gives on or standby while the session is up. A session that cannot be reached is shown as "Off or unreachable".
- **Power button logic** (pure `PowerPolicy`, unit-tested):
  1. Connected: send `KEYCODE_POWER` `SHORT`. Many Google TVs keep 6466 alive in standby, so this toggles both ways.
  2. Not reachable, but a MAC is known:
     - Send a **WoL magic packet** (6×`FF` plus the MAC ×16) to UDP 9, addressed to the subnet broadcast from `LinkProperties`. Send it 3×, 100 ms apart.
     - Then reconnect with backoff for 20 s, and show "Waking <name>…".
     - When connected, check `remote_start.started`. If the TV is still in standby, send `KEYCODE_POWER`.
  3. Still nothing: show a per-device tip card.
- **Limits** (the UI explains these per device):
  - Google TVs only wake if **network standby** keeps the NIC alive. Menu names differ by brand: "Network standby", "Wake on Wi-Fi/LAN", "Quick start", or TCL's **"Screenless service"** under Power & energy ([HA Android TV Remote](https://www.home-assistant.io/integrations/androidtv_remote/); TCL report: [365tipu](https://365tipu.cz/?p=55427)). Which TCL models have it is [UNVERIFIED].
  - **Wi-Fi WoL (WoWLAN) is unreliable.** Ethernet is far better. The **Horizon 20 Max has no Ethernet** ([RTINGS](https://www.rtings.com/projector/tools/compare/xgimi-horizon-20-max-vs-xgimi-horizon-20-pro/115774/120492)). Whether the **QM6K** has Ethernet is [UNVERIFIED]; check it when the TV arrives.
  - Some devices fall into deep sleep with the NIC off. Nothing over the LAN can wake them.
  - Some Wi-Fi connections use a **randomised MAC**. WoL needs the MAC actually used on that network. The certificate CN reports the device's MAC, and the checklist (§15) compares it with what the router shows.
  - A connection can look alive while the panel is fully off. A SmartThings driver author saw this ([SmartThings community](https://community.smartthings.com/t/edge-generic-google-tv-android-tv-driver-control-any-google-tv-via-smartthings/310455?page=2)). Always trust `remote_start.started` over "socket is open".
- WoL is LAN UDP broadcast, so it falls under the local-network permission at target 37 (§8).

---

## 8. Android 16/17 local-network permission

Verified on the [official page](https://developer.android.com/privacy-and-security/local-network-permission) and in the [Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17).

- **Name:** `android.permission.ACCESS_LOCAL_NETWORK`, a runtime permission in the **`NEARBY_DEVICES`** group. If the user has already granted another permission in that group (for example Bluetooth), they are not asked again.
- **Enforcement:**
  - mandatory on Android 17 for apps that **target 37+**;
  - at target ≤ 36, apps with `INTERNET` get a temporary implicit grant;
  - on Android 16 it is opt-in testing only, gated by `NEARBY_WIFI_DEVICES`.
- **What it blocks:** outgoing and incoming TCP, UDP unicast, multicast and broadcast, `NsdManager`, and `.local` resolution, across all libraries including OkHttp. TCP times out; UDP fails with `EPERM`. DNS to a LAN resolver on port 53 is exempt.
- **Official guidance:** "For these lower target SDKs, don't add ACCESS_LOCAL_NETWORK to your manifest or request it at runtime."
- **`NEARBY_WIFI_DEVICES`: not declared.**
  - A LAN remote does not need Wi-Fi Direct, Aware or RTT.
  - It only matters for the Android 16 compat test (`adb shell am compat enable RESTRICT_LOCAL_NETWORK com.shibi.sceptrum`, then a reboot), which is used in QA only.
  - If it is ever declared, add `usesPermissionFlags="neverForLocation"`.

**Runtime prompt plan (PR 6, together with targetSdk 37):**
1. **Onboarding screen, before the first scan:** "Sceptrum talks to your TV over your home Wi-Fi. Android will ask to let it find and connect to devices on your network." Buttons: **Continue**, which triggers `requestPermissions(ACCESS_LOCAL_NETWORK)`, and **Pick one device instead**, which uses the `FLAG_SHOW_PICKER` path for mDNS devices.
2. **Check before any socket:** `ContextCompat.checkSelfPermission` runs before every connect, scan or WoL. A pure `LanAccess.classify(sdkInt, targetSdk, granted, pickerGrantedHosts)` returns `Allowed`, `PickerOnly(hosts)` or `Blocked`.
3. **When blocked:** a full-height `ContrastSurface` card says "Sceptrum can't reach your TV because network access is off". It offers **Allow** (re-request) and **Open settings**, using `ACTION_APPLICATION_DETAILS_SETTINGS` > Permissions > Nearby devices.
4. **Error mapping:** a socket timeout or an `EPERM` while the permission is not granted maps to the `Blocked` state, never to "TV unreachable".
5. **Revocation mid-session:** the next failure re-checks the permission and shows the card.

The onboarding copy and the `Blocked` card are built in PR 4/5, behind `LanAccess`, which returns `Allowed` at target 36. PR 6 only flips the target and adds the manifest line.

---

## 9. Architecture

### 9.1 Modules
```
:app                      Activity, NavigationSuiteScaffold, screens, DI wiring (manual or Hilt; recommend manual
                          constructor DI like Spectacula until > 3 drivers), SessionManager, WorkManager-free
:core:designsystem        SceptrumTheme, AppBackground/PastelColor/BackgroundContrast (ported), ContrastSurface,
                          RemoteButton, Rocker, DpadDisc, SwipePad, StatusChip, haptics helpers        (Android)
:core:model               Device, Capability, Command, DeviceState, PairingFlow, DeviceDriver, DeviceSession,
                          PowerPolicy, LanAccess, KeyRepeat timing                                      (pure JVM)
:core:network             TLS helpers (KeyManager interface, pinning TrustManager), varint framing, WoL packet
                          builder + sender, subnet math                                                 (pure JVM, Android bits behind interfaces)
:core:storage             Tink AEAD DataStore, DeviceRepository, Keystore client-cert provider          (Android)
:core:discovery           NsdManager wrapper, manual probe, later SSDP + vendor UDP; MulticastLock scope (Android)
:driver:androidtv         Wire-generated polo + remotemessage, PairingMachine, RemoteSession, key map   (pure JVM)
:testing:fakes            FakeAndroidTvServer (TLS + protobuf), FakeDriver, fixtures (certs, vectors)    (pure JVM)
-- later --
:driver:webos  :driver:samsung  :driver:bravia  :driver:pjlink  :driver:gree  :driver:daikin
```
- **Pure-JVM drivers** keep protocol tests fast (no Robolectric). Android-only parts, such as the Keystore `KeyManager`, are injected through interfaces from `:core:storage`.
- **Licences:**
  - The `.proto` files vendored from androidtvremote2 keep their Apache-2.0 header.
  - A `NOTICE` and the About > Open-source licences screen credit androidtvremote2.
  - GPL/LGPL code (samsungtvws, pydaikin, greeclimate) is re-implemented from protocol knowledge, never translated.
  - openHAB (EPL-2.0) is read for understanding, not copied (earlier research §3).

### 9.2 Driver interface (sketch, from the earlier research and adjusted)
```kotlin
interface DeviceDriver {
    val id: String                                   // "androidtv", "webos", "pjlink", "gree"…
    val discoveryHints: List<DiscoveryHint>          // Mdns("_androidtvremote2._tcp"), Ssdp(st), UdpProbe(port, payload), TcpPort(n)
    suspend fun identify(endpoint: DiscoveredEndpoint): DeviceDescriptor?
    fun pairing(device: DeviceDescriptor): PairingFlow?          // null = no pairing
    suspend fun connect(device: DeviceDescriptor, secrets: DriverSecrets): DeviceSession
}
interface DeviceSession : AutoCloseable {
    val capabilities: StateFlow<Set<Capability>>     // can change after configure (e.g. IME, tuner)
    val state: StateFlow<DeviceState>                // power, volume(level,max,muted), currentApp, imeField, link status
    suspend fun send(cmd: Command, phase: KeyPhase = KeyPhase.Tap): SendResult   // Tap | Down | Up
}
sealed interface PairingFlow {
    data class CodeEntry(val length: Int, val alphabet: Alphabet /* HEX for ATV */) : PairingFlow
    data class AcceptOnScreen(val hint: String) : PairingFlow     // LG, Samsung
    data class SecretEntry(val label: String) : PairingFlow       // Sony PSK, PJLink password
}
```
- **Capability-driven UI:** the remote renders only what `capabilities` contains. Examples: PJLink has no volume, so the rocker is hidden; an AC session swaps the remote for the climate layout.
- **`DriverRegistry`** is a plain `Set<DeviceDriver>` built in `:app`.

---

## 10. CI (GitHub Actions)

Base it on Spectacula's `.github/workflows/android.yml`.
- **Trigger:** push to `main` and PRs; `permissions: contents: read`.
- **Setup:**
  - `actions/checkout@v4`, Temurin JDK 17 (`actions/setup-java@v5`), the same `sdkmanager` step installing `platforms;android-36` (and `android-37` from PR 6), `build-tools;36.0.0`, then `gradle/actions/setup-gradle@v4`.
- **Build and test:** `./gradlew assembleDebug test --stacktrace`. This includes the pure-JVM protocol tests and the fake-server tests.
- **Warning gate:** a step greps the Kotlin compile log for `^w:` and fails if it finds any, matching the definition of done.
- **Artifacts:**
  - upload `app/build/outputs/apk/debug/*.apk` as **`debug-apk`**;
  - run `./gradlew recordRoborazziDebug` and upload `app/build/outputs/roborazzi` as **`screenshots`** (`if: always()`).
- **Screenshot matrix** (Roborazzi 1.76.0 + Robolectric 4.17, `@Config(qualifiers=…)`):

| Size | Qualifier |
|---|---|
| Folded | `w411dp-h891dp-xxhdpi` (same as Spectacula) |
| Unfolded portrait | `w984dp-h1092dp-xhdpi` |
| Unfolded landscape | `w1092dp-h984dp-xhdpi` |
| Short phone | `w360dp-h640dp` |
| Tabletop | unfolded size plus a fake `FoldingFeature(HALF_OPENED, HORIZONTAL)` via `androidx.window:window-testing` |

  - Themes: Dark, light default, and pastel `sky`, `blush` and `mermaid` (a blend). That gives a 5 × 5 = 25 baseline per key screen.
  - Key screens: remote, devices, pairing (code entry and each error card), keyboard bar, apps grid, the Blocked permission card, and the volume chip.
  - Sheets are captured through extracted `*Content` composables, because a `ModalBottomSheet` captures empty (Spectacula retro V0).
  - Paths are full `build/outputs/roborazzi/<name>.png` (retro V0/V5).

---

## 11. Testing strategy

| Layer | What | How |
|---|---|---|
| Pairing secret | `PairingSecret.compute(clientCert, serverCert, code)` and `check()` | **Golden vectors:** two fixed RSA-2048 PEM pairs committed in `:testing:fakes`, with expected digests produced once by running androidtvremote2's own pairing code on them (recorded in the fixture header with the library version). Cases: correct code, wrong first byte, lowercase and space input, odd-length modulus padding, 5- and 7-char input. |
| Framing | varint codec | split reads at every byte offset, multi-message chunks, 1-byte vs 2-byte lengths (≥128), corrupt varint closes the stream |
| Protocol state machines | `PairingMachine`, `RemoteSession` | driven by the **FakeAndroidTvServer**: a JVM `SSLServerSocket` with a fixture self-signed certificate. It requires a client certificate, speaks the polo and remotemessage messages, and can be scripted to: send a code (fixed for the test), return `BAD_SECRET`, drop mid-handshake, stall (timeout), ping every 100 ms, stop pinging (idle watchdog fires at the injected clock time), send volume, start and IME events, change its certificate (pin mismatch), or reject the client certificate (`NeedsRepair`). Assertions cover the exact key-inject sequence for tap, hold (repeat) and long-press (`START_LONG`/`END_LONG`), the IME batch-edit counters echoed, and the app_link prefixing. Runs with `kotlinx-coroutines-test` virtual time. |
| Reconnect | backoff schedule 0.1→0.2→…→30 s, reset on success, stop on SSL error | virtual time against the fake server |
| Pure policies | `PowerPolicy`, `LanAccess.classify`, `KeyRepeat`, `VolumeChip` formatting, subnet broadcast math, WoL packet bytes, cert-CN → name and MAC parser | plain JUnit |
| Storage | AEAD serializer round-trip, corruption leads to wipe, associated-data mismatch fails | Robolectric (Keystore is faked with Tink's in-memory keyset in tests) |
| Contrast | every theme × every button container ≥ 4.5:1 (text) / 3:1 (icons); no `Slider(` in `ui/` | JUnit |
| UI | Remote screen on `FakeDriver`: capability hiding (no CH rocker without a tuner), disabled keyboard until an IME field exists, status dot states, error cards, fold/unfold keeps the session (`RuntimeEnvironment.setQualifiers` + `recreate()`, Spectacula retro "carousel v4") | Compose UI test on Robolectric, plus the Roborazzi matrix |
| Debug "Fake living room" | debug-only toggle that starts FakeAndroidTvServer on `127.0.0.1` inside the app | lets the UI be driven end-to-end on the Fold without a TV |
| Hardware | the owner's matrix: device, firmware, Remote Service version, date, pass/fail per feature | `docs/hardware-matrix.md` in the repo, updated in PRs 4, 5 and after the TCL arrives |

---

## 12. PR breakdown

Each PR is its own branch with green CI (`assembleDebug test`, 0 `w:`), the `debug-apk` and `screenshots` artifacts, a changed-PNG list, and an `AGENTS.md` retro line. Sizes: S < 300 lines, M 300-700, L 700-1200.

### Google TV MVP (PRs 1-5)

| # | PR | Scope | Acceptance criteria | Size |
|---|---|---|---|---|
| **1** | Skeleton, design system, CI | <ul><li>Repo files, version catalogue, modules `:app`, `:core:designsystem` and `:core:model`.</li><li>Ported themes (`AppBackground`, `PastelColor`, `BackgroundContrast`, `ContrastSurface`) and the root Surface.</li><li>`NavigationSuiteScaffold` with Remote, Devices and Settings placeholders; Appearance settings (dark, pastel grid).</li><li>Static remote layout (folded, unfolded, tabletop) on `FakeDriver`, with no networking.</li><li>CI workflow.</li></ul> | <ul><li>CI green with `debug-apk` and `screenshots`.</li><li>Contrast test passes for all 24 backdrops × Dark on/off.</li><li>The no-`Slider` test passes.</li><li>25 remote screenshots in the matrix.</li><li>Installs on the Fold 7; fold/unfold swaps layouts with no crash.</li></ul> | M-L |
| **2** | Android TV protocol core (pure JVM) | <ul><li>`:driver:androidtv`, `:core:network` and `:testing:fakes`.</li><li>Vendored `polo.proto` and `remotemessage.proto` (Apache header kept), Wire codegen, varint framing.</li><li>`PairingSecret`, `PairingMachine` and `RemoteSession` (configure, set_active, ping/pong, idle watchdog, key inject tap/hold/long, volume, start and IME state, app_link, reconnect backoff).</li><li>`FakeAndroidTvServer`.</li></ul> | <ul><li>Golden secret vectors pass.</li><li>The framing split test passes at every offset.</li><li>Every scripted fake-server scenario in §11 passes, and the suite runs under 30 s on CI.</li><li>No Android imports in these modules.</li></ul> | L |
| **3** | Pair a real Google TV | <ul><li>`:core:storage`: Keystore client certificate and `X509KeyManager`, pinning trust manager, Tink AEAD DataStore `DeviceRepository`, backup exclusion.</li><li>`:core:discovery`: NsdManager (API 34+ callbacks, queued resolve for 31-33), scoped MulticastLock, manual IP add with 6467 probe and cert-CN name/MAC.</li><li>Devices screen, pairing flow UI, all §5.5 error cards.</li></ul> | <ul><li>**On the XGIMI: discovered by mDNS and by manual IP, paired with the on-screen code, and the device survives an app restart.**</li><li>A wrong code is rejected locally.</li><li>A pin mismatch shown by a fake-server test.</li><li>Screenshots of each pairing state.</li><li>Software-key fallback flag tested on the device.</li></ul> | L |
| **4** | **Usable remote** (first shippable build) | <ul><li>`SessionManager` lifecycle (connect on start, 60 s grace, network-callback reconnect, status dot).</li><li>All §3.1 keys wired, with hold-to-repeat and long-press, haptics, phone volume keys, and the volume chip from TV state.</li><li>Power key while connected.</li><li>Channel rocker hidden unless tuner/TV.</li><li>`LanAccess` stub plus Blocked card UI.</li></ul> | <ul><li>**On the XGIMI: navigate the Google TV home, open and play a YouTube video, volume up/down/mute with the chip showing the TV's level, Back/Home, and standby via Power.**</li><li>Reconnects within 2 s after toggling the phone's Wi-Fi.</li><li>Fold and unfold mid-use keeps the connection.</li><li>Hardware matrix row added.</li></ul> | M-L |
| **5** | **Google TV MVP complete** | <ul><li>Keyboard bar (IME batch edit, Enter, key-press fallback).</li><li>Apps grid (app_link defaults, add by package or URL, "add current app").</li><li>Power-on flow (`PowerPolicy`, WoL with subnet broadcast ×3, wait-and-reconnect, per-device standby tips).</li><li>Device rename and forget, About > licences (androidtvremote2 Apache-2.0, Tink, Wire), onboarding copy.</li></ul> | <ul><li>**On the XGIMI: type a YouTube search, launch Netflix and YouTube from tiles, and power on from standby if the §15 checklist showed the network stays up (otherwise the tip card shows).**</li><li>WoL bytes unit-tested.</li><li>Release-build smoke test.</li><li>Tagged `v0.1.0`.</li></ul> | M-L |

### After the MVP

| # | PR | Scope | Acceptance criteria | Size |
|---|---|---|---|---|
| 6 | targetSdk 37 + local-network permission | Manifest `ACCESS_LOCAL_NETWORK`, onboarding request, `LanAccess` real implementation, `FLAG_SHOW_PICKER` path, error mapping (timeout/EPERM → Blocked) | On the Fold 7 (One UI 9 / Android 17): fresh install prompts; deny shows the Blocked card and nothing hangs; grant works; picker path pairs the XGIMI without the broad permission | M |
| 7 | **TCL 75QM6K validation** (when it arrives) | No new feature. Run the hardware matrix on Android 12, then again after the Android 14 update (reported firmware v590/v313 for Pentonic 700 sets: [9to5Google](https://9to5google.com/2026/01/22/tcl-is-preparing-android-14-updates-for-its-google-tv-lineup-whats-new/), [Android Authority](https://www.androidauthority.com/tcl-android-14-firmware-update-3634699)). Fix quirks: tuner/CH detection, Screenless service tip, Ethernet WoL | Both firmware versions pass §3.1, §3.2 and §7.3; note whether the update forced a re-pair | S-M |
| 8 | SSDP + driver registry generalised | Hand-rolled SSDP M-SEARCH client, merge and dedupe by IP/MAC, multi-driver claim, capability-driven layout switching | Unit tests with recorded SSDP replies; XGIMI still found once | M |
| 9 | LG webOS | SSAP over wss 3001 (ws 3000 fallback), PROMPT pairing and client-key, pointer socket for d-pad, WoL | Fake WS server tests; real-device row "beta" until tested | L |
| 10 | Samsung Tizen | wss 8002 token pairing, key Press/Release for hold, text, app launch, "First time only" tip, WoL | Fake WS tests; beta | L |
| 11 | Sony BRAVIA | REST + IRCC with PSK; `setPowerStatus`; WoL | MockWebServer tests | M |
| 12 | PJLink | Class 1/2, MD5 auth, UDP 4352 search, inputs, AV mute, no volume (rocker hidden) | Fake TCP tests; **try it on the XGIMI if 4352 is open** | M |
| 13 | Climate UI + Gree | Climate layout (mode chips, ± temperature **buttons**, fan, swing; no sliders); Gree UDP 7000 scan, bind, AES | Codec vectors; fake UDP device | L |
| 14 | Daikin local | BRP069A/B HTTP; BRP072C key + UUID; UDP 30050 discovery | MockWebServer tests | M |
| 15 | Extras (optional) | QS tile, launcher shortcuts, home-screen widget (Glance), tabletop polish | Screenshot and Robolectric tests | M |

---

## 13. Device notes for the two must-work devices

| | XGIMI projector | TCL 75QM6K |
|---|---|---|
| OS | **Confirmed by Shibi: XGIMI Horizon S Max (2024), Android TV 11** with official Google services ([XGIMI help: smartphone navigation](https://helpcenter.xgimi.com/hc/en-gb/articles/39094330905625-Can-I-navigate-through-the-HORIZON-S-series-interface-with-a-smartphone), [XGIMI KW support](https://xgimi.com.kw/pages/horizon-s-max-support)). XGIMI officially recommends the Google Home app as the phone remote, which uses the Android TV Remote Service, i.e. the same protocol v2 (ports 6466/6467) that androidtvremote2 and Sceptrum implement. v2 needs the "Android TV Remote Service" app on the device to be a recent version (v5+); update it via Play Store on the projector if pairing fails. Exact installed version [UNVERIFIED until checklist step 1]. | Google TV (Android 12, update to 14 announced) |
| Network | Wi-Fi 6. **No Ethernet** on the 20 Max ([RTINGS](https://www.rtings.com/projector/tools/compare/xgimi-horizon-20-max-vs-xgimi-horizon-20-pro/115774/120492)) | Ethernet presence [UNVERIFIED]; prefer wired if available |
| Power-on | Depends on standby keeping Wi-Fi up; WoL unlikely | Network standby / "Screenless service" plus WoL; test both |
| PJLink | **[UNVERIFIED]**: no XGIMI documentation found that mentions it. Check port 4352 (§15 step 8) | n/a (TV) |
| Tuner / channels | none (hide CH rocker) | yes (show CH rocker) [UNVERIFIED per region] |

---

## 14. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Google changes the Remote Service protocol or adds stricter client checks | Pairing or keys break after a TV update | <ul><li>Driver isolated behind the interface.</li><li>Keep up with androidtvremote2 releases (last push 2026-10-05).</li><li>Debug toggle for `package_name="atvremote"`.</li><li>Fake-server tests make any fix quick to verify.</li></ul> |
| Keystore-backed RSA key fails a TV's TLS handshake (TLS 1.3 needs RSA-PSS) | Cannot connect | PSS and NONE digests enabled; software-key fallback behind a flag (§5.4), tested in PR 3 |
| Projector cannot be woken over Wi-Fi | Power-on works only from the projector's own remote | <ul><li>Honest tip card.</li><li>Power **off** always works.</li><li>§15 tells you early.</li></ul> |
| IME text does not appear in some apps | Keyboard looks broken | Clear guidance ("select a search box first"), key-press fallback, per-app notes in the hardware matrix |
| TCL Android 14 update resets the remote service identity | Re-pair needed | Pin-mismatch card offers "Pair again" in one tap |
| Android 17 permission denied | No LAN at all once target 37 | Picker path; Blocked card; never mislabelled as "TV off" |
| Samsung One UI kills the app in the background | Reconnect lag | No background work by design; fast reconnect on resume |
| Router client isolation / guest network / mesh band steering | Discovery or connect fails | Same-subnet check, manual IP, troubleshooting card |
| LG pairing hardening after the Oct 2026 port 3001 exploit ([THN](https://thehackernews.com/2026/10/lg-smart-tvs-vulnerable-to-pre-auth.html)) | LG driver may break | LG ships "beta"; isolated driver |
| Licence contamination | Legal | Apache code credited; GPL/LGPL/EPL code not translated (§9.1) |

---

## 15. Owner checklist: run now on the XGIMI (about 10 minutes)
> **Update 2026-10-17:** the projector is the **XGIMI Horizon S Max (Android TV 11)**, not the Horizon 20 Max. Android TV 11 devices with an up-to-date "Android TV Remote Service" support the same Remote protocol v2 as Google TV, so the plan is unchanged; step 1 now checks that service version. Ethernet/WoL capability of the S Max is [UNVERIFIED]; steps 6-7 still apply.


Write the answers into a note, or paste them back to Claude.
1. **Exact model and OS:**
   - On the projector, open Settings > Device preferences > About and note the Android TV OS version (expect 11) and build. Then Settings > Apps > See all apps > Show system apps > **Android TV Remote Service**: note its version (needs v5 or later for protocol v2; update from the Play Store on the projector if older).
   - Then open Settings > Apps > See all apps > Show system apps > **Android TV Remote Service** and note its version. 5.x or newer means protocol v2 [UNVERIFIED threshold].
2. **Official app pairing works:**
   - On the Fold, open the **Google TV** app > Remote > "Scanning for devices" > choose the projector ([Google help](https://support.google.com/androidtv/answer/6122465)).
   - Confirm a **6-character code** appears on the projector and that the d-pad, volume and keyboard work. Note whether the keyboard typed into YouTube search.
3. **IP and MAC:**
   - Open Settings > Network & Internet > (your Wi-Fi) and note the **IP address**, and the **MAC** if shown. Note whether "Privacy: randomised MAC" is on.
   - In your router app, note the MAC it shows for the projector, and **reserve that IP** (DHCP reservation).
4. **Ports reachable** (Windows PowerShell on a laptop on the same Wi-Fi; replace the IP):
   ```powershell
   foreach ($p in 6466,6467,4352,8008,8009) { Test-NetConnection 192.168.1.50 -Port $p -InformationLevel Quiet | ForEach-Object { "$p : $_" } }
   ```
   Expected: `6466 : True` and `6467 : True`. 8008/8009 (Cast) are informational, and 4352 is PJLink (see step 8).
5. **mDNS visible:**
   - Install any free "Service Browser" / Bonjour browser app on the Fold.
   - Look for **`_androidtvremote2._tcp`** and note the name, IP and port it shows.
   - Also note whether `_googlecast._tcp` is present.
6. **Standby behaviour, which decides power-on:**
   - Press Power on the **projector's remote** (standby, not unplugging).
   - Then **after 1 minute** and again **after 15 minutes**:
     - (a) rerun step 4 for 6466;
     - (b) open the Google TV app remote and press Power. Does the projector turn on?
7. **Standby settings:** look in Settings > System > Power & energy (or "Power", or "Standby") for anything like **Network standby, Wake on Wi-Fi/LAN, Quick start, Screenless service, Eco/Deep standby**. Note the exact names and current values. Switch on the network-standby option, then repeat step 6.
8. **PJLink presence** (only if 4352 was `True` in step 4):
   ```powershell
   $c = New-Object Net.Sockets.TcpClient('192.168.1.50',4352); $s = New-Object IO.StreamReader($c.GetStream()); $s.ReadLine(); $c.Close()
   ```
   - `PJLINK 0` means PJLink with no password.
   - `PJLINK 1 xxxxxxxx` means a password is set.
   - Nothing, or a timeout, means no PJLink.

   Also look for a "PJLink" or "Network control" menu item.
9. **Network sanity:** confirm the phone and projector are on the **same SSID** and not on a guest network, and that the router has **"AP/client isolation" off**.
10. **Two remotes at once:** with the Google TV app paired, will a second app pair too? This is for later. Just note whether the projector lists or limits "connected remotes" anywhere in Settings.

When the **TCL** arrives: repeat steps 1-9. Note whether it has an **Ethernet** port (use it if you can). Run everything once on Android 12 and again after the Android 14 update.

---

## 16. Open questions for the owner

1. **Roku:** the ECP doc says commands "may not be sent from 3rd-party platforms (for example, mobile applications)" ([Roku ECP](https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md)). Ship it, skip it, or ask Roku? It is not scheduled.
2. **Cloud ACs** (SmartThings, LG ThinQ, Sensibo, Daikin ONECTA): in scope as "bring your own token", or local-only forever? Daikin ONECTA is EMEA-only, limited to 200 calls a day, and needs a partner licence for public apps.
3. **Midea V3 keys:** accept only user-imported token+key (recommended), or embed a reverse-engineered cloud fetch? The Midea token APIs closed in July 2026 ([mobiFlip](https://www.mobiflip.de/midea-portasplit-hersteller-zieht-den-stecker-bei-wichtiger-token-api/amp/)).
4. **Licence** for Sceptrum's own code (irreversible once others rely on it): Apache-2.0 (matches androidtvremote2; recommended), MIT, GPL-3, or none (all rights reserved)?
5. **Store distribution:** sideload via CI artifacts only, Google Play (US$25 one-time; trademark-safe listing, no brand logos; privacy policy needed because it handles device credentials), or F-Droid (FOSS only, no proprietary SDKs, which Sceptrum can meet)?
6. ~~XGIMI model~~ **Answered:** Horizon S Max (2024), Android TV 11.
7. **Voice search** through `remote_voice_*`: wanted? It needs the `RECORD_AUDIO` permission and adds a mic button. Not in the MVP.
8. **Background presence:** is "no notification, no background service" right? The alternative is an optional persistent media-style notification with Power, Vol ± and Mute for quick control.
9. **Navigation look:** a plain 3-item rail/bar (recommended for thumb space), or a small Spectacula-style glass carousel for brand consistency?
10. **Icon concept:** a sceptre (an eagle-topped rod) as a single glyph in Spectacula's amber-on-dark palette, plus a monochrome layer. Approve, or propose another?
11. **Hardware for later drivers:** which LG, Samsung, Sony or AC units can you actually test on? Drivers without hardware ship labelled "beta".

---

## Sources

- Protocol (androidtvremote2, Apache-2.0):
  - [repo](https://github.com/tronikos/androidtvremote2)
  - [remotemessage.proto](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto)
  - [polo.proto](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/polo.proto)
  - [pairing.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/pairing.py)
  - [remote.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py)
  - [androidtv_remote.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/androidtv_remote.py)
  - [certificate_generator.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/certificate_generator.py)
  - [base.py](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/base.py)
  - [AOSP pairing protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/+/refs/heads/master)
- Home Assistant and community:
  - [HA Android TV Remote integration](https://www.home-assistant.io/integrations/androidtv_remote/)
  - [HA community: IME text not appearing](https://community.home-assistant.io/t/androidtv-remote-v2/818996)
  - [SmartThings Google TV driver thread](https://community.smartthings.com/t/edge-generic-google-tv-android-tv-driver-control-any-google-tv-via-smartthings/310455?page=2)
  - [TCL Screenless service report](https://365tipu.cz/?p=55427)
  - [atvremote (Remote Service version note)](https://beta.pkg.go.dev/github.com/drosocode/atvremote)
  - [Google TV app pairing help](https://support.google.com/androidtv/answer/6122465)
- Android platform:
  - [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
  - [Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)
  - [Play target API](https://developer.android.com/google/play/requirements/target-sdk)
  - [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)
  - [ServiceInfoCallback](https://developer.android.com/reference/android/net/nsd/NsdManager.ServiceInfoCallback)
  - [MulticastLock](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock)
  - [Keystore](https://developer.android.com/privacy-and-security/keystore)
  - [security-crypto deprecation](https://developer.android.com/jetpack/androidx/releases/security)
  - [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore)
  - [fold-aware layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware)
- Library versions (Maven metadata, checked 2026-10-17):
  - [datastore-tink](https://dl.google.com/android/maven2/androidx/datastore/datastore-tink/maven-metadata.xml) (1.3.0-rc01)
  - [tink-android](https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/maven-metadata.xml) (1.24.0)
  - [wire-runtime](https://repo1.maven.org/maven2/com/squareup/wire/wire-runtime/maven-metadata.xml) (7.1.0)
  - [protobuf-javalite](https://repo1.maven.org/maven2/com/google/protobuf/protobuf-javalite/maven-metadata.xml) (4.36.2 stable)
  - [material3-adaptive](https://dl.google.com/android/maven2/androidx/compose/material3/adaptive/adaptive/maven-metadata.xml) (1.3.0 stable)
  - [androidx.window](https://dl.google.com/android/maven2/androidx/window/window/maven-metadata.xml) (1.5.1 stable)
  - [Roborazzi](https://repo1.maven.org/maven2/io/github/takahirom/roborazzi/roborazzi/maven-metadata.xml) (1.76.0)
- Devices:
  - XGIMI Horizon 20 Max: [Android Authority](https://www.androidauthority.com/xgimi-horizon-20-google-tv-projectors-3593374/), [Notebookcheck](https://www.notebookcheck.net/Extremely-bright-yet-quiet-Xgimi-Horizon-20-Max-Beamer-review.1186899.0.html), [RTINGS](https://www.rtings.com/projector/tools/compare/xgimi-horizon-20-max-vs-xgimi-horizon-20-pro/115774/120492)
  - Horizon S Max: [androidtv-guide](https://www.androidtv-guide.com/?p=17178)
  - TCL Android 14: [9to5Google](https://9to5google.com/2026/01/22/tcl-is-preparing-android-14-updates-for-its-google-tv-lineup-whats-new/), [Android Authority](https://www.androidauthority.com/tcl-android-14-firmware-update-3634699)
  - Z Fold 7 viewport: [yesviz](https://yesviz.com/devices/samsung-z-fold7/)
- Prior research: `REMOTE_research.md` (same folder). Design language: `SPECTACULA_plan.md` and `local_music_player` (`AGENTS.md`, `BackgroundContrast.kt`, `ContrastSurface.kt`, `AppBackground.kt`, `libs.versions.toml`, `app/build.gradle.kts`, `.github/workflows/android.yml`), all read-only.
