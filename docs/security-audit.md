# Sceptrum security audit (PR 6, skills hardening)

Checked on branch `opus/pr6-skills-hardening` (from v0.1.1) with Google's
`android-permissions-security` and `android-intent-security` skills, 2026-10-17.
Scope: `app/src/main/AndroidManifest.xml`, the merged debug manifest, backup
rules, the app-link path and the error log. targetSdk and compileSdk are 36.

## Summary

| Area | Result | Action in this PR |
|---|---|---|
| Permissions | Four normal permissions, no runtime permissions. One is unused (`ACCESS_WIFI_STATE`). | None (see F3). |
| Exported components | Only `MainActivity` (launcher). It never reads its Intent. | None. |
| Backup and device transfer | `allowBackup="false"` and `data_extraction_rules.xml` excludes every domain. | None. |
| Local network (Android 17) | Correct for targetSdk 36: implicit grant, nothing declared. | None; plan for 37 below. |
| User-added app links | Any scheme was accepted, including `intent:`, `file:` and `javascript:`. | **Fixed** (F1): scheme allow-list. |
| Error log | Callers passed no secrets, but platform exception text was logged unfiltered. | **Fixed** (F2): redaction layer. |
| Soft keyboard | No `windowSoftInputMode`. Not a security issue; fixed with the IME work. | `adjustResize` added. |

## Permissions

| Permission | Level | Used by | Needed? |
|---|---|---|---|
| `INTERNET` | normal | TLS sockets to the TV (6466/6467), Wake-on-LAN UDP | Yes |
| `ACCESS_NETWORK_STATE` | normal | `ConnectivityNetworkEvents` (network callback), `ConnectivityLanAddress` (`getLinkProperties`) | Yes |
| `CHANGE_WIFI_MULTICAST_STATE` | normal | `NsdTvDiscovery` multicast lock, held only during the 8 s scan | Yes (OEM builds; SSDP later) |
| `ACCESS_WIFI_STATE` | normal | Nothing in the code today | No (F3) |
| `ACCESS_LOCAL_NETWORK` | runtime, `NEARBY_DEVICES` | Not declared | Not at targetSdk 36 (see below) |
| `NEARBY_WIFI_DEVICES` | runtime | Not declared | No: no Wi-Fi Direct, Aware or RTT |
| Location | runtime | Not declared | No: the subnet comes from `LinkProperties`, not the SSID |

The app defines no custom permission. The merged manifest has one from
androidx.core, `com.shibi.sceptrum.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
at protection level `signature`. That is correct for a library that supports
`RECEIVER_NOT_EXPORTED` below API 33. No permission state is cached, because
there is no runtime permission yet.

## Components (merged debug manifest)

From `app/build/intermediates/merged_manifests/debug/…` (debug builds set
`android:debuggable="true"`, as expected).

| Component | Exported | Notes |
|---|---|---|
| `com.shibi.sceptrum.MainActivity` | yes | Required for `MAIN`/`LAUNCHER`. `singleTop`. Does not read `getIntent()` and has no `onNewIntent`, so there is nothing to redirect or validate. |
| `androidx.activity.ComponentActivity` | yes | **Debug builds only**, from `ui-test-manifest` (Compose UI tests). Shows an empty screen. Release builds don't contain it. |
| `androidx.compose.ui.tooling.PreviewActivity` | yes | **Debug builds only**, from `ui-tooling` (`debugImplementation`). It renders a named `@Preview`; Sceptrum has none. Release builds don't contain it. |
| `androidx.startup.InitializationProvider` | no | Library initialisers. |
| `androidx.profileinstaller.ProfileInstallReceiver` | yes | Protected by `android.permission.DUMP` (shell/system only). Standard. |

No services, no app-owned receivers or providers, and no `PendingIntent`. The
app never launches an Intent built from outside input: the only `startActivity`
is the fixed `ACTION_APPLICATION_DETAILS_SETTINGS` for its own package.

## Backup, device transfer and storage

- `allowBackup="false"`, and `dataExtractionRules` excludes `root`, `file`,
  `database`, `sharedpref` and `external` for both cloud backup and device
  transfer. minSdk is 31, so `fullBackupContent` is not needed.
- The device store (paired TVs, the client private key and certificate) is one
  DataStore file sealed with Tink AES-256-GCM. Its keyset is wrapped by an
  Android Keystore key, so a copied file can't be read.
- `error_log.txt` is in internal storage (`filesDir`). It is never shared
  except through **Copy all**, which the user starts.

## Local network permission: targetSdk 36 vs 37

| OS | targetSdk 36 (today) | targetSdk 37 (future PR) |
|---|---|---|
| Android 16 (One UI 8) | No restriction (QA-only compat flag, gated by `NEARBY_WIFI_DEVICES`). | Same. |
| Android 17 (One UI 9) | Temporary implicit grant for apps with `INTERNET`. Google says not to declare or request `ACCESS_LOCAL_NETWORK` yet. | LAN blocked by default: TCP, UDP (unicast, multicast, broadcast), `NsdManager`, `.local`. Must declare and request `ACCESS_LOCAL_NETWORK`. |

Sceptrum follows the targetSdk 36 rule. `LanAccess.classify` already models 37
(`Blocked` → `LanBlockedCard`, whose button opens the app's details settings).
For the 37 PR (already planned in `SCEPTRUM_plan.md` §8):

1. Declare `ACCESS_LOCAL_NETWORK` and request it from the onboarding screen.
   Use the 3-step flow: check the grant → show the rationale →
   `ActivityResultContracts.RequestPermission`. After a permanent denial, open
   `ACTION_APPLICATION_DETAILS_SETTINGS`.
2. Don't cache the grant. `RemoteRoute` currently keeps `graph.lanAccess()` in a
   `remember`, which is fine while it is always `Allowed`. At 37, call
   `checkSelfPermission` before each connect, scan and wake, and re-check on
   resume.
3. Treat a `SecurityException` or `EPERM` from a socket as `Blocked`, not
   "TV offline".
4. Keep `NEARBY_WIFI_DEVICES` undeclared. If it is ever added, add
   `usesPermissionFlags="neverForLocation"`.

## Findings

### F1 (Medium, fixed): user-added app links accepted any URI scheme

`AppShortcuts.linkFor()` accepted anything with a scheme. Sceptrum sends the
link to the TV as `remote_app_link_launch_request`, and the TV starts what it
names. A typed `intent:#Intent;component=…;S.extra=…;end` could therefore start
any exported activity on the TV with chosen extras. `file:`, `content:` and
`javascript:` links could reach whatever handles them on the TV. Low real-world
risk (the owner's own phone and TV), but it was an open door.

Fix:
- One allow-list in `AppShortcuts`: `webSchemes` (https, http, market) plus
  `appSchemes` (plex, netflix, youtube, vnd.youtube, spotify, primevideo).
  Web and market links also need a `//host`. Control characters, whitespace
  and any `#` fragment are refused. The fragment matters: `Intent.parseUri`
  turns `https://x#Intent;component=…;end` into an arbitrary intent whatever
  the scheme (all three reviewers flagged this). Schemes are stored lower-case.
- The add form shows "Sceptrum only sends web, Play Store and known app links
  (…)" for any other scheme. The text is `onSurfaceVariant` inside the glass
  card, so it takes the card's own content colour.
- Tiles already saved in the device store go through the same check.
  `AppShortcuts.effective()` leaves out tiles that fail, and `AppsViewModel`
  logs each one once to the ErrorLog (scheme only, never the rest of the link).
  `AppsViewModel.launch()` checks again before sending.
- The built-in `intent:` fallbacks for Settings and Input stay in
  `TvShortcut` and go `RemoteViewModel` → `RemoteSession.launchApp`. They never
  pass through the user path. A test checks that none of them would pass the
  user allow-list.

### F2 (Low, fixed): error log could store secrets carried by platform exceptions

Every `ErrorLog.record` call site passes plain messages, device name, address,
status, links and exception chains. None passes the client key, certificate,
pairing secret or code. Checked call sites: `SessionManager` (6),
`PairingViewModel` (3) and `AppsViewModel` (2). Links are now logged through
`AppShortcuts.logLabel`: scheme and host (plus a market package) only, never a
path, query or fragment. The exception is the built-in `intent:` fallbacks,
which hold no user data.
`PairingDiagnostics.report` carries the key *algorithm and size* only. But
exception messages from Conscrypt or the JDK are logged as they are, and a
future driver change could put a certificate or token into one.

Fix: `diagnostics/Redaction` scrubs every field before it is stored. Exception
messages are cut to 500 characters first. Old entries are scrubbed again when
read back from disk, and the file is rewritten if anything changed. It removes:
- PEM blocks, including cut-off ones;
- base64 or base64url runs of 40 or more characters that mix letters and digits (DER, keys);
- hex runs of 32 or more characters (digests, secrets);
- colon-separated fingerprints of 16 or more octets;
- the value after a known secret field (`secret`, `private_key`,
  `pairing code`, `token`, `password`, `authorization`, …);
- a six-hex pairing code after the word "code".

Device names, IP addresses, ports and MAC addresses (6 octets) stay, as the
owner wants. `ErrorLogTest` feeds a chain with all of these and checks the
report and the file on disk.

### F3 (Info, not changed): `ACCESS_WIFI_STATE` is declared but unused

`NsdTvDiscovery` uses `WifiManager` only for `createMulticastLock`. Nothing
calls `getConnectionInfo`, `isWifiEnabled` or scan results. The multicast lock needs only `CHANGE_WIFI_MULTICAST_STATE`.
`LinkProperties` needs only `ACCESS_NETWORK_STATE`. It is a normal permission,
so the user sees no prompt. Proposal: remove it in the targetSdk 37 PR, after
one discovery check on the Fold 7 (One UI 8 and 9).

### F4 (Info): debug-only exported `ComponentActivity` and `PreviewActivity`

These come from `ui-test-manifest` and `ui-tooling` (`debugImplementation`),
which Compose UI tests and Android Studio previews need. Neither exposes app
data, and release builds contain neither. No action. Install a release build
for daily use once signing is decided.

### F5 (Info): clipboard

**Copy all** puts the redacted report on the clipboard. It holds device names
and LAN addresses, which the owner wants in reports. No secrets remain after
F2. Optional: set `ClipDescription.EXTRA_IS_SENSITIVE` so Android 13+ hides
the preview.
