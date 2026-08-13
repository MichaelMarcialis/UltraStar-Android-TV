# UltraStar Android TV — Project Brief

A minimal, purpose-built karaoke scoring app for a single specific Nvidia Shield TV Pro. Not a general-purpose product — scoped tightly to one household's exact hardware.

## The goal

A karaoke game that tracks and scores singers' pitch accuracy in real time (Karaoke Revolution / SingStar style), using either an existing library of community UltraStar-format songs or the user's own. Core loop: pick a song, scroll lyrics + a pitch bar in sync with audio/video playback, capture mic input, score against the reference melody.

## Why this project exists (context for decisions below)

The user tried UltraStar Play (the official Android TV port of UltraStar) on this exact Shield TV Pro first. It had four serious problems:

1. **Settings don't persist** — song folder path and other config reset on every app launch.
2. **UI not TV-friendly** — requires scrolling, elements land off-screen. Traced to a maintainer-acknowledged issue: a Unity dependency was stripped from the current Android build to fix a crash, which broke UI Toolkit styling/translations across the app.
3. **D-pad navigation barely works** — the UI is clearly touch-first. There's an opt-in "gamepad navigation" setting in Play, but it was itself flagged as buggy on some devices, not a reliable fix.
4. **Mic tracking fails in actual gameplay** — both wired mics test fine individually in Settings, but during a real song, one player's mic isn't captured at all. Originally attributed to a long-standing cross-platform UltraStar Play bug. **Since diagnosed as a Shield firmware problem, not Play's fault** — this device's USB audio HAL can't open these mics at all, so anything routed through Android's audio stack fails. See the mic section below; it's solved, but only by bypassing that stack entirely.

Researched mainstream "karaoke apps for smart TV" (Smule, StarMaker, Singa, Karaoke Anywhere) as alternatives — ruled out. They're subscription services built around licensed catalogs and vocal effects/social features, not open pitch-scoring against a user-provided library.

**Decision: build fresh rather than fork UltraStar Play.** Forking would inherit Play's actual bugs (the above), embedded in a large, unfamiliar Unity/C# codebase, on top of the user having zero prior Android development experience. A scoped-down native app avoids inheriting those specific bugs and can be dramatically smaller than Play's full feature set.

## Explicitly out of scope

- Song editor
- Cross-platform support (desktop/mobile) — Shield TV Pro only, single device
- Multi-language UI
- Anything from UltraStar Play's actual codebase — reuse ideas/formats, not code
- A phone/tablet companion mic app — was the planned fallback for two-player input, now unnecessary since two wired USB mics are working. The user's kids have no phones, so this was never desirable anyway.

## What to reuse from the UltraStar ecosystem (formats/ideas, not code)

- **Song format**: standard UltraStar `.txt` song folders (lyrics + pitch + timing data as plain text, paired with an audio file and a background video or image). Write an original parser for this format — it's simple, well-documented, and gives access to the entire existing community song library without touching Play's code.
- **Pitch detection**: the **YIN** algorithm (de Cheveigné & Kawahara, JASA 111(4), 2002) — the same method UltraStar and most karaoke scorers use. Implemented originally in `pitch/Yin.kt` from the published paper. TarsosDSP was the obvious library here and was used and verified first, but it's GPL-3.0 and isn't on Maven Central (only the author's personal repo), and this repo may be opened up publicly — so it was replaced with ~90 lines of original code. **Don't reintroduce a GPL audio library.** Note the algorithm itself is not the licensed part; only implementations are.
- **UI toolkit**: **Compose for TV** (`androidx.tv:tv-material`, `androidx.tv:tv-foundation`), not the older Leanback library. Leanback is now officially deprecated by Google in favor of Compose for TV. Note the historical naming gotcha: the Android manifest category for "show this app on the TV home screen" is still literally named `LEANBACK_LAUNCHER` for legacy reasons, even though it has nothing to do with the deprecated Leanback UI library — every TV app, Compose or not, still uses that exact category name.

## Mic input — SOLVED, but not the way you'd expect (read before touching audio)

**Do not use `AudioRecord` / `AudioManager` for mic input on this device. It cannot work.**

This Shield's USB audio HAL (`modules.usbaudio.audio_hal`) fails to profile *any* USB audio device, in *either* direction, with `adev_open_input_stream fail - cannot read profile` → `checkInputsForDevice: No input available`. It fires automatically on plug-in, before any app touches the device, so the mic never enters `AudioManager.getDevices()` and `AudioRecord` can never target it.

Ruled out exhaustively (2026-08-07 → 08-09): the USB hub, power, cabling, defective units, the user's debloat, firmware version (already latest), the stock remote being awake, capture-only vs duplex devices, SELinux, device-busy, the `usb_audio_automatic_routing_disabled` setting, and forcing a re-probe. Three mic products from three manufacturers all fail identically. USB audio *output* is broken too — confirmed by ear. The kernel is fine: `snd-usb-audio` binds and creates correct PCM nodes every time; the breakage is purely in Nvidia's userspace HAL, and it is unreachable without root.

**This almost certainly explains UltraStar Play's bug too** — Play's mics "tested fine in Settings" but failed during songs because gameplay capture went through this same broken HAL.

**The working architecture: bypass Android's audio stack entirely via the USB Host API.** Kotlin does permission → `openDevice()` → `claimInterface(force=true)` (which detaches the kernel's `snd-usb-audio`) → `setInterface(alt=1)`, then a small C layer pumps isochronous URBs over usbfs. No libusb, no root. Verified live: **two mics simultaneously at 93 KB/s each** (vs 93.75 theoretical) with independently responding levels.

**Two wired mics work — the phone-as-second-mic fallback is obsolete, drop it.** Implementation in `mic/`, harness in `diagnostics/IsoCaptureScreen.kt`.

Gotchas if you touch this code:
- The JNI symbol names in `cpp/usb_iso.c` encode the Kotlin package — moving `UsbIsoNative.kt` silently breaks it at runtime only.
- usbfs derives each iso packet's buffer offset by summing the *requested* lengths, so packet *p* sits at `p * maxPacketSize` when all packets request the max.
- usbfs signals URB completion as **POLLOUT**, not POLLIN.
- `struct usbdevfs_urb` ends in a flexible array member — size is `sizeof(urb) + packets * sizeof(iso_packet_desc)`.
- Android's `UsbInterface` does **not** expose class-specific descriptors; channel count / bit depth / sample rates only come from parsing `UsbDeviceConnection.rawDescriptors`.
- A USB mic reports `mHasAudioPlayback=true` even with no speaker — that's Android misreading the UAC "Output Terminal" (type `0x0101` = USB Streaming, i.e. audio *to* the host). It is not a duplex device.

**Mic audio format** (Let's Sing 2024, `046d:0a03`, parsed from raw descriptors): UAC 1.0, **mono, 16-bit signed little-endian**, supports 48000/44100/22050/11025/8000 Hz, endpoint `0x82` isochronous asynchronous, `wMaxPacketSize` 208, `bInterval` 1. Both mics share this VID:PID — tell them apart via `UsbDevice.getDeviceName()`, which is unique per port.

## Hardware and environment

- **Device**: Nvidia Shield TV Pro (2019 model). Confirmed via on-device Settings: Android version **11** (API level 30), security patch Jan 5 2026, build `RQ1A.210105.003`. ("SHIELD Android TV SW Version" 9.2.4 is Nvidia's own firmware layer on top of Android — a different number, not the OS version, don't confuse the two.)
- **minSdk 30 / targetSdk 37 / compileSdk 37**. minSdk matches the device exactly, so there's no backward-compatibility hedge to maintain — use any API 30-era API freely. (targetSdk 37 does mean newer-API behaviour changes still apply where the device supports them, e.g. `PendingIntent` mutability flags.)
- **Reboot with USB peripherals unplugged.** The Shield hangs on the Nvidia boot logo if the hub/mics/SD adapter are attached at power-on — reproduced three times, resolved every time by unplugging. Attach peripherals *after* it reaches the launcher. Almost certainly the bootloader's minimal USB stack choking on hub topology or inrush current.
- **Storage**: 1TB microSD card in a USB adapter, plugged into one of the Shield's two USB 3.0 ports, set up as Removable Storage. This is where the song library lives.
- **Microphones**: two wired USB mics (from Let's Sing 2024, PS5 version) — standard class-compliant USB audio devices, connected through a powered USB hub in the Shield's other USB port.
- **TV**: LG OLED. CEC handoff between the Shield and this TV has historically been unreliable — don't assume Shield-remote control of TV-level settings; the user may need the LG remote directly for anything TV-side.
- **Launcher**: Shield runs Projectivy (a third-party Android TV launcher replacing the stock home screen). Confirmed this doesn't affect anything architecturally — it reads the same standard `LEANBACK_LAUNCHER` manifest category every other TV launcher does, regardless of UI toolkit used internally.

## Dev environment

- Windows 11 PC, Git for Windows, Claude Code CLI run from Android Studio's terminal.
- Android Studio, SDK Platform 30 + Build-Tools, **NDK 30.0.15729638**, CMake (AGP auto-installs 3.22.1 to satisfy `cmake_minimum_required`).
- Project: **"UltraStar Android(TV)"**, package `com.example.ultrastarandroidtv`, Kotlin, Android TV template.
- Key Gradle config: Compose BOM `2026.06.00`, `androidx.tv:tv-material`, the **Compose Compiler Gradle plugin** (`org.jetbrains.kotlin.plugin.compose`, version-locked to Kotlin `2.2.10` — mandatory since Kotlin 2.0, and its absence causes a confusing compiler ICE rather than a clear error), `buildFeatures { compose = true }`, `externalNativeBuild` → `cpp/CMakeLists.txt`, `ndkVersion`, and `abiFilters = ["arm64-v8a"]` (the Shield is arm64-only, so don't build other ABIs).
- **Dependencies come from Maven Central and Google only** — no custom repositories, and every runtime dependency is permissively licensed. This is deliberate: the repo may be opened publicly, and a personal Maven host is a durability risk (it can vanish and break builds years later) as well as a licence question. Check both before adding anything.
- Deploys over network ADB (`adb connect <shield-ip>:5555`). The connection drops occasionally mid-install with `InstallException: EOF` — just `adb disconnect` + `adb connect` and retry, it's not a code problem.
- Build/test from the CLI needs `JAVA_HOME` set to Android Studio's bundled JBR: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

## Current state

**Done:**
- **Song parser** (`song/`) — full UltraStar `.txt` parsing: headers, note types (normal/golden/freestyle/rap/golden-rap), line breaks, duets (`P1`/`P2`), comma-decimal values, BOM handling, plus beat↔time conversion. Covered by JVM unit tests (`./gradlew testDebugUnitTest`). Known gap: `#RELATIVE:YES` beat offsets are not implemented (rare, deliberately deferred).
- **USB mic capture** (`mic/` + `cpp/usb_iso.c`) — see the mic section above. Two mics simultaneously, confirmed on hardware.
- **Pitch detection** (`pitch/`) — `Yin.kt` is an original YIN implementation (no third-party DSP dependency; see the reuse section above). `PitchTracker` runs it, one instance per mic, fed straight from `UsbIsoCapture`'s callback: it buffers the ragged USB chunks into 2048-sample windows sliding by 1024 (~47 readings/s, 43 ms latency, allocation-free in steady state). Three gates before a reading counts as voiced — window RMS, YIN confidence, and a 65–1200 Hz range that bounds the lags searched rather than filtering after. Plus `Pitches.kt` (Hz↔MIDI, note names, cents). Unit tests cover pure tones, sawtooth/square waves and a missing fundamental (the cases where octave errors actually happen — pure sines prove nothing there), noise, and silence. **Verified on hardware** with a 4-octave reference sweep (A2/A3/A4/A5 read back exactly, no octave errors), both mics live at 45–47 readings/s each, 100% voiced on sustained notes, 0% on silence.

**Measured CPU baseline** (both mics capturing + pitch + the diagnostic screen): 72–76% of *one* core of four. Split per-thread, roughly 60% of that is the two capture/pitch threads and 40% is the diagnostic UI redrawing. So pitch detection for two mics costs ~45% of a core. Fine as-is, but it is the number to re-check once video playback and the real gameplay UI are added. **If it ever needs to come down, the lever is downsampling before YIN** — voices top out near 1200 Hz, so 48 kHz is enormously oversampled for this; decimating to ~12 kHz shortens both the window and the lag range for roughly an order of magnitude less work. (An FFT-based difference function is the other option, but a bigger change for less gain.)

**Not started:** song library scanning from the USB drive (SAF + persisted URI permissions), TV browse UI, playback engine (Media3/ExoPlayer synced to parsed timing), scoring, two-player gameplay UI, settings.

**Next up:** scoring — comparing `PitchTracker` output against parsed `Note.pitch` over each note's beat window. Note that UltraStar scores **octave-agnostically** (pitch class, i.e. `mod 12`), so resolve the format's pitch-to-MIDI reference offset when starting this; nothing so far depends on it.

Deliberately not done in the pitch layer: no smoothing/median filter over readings. Raw YIN proved stable enough on the real mics that adding one would only hide information. Revisit only if scoring shows dropouts.

Note `MainActivity` currently launches `diagnostics/IsoCaptureScreen.kt`, which is a test harness, not real UI.
