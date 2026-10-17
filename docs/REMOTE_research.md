# Universal Wi-Fi Remote for Android: Research Report

*Research date: 2026-10-17. Scope: research only. No code has been written.*
*Target: Kotlin + Jetpack Compose, LAN/Wi-Fi only (no IR), Galaxy Z Fold 7 (folded and unfolded).*
*Anything marked **[UNVERIFIED]** could not be confirmed from a primary or reliable secondary source during research.*

---

## 0. Executive summary

- **Feasible, with a clear first release.** Six device families can be controlled over the local network with no cloud account, no API key and no vendor developer program:
  - Android TV / Google TV (Remote protocol v2)
  - LG webOS (SSAP)
  - Samsung Tizen (WebSocket)
  - Sony BRAVIA (official REST/IRCC)
  - Roku (official ECP)
  - Projectors via PJLink

  Together these cover most smart TVs and networked projectors sold in the last ~8 years.
- **Platform change you must design for now.** Android 17 (One UI 9 is already rolling out to the Z Fold 7 since late Sept 2026) adds a **local network permission (`ACCESS_LOCAL_NETWORK`)**.
  - For apps targeting API 37+, all LAN traffic is blocked by default unless the user grants it. That includes SSDP, mDNS, TCP/WebSocket to the TV and Wake-on-LAN.
  - Apps targeting ≤36 get a temporary implicit grant.
  - The app should be built permission-aware from day 1. [Android docs](https://developer.android.com/privacy-and-security/local-network-permission)
- **Air conditioners are the hard part.**
  - **Fully local, no cloud:** Gree/EWPE (UDP 7000) and older Daikin Wi-Fi adapters (HTTP).
  - **Midea family** (Midea, Comfee, Carrier, Toshiba-NA…): LAN-controlled, but V3 devices need a token and key that historically came from Midea's cloud. Midea closed several of those token APIs in July 2026.
  - **Cloud-only with official APIs:** LG ThinQ, Samsung SmartThings, Daikin ONECTA and Sensibo all need the user's own token or account.
  - **Recommendation:** defer ACs to phase 2, starting with Gree and Daikin local.
- **Legal and ToS flags.**
  - **Roku's ECP doc says ECP "may not be sent from 3rd-party platforms (for example, mobile applications)".** Many Play Store apps do it anyway. This is an owner decision.
  - The Google Cast terms forbid re-implementing Cast SDK functionality.
  - Hisense VIDAA needs certificates extracted from Hisense's app. Avoid it.
  - Reverse-engineered AC clouds (Haier, Panasonic, MELCloud) have seen takedowns or blocking. Avoid them.
- **Code to learn from.** Apache/MIT Python libraries (androidtvremote2, aiowebostv, rokuecp, pybravia, aiopjlink, midea-local) are safe to port. openHAB (Java, EPL-2.0) has JVM implementations of nearly every protocol. Samsung (LGPL), Daikin and Gree (GPL) libraries should be **re-implemented from protocol knowledge, not translated**.
- **Storage.** `EncryptedSharedPreferences` (security-crypto) is **deprecated**: all APIs were deprecated in 1.1.0 stable (2025-07-30) with no further releases. Use **DataStore + Tink AEAD, with the master key in the Android Keystore**. Android TV pairing needs a TLS client certificate, which can be generated in the Keystore.
- **Recommended MVP:** Android/Google TV, LG webOS, Samsung Tizen, Roku (subject to owner decision), Sony BRAVIA, then PJLink. Add Wake-on-LAN, an adaptive foldable UI and pastel/dark themes. See §5 for the PR breakdown.

---

## 1. Device discovery on the LAN

### 1.1 Discovery mechanisms

| Mechanism | How it works | Who uses it |
|---|---|---|
| **SSDP / UPnP** | UDP multicast `M-SEARCH * HTTP/1.1` to `239.255.255.250:1900` with `MAN: "ssdp:discover"`, `MX` and `ST`. Devices answer with a unicast reply whose `LOCATION` header points to an XML description. Bind the socket to the Wi-Fi interface (`MulticastSocket.setNetworkInterface`) or sends can fail ([chainfire](https://chainfire.eu/articles/742/_dev_Android_Datagrams_UDP_Broadcast_and_Multicast_..)). | Samsung, LG, Roku, Sony, Vizio, DIAL devices, DLNA renderers |
| **mDNS / DNS-SD** | Android `NsdManager`. Built in, and since T Extensions 7 the system manages multicast reception for NSD, so no MulticastLock is needed [partly UNVERIFIED]. | Android/Google TV, Chromecast, Philips, Vizio, Daikin |
| **DIAL** | SSDP ST `urn:dial-multiscreen-org:service:dial:1`. The device description returns an `Application-URL` header (an `/apps/` REST endpoint). An optional SSDP `WAKEUP: MAC=…;Timeout=…` header advertises Wake-on-LAN support and gives you the MAC ([Google Fiber DIAL ref](https://gfiber.googlesource.com/vendor/opensource/dial/+/6ec256e4f5d2ebe60e668ec77eba4ae5f9fca859/server/quick_ssdp.c)). | Most smart TVs (Netflix/YouTube launch). Good for "a TV is here" and getting the MAC. |
| **Vendor UDP broadcast** | Proprietary scan packets | Gree (UDP 7000 `{"t":"scan"}`), Midea (UDP 6445), Daikin (UDP 30050), PJLink Class 2 (UDP 4352 `SRCH`) |
| **Manual IP entry / subnet probe** | Fallback: probe known ports (4352, 8060, 3001, 8002, 6466…) on the /24 subnet | Projectors with PJLink Class 1, devices on other VLANs |

**Discovery strings, verified from Home Assistant's integration manifests** (which makes them a free, maintained discovery table: [home-assistant/core](https://github.com/home-assistant/core/tree/dev/homeassistant/components)):

| Ecosystem | SSDP ST / mDNS type |
|---|---|
| Samsung Tizen | SSDP `urn:samsung.com:device:RemoteControlReceiver:1` (also `MainTVAgent2`, RenderingControl). DHCP hostname `tizen*`. |
| LG webOS | SSDP `urn:lge-com:service:webos-second-screen:1` |
| Roku | SSDP `roku:ecp` (also `urn:roku-com:device:player:1-0`) |
| Android TV / Google TV | mDNS `_androidtvremote2._tcp` |
| Sony BRAVIA | SSDP `urn:schemas-sony-com:service:ScalarWebAPI:1` |
| Philips (Android) | mDNS `_philipstv_s_rpc._tcp`, `_philipstv_rpc._tcp` |
| Vizio | mDNS `_viziocast._tcp`. SSDP `urn:schemas-kinoma-com:device:shell:1` ([tux0010](https://github.com/tux0010/Vizio_SmartCast_API)) |
| Daikin | mDNS `_dkapi._tcp` |
| Chromecast | mDNS `_googlecast._tcp` |
| Generic DLNA | SSDP `urn:schemas-upnp-org:device:MediaRenderer:1` |

### 1.2 NsdManager notes (Android 14+)

- `registerServiceInfoCallback()` / `NsdManager.ServiceInfoCallback` were added in API 34. They push `onServiceUpdated` / `onServiceLost` continuously and replace the deprecated one-shot `resolveService()` ([docs](https://developer.android.com/reference/android/net/nsd/NsdManager.ServiceInfoCallback)).
- `NsdServiceInfo.getHostAddresses()` (API 34) replaces the deprecated `getHost()`.
- On API ≤33, `resolveService` returns `FAILURE_ALREADY_ACTIVE` if a resolve is already in flight. Serialize resolves through a queue. The rule comes from community practice [UNVERIFIED as an official statement].
- `DiscoveryRequest.Builder` (API 35) accepts `FLAG_SHOW_PICKER` (API 37). It shows a **system device picker that grants access to the chosen device without the broad local network permission** ([API 37 diff](https://developer.android.com/sdk/api_diff/37/changes/android.net.nsd.DiscoveryRequest)). It covers mDNS only, so it is useless for SSDP-based brands.

### 1.3 Android permissions

| Permission | Type | Needed? |
|---|---|---|
| `INTERNET` | normal | **Yes.** Required for any socket. |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | normal | **Yes.** Detect Wi-Fi and read `LinkProperties` (subnet, for the WoL broadcast address). |
| `CHANGE_WIFI_MULTICAST_STATE` | normal | **Yes**, for SSDP. Hold a `WifiManager.MulticastLock` **only while scanning**: it costs battery, is reference-counted and is released if the process dies ([docs](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock)). |
| `ACCESS_LOCAL_NETWORK` | runtime, `NEARBY_DEVICES` group | **Yes, from target SDK 37.** See below. |
| `NEARBY_WIFI_DEVICES` | runtime (13+) | Only for Wi-Fi Direct, Aware, hotspot and RTT, which a LAN remote doesn't need ([docs](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)). It is also the gate during the **Android 16 opt-in test mode**. If declared, add `usesPermissionFlags="neverForLocation"`. |
| `ACCESS_FINE_LOCATION` | runtime | **Avoid.** Only needed for Wi-Fi scan results or reading the SSID. Label networks by subnet instead. |

**Local network permission timeline** ([official page](https://developer.android.com/privacy-and-security/local-network-permission), [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)):

| OS / targetSdk | Behavior |
|---|---|
| Android 16 | Opt-in testing only: `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pkg>` plus a reboot, gated by `NEARBY_WIFI_DEVICES`. |
| Android 17, target ≤ 36 | Apps holding `INTERNET` get a **temporary implicit grant**. Google: *"Don't request `ACCESS_LOCAL_NETWORK` at runtime prior to targeting SDK 37."* |
| Android 17, target ≥ 37 | **LAN blocked by default.** Declare and request `ACCESS_LOCAL_NETWORK` at runtime. Denial blocks all LAN TCP, UDP unicast/multicast/broadcast, mDNS, SSDP, NsdManager and `.local` resolution, across all APIs (OkHttp included). TCP typically times out and UDP returns `EPERM`. |

- **Play deadline:** new apps and updates must target **API 36 since 2026-08-31** ([Play target API](https://developer.android.com/google/play/requirements/target-sdk)). A target-37 deadline around Aug 2027 is likely [UNVERIFIED].
- **Recommendation:** target 36, declare `ACCESS_LOCAL_NETWORK` now, and build the rationale screen and the "blocked" error state now. Turn on the runtime request when moving to 37.
- **Fold 7 on Android 17:** One UI 9 stable reached the Z Fold 7 from 2026-09-28 (Korea first, then US and India) ([SammyFans](https://www.sammyfans.com/2026/09/28/samsung-starts-stable-one-ui-9-rollout-for-galaxy-z-fold-7/), [Beebom](https://gadgets.beebom.com/news/one-ui-9-stable-galaxy-z-fold-7-z-flip-7-rolling-out-india)). Test on both One UI 8 (Android 16) and One UI 9 (Android 17).

### 1.4 Wake-on-LAN (power-on)

- **Packet:** UDP with 6×`0xFF` followed by the MAC repeated 16 times, sent to port 9 (or 7) with `setBroadcast(true)`.
  - Send it to the **subnet broadcast** derived from Wi-Fi `LinkProperties` (e.g. `192.168.1.255`). `255.255.255.255` fails on some phones.
  - Repeat it about 3× (Sony recommends repeats: [Sony](https://pro-bravia.sony.net/?p=4089)).
- **The MAC must be captured while the TV is on.** Sources:
  - DIAL `WAKEUP` header
  - Sony `getSystemInformation`
  - Samsung REST `/api/v2/` device info [UNVERIFIED field]
  - LG / Android TV system info
  - Manual entry as a fallback

  Normal apps can't read the ARP table [UNVERIFIED].
- **The TV must keep its NIC alive in standby:**

  | TV | Setting / behavior |
  |---|---|
  | Samsung | "Power On with Mobile" ([Samsung AU](https://www.samsung.com/au/support/tv-audio-video/power-on-tv-smartthings-app/)) |
  | LG | "Mobile TV On > Turn on via Wi-Fi". On 2025+ models: Support > IP control > Wake on LAN ([HA webostv](https://www.home-assistant.io/integrations/webostv/)). |
  | Sony | "Remote start" |
  | Roku | No WoL [UNVERIFIED]. Use Fast TV Start. |
  | Android TV | Send `KEYCODE_POWER` while the remote service is still reachable in standby. |

  Wired Ethernet is far more reliable than Wi-Fi. **The UI must explain this per brand.**
- WoL broadcasts are also gated by the local network permission on target 37.

---

## 2. Per-ecosystem control protocols

Legend: 🟢 local LAN, no account · 🟡 local, but with a one-time cloud step or friction · 🔴 cloud account or API key required. "Official" means a vendor-published API.

### 2.1 TVs and streamers

| Ecosystem | Transport / port | Pairing / auth | Power-on | LAN vs cloud | API status / ToS |
|---|---|---|---|---|---|
| **Android TV / Google TV** (Sony, TCL, Hisense-GTV, Philips-GTV, Xiaomi, Chromecast w/ GTV, Shield, many projectors) | Mutual TLS: **6467 pairing, 6466 commands**. Length-prefixed protobuf (`polo.proto`, `remotemessage.proto`). | 6-hex-character code shown on the TV. The client hashes SHA-256(client modulus+exponent, server modulus+exponent, last 4 code characters), and the first byte must match the first 2 code characters. The client cert is persisted. | `KEYCODE_POWER` while in standby. Device-dependent: Xiaomi drops off the network, TCL needs "Screenless service" ([HA](https://www.home-assistant.io/integrations/androidtv_remote/)). | 🟢 | Reverse-engineered, but it is Google's own "Android TV Remote Service" used by the Google TV app. Google's original Java pairing code is on [AOSP](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/+/refs/heads/master). No published ToS [UNVERIFIED]. |
| **LG webOS** | SSAP (JSON over WebSocket). **wss 3001** (self-signed); ws 3000 is rejected on newer firmware ([openHAB](https://community.openhab.org/t/lgwebos-binding-not-working-since-latest-tv-firmware-update/150331)). Pointer/button socket via `ssap://com.webos.service.networkinput/getPointerInputSocket`. | `register` with a manifest and `pairingType: PROMPT`. The user accepts on the TV and the client stores the returned `client-key`. | WoL only | 🟢 | Unofficial. LG points to Connect SDK ([LG forum](https://forum.webostv.developer.lge.com/t/json-code-for-simple-turnoff/1612)). **Risk:** Oct 2026 pre-auth exploit via 3001 pairing patched by LG ([THN](https://thehackernews.com/2026/10/lg-smart-tvs-vulnerable-to-pre-auth.html)). Further hardening may break third-party pairing [UNVERIFIED]. |
| **Samsung Tizen (2016+)** | WebSocket `wss://IP:8002/api/v2/channels/samsung.remote.control?name=<b64>&token=<t>`. ws 8001 on older sets. REST app info on `:8001/api/v2/`. | The TV shows an Allow prompt, and the token arrives in `ms.channel.connect`. The user should set *Device Connection Manager > Access Notification* to "First time only" ([HA](https://www.home-assistant.io/integrations/samsungtv/)). | WoL with "Power On with Mobile" | 🟢 (SmartThings alternative is 🔴) | Undocumented. Samsung retired the Smart View SDK in 2020 and points to SmartThings ([SamMobile](https://www.sammobile.com/news/samsung-smart-view-app-cease-to-exist-october-5/)). **Known break:** some 2024 models (DU8300) return `ms.channel.timeOut` without a prompt ([Samsung forum](https://forum.developer.samsung.com/t/issue-with-websocket-remote-control-on-samsung-tv-model-ua55du8300ulxl/40467)). 2014–15 H/J series use an "encrypted" pairing; pre-2014 use port 55000. |
| **Roku** (incl. TCL/Hisense/Sharp Roku TVs) | HTTP **8060**: `POST /keypress|keydown|keyup/<Key>`, `/launch/<id>`, `GET /query/device-info`. Text via `Lit_<char>`. TV keys include VolumeUp/Down/Mute, PowerOff, InputHDMI1-4 ([Roku ECP](https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md)). | None. **The user must set *Settings > System > Advanced > Control by mobile apps* = Enabled.** Since Roku OS 14.1.4 (Dec 2024) the default is "Limited", which blocks keypresses ([openHAB](https://community.openhab.org/t/roku-firmware-update-requires-configuration-change/160938)). | Not documented for PowerOn [UNVERIFIED]. Standby TVs only answer device-info. | 🟢 | **Official doc, but it states: "ECP commands may not be sent from 3rd-party platforms (for example, mobile applications)."** Owner decision needed (§6). |
| **Sony BRAVIA** | REST JSON-RPC (`/sony/system`, `/sony/audio`, `/sony/avContent`, `/sony/appControl`) and IRCC-IP SOAP (`POST /sony/ircc`) ([Sony IP control](https://pro-bravia.sony.net/develop/integrate/ip-control/)) | **Pre-Shared Key** in the `X-Auth-PSK` header (set on the TV under IP Control), or PIN pairing | `setPowerStatus` from sleep with "Remote start" on. From suspend, send WoL first ([Sony](https://pro-bravia.sony.net/develop/integrate/rest-api/spec/service/system/v1_0/setPowerStatus/)). | 🟢 | **Official, public docs.** The cleanest legal case. Recent BRAVIAs are also Google TV, so Remote v2 works too. |
| **Vizio SmartCast** | HTTPS (self-signed) **7345** (older: 9000). `PUT /key_command/` with CODESET/CODE ([pyvizio](https://github.com/raman325/pyvizio)) | `PUT /pairing/start`, then a 4-digit PIN on the TV, then `/pairing/pair` returns `AUTH_TOKEN`, sent in the `AUTH` header | WoL, but not in Eco mode | 🟢 | Reverse-engineered. Post-Walmart firmware status [UNVERIFIED]. |
| **Philips** | JointSpace: HTTP 1925 (non-Android, no auth), HTTPS **1926** `/6/` with digest auth (2016+ Android) ([pylips](https://github.com/eslavnov/pylips)) | `pair/request` shows a PIN. `pair/grant` uses an HMAC signed with a hardcoded secret and returns a user/password. | Partial (`allow_power_on`) [UNVERIFIED for WoL] | 🟢 | Reverse-engineered. Philips Google TV models can use Remote v2 instead. |
| **Amazon Fire TV (Fire OS)** | (a) ADB over TCP 5555. Developer Options > ADB debugging, plus an "Allow debugging" prompt; client via [dadb](https://github.com/mobile-dev-inc/dadb). (b) The Fire TV app's protocol: `POST :8009/apps/FireTVRemote`, then HTTPS 8080 with a hardcoded `X-Api-Key` and PIN-derived `X-Client-Token` ([HA community](https://community.home-assistant.io/t/rest-commands-for-fire-tv/901314)). | ADB RSA key prompt, or a PIN on screen | Unsolved | 🟡 | ADB is an official dev tool, but asking normal users to enable developer mode is poor UX. (b) is reverse-engineered with an embedded Amazon key, which is ToS risk. **Vega OS** (Fire TV Stick 4K Select 2025; "all future Fire TV Sticks") has no consumer network ADB path. Treat it as unsupported ([AFTVnews](https://www.aftvnews.com/tag/sideload/)). |
| **Hisense VIDAA** | MQTT over TLS **36669** ([ha_hisense_tv](https://github.com/clockbrain/ha_hisense_tv)) | Modern firmware needs **mutual TLS with a client cert/key extracted from Hisense's app**, plus a PIN | WoL [UNVERIFIED] | 🟡 | **Avoid.** Shipping extracted certs is a legal risk. Open libraries deliberately don't ship them ([myhisense-tv](https://github.com/warrenrees/myhisense-tv)). Hisense Roku and Google TV models are covered by those drivers. |
| **TCL** | Mostly Roku TV or Google TV, so covered above. Some non-Android Linux TCL/Thomson sets use TCP 4123 [UNVERIFIED] ([popeen](https://github.com/popeen/Home-Assistant-Custom-Component-TCL-Remote)). | | | 🟢 | |
| **Chromecast / Google Cast** | CASTV2: TLS **8009**, protobuf `CastMessage`. mDNS `_googlecast._tcp`. The receiver namespace offers device volume/mute and media pause/seek ([Open Screen](https://chromium.googlesource.com/openscreen/+/HEAD/cast/docs/protocol_flow.md)). | None | n/a | 🟢 | **Cast SDK terms §3.2: "you may not build functionality equivalent to the APIs provided by the Google Cast SDK"** ([terms](https://developers.google.com/cast/docs/terms)). The official SDK sender only controls its own sessions [UNVERIFIED for joining others]. Chromecast with Google TV and the Google TV Streamer should use Remote v2 instead. Recommendation: offer Cast volume and media via the official Android **Output Switcher**/MediaRouter system UI, or skip it. |

### 2.2 Projectors

| Protocol | Transport | Auth | Brands | LAN vs cloud | Notes |
|---|---|---|---|---|---|
| **PJLink Class 1/2** (JBMIA open standard, [spec site](https://pjlink.jbmia.or.jp/english/)) | TCP **4352**. Class 2 adds UDP 4352 search (`SRCH`/`ACKN`, `LKUP` on power-up) [syntax UNVERIFIED] | Greeting `PJLINK 0` (none) or `PJLINK 1 <rand>`. Reply MD5(rand+password) as 32 hex characters prefixed to the first command ([OpenLP](https://lists.launchpad.net/openlp-core/msg30005.html)). | Epson ([confirmed](https://files.support.epson.com/docid/cpd6/cpd64112/source/network/concepts/pjlink.html)), Barco ([confirmed](https://www.barco.com/manuals/R5906112/zweecd1065fw.html)). Widely BenQ, Optoma, NEC/Sharp, Sony, Panasonic, Maxell, Canon, Christie, Vivitek [per-brand UNVERIFIED; check the JBMIA list] | 🟢 | Class 1 (`%1`): POWR, INPT, AVMT, ERST, LAMP, INST, NAME, INF1/2, INFO, CLSS. Class 2 (`%2`) adds SNUM, SVER, INNM, IRES, FILT, FREZ. **No volume command**, so use vendor protocols or hide the volume buttons. "Network standby" must be enabled. Often only one TCP session at a time. |
| **Epson ESC/VP.net** | TCP **3629**. Handshake `ESC/VP.net` + `10 03 00 00 00 00`, then ESC/VP21 ASCII (`PWR ON\r`, `SOURCE 30\r`, `VOL INC\r`) ([Epson FAQ](https://www.epson.eu/en_EU/faq/KA-01646), [ESC/VP21 guide](https://www.epson.com.au/d/pub/epson/drivers/escvp_l5e.pdf)) | None, or an optional password [UNVERIFIED] | Epson | 🟢 | Adds volume, menu and navigation that PJLink lacks |
| **BenQ** | Raw TCP **8000**, RS232 syntax `*pow=on#`, `*sour=hdmi#` ([BenQ guide](https://esupportdownload.benq.com/esupport/PROJECTOR/Control%20Protocols/TK810/TK810_RS232%20Control%20Guide_0_Windows10_Windows7_Windows8.pdf)) | None | BenQ | 🟢 | Model-dependent command table |
| **Optoma** | Telnet **23**, `~0000 1\r` = on ([Optoma](https://onlinemanual.optoma.com/omsl)) | None | Optoma | 🟢 | Community-sourced syntax |
| **Sony ADCP** | TCP **53595** ([RTI note](https://www.rticontrol.com/pub/media/wysiwyg/dealer/technote/How_to_Use_the_RTI_IP-RS232_Driver_for_Sony_4K_Projectors.pdf)) | Nonce plus a SHA-256 hashed password, on by default [hash details UNVERIFIED] | Sony | 🟢 | |
| **Android-based "smart" projectors** | Android TV Remote v2 (if Google-certified) | | XGIMI, Dangbei, etc. | 🟢 | AOSP clones without Google services lack it [UNVERIFIED] |

### 2.3 Air conditioners

| Ecosystem | Transport | Auth | LAN vs cloud | ToS / legal | Status |
|---|---|---|---|---|---|
| **Gree / EWPE Smart / Gree+** (Gree and many OEMs) | UDP **7000** JSON. `scan` → `bind` → `status`/`cmd` ([gree-remote](https://github.com/tomikaa87/gree-remote)) | AES-128-ECB (v1) or GCM (v2) with a generic key, then a per-device key from `bind` | 🟢 fully local | Interoperability reverse engineering, with no cloud ToS involved | Best local AC target. Polling only. Some firmware disables LAN after cloud binding [UNVERIFIED]. |
| **Daikin BRP069A/B** | HTTP 80: `/aircon/get_control_info`, `/aircon/set_control_info`, discovery UDP 30050 ([revk](https://www.revk.uk/2019/03/daikin-air-con-wifi-control.html)) | None | 🟢 | Unofficial | Stable |
| **Daikin BRP072C** | HTTPS: `/common/register_terminal?key=<sticker key>` with `X-Daikin-uuid` | Sticker key + UUID | 🟢 | Unofficial | Community-documented |
| **Daikin BRP069C4x (Onecta)** | Firmware ≥2.8 replaces the local API with `/dsiot/multireq`. Some firmware is cloud-only ([daimik](https://github.com/daimik/Daikin-BRP069C4x-Local-API)) | | 🟡/🔴 | | Firmware-dependent |
| **Daikin ONECTA cloud** ([dev portal](https://www.daikin-ce.com/en_us/product-group/control-systems/daikin-developer-portal.html)) | HTTPS OAuth2 | User's own credentials | 🔴 | **Official.** **EMEA only.** **200 calls/day per user, 20/min.** Daikin itself says it is "not suitable for reactive control". Public multi-user apps need the business-partner licence. | Poor fit for a remote |
| **Midea family** (Midea, Comfee, Carrier, Toshiba-NA, Inventor, Pioneer, Artic King…) | TCP **6444** control, UDP **6445** discovery. V2 = static AES. **V3 (`0x8370`) needs a per-device token+key** ([midea-msmart](https://github.com/mill1000/midea-msmart)). | Token+key obtained **once** from Midea's cloud, then fully local | 🟡 | Token fetch uses reverse-engineered app credentials ("cracked client encryption", per [midea_ac_lan](https://github.com/wuwentao/midea_ac_lan)), so embedding them in a Play app is ToS risk | **July 2026: Midea closed the Meiju and MSmartHome token APIs. NetHome Plus still worked but is expected to close** ([mobiFlip](https://www.mobiflip.de/midea-portasplit-hersteller-zieht-den-stecker-bei-wichtiger-token-api/amp/)). Recommendation: support V2, and V3 via user-imported token/key only. |
| **LG ThinQ** | ThinQ Connect REST plus AWS IoT MQTT events ([LG docs](https://thinq.developer.lge.com/en/cloud/docs/thinq-connect/PAT-en)) | User-generated **PAT** from connect-pat.lgthinq.com, a client-ID UUID and a country | 🔴 | **Official.** Rate limits exist, numbers [UNVERIFIED] | Feasible as "bring your own token" |
| **Samsung SmartThings** | REST `api.smartthings.com` (`/devices/{id}/commands`) | **PATs issued after 30 Dec 2024 expire in 24 h** ([SmartThings](https://community.smartthings.com/t/changes-to-personal-access-tokens-pat/292019)). A real app needs an OAuth SmartApp (auth code + refresh). | 🔴 | **Official.** Reported cap of about 500 users per OAuth app [UNVERIFIED] | Also covers Samsung TVs, Samsung ACs and others |
| **Sensibo** | REST `home.sensibo.com/api/v2` ([API](https://support.sensibo.com/api/)) | User's API key | 🔴 | **Official** | Easy; it's a cloud IR bridge |
| Mitsubishi MELCloud, Panasonic Comfort Cloud, Haier hOn | Cloud, reverse-engineered | Account login | 🔴 | **High risk.** Haier sent a takedown to the HA developer (Jan 2024) ([iGen](https://www.igen.fr/domotique/2024/01/domotique-haier-veut-son-tour-bloquer-une-integration-dans-home-assistant-141587)). Mitsubishi and Panasonic block or lock out clients. | **Do not build** |
| Mitsubishi Kumo (US) | Cloud for credentials, then local HTTP per unit ([HA](https://www.home-assistant.io/integrations/mitsubishi_comfort/)) | | 🟡 | Unofficial | Later, maybe |
| Tuya-based ACs | Local TCP 6668, protocol 3.3–3.5 ([tinytuya](https://pypi.org/project/tinytuya/1.15.0.dev0)) | `local_key` from a Tuya IoT cloud project (trial expires) | 🟡 | Requires a user developer account | Power-user feature only |
| Fujitsu FGLair, Hisense ConnectLife | Cloud (Ayla / ConnectLife) | Account | 🔴 | No public developer API [UNVERIFIED] | Skip |

---

## 3. Open-source code to learn from

Metadata pulled from the GitHub API on 2026-10-17. "Last push" can be a CI or dependabot push.

### 3.1 Home Assistant core integrations
[home-assistant/core](https://github.com/home-assistant/core) is Apache-2.0, about 91.6k stars and active daily. Code lives in `homeassistant/components/<domain>/`, and each `manifest.json` gives the discovery strings and the pinned library.

| Integration | Library (pinned) | Quality | Docs |
|---|---|---|---|
| samsungtv | samsungtvws 3.0.6, async-upnp-client | gold | [docs](https://www.home-assistant.io/integrations/samsungtv) |
| webostv | aiowebostv 0.10.0 | platinum | [docs](https://www.home-assistant.io/integrations/webostv) |
| roku | rokuecp 0.19.5 | — | [docs](https://www.home-assistant.io/integrations/roku) |
| androidtv_remote | androidtvremote2 0.3.2 | platinum | [docs](https://www.home-assistant.io/integrations/androidtv_remote) |
| braviatv | pybravia 0.5.3 | — | [docs](https://www.home-assistant.io/integrations/braviatv) |
| philips_js | ha-philipsjs 3.3.0 | — | [docs](https://www.home-assistant.io/integrations/philips_js) |
| vizio | **vizaio 0.7.0** (replaced pyvizio) | platinum | [docs](https://www.home-assistant.io/integrations/vizio) |
| pjlink | pypjlink2 1.2.1 | — | [docs](https://www.home-assistant.io/integrations/pjlink) |
| epson | epson-projector 0.6.0 | — | [docs](https://www.home-assistant.io/integrations/epson) |
| daikin | pydaikin 2.20.0 | — | [docs](https://www.home-assistant.io/integrations/daikin) |
| gree | greeclimate 3.2.2 | — | [docs](https://www.home-assistant.io/integrations/gree) |
| midea_ac_lan | Custom integration (not in core): [wuwentao/midea_ac_lan](https://github.com/wuwentao/midea_ac_lan) | — | — |

### 3.2 Protocol libraries

**Port freely (permissive licenses):**

| Library | License | Last push | Notes |
|---|---|---|---|
| [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) | Apache-2.0 | 2026-10-05 | Ships the `.proto` files. **Primary reference for Google TV.** |
| [home-assistant-libs/aiowebostv](https://github.com/home-assistant-libs/aiowebostv) | Apache-2.0 | 2026-10-13 | Maintained LG SSAP, including the permission manifest |
| [chros73/bscpylgtv](https://github.com/chros73/bscpylgtv) | MIT | 2026-09-30 | Large LG endpoint list |
| [bendavid/aiopylgtv](https://github.com/bendavid/aiopylgtv) | MIT | **2022 (stale)** | Superseded by the two above |
| [ctalkington/python-rokuecp](https://github.com/ctalkington/python-rokuecp) | MIT | 2026-10-17 | |
| [jcarbaugh/python-roku](https://github.com/jcarbaugh/python-roku) | BSD-3 | 2026-10-09 | |
| [Drafteed/pybravia](https://github.com/Drafteed/pybravia) | MIT | 2026-10-12 | |
| [raman325/pyvizio](https://github.com/raman325/pyvizio) | MIT | 2026-05 | Frozen. Successor is [vizaio](https://pypi.org/project/vizaio/) (repo URL [UNVERIFIED]). |
| [danielperna84/ha-philipsjs](https://github.com/danielperna84/ha-philipsjs) | MIT | 2026-10-16 | |
| [HEInventions/aiopjlink](https://github.com/HEInventions/aiopjlink) | MIT | 2026-05-29 | PJLink Class 1 and 2. [benoitlouy/pypjlink](https://github.com/benoitlouy/pypjlink) (Apache-2.0) is stale (2020). |
| [midea-lan/midea-local](https://github.com/midea-lan/midea-local) | MIT | 2026-10-16 | |
| [wuwentao/midea_ac_lan](https://github.com/wuwentao/midea_ac_lan) | MIT | 2026-10-14 | Active fork. The [georgezhao2010 original](https://github.com/georgezhao2010/midea_ac_lan) was abandoned in 2024. |
| [mill1000/midea-msmart (msmart-ng)](https://github.com/mill1000/midea-msmart) | MIT | 2026-09-14 | |
| [home-assistant-libs/pychromecast](https://github.com/home-assistant-libs/pychromecast) | MIT | 2026-10-15 | |
| [JeffLIrion/adb_shell](https://github.com/JeffLIrion/adb_shell) | Apache-2.0 | 2024-10 | On the JVM, use dadb instead |

**Reimplement from protocol knowledge, do not translate (copyleft licenses):**

| Library | License | Last push | Notes |
|---|---|---|---|
| [xchwarze/samsung-tv-ws-api (samsungtvws)](https://github.com/xchwarze/samsung-tv-ws-api) | **LGPL-3.0** | 2026-09-11 | |
| [fredrike/pydaikin](https://github.com/fredrike/pydaikin) | **GPL-3.0** | 2026-10-12 | |
| [cmroche/greeclimate](https://github.com/cmroche/greeclimate) | **GPL-3.0** | 2026-10-17 | |

**Avoid shipping:**

| Library | License | Last push | Notes |
|---|---|---|---|
| [warrenrees/myhisense-tv (pyvidaa)](https://github.com/warrenrees/myhisense-tv) | MIT | 2026-08 | Needs extracted VIDAA certificates. |

### 3.3 Kotlin / Java / JVM availability

**The biggest JVM resource is [openHAB add-ons](https://github.com/openhab/openhab-addons)** (Java, **EPL-2.0**, active). It has bindings for `androidtv` (including Google TV v2 PIN pairing), `samsungtv`, `lgwebos`, `roku`, `vizio`, `pjlinkdevice`, `epsonprojector`, `daikin`, `gree`, `mideaac`, `chromecast` and `androiddebugbridge`.
- The code is OSGi-bound, so **port the logic rather than adding it as a dependency**.
- EPL-2.0 is file-level weak copyleft: any ported or modified files stay EPL and their source must be published.

| Protocol | JVM status |
|---|---|
| Roku ECP | **Trivial**: HTTP via OkHttp. Reference: RoMote (Kotlin, Apache-2.0). |
| ADB (Fire TV) | **Ready**: [dadb](https://github.com/mobile-dev-inc/dadb), pure Kotlin, Apache-2.0, 1.2k★ |
| mDNS | **Ready**: `NsdManager`. Fallback [JmDNS](https://github.com/jmdns/jmdns), Apache-2.0, active. |
| SSDP | Hand-roll it (about 150 lines on a MulticastSocket). [Cling](https://github.com/4thline/cling) is end-of-life (2020). [jUPnP](https://github.com/jupnp/jupnp) (CDDL, active) targets JDK 11+ and has unclear Android support [UNVERIFIED]. |
| LG webOS | [Connect-SDK-Android-Core](https://github.com/ConnectSDK/Connect-SDK-Android-Core) (Apache-2.0, last push 2026-01, low maintenance, Java, old Gradle; the wrapper bundles an LGPL gstreamer `.so`). **Better to write a small SSAP client on OkHttp WebSocket.** |
| Chromecast | [chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2) (Apache-2.0, last push 2024). ToS caveat in §2.1. |
| Android TV Remote v2 | **Needs a port.** There is no maintained Kotlin library. Generate Kotlin from androidtvremote2's `.proto` files (Square Wire or protobuf-lite) and port the pairing state machine. References: AOSP Java pairing code; openHAB `androidtv`; [kunal52/AndroidTvRemote](https://github.com/kunal52/AndroidTvRemote) (Java, **no license**, reference only); [tzvika-por/CouchPilot](https://github.com/tzvika-por/CouchPilot) (Kotlin+Compose, Apache-2.0, tiny/new). |
| Samsung, Sony, Philips, Vizio, PJLink, Epson, Daikin, Gree, Midea | **Need porting.** All are small (each is an HTTP, WebSocket or TCP text protocol). openHAB has Java versions. |
| Hisense VIDAA | Blocked (certificates) |

### 3.4 Existing open-source Android remote apps

| App | License | Activity | Relevance |
|---|---|---|---|
| [RoMote](https://github.com/wseemann/RoMote) ([F-Droid](https://f-droid.org/packages/wseemann.media.romote/)) | Apache-2.0 | 2026-09 | Kotlin Roku remote with widget and notification controls |
| [CouchPilot](https://github.com/tzvika-por/CouchPilot) | Apache-2.0 | 2026-10 | Kotlin+Compose: webOS, Google TV v2, Keystore. **Closest architecture match** |
| [heroslender/lg-remote](https://github.com/heroslender/lg-remote) | **none** | 2026-05 | Kotlin webOS. Reference only (no license) |
| [Nicsilver/LGPower](https://github.com/Nicsilver/LGPower) | AGPL-3.0 | 2026-10 | Kotlin webOS |
| [canapestudio/webmote](https://github.com/canapestudio/webmote) | GPL-3.0 | 2026-10 | Kotlin webOS |
| [SamLatsin/lg-smart-remote](https://github.com/SamLatsin/lg-smart-remote) | MIT | 2022 | Java webOS / Netcast |
| [ravem/TCL-remote](https://github.com/ravem/TCL-remote) | **none** | — | Kotlin+Compose Android TV v2 |
| [mazen-salah/Smart-TV-Remote-Control](https://github.com/mazen-salah/Smart-TV-Remote-Control) | MIT | 2026-10 | Flutter: Samsung + webOS + WoL. Good reference for the Samsung flow |
| [PRProd/HA-Firemote](https://github.com/PRProd/HA-Firemote) | GPL-3.0 | 2026-03 | Home Assistant card. Good reference for **per-brand button layouts** |

**Gap:** no F-Droid app does Google TV v2 pairing or webOS from the phone side, and none is multi-brand and polished. That is a real opening for this app.

---

## 4. Recommended architecture

### 4.1 Module layout (Gradle multi-module)

```
:app                     Compose UI, navigation, DI wiring (Hilt or Koin)
:core:model              Device, Capability, Command, DeviceState (pure Kotlin)
:core:discovery          SSDP client, NsdManager wrapper, vendor UDP scanners, subnet prober
:core:network            OkHttp (WS + HTTP), TLS helpers (TOFU pinning, Keystore client certs), WoL sender
:core:storage            DataStore + Tink AEAD, Keystore key management, device repository
:core:testing            Fake drivers, fake TV servers (MockWebServer / local WS / TCP), golden packets
:driver:androidtv        Remote v2 (protobuf via Wire)
:driver:webos
:driver:samsung
:driver:roku
:driver:bravia
:driver:pjlink
:driver:epson ...        (projectors)
:driver:gree, :driver:daikin, :driver:midea ... (phase 2)
```

### 4.2 Driver (plugin) interface, sketched

```kotlin
interface DeviceDriver {
    val id: String                                 // "webos", "roku", ...
    val discoveryHints: List<DiscoveryHint>        // SSDP STs, mDNS types, UDP probes, ports
    suspend fun identify(candidate: DiscoveredEndpoint): DeviceDescriptor?   // claim/reject a discovered endpoint
    fun pairingFlow(device: DeviceDescriptor): PairingFlow?                  // null = no pairing needed
    suspend fun connect(device: DeviceDescriptor, creds: Credentials?): DeviceSession
}

interface DeviceSession : AutoCloseable {
    val capabilities: Set<Capability>              // drives which buttons the UI shows
    val state: StateFlow<DeviceState>              // power, volume, muted, input, app, AC setpoint...
    suspend fun send(cmd: Command): Result<Unit>
}

sealed interface PairingFlow {                     // UI renders by type
    data class OnScreenPrompt(...)                 // Samsung / LG: "Accept on your TV"
    data class PinEntry(val length: Int, ...)      // Android TV (6 hex), Vizio (4), Philips, Sony PIN
    data class SecretEntry(val label: String)      // Sony PSK, PJLink password, Midea token/key, cloud PAT
    data class Instructions(...)                   // Roku: "Enable Control by mobile apps"
}
```

- **`DriverRegistry`.** Drivers are registered in a set (Hilt multibinding) and are not loaded dynamically, so each protocol is a separate module that can be enabled, tested or removed independently.
- **Capability-driven UI.** The remote screen renders only what `capabilities` declares, so there are never dead buttons. For example, PJLink has no volume, so its volume buttons are hidden.

### 4.3 Command model

```kotlin
sealed interface Command {
    // Power
    data object PowerOn; data object PowerOff; data object PowerToggle
    // Audio: buttons/gestures only (no slider, per design rule)
    data object VolumeUp; data object VolumeDown; data object MuteToggle
    // Navigation
    enum class Dpad { UP, DOWN, LEFT, RIGHT, OK }; data object Back; data object Home; data object Menu
    data class Digit(val n: Int); data object ChannelUp; data object ChannelDown
    // Media
    data object PlayPause; data object Stop; data object Rewind; data object FastForward; data object Next; data object Previous
    // Inputs / apps / text
    data class SelectInput(val inputId: String); data class LaunchApp(val appId: String); data class TypeText(val text: String)
    // Projector
    data class AvMute(val on: Boolean); data object Freeze
    // Climate
    data class SetHvacMode(val mode: HvacMode)   // COOL, HEAT, DRY, FAN, AUTO
    data class SetTargetTemp(val celsius: Float)
    data class SetFanSpeed(val speed: FanSpeed)  // AUTO, LOW, MID, HIGH, TURBO
    data class SetSwing(val vertical: Boolean, val horizontal: Boolean)
    data class Raw(val driverSpecific: String)   // escape hatch for model-specific keys
}
```

- **Volume.** Volume buttons support press-and-hold auto-repeat, using the key-down/up semantics in Samsung `Press/Release`, Roku `keydown/keyup` and Android TV `START_LONG/END_LONG`. The d-pad area can optionally support swipe gestures, and vertical swipe on a side strip can change volume. The current level is shown as text or a small pill, **never as a slider** (per the design brief).

### 4.4 Discovery service

1. When the remote is opened, run SSDP M-SEARCH (one burst per ST from all drivers' hints) and NsdManager browse for all mDNS types **in parallel**, for about 5 s. Hold the MulticastLock only during that window.
2. Merge results by IP and MAC into `DiscoveredEndpoint`. Each driver's `identify()` claims an endpoint (for example, a Sony that matches both `ScalarWebAPI` and `_androidtvremote2` can offer both drivers).
3. Add vendor UDP scans on demand (Gree, Midea, PJLink Class 2) and manual IP entry.
4. Remember devices across sessions: reconnect by IP and re-discover when the IP changes, matching by MAC/UUID. Check that the phone is on the same subnet (`LinkProperties`) without needing an SSID or location permission.

### 4.5 Pairing and credential storage

- **`EncryptedSharedPreferences` is deprecated.** All `androidx.security:security-crypto` APIs were deprecated in **1.1.0 stable (2025-07-30)**, and Google says there won't be more releases ([release notes](https://developer.android.com/jetpack/androidx/releases/security), [crypto guide](https://developer.android.com/privacy-and-security/cryptography)). Do not use it.
- **Use DataStore + Tink + Keystore:**
  - Store per-device credentials (LG client-key, Samsung token, Vizio auth token, Sony PSK, PJLink password, Midea token/key, cloud PATs) in a Proto/JSON **DataStore** encrypted with a **Tink AEAD**. The Tink keyset is wrapped by an **Android Keystore** master key (`AndroidKeysetManager`, `android-keystore://…`) ([Tink](https://developers.google.com/tink)).
  - `androidx.datastore:datastore-tink` (`AeadSerializer`) is in DataStore **1.3.0, which is at rc01 (2026-10-12)**. Use it once stable, or write the roughly 30-line serializer yourself ([release notes](https://developer.android.com/jetpack/androidx/releases/datastore)).
- **TLS client certificate for Android TV Remote v2:**
  - Generate an RSA-2048 key pair **inside the Android Keystore** with a self-signed X.509 cert (`KeyGenParameterSpec.Builder.setCertificateSubject/...`).
  - Expose it through a custom `X509KeyManager` to an `SSLContext`.
  - The pairing hash needs the client's RSA modulus and exponent, which are readable from the public cert.
  - The private key is non-exportable, so restoring to a new phone requires re-pairing. That is acceptable.
  - Sources: [Keystore docs](https://developer.android.com/privacy-and-security/keystore); the SSLContext wiring is a common pattern [UNVERIFIED against an official sample].
- **Server certificates.** TVs use self-signed certs (LG 3001, Samsung 8002, Vizio, Android TV, Philips). Use **trust-on-first-use pinning** per device, restricted to private-range IPs. Never install a global trust-all manager.
- **Backups.** Exclude the credential store from Auto Backup, since its keys can't restore anyway.

### 4.6 Wake-on-LAN

`WakeOnLan.send(mac, broadcast = subnetBroadcast(LinkProperties), repeats = 3)`. `PowerOn` falls back to it whenever the driver can't power on natively, followed by a "waiting for TV…" state that polls the driver until the device answers.

### 4.7 UI notes (foldable, theming)

- **Libraries:** Material 3 Adaptive **1.3.0 stable** (2026-08-12), using `currentWindowAdaptiveInfo()`, `ListDetailPaneScaffold` and `NavigationSuiteScaffold` ([release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)).
- **Posture detection:** `calculatePosture()` became internal in 1.2.0-beta02, so read the posture from Jetpack WindowManager 1.5.x, using `FoldingFeature` with `HALF_OPENED` + `HORIZONTAL` for tabletop ([fold-aware guide](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware)).
- **Layouts by posture:**

  | Screen / posture | Layout |
  |---|---|
  | **Folded** (cover screen 6.5", 2520×1080; Compact width) | Single-pane remote |
  | **Unfolded** (inner screen 8.0", 2184×1968; probably Expanded width [UNVERIFIED dp]) | Device list and remote side by side, or remote plus a number pad / apps panel |
  | **Tabletop** | D-pad on the lower half, now-playing and status on the upper half |

  Screen specs from [Wikipedia](https://en.wikipedia.org/wiki/Samsung_Galaxy_Z_Fold_7).
- **Theming:**
  - Multiple pastel `ColorScheme`s plus Dark, with optional Material You dynamic color.
  - **Contrast rule:** every `on*` colour must meet WCAG AA (≥4.5:1) against its container. Enforce it with a unit test over all theme pairs, so dark-on-dark text can't ship.
  - Material 3 Expressive APIs are graduating to stable in material3 1.5.0 (beta01 on 2026-10-07) ([notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)). Full `MaterialExpressiveTheme` stability is [UNVERIFIED].

### 4.8 Testability

- **Pure-Kotlin protocol codecs** (JVM unit tests, no Android needed):
  - PJLink MD5 auth vectors, SSAP JSON encoding, the ATV pairing-hash computation with known vectors from androidtvremote2, protobuf framing, the Gree AES pack/unpack, and the Midea frame checksum.
- **Fake devices:**
  - OkHttp **MockWebServer** for Roku, Sony, Vizio and Philips (HTTP).
  - A local WebSocket server for LG and Samsung pairing (accept, deny, timeout).
  - A TCP fake for PJLink and Epson.
  - A TLS fake for Android TV.
  - These run in JVM tests and can also run as a **debug-only "Fake Living Room"**, so the UI can be developed without hardware.
- **`FakeDriver`** in `:core:testing` for UI and screenshot tests across folded, unfolded and tabletop sizes, and across all themes (Compose preview / Roborazzi or Paparazzi).
- **Contract test suite** that every driver must pass, covering: capabilities match accepted commands, reconnect after drop, and credentials round-trip through storage.
- **Manual hardware matrix:** for each supported brand, record model, firmware and date tested.

---

## 5. MVP scope and PR breakdown

### 5.1 Ranking (coverage × effort × risk)

| Rank | Ecosystem | Coverage | Effort | Risk | Phase |
|---|---|---|---|---|---|
| 1 | **Android TV / Google TV** | Very high: Sony, TCL, Hisense, Philips, Xiaomi, Chromecast-GTV, Shield, many projectors | High (TLS + protobuf + pairing) | Low–medium | **MVP** |
| 2 | **LG webOS** | High | Medium | Medium (pairing hardening) | **MVP** |
| 3 | **Samsung Tizen** | Very high (No. 1 TV brand) | Medium | Medium (model quirks) | **MVP** |
| 4 | **Roku** | High in the US (Roku, TCL, Hisense Roku TVs) | **Very low** | **ToS clause.** Owner decision. | MVP if approved |
| 5 | **Sony BRAVIA (REST)** | Medium (older non-Google Sony; adds PSK and power-on) | Low | Low (official) | MVP or 1.1 |
| 6 | **PJLink** | Most networked projectors | Low | Low | MVP or 1.1 |
| 7 | Epson ESC/VP.net, BenQ, Optoma | Projector extras (volume, menu) | Low each | Low | 1.1 |
| 8 | Vizio, Philips JointSpace | Medium (US / EU) | Medium | Medium | 1.2 |
| 9 | Gree, Daikin local | AC: fully local | Medium | Low–medium | 2.0 |
| 10 | Midea (V2 + imported V3 token) | AC: very many brands | Medium–high | Token supply | 2.0 |
| 11 | Cloud "bring your own token": SmartThings, LG ThinQ, Sensibo | AC + more | Medium (OAuth) | Low (official) | 2.x, owner decision |
| — | Fire TV (ADB), Chromecast raw, Hisense VIDAA, Haier/Panasonic/MELCloud | | | High | Defer or never |

### 5.2 PR breakdown (each PR small and reviewable)

1. **Project skeleton:** Gradle multi-module, version catalog, CI (lint, detekt, unit tests), target SDK 36, manifest permissions (including `ACCESS_LOCAL_NETWORK` declared).
2. **Design system:**
   - Pastel and dark `ColorScheme`s plus typography and shapes.
   - **Contrast unit test** over all theme colour pairs.
   - Theme picker.
3. **Adaptive shell:** `NavigationSuiteScaffold`, list-detail for devices, posture detection (tabletop), and preview and screenshot tests for cover, inner and tabletop.
4. **Core model:** `Command`, `Capability`, `DeviceState`, the `DeviceDriver`/`DeviceSession`/`PairingFlow` interfaces, `DriverRegistry`, and `FakeDriver`.
5. **Remote UI (on FakeDriver):**
   - Controls: d-pad, swipe pad, hold-to-repeat volume buttons (no slider), numbers sheet, media row, inputs and apps sheet, keyboard.
   - Capability-driven visibility.
6. **Secure storage:** Tink-AEAD DataStore, Keystore master key, device repository, backup exclusion.
7. **Networking core:** OkHttp setup, TOFU cert pinning for private IPs, Keystore client-cert `SSLContext`, WoL sender with subnet broadcast.
8. **Discovery service:** SSDP client, NsdManager wrapper (callback API on 34+, serialized resolve below that), merge and dedupe, MulticastLock scoping, manual IP add, local-network-permission error states.
9. **Driver: Roku** (smallest; proves the pipeline end to end). Includes the "Enable Control by mobile apps" onboarding. *Gated on the owner's ToS decision.*
10. **Driver: LG webOS:** SSAP over wss:3001 with ws:3000 fallback, prompt pairing, pointer socket, WoL.
11. **Driver: Samsung Tizen:** wss:8002 token pairing, key press/hold, text input, app launch, "First time only" guidance, WoL.
12. **Driver: Android TV Remote v2, part 1:** protobuf (Wire) from the `.proto` files, Keystore client cert, pairing state machine, and pairing-hash unit tests.
13. **Driver: Android TV Remote v2, part 2:** command session, key long-press, IME text, deep-link app launch, state events (volume, power, app).
14. **Driver: Sony BRAVIA:** PSK and PIN, REST and IRCC, power-on via Remote start plus WoL.
15. **Driver: PJLink:** Class 1 and 2, MD5 auth, UDP search, input list, AV mute. Hide volume.
16. **Polish and release:**
   - Onboarding for the local-network permission rationale and per-brand "enable this setting" tips.
   - Widgets and quick-settings tile (optional).
   - Play listing that is compliant on trademarks: generic name, "works with" text, no brand logos, non-affiliation disclaimer ([Play impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374)).

Later PRs:
- Epson, BenQ, Optoma
- Vizio, Philips
- Gree, Daikin, Midea
- Climate UI (mode chips, ± temperature buttons, fan and swing)
- Opt-in cloud connectors

---

## 6. Risks and open questions for the owner

Only product-scope, money or irreversible decisions are listed here.

1. **Roku ECP ToS clause.** Roku's doc says ECP must not be sent from third-party mobile apps.
   - Options: (a) ship Roku anyway, as many Play apps do, accepting takedown risk; (b) leave Roku out; (c) ask Roku.
   - **Decision needed before PR 9.**
2. **Cloud-connected ACs in scope?** SmartThings, LG ThinQ, Sensibo and Daikin ONECTA all need the *user's* account and token.
   - SmartThings OAuth requires registering an app with Samsung, with a possible per-app user cap [UNVERIFIED].
   - Daikin ONECTA is EMEA-only with a 200 calls/day limit, and a public app needs a partner licence.
   - Including them breaks the "pure local" story, but it is the only way to reach many ACs.
3. **Midea V3.** Should the app **embed a reverse-engineered cloud token fetch**? That carries ToS and breakage risk, and the APIs are already being shut down. The alternative is to accept only user-imported tokens. A "free app" doesn't remove ToS exposure.
4. **Licensing of the app itself.** Choose an open-source license (e.g. Apache-2.0) or closed source.
   - Porting openHAB code forces those files to be EPL-2.0.
   - The GPL/LGPL Python libraries must not be translated into a non-GPL app.
   - Irreversible once published.
5. **Fire TV support.** It needs users to enable developer mode (ADB), or use a reverse-engineered protocol with an embedded Amazon API key, and it doesn't work on Vega OS. Include it as "advanced", or skip it?
6. **Minimum Android version.** Proposed **minSdk 29 or 31** (NsdManager callbacks on 34+, with a queued fallback below) and **targetSdk 36**. Also decide when to move to 37 and enforce the runtime local-network prompt.
7. **Distribution and money.**
   - Google Play developer account (one-time US$25).
   - Do you also want F-Droid, which requires FOSS and no proprietary SDKs?
   - Any optional Chromecast app registration (US$5) [UNVERIFIED fee].
   - "Free" means no ads and no analytics SDKs? That affects privacy-policy scope.
8. **App name and trademark check** before publishing (see §7). A Play listing rename is possible, but the package ID is permanent.
9. **Hardware for testing.** Which real TVs, projectors and ACs does the owner have access to? Drivers without real-device validation should ship labelled "beta".

---

## 7. App name ideas (Imperial Roman theme: command and authority)

| Name | Meaning |
|---|---|
| **Imperium** | "Supreme command": the Emperor's power to give orders that must be obeyed, here over every screen in the house. |
| **Sceptrum** | The Emperor's sceptre, the hand-held symbol of authority. A neat metaphor for a remote held in the hand. |
| **Iussum** | "A command, an order". Every tap is an imperial decree. |
| **Fasces Remote** | The rod-bundle carried before Roman magistrates as the emblem of authority. Note: modern political connotations, so probably avoid. |
| **Edictum** | An imperial edict: one proclamation, and the TV, projector and AC all comply. |
| **Augustus Remote** | After the first Emperor. Fits the *Fabrica Augusta* family of names. "Augustus" also means "venerable, majestic". |

Top picks: **Imperium** or **Sceptrum**. Both are short, easy to pronounce and on theme. Run a Play Store and trademark search before committing [not checked in this research].

---

## Appendix: key sources

- Android local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Android 17 behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Wi-Fi permissions: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- NsdManager ServiceInfoCallback: https://developer.android.com/reference/android/net/nsd/NsdManager.ServiceInfoCallback
- MulticastLock: https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock
- Roku ECP: https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
- Sony BRAVIA IP control: https://pro-bravia.sony.net/develop/integrate/ip-control/
- Google Cast terms: https://developers.google.com/cast/docs/terms
- PJLink (JBMIA): https://pjlink.jbmia.or.jp/english/
- Daikin developer portal: https://www.daikin-ce.com/en_us/product-group/control-systems/daikin-developer-portal.html
- LG ThinQ Connect PAT: https://thinq.developer.lge.com/en/cloud/docs/thinq-connect/PAT-en
- SmartThings PAT change: https://community.smartthings.com/t/changes-to-personal-access-tokens-pat/292019
- Sensibo API: https://support.sensibo.com/api/
- security-crypto deprecation: https://developer.android.com/jetpack/androidx/releases/security
- DataStore releases: https://developer.android.com/jetpack/androidx/releases/datastore
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Material 3 Adaptive: https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Fold-aware layouts: https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware
- Play impersonation policy: https://support.google.com/googleplay/android-developer/answer/9888374
- Play target API requirements: https://developer.android.com/google/play/requirements/target-sdk
