# Hardware matrix

The owner fills this in on real devices (plan §11). One row per device, firmware and feature; mark each **pass**, **fail** or **n/a**, with the date and a note.

## XGIMI Horizon S Max (Android TV 11)

Android TV Remote Service version: _fill in_ · Sceptrum version: 0.1.0 (PR 5)

| Feature (PR) | How to test | Result | Date | Notes |
|---|---|---|---|---|
| Discovery and pairing (3) | Devices: the projector shows up; pair with the on-screen code | | | |
| Keys, volume chip, Back/Home (4) | Navigate home, play a YouTube video, VOL +/−/mute | | | |
| Standby via Power while connected (4) | Power key | | | |
| Keyboard: IME text (5) | YouTube > Search, select the search box: the bar opens by itself and shows "Type on TV · YouTube"; type "lofi", results update; Delete shortens; Enter searches | | | |
| Keyboard: clear (5) | Clear the bar: the TV field empties (sent as DEL keys) | | | |
| Keyboard: key-press fallback (5) | Settings > Type with key presses on, then type in a field the IME ignores | | | |
| Apps: YouTube and Netflix tiles (5) | Apps > YouTube; Apps > Netflix | | | |
| Apps: other defaults (5) | Prime Video, Disney+, Plex, Settings (note which open) | | | |
| Apps: add current app / add by package (5) | Open any app on the TV, Apps > Add current app; add `com.android.tv.settings` | | | |
| Power on from standby (5) | Standby with the projector's remote, wait 1 min and 15 min, press Power in Sceptrum: "Waking…" then on, or the tip card after 20 s | | | |
| Swipe pad (5) | Swipe toggle; swipe moves focus one step per finger-width, tap = OK | | | |
| Rename and forget (5) | Devices > pencil: rename; forget and pair again | | | |
| Release build (5) | Install the release APK (see README) and repeat keys + one app tile | | | |

## TCL 75QM6K (Google TV)

To do when it arrives (plan PR 7): the same rows, on Android 12 and again after the Android 14 update; also note Ethernet and "Screenless service".
