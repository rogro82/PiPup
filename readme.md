# PiPup

Enhanced notifications for Android TV / Fire TV — show popups (text, images, video or live camera
streams) on your TV from your home-automation system, for **as long as you want**.

> **Credits:** PiPup was originally created by [Rob Groenendijk (rogro82)](https://github.com/rogro82/PiPup).
> This repository is a maintained fork of that project — all credit for the original idea and
> implementation goes to him. The fork modernizes the build (AndroidX, AGP 8, Kotlin 2, targetSdk 34)
> and adds the features below, aimed at Home Assistant use.
>
> Contributors to this fork: [David Bebawy (davbebawy)](https://github.com/davbebawy) built several
> popups at once, push to a webhook, see-through popups and the configurable update source (0.24.0);
> [andrewm1205](https://github.com/andrewm1205) added the top/bottom center positions (0.22.0).

![](graphics/screenshot-1.png)

## What this fork adds (compared to [rogro82/PiPup](https://github.com/rogro82/PiPup))

- **TV control** (since 0.29.0) — `POST /launch` starts an app or opens a deep link, `POST /key` sends
  media keys (play/pause, next, …), `POST /volume` sets or steps the volume and mutes, `GET /apps` lists
  what can be launched. No adb needed, so this keeps working on TVs where adb debugging drops its grant
  or a media-player integration's adb route broke. See [TV control](#tv-control).
- **Camera wall** (since 0.29.0) — `media.grid` shows up to nine WHEP streams in one popup, each with its
  own label and poster, or full screen as a wall. See [Camera wall](#camera-wall-grid).
- **WHEP streams** (since 0.25.0) — `media.whep` plays a WebRTC stream straight from a WHEP endpoint
  (go2rtc `/api/webrtc?src=…`), about a second faster and far steadier than go2rtc's player page.
  See [Which stream should a camera popup use?](#which-stream-should-a-camera-popup-use).
- **Several popups at once** (since 0.24.0) — each popup `id` is its own window, so a doorbell camera
  and a waste reminder can stand side by side. `/state.popups` lists them in stack order,
  `/cancel?all=true` clears the screen. See [Cancelling a popup](#cancelling-a-popup).
- **Push instead of poll** (since 0.24.0) — `POST /settings?webhook=<url>` makes the app POST its
  `/state` plus an `event` (`popup_shown`, `popup_removed`, `screen_off`, …) on every change, so a
  controller knows within milliseconds instead of at its next poll. See [Settings](#settings).
- **See-through popups** (since 0.24.0) — `opacity` (0..1) on any popup, and `transparent: true` on
  web media so a page with a transparent body floats over live TV.
- **Update source and switch** (since 0.24.0) — the release check can be switched off for TVs without
  internet, or pointed at another GitHub repo or a LAN folder with `releases.json`.
- **Indefinite popups** — `duration: 0` (or negative) shows a popup until it is cancelled or replaced,
  e.g. show a camera stream for exactly as long as there is motion.
- **Popup `id` + update-in-place** — re-sending a notify with the same `id` and content only reschedules
  the removal timer without rebuilding the view, so a video/web stream keeps playing without flicker.
- **Companion-version panel** (since 0.21.0) — `/state` carries a `haPipup` object: `recommended`
  (the latest [ha-pipup](https://github.com/mhoogenbosch/ha-pipup) release, fetched with the hourly
  update check — never maintained by hand), `minimum` (oldest integration that can drive this app's
  full API, a build-time constant) and `connected` (what actually talks to us: ha-pipup ≥ 1.18.0
  announces itself with an `X-HA-PiPup-Version` header on every request, including the 15s `/state`
  poll). The app's own status screen shows the same line: ✓ up to date, update available, or too old.
- **`/state` endpoint** — popup visibility, screen on/off (`screenOn`, since 0.2.3), popup counter,
  uptime, device info and a **stable device id** (since 0.2.5).
- **`/cancel`** (existed upstream but undocumented) with optional selective `?id=`.
- **`/notify` and `/cancel` answer once `/state` reflects the change** (since 0.17.1) — a `200` means the
  popup is on (or off) screen, so a client may read `/state` straight after the call. The reply waits for
  the view, not for its media to load. A popup that fails to build answers `500`.
- **Muted media** (since 0.2.4) — `muted: true` on video/web media plays without audio, so a popup
  never claims audio focus (audio in a popup can freeze video playback on some devices).
- **Text-to-speech** (since 0.2.5) — a `tts` field speaks a text on the TV when the popup appears,
  with optional `ttsLanguage` (BCP-47).
- **mDNS/zeroconf discovery** (since 0.2.5) — the app advertises `_pipup._tcp` with a stable device
  id, so clients (like the Home Assistant integration) find TVs automatically and follow them across
  DHCP address changes.
- **Overlay watchdog** (since 0.2.6) — popup removal is guarded step-by-step and a 30s consistency
  check force-removes any overlay left behind by a failed teardown, so a popup can no longer stay
  on screen after its dismiss. `/state` reports `watchdogCleanups` so you can see if it ever fired.
- **Buttons on the popup** (since 0.3.0) — `buttons: [{id, label}]` renders remote-operable buttons:
  the overlay only becomes focusable when buttons are present (it never steals the remote
  otherwise), **OK** activates (POST `{popup, button, label, device, name}` to the `callback` URL
  and dismiss), **BACK** dismisses without an action.
- **Countdown bar** (since 0.3.0) — `showProgress: true` animates a progress bar over a finite duration.
- **Urgency presets** (since 0.3.0) — `urgency: info|warning|critical` adds a blue/orange/red border.
- **Custom border styling** (since 0.7.0) — `borderColor`, `borderWidth` and `cornerRadius` style the
  popup frame yourself; each field overrides its part of the `urgency` preset, so the preset stays a
  shorthand and `borderWidth: 0` switches its border off again. Also available on uploaded snapshots
  (multipart), which previously ignored `urgency` and `showProgress` entirely.
- **Icon beside the text** (since 0.13.0) — an optional `icon` (image URL) shown next to the
  title/message, notification-style, with `iconPosition` (`left`/`right`) and `iconWidth`.
- **Seen over the screensaver** (since 0.18.0) — when the TV's screensaver / ambient mode is showing, the
  app ends it before showing the popup (the same wake path as `/power`), because on several Android
  builds the dream layer covers app overlays and Android 12+ lets it hide them. `dismissScreensaver: false`
  keeps the screensaver; `/state.dreaming` reports the current state.
- **Compact buttons** (since 0.19.0) — `buttonSize` (sp) scales button text and padding together, so a
  popup with buttons can be small; omit it for the classic look.
- **Entrance/exit animations** (since 0.19.0) — `animation: fade | slide_left | slide_right | slide_top |
  slide_bottom`, played when the popup is built and again (reversed) when it expires naturally. An
  update-in-place never re-animates, and replace/`/cancel` still remove instantly.
- **Notification sound** (since 0.18.0) — `sound: "default"` plays a built-in chime when a popup is newly
  shown, or give a URL to your own clip; `soundVolume` (0–1). Not replayed on an update-in-place.
- **Poster** (since 0.17.0) — an optional `poster` (image URL) on `video` and `web` media: a still
  (e.g. a camera snapshot) shown over the stream area the moment the popup appears and faded out on
  the stream's first rendered frame. A live popup never opens as an empty box while the RTSP handshake
  or WebView start-up runs; the stream area takes the poster's aspect, so still and live line up. If the
  stream never paints the poster simply stays. On a web page that hosts a `<video>` (e.g. go2rtc's WebRTC
  viewer) the fade waits for actual playback (since 0.20.0), not for the page paint — so WebRTC + poster
  gives an instant still followed by near-realtime video. `/state.lastPopup.firstFrameMs` reports the time
  to first frame.
- **Screen on/off** (since 0.7.0) — `POST /power?state=on|off|toggle` wakes the TV or puts it in
  standby, without a second integration for ADB or HDMI-CEC. `/state` publishes what is actually
  possible on this device (`power.canSleep`, `power.sleepMethod`) instead of accepting a request it
  cannot honour. See [Screen on/off](#screen-onoff).
- **Permission screen with fix buttons** (since 0.8.0) — the status screen on the TV lists every
  permission with its actual state, and a **Fix** button next to the missing ones that jumps straight
  to the system screen where it is granted, operable with the remote. `POST /permissions/fix` does
  the same from a controller (the Home Assistant integration has a button, an action and a
  self-fixing repair). Where a device has no such screen — or where the permission is one the device
  actively **blocks** (some TVs lock "install unknown apps" for sideloaded apps at system level, e.g.
  Samsung's Auto Blocker; since 0.11.1) — the app shows the adb command instead of a dead button. See
  [Permission screen](#permission-screen).
- **Permission reporting + installers** (since 0.7.0) — `/state` reports what the app was granted
  (`permissions.overlay`, `installPackages`, vendor `autoStart`, `deviceAdmin`, `accessibility`) and the
  status screen on the TV warns when the overlay permission is missing — until now that failure was
  invisible: every popup was answered with HTTP 200 and nothing appeared. `install.sh` / `install.ps1`
  ship with each release and do the whole install *including* the app-ops that an app cannot grant
  itself and that every reinstall resets.
- **Localization** (since 0.3.1) — the app UI follows the device language (English/Dutch).
- **Lazy TTS engine** (since 0.4.0) — the speech engine is only bound when a popup actually carries
  a `tts` field and is released again after 60s idle. On Google TV devices this keeps the separate
  ~100MB `com.google.android.tts` process out of memory, which matters a lot on 1GB TVs where the
  low-memory killer picks the heaviest processes.
- **Restart after an update** (since 0.6.2) — the app listens for `MY_PACKAGE_REPLACED`, so the service
  comes back by itself after a self-update (or an `adb install -r`). Before this, replacing the APK left the
  TV silently offline until something started the service again.
- **Starts on a silent power-restore boot** (since 0.12.0) — the boot receiver and the service are
  `directBootAware` and also listen for `LOCKED_BOOT_COMPLETED`, so the service comes up in the early
  locked-boot phase. `BOOT_COMPLETED` alone is only broadcast once the device reaches an *unlocked*
  session, which a TV that boots to standby after a mains-power cut may never reach until it is turned
  on — so before this the app stayed down after a power outage until it was opened by hand. App prefs
  (device id, version markers) live in device-protected storage so they survive direct boot; the id is
  migrated in place and stays the same.
- **Visible updates** (since 0.11.0) — an update started via the Install button (or `POST /update`) shows
  an "Installing PiPup vX…" popup with a countdown, and where the system demands on-screen confirmation
  (Android < 12) the app turns that into a popup **with a button** — a press gives the installer the
  visible window it needs, instead of a confirmation dialog that flashes past and strands the update.
- **Crash fix: repeated start requests** (since 0.6.1) — `startForeground()` is now called on *every*
  `startForegroundService()` (i.e. also in `onStartCommand`), not only on creation. Without it Android
  killed the process with `RemoteServiceException: Context.startForegroundService() did not then call
  Service.startForeground()`, so every keep-alive attempt — an automation, or the connectivity Receiver —
  crashed the app instead of keeping it alive. `onStartCommand` also revives the web server when it is no
  longer alive.
- **Resilient web server startup** (since 0.4.0) — binding port 7979 is retried (3 attempts, 500ms
  apart) and a definitive failure stops the service for a clean restart, instead of leaving a live
  process with a dead server behind.
- **Self-update** (since 0.6.0) — the app checks the fork's GitHub releases twice a day and can install a
  newer version itself: it announces a new release once on screen with an **Install** button (since
  0.30.1 not when Home Assistant installs the updates by itself: ha-pipup ≥ 1.30.2 with automatic
  updates on says so in a request header, and `/state.update.haManaged` shows it), exposes
  `update` in `/state`, and accepts `POST /update` to trigger the update (used by the Home Assistant
  integration's update entity). Android only accepts an APK signed with the same key, so a tampered
  download can never replace the app. On **Android 12+** the self-update is silent; on older devices the
  system shows its install confirmation on the TV, which someone has to accept with the remote (see the
  limitation below) — unless Home Assistant installs over ADB instead: ha-pipup ≥ 1.29.0 can, and
  ≥ 1.30.0 also does it by itself at night ([Advanced install](https://github.com/mhoogenbosch/ha-pipup#built-in-advanced-install-1290)). Grant the install permission once (survives updates, not reinstalls):
  `adb shell appops set nl.rogro82.pipup REQUEST_INSTALL_PACKAGES allow`
- WebView media supports JavaScript, DOM storage and unattended (autoplay) playback, and cleartext
  (http) LAN URLs are allowed — required for camera streams from e.g. go2rtc/Frigate.
- Assorted fixes (request-body handling, message size/color defaults, WebView cleanup).

**Home Assistant users:** there is a companion integration —
[mhoogenbosch/ha-pipup](https://github.com/mhoogenbosch/ha-pipup) — with a config flow per TV,
a popup binary sensor and `pipup.show` / `pipup.dismiss` actions (including camera entities).

## Which stream should a camera popup use?

**Short answer: `whep` + `poster`** for anything where "now" matters (doorbell, motion), when you run
go2rtc (stand-alone or inside Frigate):

```json
{ "id": "doorbell", "duration": 0, "media": { "whep": {
  "uri": "http://go2rtc:1984/api/webrtc?src=doorbell",
  "width": 720, "height": 540,
  "poster": "http://frigate:5000/api/doorbell/latest.jpg"
}}}
```

The poster (a camera snapshot) is on screen at once; the live WebRTC picture takes over on its first
frame, at full frame rate and well under a second behind reality. Without go2rtc, MJPEG is a fine
zero-dependency fallback; RTSP sits in between and needs no web page.

The trade-off between the routes is **start-up time versus how far the picture lags behind reality**:

| Route | First live frame | Live lag | Notes |
|---|---|---|---|
| **WHEP** (`whep` → go2rtc `/api/webrtc?src=<cam>`, since 0.25.0) | 2.2–2.8 s | **< 0.5 s** | **Recommended.** WebRTC without go2rtc's player page. |
| WebRTC page (`web` → go2rtc `stream.html?src=<cam>&mode=webrtc`) | 2–7 s (up to ~11 s on slow webviews) | < 0.5 s | Same stream as WHEP, but the page, its player script and websocket signalling come first. Use only on an app older than 0.25.0. |
| RTSP (`video` → `rtsp://…`) | 4–6 s | ~1 s | ExoPlayer, RTP over TCP; renders over playing video. |
| MJPEG (`web` → Frigate `/api/<cam>?fps=5`) | 0.2–0.9 s | 2–3 s | Fastest start, but choppy (detect fps) and behind: camera GOP + Frigate's detect pipeline + frame sampling. |
| HLS (`video` → `…m3u8`, `camera_mode: stream`) | 7–12 s | 5–10 s | Avoid for live viewing; fine for non-urgent clips. |

### Measurements

Fire TV (AFTKA, Android 9), app 0.25.0, alternating runs, time from the popup request to the first
rendered frame (`/state.lastPopup.firstFrameMs`):

| Stream | WebRTC page in `web` | `whep` | Frigate MJPEG |
|---|---|---|---|
| `deurbel` (main, 2560×1920, 10 fps), 6 runs | 3423 ms (1977–6635) | 2343 ms (2207–2536) | 906 ms (883–934) |
| `deurbel_noaudio` (go2rtc re-stream of the sub-stream), 5 runs | 6357 ms (5589–6698) | 2524 ms (2262–2772) * | — |
| main streams after setting the camera keyframe interval to 1 s (`gop 1`), 6 runs over 4 cameras | — | **1.0–1.6 s** (avg 1.3 s) | — |

\* Measured while that stream happened to be warm from the runs before. Cold, as in real use, it took
6.4 s: go2rtc answered only after 4.2 s because it had to start the stream first.

Where WHEP's time goes (its logcat timeline): offer after 73 ms, go2rtc's answer after 257 ms, video
track after 354 ms, first frame after about 1.5 s. The rest of the wait is the **camera's keyframe**:
WebRTC can only show a picture from the next I-frame, so a shorter I-frame interval on the camera
starts every WebRTC route sooner (going from a 2 s to a 1 s interval brought the main streams down to
1.0–1.6 s).

**Use a stream go2rtc is already pulling.** A stream with no other viewer is started on demand: go2rtc
answers only once it has connected to the camera, which took 3–4 s here. The main stream Frigate
records from is always running, so its answer comes in ~0.3 s. A separate "no audio" re-stream is not
needed: with `muted` (the default) the app asks for video only. Check with go2rtc's `/api/streams`: a
stream with consumers is warm.

### Keep the camera's keyframes under the TV's UDP buffer

WebRTC sends each keyframe as one burst of UDP packets, and the TV's kernel has to hold that whole
burst in the socket's receive buffer until Chromium reads it. On some Android TV boxes that buffer is
small: a Nokia Streaming Box 8010 (Android 14, wired) caps it at **256 KB** (`/proc/sys/net/core/rmem_max`),
and nothing an app can do raises it. A keyframe larger than that arrives with holes, WebRTC never gets
a decodable frame, and the popup sits on its poster forever while the kernel counts the loss
(`cat /proc/net/snmp | grep Udp` → `RcvbufErrors` climbing; logcat shows `video_receive_stream2.cc:
No decodable frame … requesting keyframe` every 200 ms and `PacketBuffer is already at max size`).
A Fire TV on Wi-Fi played the same streams; the symptom is per device.

Measure your cameras' keyframes and keep them around **≤ 220 KB**:

```sh
ffprobe -v error -rtsp_transport tcp -select_streams v:0 -read_intervals %+4 \
  -show_entries frame=key_frame,pkt_size -of csv=p=0 rtsp://<go2rtc>:8554/<cam> | grep ^1,
```

Measured here (all H.264 High, 10 fps, 1 s keyframe interval): 3 Mbit/s at 2880×1616 → 225–238 KB
(played); 5 Mbit/s at 2560×1920 → 340 KB and 6 Mbit/s at 2560×1440/1920 → 420–535 KB (poster only).
The fix was the camera's main-stream bitrate: 2560×1920 at 3 Mbit/s → 218 KB, 2560×1440 and the
doorbell at 2 Mbit/s → ~215–225 KB. Note that halving the bitrate does not halve the keyframe (the
encoder spends relatively more on I-frames when P-frames get cheap), so measure after each step. The
alternative is a smaller stream for that TV only (the camera's sub-stream), at the cost of a softer
picture and, for a stream nobody else pulls, the cold-start delay above.

### The poster hand-over

The poster (since 0.17.0) makes start-up time matter much less: a still of the same camera shows
instantly and fades on the stream's first frame. For `whep` that moment comes from the app's own
`<video>` element. For a `web` page the app injects a watcher: since 0.20.0 the poster waits until
the page's video **actually plays** (not when the page paints), and since 0.20.1 there is no time limit
once the page is seen to contain a `<video>`. Only a page where no video is ever found falls back to
showing the page after 20 s.

## Supported devices

**Minimum: Android 6.0.1 (API 23).** Built for Android TV / Fire TV, but since 0.14.0 it also
installs on plain-Android devices (projectors, TV boxes) and gets a normal launcher icon there. Since 0.31.0 the
app's own screen follows the device's orientation, so it also works on a phone or tablet held upright (#80); the
popups always did. Phones and tablets are not tested by the maintainer, and their battery optimisation may stop
the background service: exclude PiPup from it if popups stop arriving.

Everything core works on **every** supported version — popups (text, image, video, web, camera),
muted media, TTS, remote-operable buttons, countdown bar, urgency/border styling, the icon beside the
text, `/state`, `/notify`, `/cancel`, mDNS discovery, the overlay watchdog, and turning the screen
**on**. A few things depend on the Android version:

| Capability | Works on |
|---|---|
| Popups, TTS, buttons, styling, `/state`, screen **on** | **6.0.1+** (all) |
| **Self-update** | **6.0.1+** since 0.19.3 (bundles ISRG Root X1 — Android < 7.1.1 lacks the Let's Encrypt root that GitHub's download hosts use) |
| Overlay rendering | 6–7 via `TYPE_SYSTEM_ALERT` (needs the overlay app-op — the installer grants it); 8+ via `TYPE_APPLICATION_OVERLAY` |
| **`video_url`** — `rtsp://`, HLS `.m3u8` (incl. `camera_mode: stream`), progressive http (since 0.16.0) | **6.0.1+**, via ExoPlayer rendered in a `TextureView` — composited by the GPU inside the popup, so it also shows **over video the TV is already playing** (verified on a Fire TV with a film running). RTSP uses RTP-over-TCP. Audio only with `muted: false`. Not for DRM content (irrelevant for cameras). On **Android < 8 and Amlogic SoCs** video is decoded in software by default (since 0.17.2) because the vendor decoder froze the HDMI input on release; `softwareDecoder` overrides. |
| MJPEG camera streams | use **`web_url`** (or the HA integration's `camera_mode: mjpeg`), never `image_url` — `image_url` decodes a single still image and cannot render a multipart MJPEG stream (it shows only the text). For a still, point `image_url` at a snapshot such as Frigate's `/api/<cam>/latest.jpg`. |
| Screen **off** (`POST /power?state=off`) | any version, after a one-time device-admin **or** accessibility grant (`--power` / `--accessibility`); which route works depends on the device |
| **Silent** self-update | **12+** only. On older devices the update still works but the system shows an install confirmation the app wakes the screen for and turns into a popup with a button — one press on the remote finishes it (see [the limitation](https://github.com/mhoogenbosch/ha-pipup#the-update-button-is-silent-only-on-android-12)). Silent on **any** version when ha-pipup ≥ 1.29.0 installs over ADB ([Advanced install](https://github.com/mhoogenbosch/ha-pipup#built-in-advanced-install-1290)) |
| Restart after a **silent power-restore boot** (`LOCKED_BOOT_COMPLETED`) | **7.0+** (direct boot). On Android 6 there is no direct boot, so a restart relies on the normal `BOOT_COMPLETED` — fine on a TV/projector without a lock screen |
| `specialUse` foreground-service type | 14+ (cosmetic; older run a normal foreground service) |

> **Android 6 is not hardware-tested by the maintainer** (no API 23 device on hand) — the code paths
> are version-guarded and the APK builds and runs without regression on the Android 9+ fleet, but if
> you hit something on a 6.0.1 device please open an issue with the `/permissions/diagnose` output.

## Installation (sideloading)

### Prerequisite: enable ADB debugging on the TV

Sideloading requires ADB over the network, which is off by default:

- **Android TV / Google TV:** Settings → System → About → press **Build number 7 times**
  (unlocks Developer options) → Settings → System → Developer options → enable
  **USB debugging** (on recent Google TV also **Wireless debugging**).
- **Fire TV:** Settings → My Fire TV → About → press the device name 7 times →
  My Fire TV → Developer options → enable **ADB debugging**.

Then connect from your computer with `adb connect <tv-ip>:5555` and accept the
authorization prompt on the TV (once per computer).

### Install

Grab `install.sh` (Linux/macOS/WSL) or `install.ps1` (Windows) from the
[releases](../../releases) page and point it at your TVs:

```
./install.sh 192.168.1.10 192.168.1.11          # downloads the latest APK itself
./install.sh --power --apk PiPup.apk 192.168.1.10
# TCL Google TV: the accessibility keep-alive is enabled by default (see the TCL section)
```

It installs the APK, grants the app-ops below, starts the service and verifies over HTTP that the
app is actually answering with the overlay permission in place. `--power` also activates the device
admin (for screen off), `--accessibility` enables the fallback for that, and `--force-uninstall`
handles a differently-signed build that is already installed — note that uninstalling wipes the
app's stable device id, so Home Assistant sees a new device afterwards.

Sleeping TVs are **left asleep**: the service is started in the background, which does not touch
what is on screen. On a TCL Google TV use `--wake`, because its vendor guard freezes a service
started from the background (see below) — there the app has to come up in the foreground.

> **This is also the way to update Android < 12 TVs silently.** The in-app self-update installs silently
> only on Android 12+; on older devices the OS forces an on-screen confirmation for any *app*-initiated
> install (a platform limit — see [self-update](#what-this-fork-adds-compared-to-rogro82pipup)). A
> **shell**-initiated `adb install -r`, which is what `install.sh` does, has no confirmation on any
> Android version.
>
> **With Home Assistant you no longer need the script for that:** ha-pipup ≥ 1.29.0 does the same
> install itself, in pure Python, so also on Home Assistant OS (no `adb` binary), and ≥ 1.30.0 can do
> it automatically inside a quiet window at night. Turn on network debugging on the TV once and choose
> *Advanced install* in the integration's options — see
> [Advanced install](https://github.com/mhoogenbosch/ha-pipup#built-in-advanced-install-1290).
> `install.sh` remains the way for the first install, and for keeping TVs updated without Home
> Assistant (run it on a schedule).

<details>
<summary>Doing it by hand</summary>

```
adb connect <tv-ip>:5555
adb install -r PiPup.apk
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
adb shell appops set nl.rogro82.pipup REQUEST_INSTALL_PACKAGES allow
```

The overlay permission has no settings UI on Android TV, and the install permission is what lets the
app apply its own updates. **Both are app-ops**: an app cannot grant them to itself (that is
shell/system territory), which is why they are handed out over adb — and why `adb install -r`
**resets them**, so they have to be granted again after every install. `/state` and the status screen
on the TV show whether the overlay permission is currently in place.

If you have the original Play Store version installed you need to uninstall that first (different
signature, same application id).
</details>

_After installation or updating, open the application once (or reboot the TV) to make sure the
background service is running._ Starting the service without bringing the app to the foreground
(handy from an automation, it does not interrupt whatever is playing) also works:

```
adb shell am start-foreground-service -n nl.rogro82.pipup/.PiPupService
```

> **Not on TCL Google TV.** There a background start lands the service at `oom_score_adj` 500 and
> the vendor guard freezes it within seconds. Start the activity instead — see below.

#### TCL Google TVs: vendor guard kills and freezes the app

TCL ships an extra guard (`com.tcl.guard`) with two separate mechanisms. Both look like an app bug
and neither is caused by memory pressure — measured on a 1 GB set, PiPup used 26 MB PSS while the
launcher used 119 MB and the screensaver 91 MB.

**The fix that covers both: enable PiPup's accessibility service.** The installers on master do this by
default on TCL, shipping with the first release after 0.18.1 (`--no-accessibility` / `-NoAccessibility`
to opt out). The service itself is
dormant — it observes nothing and the app only uses it as the screen-off fallback — but once it is
enabled, `system_server` keeps it *bound*, and a process with a system-bound service sits at
`oom_score_adj` **100** ("visible"): above anything the guard kills or freezes, and above the 200 the
activity route below reaches. Measured on a TCL Google TV (Android 11):

```
$ adb shell 'P=$(pidof nl.rogro82.pipup); cat /proc/$P/oom_score_adj'
100
$ adb shell dumpsys activity processes | grep -A1 nl.rogro82.pipup
  Proc #12: vis  F/S/FGS  nl.rogro82.pipup (service)
      nl.rogro82.pipup/.PiPupAccessibilityService <= Proc{741:system/1000}
```

A second owner whose TCL had been dropping off for weeks went from 500 to 100 with this one change
(issue #38). TCL's accessibility settings screen is a stub, so it has to be adb — and **append**, or you
switch off every other accessibility service on the set:

```
adb shell 'cur=$(settings get secure enabled_accessibility_services); settings put secure enabled_accessibility_services "${cur:+$cur:}nl.rogro82.pipup/nl.rogro82.pipup.PiPupAccessibilityService"; settings put secure accessibility_enabled 1'
```

Like every grant it survives updates via `install.sh`, which re-applies it. The two mechanisms below are
what you are up against without it, and how to recover a TV that is already stuck.

**1. It blocks the automatic restart** of a killed service unless the app holds the vendor-specific
`APP_AUTO_START` app-op — it logs `forbid restart Servic ... callee_does't_have_OP_AUTO_START_permission`
and the service never comes back after a kill. The on-screen menu ("Permission Guardian" →
"Auto-start permission") keeps per-app entries locked while its "Automatic management" master switch
is on, so grant the op over adb instead (note the internal name `android:auto_start`; the displayed
name `APP_AUTO_START` is not accepted):

```
adb shell cmd appops set nl.rogro82.pipup android:auto_start allow
adb shell dumpsys deviceidle whitelist +nl.rogro82.pipup
```

Like the overlay permission the app-op resets on reinstall, so repeat it after every update. On
brands without this op (Fire TV, Nokia, …) the command fails with `Unknown operation string` — that
is fine, nothing needs granting there. The deviceidle entry survives reboots and stops
`am_stop_idle_service` from tearing the service down.

**2. It freezes processes** (`persist.sys.freeze=true`, independent of the AOSP freezer). A frozen
process is **alive but SIGSTOPped**, which is why this failure mode is so confusing: `ps` still
lists PiPup while port 7979 no longer answers, so clients hang in a timeout instead of getting a
connection error. Recognise it like this:

```
adb shell 'P=$(pidof nl.rogro82.pipup); grep freezer /proc/$P/cgroup; cat /proc/$P/oom_score_adj'
#  frozen  ->  5:freezer:/frozen   ...  500
#  healthy ->  5:freezer:/thaw     ...  200
adb shell netstat -ltn | grep 7979   # frozen: Recv-Q > 0 on LISTEN, plus CLOSE_WAIT rows
```

Incoming traffic does not thaw the app; only bringing it to the foreground does. Without the
accessibility service above, **what keeps it running is `oom_score_adj` 200, and the app only reaches
that when it is started from a foreground context**, i.e. via the activity:

```
adb shell input keyevent KEYCODE_WAKEUP   # only needed while the screensaver is on
adb shell am start -n nl.rogro82.pipup/.MainActivity
adb shell input keyevent KEYCODE_HOME
```

Started this way the app stays up, screensaver included. Two caveats: `am start -W` **hangs** while
the TV is dreaming (use it without `-W`), and this briefly takes over the screen, so avoid it while
someone is watching. It is worth automating the recovery — the
[ha-pipup integration README](https://github.com/mhoogenbosch/ha-pipup#device-notes-tcl-google-tv-needs-a-keep-alive-automation)
has a ready-made Home Assistant automation, including the pitfall that a `ps | grep pipup` guard
silently defeats it (a frozen process is still listed).

## Security

PiPup runs an embedded webserver (NanoHTTPD) on port **7979** with **no authentication**,
and a popup's `web` media is rendered in a WebView with JavaScript and DOM storage enabled.
That means **any device on the same network can display arbitrary content — including
JavaScript — on the TV.** This is by design (camera/stream pages need it), but it makes the
trust boundary the network itself.

- Run PiPup TVs on a **trusted network segment** (not a guest/IoT VLAN that untrusted devices share).
- Traffic is plain HTTP (`usesCleartextTraffic`), so treat everything sent to the popup — URLs,
  TTS text, button callbacks — as visible on the LAN.
- Button presses POST to the `callback` URL supplied with the popup. If you drive security-sensitive
  automations from button events (e.g. unlocking a door), have the caller include an unguessable,
  single-use token in that callback URL and verify it on receipt — the
  [ha-pipup integration](https://github.com/mhoogenbosch/ha-pipup) does this automatically.
- Since 0.7.0 the same unauthenticated port also accepts `POST /power`, so anyone who can reach the TV
  can switch its screen on or off. That is annoying rather than dangerous, but it is a reason not to
  grant the screen-off route (device admin / accessibility) on a TV you deliberately expose. Granting
  nothing leaves `/power?state=off` returning 501, and the device admin only asks for `force-lock` —
  no password, camera or wipe policies — so the worst an attacker gains is a TV that goes to standby.
- Since 0.24.0 `/settings` is on the same port. Anyone on the LAN can point the push webhook at
  their own host (and so read every later state change) or change the update source. The update
  source cannot install a foreign app: Android only accepts an update signed with PiPup's own key.
- Since 0.29.0 the port also takes `/launch`, `/key` and `/volume`: anyone on the LAN can start an app,
  pause what is playing or change the volume — what a guest with the remote can do, no more. `/launch`
  only starts apps and links; it cannot install, grant or change anything.
- Since 0.29.0 nothing of PiPup goes into an Android backup or a device-to-device transfer: settings
  include the push webhook URL (anyone holding it can post to it) and the stable device id, which would
  make a new TV show up as the old one. A new or restored TV starts clean and is discovered as a new
  device.

## Integrating

PiPup runs an embedded webserver (NanoHTTPD) on port **7979**.

### Sending notifications

#### JSON (external media: image, video or webview)

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /notify          |
| Method:       | POST             |
| Content-Type: | application/json |

Example:

```json
{
  "duration": 30,
  "id": "doorbell",
  "position": 0,
  "title": "Your awesome title",
  "titleColor": "#0066cc",
  "titleSize": 20,
  "message": "What ever you want to say... do it here...",
  "messageColor": "#000000",
  "messageSize": 14,
  "backgroundColor": "#ffffff",
  "media": { "image": {
    "uri": "https://your.host/image.png", "width": 480
  }}
}
```

All fields are optional. For `media` you can specify 4 types:

```json 
{ "image": { "uri": "address_to_your_image", "width": 480 }}
{ "video": { "uri": "address_to_your_video", "width": 480, "muted": true }}
{ "web":   { "uri": "address_to_your_resource", "width": 640, "height": 480, "muted": true }}
{ "whep":  { "uri": "http://go2rtc:1984/api/webrtc?src=doorbell", "width": 640, "height": 480 }}
```

`whep` (since 0.25.0): a WebRTC stream played straight from a
[WHEP](https://datatracker.ietf.org/doc/draft-ietf-wish-whep/) (IETF draft) endpoint, such as go2rtc's `/api/webrtc?src=<stream>`.
The app loads a small player page of its own (with the endpoint's origin as base, so no CORS setup is
needed), sends one SDP offer, and plays the answer. Compared with go2rtc's `stream.html` in a `web`
popup this skips the page download, its player script and the websocket signalling. `muted` defaults
to `true` (no audio track is requested); `poster` and `transparent` work as for `web`. On a failed
offer or a dropped connection it retries with backoff (1, 2, 4, then every 8 s).
`/state.lastPopup.mediaError` holds the last error (for example `HTTP 404` for an unknown stream
name); with adb, `logcat -s PopupView` shows a timeline per attempt (offer, answer, track, playing).

A WHEP endpoint behind basic authentication (since 0.29.0), such as go2rtc with `api.username` and
`api.password`, takes the credentials in the URL: `http://user:password@go2rtc:1984/api/webrtc?src=doorbell`.
The app removes them from the URL and sends them as an `Authorization: Basic` header, because a
browser refuses a request to a URL that carries credentials. Percent-encode reserved characters in the
password (`@` as `%40`, `:` as `%3A`). Wrong credentials show as `HTTP 401` in `mediaError`. The tiles of
a camera wall (`grid`) take credentials the same way.

#### Full-screen popups

Since 0.29.1. `"fullscreen": true` on any popup makes its window cover the whole display, status and
navigation bars included, with the content centred. Without it an overlay is laid out *inside* the
system bars: on a device that has them (a tablet, an intercom panel) a page sized to the screen stops
short of the edges and the app underneath shows as strips. TVs have no bars, so there it makes no
visible difference. For a frameless full-screen page:

```json
{ "id": "screensaver", "duration": 0, "fullscreen": true, "padding": 0, "borderWidth": 0,
  "media": { "web": { "uri": "http://ha.local:8123/local/clock.html", "width": 1024, "height": 600 } } }
```

A full-screen popup never stacks. Touch keeps working for a popup without buttons; note that it then
also takes every touch away from the app underneath for as long as it is up.

#### Camera wall (`grid`)

Since 0.29.0. Several WHEP streams side by side in one popup:

```json
{ "id": "camera_wall", "duration": 0, "position": "center", "padding": 0,
  "media": { "grid": {
    "items": [
      { "uri": "http://go2rtc:1984/api/webrtc?src=doorbell", "label": "Doorbell",
        "poster": "http://frigate:5000/api/doorbell/latest.jpg" },
      { "uri": "http://go2rtc:1984/api/webrtc?src=driveway", "label": "Driveway" },
      { "uri": "http://go2rtc:1984/api/webrtc?src=garden", "label": "Garden" },
      { "uri": "http://go2rtc:1984/api/webrtc?src=shed", "label": "Shed" }
    ],
    "fullscreen": true } } }
```

| Field | Default | |
|---|---|---|
| `items` | — | 1 to 9 tiles: `uri` (WHEP endpoint), optional `label` and `poster` |
| `columns` | auto | auto = 1, 2, 2, 2, 3, 3, 3, 3, 3 columns for 1..9 tiles |
| `width` / `height` | 1280 / 720 | size of the whole grid in px |
| `fullscreen` | `false` | fill the whole screen, system bars included (minus the popup `padding`; use `padding: 0` for edge to edge) — implies the popup's own `fullscreen` |
| `gap` | 4 | px between tiles |
| `labels` | `true` | show the captions |
| `muted` | `true` | `false` requests audio from every tile |

Each tile is its own WebRTC connection with the same retry as `whep`, so a camera that is down shows its
poster (or black) while the rest play. A tile's poster stays until that tile plays. `/state.lastPopup`
reports `firstFrameMs` for the first tile that plays, and `mediaError` names the tile by its label.
**All tiles must come from the same server as the first one** — the page is loaded with that origin so
the SDP offers are same-origin, and go2rtc sends no CORS headers by default. Every tile is decoded on
the TV, so a wall of main streams can be more than a small streaming stick manages: point the tiles at
the cameras' substreams.

`poster` (since 0.17.0, video and web; whep since 0.25.0): URL of a still image shown over the stream area until the
stream renders its first frame, then faded out. Use a camera snapshot (e.g. Frigate
`/api/<cam>/latest.jpg`) so the popup shows a picture instantly instead of an empty frame while
RTSP connects or the WebView starts. The stream area takes the poster's aspect, so still and live
match. If the poster fails to load nothing happens; if the stream never paints, the poster stays.
`/state.lastPopup.firstFrameMs` reports the time to first frame.

```json
{ "video": { "uri": "rtsp://cam/sub", "width": 640, "poster": "http://frigate:5000/api/cam/latest.jpg" }}
```
`softwareDecoder` (since 0.17.2, video only, default *automatic*): `true` decodes in software and never
touches the vendor hardware decoder; `false` forces the hardware decoder; absent = automatic — software on
**Android < 8** and wherever an **Amlogic** H.264 decoder is present, hardware everywhere else. Reason: on
Amlogic SoCs the MediaCodec decoder and the HDMI input share one video layer, and releasing the decoder when
the popup closed **froze the HDMI picture** behind it (Xiaomi laser projector, Android 6.0.1). Software
decoding is fine for a camera sub-stream (640×480 … 720p); a 1080p main stream may be heavy on a low-end
projector — use the sub-stream there. Decoder fallback is on, so a failing software decoder falls through to
hardware. `/state.lastPopup.media.softwareDecoder` echoes the requested value (`null` = automatic).

```json
{ "video": { "uri": "rtsp://cam/sub", "width": 640, "softwareDecoder": true }}
```

`bufferMs` (since 0.31.0, video only, ms, optional): low-latency playback of a live stream. ExoPlayer waits for
2.5 s of media before it starts, and a live source can't run ahead of real time, so an RTSP popup stays 3–4 s
behind the camera for as long as it is open. With `bufferMs` playback starts once that much is buffered, resumes
after a stall at twice that, and the delay stays near the value. Clamped to 100–10000; absent = ExoPlayer's
defaults. Meant for **RTSP** (intercom, doorbell talk-back); with HLS the playlist sets the delay, and a small
buffer there mostly adds stalls. Measured by @t476r9js8z-code on a Chromecast with Google TV (RTSP over TCP from
MediaMTX): default 3–4 s, `300` ≈ 2 s, `150` ≈ 1 s (#79). With a very small buffer, audio/video sync relies on the
stream's timestamps: a WebRTC → RTSP restream can start its audio and video clocks at different offsets, which
showed as video ahead of the voice at 150 ms; re-stamping on the server (ffmpeg `-use_wallclock_as_timestamps 1`)
fixed it. `/state.lastPopup.media.bufferMs` echoes the value.

```json
{ "video": { "uri": "rtsp://192.168.1.20:8554/doorbell", "width": 640, "bufferMs": 200 }}
```

`height` (since 0.28.0, video only, px, optional): the stream area's height until the first frame. Without it the
box is 16:9 and then takes the poster's aspect. Set it when the camera's snapshot has a different pixel shape
than its picture (an NVR serving a 704×480 still of a 4:3 image): the box then keeps this height and the poster
no longer resizes it. Once the stream paints, the box follows the stream's display aspect (also since 0.28.0:
anamorphic streams with a non-square pixel aspect are no longer squashed). Contributed by @pelkiegr (#67, #69).

```json
{ "video": { "uri": "rtsp://nvr/sub", "width": 640, "height": 480, "poster": "http://nvr/snapshot.jpg" }}
```


`dismissScreensaver` (since 0.18.0, default `true`): end an active screensaver / ambient mode before the
popup is shown, so it is seen on every Android version (some dream layers cover app overlays; Android 12+
can hide them). `false` leaves the screensaver running — the popup may then be invisible on such devices.

`sound` (since 0.18.0, default none): `"default"` plays the built-in chime, any other value is a URL/URI of
an audio clip (mp3/ogg/wav) when the popup is newly shown; `soundVolume` (0–1) scales it. The built-in chime
is 1.9 s with a 300 ms silent lead-in: an HDMI/eARC audio path needs a few hundred ms to open when a new
stream starts, and a very short clip disappears in that gap — give your own clip a lead-in too. An update-in-place
of the same popup does not replay it. Uses transient audio focus with ducking; like `tts` this opens an audio
path, which on some Fire TVs briefly renegotiates HDMI audio.

```json
{ "id": "doorbell", "title": "Front door", "sound": "default", "soundVolume": 0.8 }
```

`opacity` (since 0.24.0, 0..1, default 1): draws the whole popup, media included, at that alpha, so the
picture behind it stays visible. Works with every media type and with `animation`.

`transparent` (since 0.24.0, `web` media only, default `false`): the WebView paints no background, so a
page with a transparent `html, body { background: transparent }` shows the TV through it. Combine with
`"backgroundColor": "#00000000"` and `"padding": 0` for a frameless, see-through overlay:

```json
{ "id": "score", "duration": 0, "padding": 0, "backgroundColor": "#00000000",
  "media": { "web": { "uri": "http://192.168.1.96:8123/local/score.html",
    "width": 420, "height": 900, "transparent": true } } }
```

`padding` (since 0.19.1, px, default 20): the popup's outer margin around content; `0` gives a
near-borderless look.

`buttonSize` (since 0.19.0, sp): scales the popup buttons' text and padding together. Without it the
buttons look exactly as before.

Button style (since 0.28.0, each optional; unset = the classic look): `buttonColor` (fill of an unfocused
button), `buttonFocusColor` (fill of the focused one), `buttonTextColor`, `buttonBorderColor` (outline, both
states) and `buttonCornerRadius` (px, `0` = square). Colors are `#RRGGBB` or `#AARRGGBB`; a malformed value falls
back to the classic color. Contributed by @pelkiegr (#70).

Camera popup look (since 0.28.0, each off by default; contributed by @pelkiegr, #71):
- `liveBadge: true` puts a "Live" pill on the stream area once the **first frame** renders (never on the
  poster). `liveBadgeColor` (default amber `#FFA500`) and `liveBadgePosition` (`top_left`, `top_right`,
  `bottom_left`, `bottom_right` (default)). For video, whep and web media.
- `mediaFullBleed: true` lets the media run edge to edge across the top of the popup, its top corners rounded
  with the popup's `cornerRadius`; title, message and buttons keep the padding.
- `buttonsInline: true` puts the buttons at the end of the title/message row instead of on their own row,
  e.g. a single compact "✕". A popup without title, message or icon keeps its button row.

```json
{ "title": "Front door", "message": "Motion", "cornerRadius": 24, "mediaFullBleed": true,
  "liveBadge": true, "buttonsInline": true, "buttons": [{ "id": "close", "label": "✕" }],
  "media": { "whep": { "uri": "http://go2rtc:1984/api/webrtc?src=doorbell", "width": 640, "height": 360 } } }
```

`animation` (since 0.19.0, default none): `fade`, `slide_left`, `slide_right`, `slide_top` or
`slide_bottom`. Plays when the popup is built; an update-in-place of the same popup does not re-animate.
A naturally expiring popup animates out the same way; replace and `/cancel` remove instantly.

`muted` (since 0.2.4, default `false`): plays the video/web media without audio. For web media every
(also dynamically added) `<video>`/`<audio>` element on the page is muted, so the page never claims
audio focus — audio in a popup can freeze video playback on some Android TV / Fire TV devices.

`tts` (since 0.2.5): a text that is spoken aloud on the TV when the popup appears, using the
device's text-to-speech engine. Optional `ttsLanguage` takes a BCP-47 tag (e.g. `"nl-NL"`);
the device's default locale is used when omitted. Re-sending the same popup `id` with unchanged
content and unchanged `tts` does **not** repeat the speech (only the removal timer is extended);
sending a different `tts` text speaks the new text.

```json
{ "title": "Doorbell", "tts": "Er staat iemand voor de deur", "ttsLanguage": "nl-NL" }
```

Since 0.3.0 three more optional fields:

```json
{
  "urgency": "critical",
  "showProgress": true,
  "buttons": [{ "id": "unlock", "label": "Open the door" }],
  "callback": "http://your-ha:8123/api/webhook/pipup_buttons"
}
```

`urgency` (`info`/`warning`/`critical`) adds a blue/orange/red border. `showProgress` animates a
countdown bar over a finite `duration`. `buttons` (with a `callback` URL) renders remote-operable
buttons: the overlay only takes input focus when buttons are present, **OK** activates the focused
button — the app POSTs `{"popup", "button", "label", "device", "name"}` to the callback and
dismisses — and **BACK** dismisses without an action.

Since 0.7.0 the border can be styled directly, beyond the three urgency presets:

```json
{
  "borderColor": "#00E5FF",
  "borderWidth": 10,
  "cornerRadius": 28
}
```

| Field          | Type / default                                                        |
| -------------- | --------------------------------------------------------------------- |
| `borderColor`  | String `[AA]RRGGBB` (default: the urgency color, else `#ffffff`)       |
| `borderWidth`  | Integer pixels (default: the urgency width, else `4` when a color is given; `0` = no border) |
| `cornerRadius` | Number pixels (default: `8` when a border is drawn, else `0`)          |

Each field independently overrides the `urgency` preset, so the two combine: `urgency: "critical"`
with `borderWidth: 2` keeps the red but makes it thin, and `borderWidth: 0` removes the preset's
border while keeping any other styling. `cornerRadius` works without a border too, for rounded
corners on a plain popup. Sizes are in **pixels**, like every other dimension in this API (media
width, padding) — on a 1080p TV a border of 10 is comfortably visible. An unparseable color falls
back to the default instead of dropping the popup.

Since 0.13.0 an icon can be shown beside the title/message (notification-style):

```json
{
  "icon": "http://your-ha:8123/local/icons/doorbell.png",
  "iconPosition": "left",
  "iconWidth": 96
}
```

`icon` is an image URL, loaded like the other media. `iconPosition` is `left` (default) or `right`;
`iconWidth` is in pixels (default 96, aspect ratio preserved). The title and message sit in a column
next to the icon, and the `media` image (if any) stays below.

- `duration`: seconds to show the popup. **`0` or negative shows it indefinitely**, until `/cancel`
  is called or a new popup replaces it.
- `id` (string, optional): identifies the popup. Since 0.24.0 **each id is its own popup**: a popup
  with a new id opens beside the ones already on screen (newest on top), each in its own window sized
  to its content. Popups without an `id` share one slot and replace each other. There is no limit on
  how many are up; what a TV can draw smoothly depends on the TV (one video at a time is typical for
  a 1-2 GB TV). Re-sending the same `id` with identical content only reschedules the removal timer;
  the view (and a playing video/web stream) is kept as-is. Same `id` with new content redraws that
  popup in place, keeping its place in the stack.
- `bringToFront` (since 0.24.0, default `false`): a redraw of a popup already on screen opens it on
  top of the others instead of in its old place.
- `stack` (since 0.27.0, default none): `"vertical"` or `"horizontal"` lines the popup up with the
  other stacking popups at the **same position** instead of drawing it over them.
  - The first popup keeps the usual place. Each next one goes right after the previous one, with a
    12 px gap. Vertical moves away from the top/bottom edge, horizontal away from the left/right
    edge. On a centred axis the stack continues downwards (`center_left`/`center_right`) or to the
    right (`top_center`/`bottom_center`).
  - When a popup closes, the rest move up into the gap. When one changes size (a video popup grows
    as its first frame arrives), the stack is laid out again.
  - Popups without `stack` keep their fixed place and may overlap the stack. `center` never stacks.
  - The stack is not limited to the screen: keep media small when many popups can be up at once.
  - Requested in [#64](https://github.com/mhoogenbosch/PiPup/issues/64).

#### multipart/form-data (uploaded image file)

| Property      | Value               |
| ------------- | ------------------- |
| Path:         | /notify             |
| Method:       | POST                |
| Content-Type: | multipart/form-data |

Form-fields:

| Field           | Type                                         |
| --------------- | -------------------------------------------- |
| duration        | Integer (default=30, 0=indefinite)           |
| id              | String (optional popup identifier)           |
| position        | Integer (0..8, default=0)                    |
| title           | String                                       |
| titleSize       | Integer (default=16)                         |
| titleColor      | string (default=#FFFFFF, format=[AA]RRGGBB   |
| message         | String                                       |
| messageSize     | Integer (default=12)                         |
| messageColor    | String (default=#FFFFFF, format=[AA]RRGGBB   |
| backgroundColor | String (default=#CC000000, format=[AA]RRGGBB |
| image           | File                                         |
| imageWidth      | Integer (default=480)                        |
| tts             | String (optional, spoken aloud, since 0.2.5) |
| ttsLanguage     | String (optional BCP-47 tag, since 0.2.5)    |
| urgency         | String info/warning/critical (since 0.7.0)   |
| borderColor     | String (format=[AA]RRGGBB, since 0.7.0)      |
| borderWidth     | Integer pixels (since 0.7.0)                 |
| cornerRadius    | Number pixels (since 0.7.0)                  |
| icon            | String image URL (since 0.13.0)              |
| iconPosition    | String left/right (default=left, since 0.13.0) |
| iconWidth       | Integer pixels (default=96, since 0.13.0)    |
| showProgress    | Boolean (default=false, since 0.7.0)         |

`position` is an enum ranging from 0 to 8 — the full 3×3 grid:

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |
| 5     | TopCenter (since 0.22.0) |
| 6     | BottomCenter (since 0.22.0) |
| 7     | CenterLeft (since 0.26.0) |
| 8     | CenterRight (since 0.26.0) |

Edge positions keep a 20 px margin from the screen edge; on a centred axis the popup is centred
exactly. `TopRight`, `CenterRight` and `BottomRight` therefore stack cleanly along one side when
several popups (with their own `id`) are shown at once.

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional, e.g. #FFFFFF or #CCFFFFFF.

### Cancelling a popup

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /cancel          |
| Method:       | POST             |

Since 0.24.0 several popups can be up at once:

- `POST /cancel?id=doorbell` removes the popup with that id and leaves the others.
- `POST /cancel` (no id) removes the popup that was sent without an id.
- `POST /cancel?all=true` removes every popup.

When nothing matches, the call is a no-op (HTTP 200 with an explanatory message).

> **Breaking since 0.24.0:** before, `POST /cancel` without an id removed whatever popup was on screen.
> Now it only removes a popup sent *without* an id. A client that shows popups with an `id` and cancels
> without one leaves them up — with `duration: 0` for good — and still gets `200`. Cancel with the same
> `id`, or with `?all=true`.

### Screen on/off

| Property      | Value                        |
| ------------- | ---------------------------- |
| Path:         | /power?state=on\|off\|toggle |
| Method:       | POST                         |

Since 0.7.0. Answers with the result rather than a bare "accepted":

```json
{ "state": "off", "ok": true, "method": "device_admin", "screenOn": false }
```

HTTP 200 when it was carried out, **501** when this device has no way to do it, 400 on a missing or
unknown `state`. (`screenOn` in the reply can lag one poll behind on `state=on`: the wake activity is
still starting up.)

**On** needs nothing: PiPup launches an invisible activity with `setTurnScreenOn(true)`, which is the
supported way to wake a device. On HDMI-CEC setups waking the box also switches the TV to its input.

**Off** is the one capability that can genuinely be missing, because no sideloaded app may put a
device to sleep on its own. There are two routes, and PiPup uses whichever is granted (device admin
first). Grant one **once**, over adb — `install.sh --power` / `--accessibility` do exactly this:

```
# route 1 (preferred): device admin. Only asks for force-lock, nothing else.
adb shell dpm set-active-admin nl.rogro82.pipup/.AdminReceiver

# route 2: accessibility fallback, for devices without the device-admin feature
adb shell settings put secure enabled_accessibility_services \
    nl.rogro82.pipup/nl.rogro82.pipup.PiPupAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Route 2 exists because a fair number of Android TV boxes ship **without** the device-admin feature at
all (`dumpsys device_policy` shows `mHasFeature=false`). Confusingly, `dpm set-active-admin` still
prints `Success` there while nothing is registered — so trust `/state`, not `dpm` (the installer
scripts verify it that way and tell you to switch routes). Verified on hardware:

| Device | Android | Screen on | Screen off |
| --- | --- | --- | --- |
| Fire TV stick (AFTKA) | 9 (Fire OS) | ✓ | ✓ device admin |
| Nokia Streaming Box 8010 | 14 | ✓ | ✓ accessibility (no device-admin feature) |
| TCL Google TV | 11 | ✓ | ✓ accessibility (no device-admin feature) |

Two things seen while testing: a wake request that arrives **within a few seconds of putting the
device to sleep** can be ignored while the sleep transition is still completing (a second call works),
and on Android 13+ an accessibility service enabled over adb can be revoked again by the system — the
switch's `can_sleep` attribute (and `/state`) show that immediately.

⚠️ **When appending to `enabled_accessibility_services`, keep the existing value** (colon-separated) —
overwriting it disables other accessibility services, such as Projectivy Launcher's. The installer
scripts append; the snippet above only holds for a device with none enabled.

⚠️ **Like the app-ops, this grant does not survive a reinstall.** Replacing the APK (`adb install -r`,
including an update) drops the app out of the enabled list, and screen-off silently stops working —
`/state` reports `power.canSleep: false` from then on. Re-run `install.sh --accessibility`, or include
the flag in the install itself. The device admin route does not have this problem.

The accessibility service declares no event types and does not retrieve window content: it is bound
purely so `GLOBAL_ACTION_LOCK_SCREEN` can be called, and reads nothing from your screen.

`/state` publishes the capability so a client can hide a button it cannot honour:

```json
"power": { "canWake": true, "canSleep": true, "sleepMethod": "device_admin" }
```

### TV control

Since 0.29.0. Remote-control actions without adb. Like `/power`, each call answers with what happened:
`{"ok": true, "message": "…", …}` with HTTP 200, **404** for an app that is not installed, **501** when
this device cannot do it, 400 for a malformed request.

| Path | Method | Parameters |
|---|---|---|
| `/apps` | GET | — answers `{"apps": [{"package", "label", "tv"}]}`, sorted by label (`tv` = has a TV launcher entry) |
| `/launch` | POST | `package=<name>` and/or `uri=<deep link>`; `wake=false` to leave a sleeping screen off |
| `/key` | POST | `key=play_pause\|play\|pause\|stop\|next\|previous\|rewind\|fast_forward\|home\|back` |
| `/volume` | GET / POST | one of `level=0-100`, `step=up\|down`, `mute=on\|off\|toggle`; `ui=false` hides the volume bar |

```
curl -X POST "http://tv:7979/launch?package=com.netflix.ninja"
curl -X POST "http://tv:7979/launch?uri=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
curl -X POST "http://tv:7979/key?key=play_pause"
curl -X POST "http://tv:7979/volume?level=30"
```

- **Launch** works on every device where popups work: "display over other apps" also exempts PiPup from
  Android's background-activity-start rules. A sleeping screen is woken first (unless `wake=false`).
  With only `uri`, Android picks the app that handles the link; add `package` to force one.
- **Media keys** go to the app that currently holds the media session (Netflix, YouTube, Spotify,
  Jellyfin, Kodi, …) — the same path as the play button on a Bluetooth remote. An app without a media
  session ignores them.
- **Home and back** need the accessibility service (`install.sh --accessibility`); without it they answer
  501 and are left out of `/state.control.keys`.
- **Volume** is the media stream (`STREAM_MUSIC`). On a box whose volume belongs to the TV set or an
  amplifier over HDMI-CEC, `/state.volume.fixed` is `true`: `level=` then answers 501, while `step` and
  `mute` are passed on and the platform forwards them over CEC where it can. A volume change, also one
  made with the remote, is pushed as `volume_changed`.
- PiPup cannot see which app is in the foreground (that needs usage access, a grant with no screen on a
  TV). `/state.control.lastLaunched` is only what PiPup itself started.

### Permission screen

| Property      | Value                                                    |
| ------------- | -------------------------------------------------------- |
| Path:         | /permissions/fix[?what=overlay\|install\|admin\|accessibility\|next] |
| Method:       | POST                                                     |

Since 0.8.0. Puts the screen that grants a permission in front of the user and wakes the TV first.
Without `what` it opens PiPup's own status screen, which lists every permission with its own **Fix**
button; `what=next` jumps to the first missing one.

```json
{ "what": "overlay", "ok": true, "granted": false, "adb": null }
```

**The app still cannot grant anything itself** — these are app-ops, which only shell or the system
may set. What it can do is walk someone holding a remote to the exact spot, which is the part that
was missing.

Whether that spot exists differs per device, and *asking* is not enough: every Android build must
resolve these intents to pass Google's compatibility suite, so a plain "is there an activity for
this?" says yes even where nothing happens. Fire OS answers with `CTSDummyIntentHandler`, Google TV
with `frameworkpackagestubs.Stubs`. PiPup treats those placeholders as absent, because a button that
visibly does nothing is worse than no button: `/state` then reports the permission as not fixable,
`/permissions/fix` answers **501**, and both the TV screen and the reply carry the adb command.

Measured on hardware:

| | Fire OS 9 | Google TV 11 | Android 14 |
| --- | --- | --- | --- |
| Overlay permission | adb only | ✓ on screen | ✓ on screen |
| Self-update permission | ✓ on screen | ✓ on screen | ✓ on screen |
| Device admin | adb only | not supported by the platform | not supported by the platform |
| Accessibility | adb only | adb only | ✓ on screen |

`/state` publishes this as `permissions.fixable`, so a controller can show a button only where it
leads somewhere.

#### Permissions the device blocks outright

A settings screen that opens but whose toggle **will not stick** is a third case, distinct from a
missing screen. Some devices lock a permission at system level for sideloaded apps — Samsung's Auto
Blocker, or a TCL that keeps "install unknown apps" off — which shows up as an app-op stuck in the
`errored` or `ignored` state (`/permissions/diagnose` reports `opModes`). Since 0.11.1 PiPup treats
such an op as not fixable on screen: `fixIntent` returns nothing, the status screen shows the adb
command with a "this TV blocks it from its settings screen" note, and `POST /permissions/fix` answers
**501** with that reason and the command — rather than opening a screen where nothing happens. A
neutral `default` op (the normal case) still gets the on-screen Fix button.

#### The one case that cannot work remotely

**Without the overlay permission, the fix screen cannot be opened from Home Assistant.** From Android
10 on, starting an activity from the background is blocked unless the app is exempt, and holding
`SYSTEM_ALERT_WINDOW` is one of the exemptions — while a foreground service is
[explicitly not](https://developer.android.com/guide/components/activities/background-starts). So the
one permission you most want a button for is the one whose absence takes the button away. A blocked
launch does not even throw: the platform drops it silently.

PiPup therefore checks up front and answers **501** with
`reason: "Android blocks starting an activity from the background…"` instead of reporting success and
doing nothing. The way out is a visible window of the app: **open PiPup on the TV** (from the launcher,
or by tapping its ongoing notification — a notification tap is another exemption) and press the **Fix**
button on its status screen, which is running in the foreground and therefore allowed. Or grant it over
adb and never think about it again.

Once the overlay permission *is* granted, everything else — `install`, `accessibility` — opens fine from
Home Assistant with the app in the background.

### Diagnosing "the fix button does nothing"

| Property      | Value                     |
| ------------- | ------------------------- |
| Path:         | /permissions/diagnose     |
| Method:       | GET (or POST)             |

Since 0.9.0, and the first thing to attach to a bug report — no adb or logcat needed:

```json
{
  "sdk": 30,
  "device": { "model": "Smart TV", "manufacturer": "TCL", "android": "11" },
  "backgroundLaunchExempt": true,
  "activityVisible": false,
  "deviceAdminSupported": false,
  "screens": {
    "overlay": {
      "granted": true,
      "action": "android.settings.action.MANAGE_OVERLAY_PERMISSION",
      "resolvedActivity": "com.android.tv.settings.device.apps.specialaccess.SystemAlertActivity",
      "placeholder": false,
      "fixable": true,
      "adb": "adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow"
    }
  },
  "lastFix": { "what": "overlay", "ok": false, "activity": null, "error": "…", "secondsAgo": 42 }
}
```

Read it as: `resolvedActivity` null means nothing handles that intent *or* the platform hides it from
the app; `placeholder: true` means a vendor stub answered and a button would do nothing;
`backgroundLaunchExempt: false` means no launch can happen at all right now (see above); and `lastFix`
says how the previous attempt actually ended. The Home Assistant integration includes this block in its
diagnostics download.

Note on `resolvedActivity`: from Android 11 on, `resolveActivity()` is a *query* and queries are
filtered by package visibility, while `startActivity()` is not — "I cannot see it" is not "it is not
there". The app declares these intents in `<queries>` so it can see them, and treats an unresolved
intent as worth trying rather than impossible. A device whose `forceQueryable` list omits Settings
would otherwise hide a perfectly working button (`adb shell dumpsys package queries` shows that list). Note `deviceAdmin: null` on the last two: those platforms have no device
administration at all (`hasSystemFeature(FEATURE_DEVICE_ADMIN)` is false) — a different answer from
"not granted", and worth distinguishing because `dpm set-active-admin` reports `Success` there
anyway.

### Settings

| Property      | Value                               |
| ------------- | ----------------------------------- |
| Path:         | /settings[?updateChecks=true\|false] |
| Method:       | GET or POST                         |

Since 0.24.0. Persistent device settings, kept across restarts and updates. `updateChecks` (default
`true`) switches the twice-daily GitHub release check on or off: a TV kept off the internet on purpose
then makes no outbound calls. Both methods answer the current values, e.g. `{"updateChecks":false}`.

Two more keys:

- `webhook`: an `http(s)` URL. The app POSTs its `/state` JSON plus an `event` field to it on every
  change: `popup_shown` (with `shownId`), `popup_replaced` (with `shownId` and
  `replacedId`, both the id that was redrawn), `popup_removed` (with `reason`
  expired / cancelled / button / back / watchdog, and `removedId`), `started`, `screen_on`,
  `screen_off`, `permissions`, `volume_changed` and `control` (after a launch, key or volume call;
  since 0.29.0), and `settings` right after the webhook is set. A failed POST is
  retried once after 2 s. Empty turns push off. `/settings` answers only whether one is set: the URL
  is the controller's secret. `/state.push` shows the last push result.
- `updateSource`: `github:<owner>/<repo>` (default `github:mhoogenbosch/PiPup`), or an `http(s)` folder
  URL holding `releases.json` (GitHub's `/releases` answer saved as is) and the APKs under their
  release names. The first release that is neither draft nor prerelease wins. Empty resets to the
  default. A different source cannot slip in a foreign app: Android only installs an update signed
  with the same key as the installed PiPup.

Like the rest of the API, `/settings` has no authentication: anything on the LAN can change it. Keep
the TVs on a network you trust.

### State

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /state           |
| Method:       | GET (or POST)    |

Returns the current state as JSON:

```json
{
  "app": "PiPup",
  "version": "0.7.0",
  "id": "6f1f9c1e-4a3f-4a44-9d2c-6f1f9c1e4a3f",
  "name": "FireTV Veranda",
  "visible": true,
  "screenOn": true,
  "popupsShown": 12,
  "watchdogCleanups": 0,
  "uptime": 86400,
  "device": { "model": "AFTKA", "manufacturer": "Amazon", "android": "9" },
  "popup": { "id": "doorbell", "duration": 0, "indefinite": true, "elapsed": 42 },
  "popups": [
    { "id": "fantasy", "position": "TopRight", "duration": 0, "indefinite": true, "elapsed": 900, "media": "web" },
    { "id": "doorbell", "position": "BottomRight", "duration": 0, "indefinite": true, "elapsed": 42, "media": "video" }
  ],
  "power": { "canWake": true, "canSleep": true, "sleepMethod": "device_admin" },
  "control": { "launch": true, "keys": ["play_pause", "play", "pause", "stop", "next", "previous",
               "rewind", "fast_forward"], "lastLaunched": "com.netflix.ninja", "lastLaunchedSecondsAgo": 300 },
  "volume": { "level": 47, "step": 7, "max": 15, "muted": false, "fixed": false },
  "permissions": {
    "overlay": true, "installPackages": true, "autoStart": null,
    "deviceAdmin": true, "accessibility": false, "complete": true,
    "fixable": { "overlay": false, "install": true, "admin": false, "accessibility": false }
  }
}
```

Since v0.29.0 `control` lists what [TV control](#tv-control) can do here, and `volume` the media volume
(`fixed: true` = it belongs to the TV set or amplifier).

Since v0.24.0 `popups` lists every popup on screen in stack order (the last one is on top; `id` is
`null` for the popup sent without an id). `visible` is true while any popup is up, and `popup` is the
one on top, so a caller written for one popup at a time keeps working.

Since v0.7.0 `permissions` reports what the app was actually granted, and `power` what it can do with
the screen (see [Screen on/off](#screen-onoff)). `overlay: false` is the one to watch: popups are then
accepted with HTTP 200 and stay invisible. `autoStart` is TCL's vendor app-op and is `null` on every
device that does not have it — that is "not applicable", not a problem. `complete` is the short answer
to "can this device show popups at all".

Since v0.5.0 the response also contains `lastPopup`: the parameters of the last *received* popup
(id, position, duration, muted, media type/size, tts, buttons, secondsAgo) — it survives dismiss/expiry,
so you can always verify what your home-automation actually sent. The same block is rendered live on
the app's status screen on the TV.

Since v0.2.3 `/state` also reports whether the screen is on/interactive (`screenOn`), the number of
popups shown since the service started (`popupsShown`), the service uptime in seconds and basic
device info — all surfaced as entities by the Home Assistant integration. Since v0.2.5 it also
reports a stable device `id` (generated once, survives app updates) and the device `name`; since
v0.2.6 `watchdogCleanups` counts how often the overlay watchdog had to force-remove a stale popup.

### Discovery

Since v0.2.5 the app advertises itself over mDNS/zeroconf as `_pipup._tcp` (port 7979) with TXT
records `id` (the stable device id), `name` and `version`, enabling automatic discovery.

## Building

CI builds an APK on every push (see `.github/workflows/build.yml`); tagged releases get the APK
attached automatically. Locally: JDK 17 + Android SDK 35, then `./gradlew assembleDebug`.

## License

[MIT](LICENSE) for the work in this fork. The original PiPup by rogro82 was published without a
license; its code remains his.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) — every version also has a
[GitHub release](https://github.com/mhoogenbosch/PiPup/releases) with the full story and the APK.
