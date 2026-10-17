# Channel buttons: can the TV tell us it has channels?

The remote shows a CH up/down rocker (`KEYCODE_CHANNEL_UP` 166 / `KEYCODE_CHANNEL_DOWN` 167).
It is useless on a projector (the owner's XGIMI Horizon S Max, Android TV 11) and on
streaming boxes and sticks (Chromecast with Google TV, Shield). The plan (§3.1, PR 4 row) said
"hidden unless the device reports a live-TV input". This note checks whether any device
actually reports that, in October 2026.

## What the TV sends

| Source | What it carries | Tells TV from projector/box? |
|---|---|---|
| `remote_configure.device_info` (port 6466) | `model`, `vendor`, `unknown1`, `unknown2`, `package_name`, `app_version` | No. Free-form strings. `package_name`/`app_version` describe the remote service, not the hardware. A model name is a heuristic only (see below). |
| `remote_configure.code1` (feature bits) | PING 1, KEY 2, IME 4, VOICE 8, UNKNOWN_1 16, POWER 32, VOLUME 64, APP_LINK 512 | No. No tuner, input or live-TV bit. androidtvremote2 does not interpret bit 16 either. |
| `remote_start`, `remote_set_volume_level` | power state; volume level, max, muted | No. Projectors and boxes report volume too. |
| `remote_ime_key_inject.app_info` | foreground `app_package`, `label` | Not reliably. A TV app (`com.google.android.tv`, `com.android.tv`, vendor live-TV apps) may show up on a TV, but also exists on boxes, and a TV whose owner never opens live TV never reports it. Absence proves nothing. |
| mDNS `_androidtvremote2._tcp` TXT | `bt=` (Bluetooth/MAC address, used by Home Assistant as unique id) | No. That is the only key clients read; there is no device-type key. |
| Pairing (port 6467, polo) | certificates, status codes | No. |

The protobuf `RemoteKeyCode` enum lists many TV keys (`KEYCODE_TV` 170, `KEYCODE_GUIDE` 172,
`KEYCODE_TV_ANTENNA_CABLE` 242, …), but these are keys the client may send. The TV never says
which of them do anything.

## What other clients do

- **androidtvremote2** (tronikos, the reference implementation Sceptrum ports from) keeps
  `device_info` as `manufacturer`/`model`/`sw_version` and the feature mask. It never
  classifies the device and has no tuner or channel capability.
- **Home Assistant `androidtv_remote`** sets `MediaPlayerDeviceClass.TV` on every device, with
  no branching. Channels are only reachable as `play_media(media_type=channel)`, which types
  digits, for every device. Zeroconf discovery reads only `bt`.
- No androidtvremote2 issue asks for, or documents, device-type detection.

## Is the model name enough?

No. `vendor`/`model` are whatever the OEM put in `ro.product.*`: "XGIMI" can be a projector,
"TCL" can be a TV or a box, Chromecast is "Google", Sony and Philips make both TVs and
projectors or boxes, and an unknown model must default to "show". A list of model strings would
need constant upkeep and still guess wrong, which the task rules out as "not reliable on its
own". On Android itself the reliable signal would be `PackageManager.FEATURE_LIVE_TV` or
`TvInputManager` inputs, but the Remote v2 protocol does not expose them to the phone.

## Decision

**There is no reliable signal.** Sceptrum does not guess. The rocker stays visible by default,
and each saved device gets a **Show channel buttons** switch (Devices > edit card, default on).
It is stored per device as `PairedDevice.hide_channel_buttons` (field 15; proto3 `false`
default means "show", so existing devices keep the rocker). The unused `has_tuner` field 12 is
left in place and marked as unused. The channel keys stay in the protocol and in `RemoteKey`.

Pairing a device again saves a fresh record, so the switch goes back to on, just as the name
and the edited app grid go back to their defaults today. The switch saves at once; a failed write
turns it back and goes to the error log.

When the rocker is hidden, the volume row has two items (volume rocker, mute) spread evenly by
`KeyRow`'s `SpaceEvenly`, so there is no hole, folded or unfolded.

If a future firmware adds a feature bit or a TXT key for live TV, it would be checked in
`AtvRemoteClient`'s `remote_configure` handler, and the setting would then default from it.

## Sources

- androidtvremote2 protobuf: <https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto>
  (`RemoteConfigure`, `RemoteDeviceInfo`, `RemoteAppInfo`, `RemoteKeyCode`)
- androidtvremote2 client: <https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py>
  (`Feature` IntFlag, `remote_configure` handling, `device_info`)
- androidtvremote2 issues: <https://github.com/tronikos/androidtvremote2/issues> (nothing on device type)
- Home Assistant config flow (zeroconf reads only `bt`): <https://github.com/home-assistant/core/blob/dev/homeassistant/components/androidtv_remote/config_flow.py>
- Home Assistant media player (`device_class` always TV, channel via digits): <https://github.com/home-assistant/core/blob/dev/homeassistant/components/androidtv_remote/media_player.py>
- `bt` TXT key on the older `_androidtvremote._tcp` service: <https://github.com/youviewtv/tinydnssd>
