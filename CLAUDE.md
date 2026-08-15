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
- Deploys over network ADB (`adb connect <shield-ip>:5555`). The connection drops occasionally mid-install with `InstallException: EOF` — just `adb disconnect` + `adb connect` and retry, it's not a code problem. If the device shows as `unauthorized`, the Shield is waiting on its "Allow USB debugging" prompt.
- **`export MSYS_NO_PATHCONV=1` before any adb command with a device path.** Git Bash rewrites `/sdcard/foo` into `C:/Program Files/Git/sdcard/foo`, and `adb push` then fails with a baffling `remote secure_mkdirs failed`. Costs an hour if you don't know it.
- **The whole TV UI can be driven from here**, which is how the song library and the document picker were tested without asking anyone to pick up a remote:
  - `adb exec-out screencap -p > shot.png` then read the image — the fastest way to see what is actually on the TV.
  - `adb shell uiautomator dump /sdcard/ui.xml` gives the view tree with `focused="true"` and `bounds`. Use it rather than guessing: several TV widgets show no visible focus ring, so a screenshot alone will convince you focus is lost when it isn't.
  - **`input tap` coordinates are in 1920x1080, but `screencap` returns 3840x2160.** Halve screenshot coordinates or taps land off-screen and silently do nothing. Take bounds from the uiautomator dump instead, which is already in input space.
  - The Shield sleeps on its own; `adb shell input keyevent KEYCODE_WAKEUP` before a test run. A sleeping display stalls anything driven by frame callbacks.
- Build/test from the CLI needs `JAVA_HOME` set to Android Studio's bundled JBR: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

## Current state

**Done:**
- **Song parser** (`song/`) — full UltraStar `.txt` parsing: headers, note types (normal/golden/freestyle/rap/golden-rap), line breaks, duets (`P1`/`P2`), comma-decimal values, BOM handling, plus beat↔time conversion. Covered by JVM unit tests (`./gradlew testDebugUnitTest`). Known gap: `#RELATIVE:YES` beat offsets are not implemented (rare, deliberately deferred).
- **USB mic capture** (`mic/` + `cpp/usb_iso.c`) — see the mic section above. Two mics simultaneously, confirmed on hardware.
- **Pitch detection** (`pitch/`) — `Yin.kt` is an original YIN implementation (no third-party DSP dependency; see the reuse section above). `PitchTracker` runs it, one instance per mic, fed straight from `UsbIsoCapture`'s callback: it buffers the ragged USB chunks into 2048-sample windows sliding by 1024 (~47 readings/s, 43 ms latency, allocation-free in steady state). Three gates before a reading counts as voiced — window RMS, YIN confidence, and a 65–1200 Hz range that bounds the lags searched rather than filtering after. Plus `Pitches.kt` (Hz↔MIDI, note names, cents). Unit tests cover pure tones, sawtooth/square waves and a missing fundamental (the cases where octave errors actually happen — pure sines prove nothing there), noise, and silence. **Verified on hardware** with a 4-octave reference sweep (A2/A3/A4/A5 read back exactly, no octave errors), both mics live at 45–47 readings/s each, 100% voiced on sustained notes, 0% on silence.

- **Scoring** (`score/`) — `PlayerScorer` consumes timestamped `PitchReading`s against one `VoicePart` and produces a `ScoreSnapshot` (note points / golden bonus / line bonus, adding to 10000, plus hit counts). Pure JVM logic, no Android. Covered by 26 unit tests including an end-to-end pass over real parsed song text; three deliberate mutations were each caught by the test written for them.
  - **Scores per beat, not per reading** — each beat is judged by sampling the singer once at its midpoint. This is the load-bearing decision: it keeps the maximum a property of the song alone, so a dropped USB packet or a change to the analysis hop size can't change what a perfect performance is worth.
  - The beat takes whichever of the two readings *bracketing* its midpoint is nearer. Taking simply the first reading after the midpoint means a one-beat note can be scored from audio belonging to the note after it.
  - A beat whose nearest reading is more than `maxHoldSeconds` (default 100 ms) away scores as unsung rather than reusing stale audio — otherwise a capture stall would score beats nobody sang.
  - Pitch offset resolved: **UltraStar pitch 0 = C4 = MIDI 60**. Sanity check: songs use roughly −20..20, which maps to E2..G#5. Scoring can't get this wrong regardless, since it compares `mod 12` and 60 is a multiple of 12 — the offset only affects display and note height.
  - `NoteScore` keeps the sung fractional MIDI **per beat** (NaN = nothing sung), which is what a pitch bar draws as the singer's trace, and what distinguishes "sang nothing" from "sang the wrong note".

- **Playback engine** (`playback/`) — Media3/ExoPlayer 1.11.0 (Apache-2.0, Google's Maven). `SongPlayer` wraps the player; its real job is `SongClock`.
  - `SongClock` exists because `ExoPlayer.getCurrentPosition()` is callable only from the player's own thread and updates in coarse steps, while ~47 readings/s arrive on capture threads that must not touch the player. The owner re-anchors it once per frame; everyone else extrapolates with `System.nanoTime()` off a single volatile immutable time base, lock-free.
  - **It never runs backwards while playing.** Small backwards corrections are treated as jitter and ignored, since the scorer's cursor can't rewind. On backwards jitter the time base is left *untouched* rather than rewritten with the value it already yields — re-anchoring recomputes the same instant from a new origin and can land a bit lower in the last place, which a reader sees as the song stepping back. `lastCorrectionSeconds` publishes the jitter so it stays a measurement, not an assumption.
  - **Sync is one number for scoring, but two for drawing** (`SyncCalibration`). Output and capture+analysis latency sit on the same path, so a *reading* describes `playerPosition(now) − total` (`songTimeFor`). Anything *on screen* must use the output share alone (`heardSongTimeFor`), since that is how far behind the player the singer's ears are. Both are always *subtracted* — they describe the past, and flipping the sign doubles the error instead of removing it.
  - `LatencyProbe` measures that number by listening for tones at known song positions; median, so one masked tone can't move it.

- **Song library** (`library/`) — the user picks a folder once with `ACTION_OPEN_DOCUMENT_TREE`; `LibraryLocation` takes a **persistable** grant and re-checks it against `persistedUriPermissions` on every launch, so a stale stored URI becomes "pick the folder again" rather than a permission error deep in a read. **This is the fix for problem #1** — the setting Play lost on every launch.
  - No required folder, and songs must *not* live under `Android/` (which the scan skips, along with `LOST.DIR`, `System Volume Information` and dot-folders). The card's `UltraStar/` folder is what is granted today.
  - `SafDocumentTree` queries the content resolver directly rather than using `DocumentFile`, which asks the provider a fresh question per attribute per file. One query per folder instead of dozens of cross-process round trips.
  - `SongLibraryScanner` is written against a narrow `DocumentTree` interface, so all the scanning rules are unit-tested against an in-memory fake instead of needing a device and a granted folder. Each `.txt` is its own song (duet variants sit beside originals); media headers are matched by file name only, case-insensitively, because the paths inside them describe someone else's machine; a broken song is collected into `failures` and the walk carries on.
  - `SongTextDecoder`: BOM if present, else strict UTF-8, else Windows-1252 (**not** ISO-8859-1 — they differ exactly at `0x80`–`0x9F`, which is where the curly quotes and dashes in real song titles live).

- **Gameplay UI** (`game/`) — the game itself, verified on hardware in both layouts. `GameSession` owns the player, the mics and a `PlayerScorer` per singer; `NoteTrack` draws; `GameTheme` holds every colour and dimension in one file so the look can be tuned without reading drawing code.
  - **The layout follows the song, and it is one component either way.** A solo song is one track with a trace per singer (directly comparable — you see who is closer); a duet is a track each. Tracks are built from the song's *voice parts*, not from the mics, so a duet still shows both lines when only one mic is plugged in. Welding the lyrics to the scrolling notes is what made a track self-contained enough for one component to serve both.
  - **Karaoke Revolution-style scrolling marquee**, chosen by the user over the per-line static board UltraStar and SingStar use. Notes flow right-to-left past a fixed playhead at 30% width — most of the screen is lookahead, because the note *coming* matters more than the one just sung. Syllables scroll welded to their own notes, so **a rest is a real gap on screen** and the rhythm is read as spacing rather than inferred.
  - **The drawn pitch range has to follow the passage** (`PitchRange`). Scaling to the whole song looked obviously right and was obviously wrong on the TV: "Free" spans 22 semitones (pitch 3–25, both used repeatedly — not an outlier), so every phrase was squashed into a third of the height. The range now eases toward the visible notes with a time constant, against *song time* rather than per frame, so a dropped frame does not change the motion.
  - **Strict time-proportional lyrics are unreadable on real songs** (`LyricLayout`). At 5 s across the screen, fast syllables get roughly a quarter of the room their text needs — the TV showed "Butsomething" and "Byougivemehe". Syllables may now be pushed **later, never earlier**, and never more than `maxLagSeconds` from their own note. Offsets are precomputed for the whole song in absolute coordinates: a per-frame layout would depend on which syllable was first visible and the text would jitter as notes scrolled in.
  - **Do not restart the syllable chain at each lyric line.** It looks tidier and reads worse — it lets a new line land on the pushed tail of the last one, which is the most visible collision there is, right where a new phrase starts. The per-syllable cap already bounds drift, so the reset bought nothing. Both the fix and the trap are pinned by tests.
  - `NoteScore` now keeps **per-beat hit flags**, so the note lights up exactly the beats that were paid for. Re-deriving that at draw time would put the tolerance rule in two places, and the day they disagreed the bar would quietly start lying about the score next to it.
  - The sung trace is **folded into the target note's octave** for drawing. Scoring compares pitch classes, so an octave-displaced hit scores — drawing it where it was literally sung would put the trace off the track while the score went up.
  - Threading: each scorer has exactly one writer (its own mic's capture thread) so **the audio path takes no lock**. The draw pass reads live `NoteScore`s while they are written; those are single 32-bit fields, so a frame can be one write behind but can never see half a value. Score readouts are only republished when the number actually changes — writing them every frame is what made the diagnostic screens expensive.
  - **Left/right on the D-pad tunes the visible window live** (2–12 s, default 5 s). Narrowing it spreads syllables out and costs lookahead; that trade is much easier to judge with a song playing than in the abstract.
  - `SongLauncher` is a deliberately plain list in front of the game — a way in, not the browse UI.

**Sync calibration harness** — `assets/calibration.{wav,txt}` is a real UltraStar song of 16 tones at exactly 1.0–16.0 s, alternating A4/E4, generated and verified to the millisecond. Uncompressed **on purpose**: an MP3/AAC decoder's priming delay would land in the measurement and be indistinguishable from real output latency. Point a mic at the speaker and the mic hears the song itself, so the lag from a tone's written position to its detection *is* the round trip. `diagnostics/SyncCalibrationScreen.kt` loops it, adopting the measurement at the end of each pass and scoring the next one with it — a correctly calibrated system scores near 10000, because the "singer" is the song, exactly on pitch and in time.

**Measured CPU: the real game costs 39–41% of one core of four** (both mics capturing + pitch + the full gameplay UI drawing at 60 Hz). That is *less* than the diagnostic screen it replaced, because the game draws in a single `Canvas` and only recomposes the score readouts when the number changes, rather than rebuilding a column of `Text` every frame. Plenty of headroom for video.

**Earlier CPU baseline** (both mics capturing + pitch + the diagnostic screen): 72–76% of *one* core of four. Split per-thread, roughly 60% of that is the two capture/pitch threads and 40% is the diagnostic UI redrawing. So pitch detection for two mics costs ~45% of a core. Fine as-is, but it is the number to re-check once video playback and the real gameplay UI are added. **If it ever needs to come down, the lever is downsampling before YIN** — voices top out near 1200 Hz, so 48 kHz is enormously oversampled for this; decimating to ~12 kHz shortens both the window and the lag range for roughly an order of magnitude less work. (An FFT-based difference function is the other option, but a bigger change for less gain.)

**Promised, not yet built — song video behind the gameplay.** The user explicitly wants this in the final product and agreed only to defer it, on the condition that it comes back. Do not quietly drop it. The first build is a flat dark background on purpose: community song videos vary wildly in brightness, and getting the game readable first was the safer order. The scanner already resolves `#VIDEO` (`ScannedSong.videoId`), and songs on the card do carry `.mp4` files. What it needs: a video surface behind the tracks, `#VIDEOGAP` honoured, and a scrim heavy enough that the lyrics stay readable over the worst-lit video in the library.

**Not started:** TV browse UI, settings, song video (see above).

**Next up:** the design pass on gameplay, with the user — the mechanism is proven on hardware, so what is left there is judgement rather than plumbing. Then video, then the browse UI.

**The library is verified on the real card.** All 8 songs under `UltraStar/` on the SanDisk parse and resolve their audio: 8 playable, 0 broken, in 0.8 s. The grant survives a force-stop and relaunch with no prompt. Titles carrying `’` (U+2019) come through intact.

- **Scan cost is ~100 ms per song**, dominated by SAF I/O rather than parsing. Fine for 8; a 500-song library would take ~50 s, which is when the incremental `onSong` callback (already wired into the screen) starts to matter and caching parsed metadata by document id becomes worth building. Measured, not assumed — do not add a cache before the library is big enough to need one.
- **The document picker *is* D-pad navigable** on this Shield, which was the real risk in choosing SAF for a TV app. Verified by driving it entirely over adb.
- Granting a volume *root* is refused by Android 11 ("To protect your privacy, choose another folder"), so the folder picked has to be one level in — `UltraStar/`, not the drive itself.
- Scanner edge cases were verified against a synthetic library on the device before touching the real one: Windows-1252 and UTF-8-BOM files, a `#MP3:` header with a full Windows path and different case, two songs in one folder, a song whose audio is missing, a malformed song, a readme, and skipped junk folders. All behaved.

**The whole chain is verified end to end on hardware.** Playing the calibration song with a mic at the speaker, the first pass scores **9766–9922 / 10000 (125–127 of 128 beats)** using the shipped 127 ms default. The "singer" is the song itself, so a correct system has to score near the maximum — that number is the proof that playback, capture, pitch detection, timing and scoring all agree.

**Measured latency: 127 ms round trip**, from four runs whose medians landed within 3 ms (124–132). This is now `SyncCalibration.DEFAULT_LATENCY_SECONDS`: a measured default, not a guess, because the app targets exactly one device and one TV.

Findings worth keeping:
- **Most of the 127 ms is Dolby.** The Shield re-encodes to E-AC3 for HDMI (`AudioOut_3D5`, type DIRECT) and the TV decodes it again. Switching the Shield's audio output away from Dolby would cut most of it — re-run the calibration if that or the TV ever changes.
- **The split matters for what is drawn.** Scoring subtracts the whole 127 ms; lyrics and the pitch bar must subtract only the *output* share (~106 ms), because that is how far behind the player the singer's ears are. Rendering at the raw player position would push singers early and then score them for it. See `heardSongTimeFor` vs `songTimeFor`.
- **The first note after playback starts is ~65 ms late**, every time (samples like `[192, 134, 121, 122, 130, …]`). The audio pipeline is still re-syncing. A median absorbs it, which is why the probe uses one — but expect the very first note of a real song to be judged slightly late.
- Residual onset jitter is about ±15 ms, roughly one analysis hop plus room acoustics.
- **Frame-driven loops stall when the display sleeps.** `withFrameNanos` simply stops resuming, so playback ran on with nothing advancing the game — this looked exactly like a hang. `MainActivity` now sets `FLAG_KEEP_SCREEN_ON`, which gameplay needs regardless.

`PlayerScorer` is not thread-safe and capture runs on its own thread. Timestamp each reading where it is produced, then drive `update()` and read `snapshot()` from one thread — or take a lock, as `SyncCalibrationScreen` does, since its per-pass teardown races the capture threads.

Deliberately not done in the pitch layer: no smoothing/median filter over readings. Raw YIN proved stable enough on the real mics that adding one would only hide information. Revisit only if scoring shows dropouts.

Deliberately not done in scoring: no seeking. The scorer walks its notes with a forward-only cursor, so a practice mode that jumps around a song needs `reset()` and a rebuild, not a rewind.

`MainActivity` launches `game/SongLauncher.kt`, which is the real app. The diagnostics are still there and still useful — `diagnostics/SyncCalibrationScreen.kt` (latency, and the end-to-end proof), `diagnostics/IsoCaptureScreen.kt` (mic capture and pitch alone), `diagnostics/SongLibraryScreen.kt` (scanning). Swap the one line in `MainActivity` to run one. They all open their mics through `mic/UsbMicSession.kt`, which owns the asynchronous USB permission dance.

**The song library has no duets in it**, so the split layout has nothing real to exercise it. `tools/make_duet_test.py` deals any song's lyric lines alternately to P1 and P2 and writes a `.txt` beside it that shares the original's audio — the two parts then genuinely differ in notes, lyrics and beat count, which is the thing worth testing. That is how the split layout was verified; the generated file was pushed to the card, checked, and removed again rather than left cluttering the library.
