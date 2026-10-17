# Apps panel: showing the TV's real apps

## The problem

Today the Apps grid is a hardcoded list in
`app/src/main/java/com/shibi/sceptrum/domain/AppShortcuts.kt` (`defaults`),
drawn with letter avatars (`initials`). The owner wants the TV's real
installed apps, with real icons.

## What the protocol can do (and cannot)

Sceptrum speaks the Android TV Remote protocol v2 on port 6466.

- The protocol has **no message that lists installed apps**, and none that
  sends icons.
- The TV only reports the **current foreground app** package, in
  `remote_ime_key_inject.app_info.app_package`. Sceptrum already shows this as
  `currentApp` and offers "Add current app".
- Launching only happens through `remote_app_link_launch_request` with a URI:
  a deep link (`https://www.youtube.com`, `plex://`) or
  `market://launch?id=<package>`. Google changed `market://launch` and it no
  longer works reliably for many apps.
- androidtvremote2's author confirms the app list cannot be retrieved
  (github.com/tronikos/androidtvremote2 issue 17). Other Remote v2 clients
  (for example reMaestro) ship a static list for the same reason.

So a real app list needs another route. Four routes are possible; the last one
is what we have today.

## Option A - learn over time (no TV setup)

Remember every package the TV reports as the foreground app, per device, and
offer each as a tile.

1. When the TV reports `app_info.app_package`, save the package for that device.
2. Show a tile per remembered package, newest first.
3. Label from the built-in table of known packages, else the last part of the
   package name.

Icons still come from the TV's side? No: **no icons from the TV**, only letter
badges or bundled generic artwork.

- Risk: none.
- Result: partial list, only apps the owner has opened.

**Status: shipped in PR 7.** Rules in `domain/LearnedApps.kt` (deny-list of
launchers, settings and system screens plus any package whose last part
contains "launcher" or is "settings"/"systemui"; 40 apps per TV, least recently
seen evicted; removals stick), storage in `data/LearnedAppsRepository.kt`
(`learned_apps.pb`, Tink-encrypted, keyed by device id), tiles under "Seen on
this TV" after the curated grid. Launch uses the known deep link, else
`market://launch?id=<package>`, through the PR 6 allow-list. Badges are
generated per package (`domain/AppBadge.kt`), never artwork. Options B and C
are not started; the owner has not chosen them.

## Option B - ADB over the network (one-time setup on the projector)

No app is installed on the TV. The owner enables developer options once.

On-screen steps (Android TV 11 names; XGIMI menu names unverified, some
firmware puts About under Settings > System):

1. Open Settings (gear) > Device Preferences > About.
2. Select Build (Android TV OS build) and press OK seven times quickly, until
   "You are now a developer".
3. Go back to Device Preferences > Developer options and turn on USB debugging
   (some firmware calls it Network debugging or ADB debugging; turn it on if
   shown).
4. Note the projector's IP: Settings > Network & Internet > (your Wi-Fi).
5. In Sceptrum (future feature) tap "Connect for app list". The projector shows
   "Allow USB debugging?" with the phone's key fingerprint: tick "Always allow
   from this computer" and select OK.

Classic network ADB listens on port 5555 on most Android TVs once USB debugging
is on (XGIMI Horizon users report it works; **unverified on the Horizon S Max**).
Android TV 11 normally has no "Wireless debugging" pairing-code mode; that newer
mode is different and not covered here.

What the app could do then:

- List launchable TV apps:
  `cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER`
  (plus `pm list packages -3` for user apps).
- Launch with:
  `monkey -p <package> -c android.intent.category.LEANBACK_LAUNCHER 1`.
- Icons and labels are hard over a shell: no shell command prints a label or
  icon. It needs `pm path <package>`, pulling the APK and parsing its resources
  (heavy), so icons may still need a fallback.

Library: dadb (Apache-2.0, pure Kotlin/JVM ADB client by mobile.dev) would
avoid writing the ADB protocol. Its RSA key would be stored in the encrypted
store and never logged.

Downsides:

- ADB gives full shell access to the projector (security).
- Android drops an authorisation not used for 7 days unless "Disable ADB
  authorisation timeout" is on.
- The owner must leave debugging on; some firmware turns it off after updates.
- Revoke with Developer options > Revoke USB debugging authorisations.

## Option C - a small companion app on the projector

A "Sceptrum TV helper", installed once by sideloading (for example with the
Downloader app, or via Play if published). It asks PackageManager for
LEANBACK_LAUNCHER activities and returns labels, banners and icons over the LAN
to the phone, and can launch any package directly.

- Result: best - real icons and names, reliable launch.
- Cost: build, sign and install a second app, and allow "Install unknown apps"
  for the sideloading app on the projector.

## Option D - icons from public sources by package name

Look up icons from a store page by package name. Combine with Option A.

- Fragile, depends on the network, has terms-of-use questions.
- Not recommended as the main path.

## Option E - keep the editable curated list (today)

Keep today's editable list, but mark tiles not confirmed on this TV.

## Comparison

| Option | Setup on TV | Icons | Labels | App list | Launch | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| A learn over time | none | badge only | known table / package | partial | as today | none |
| B ADB over network | dev options on | fallback only | shell (no labels) | full launchable | monkey | full shell access |
| C companion app | sideload once | real | real | full | direct | second app, signing |
| D public sources | none | lookups | lookups | partial | as today | fragile / terms |
| E curated (today) | none | badge | typed | manual | URI / market | none |

## Recommendation

1. Ship **A** first: cheap, no setup, no risk.
2. Then offer **B** as an opt-in "Connect for app list", for owners willing to
   enable developer options.
3. Consider **C** only if real icons matter more than setup effort.

## What Shibi can test now

1. Enable Developer options and USB debugging with the steps in Option B, and
   report the **exact menu names** on the Horizon S Max.
2. From a PC, run:

   ```
   adb connect <ip>:5555
   adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER
   ```

   Paste the output. It tells us whether Option B works on this projector.
