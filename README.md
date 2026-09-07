# UltraStar Android TV

A karaoke game for Android TV that scores singers on pitch, in real time, against the community
UltraStar song format — lyrics scroll, notes scroll, you sing, it marks you out of 10000.

It reads standard UltraStar `.txt` song folders, so it works with the whole existing library of
community songs, and it can search and download new ones without leaving the sofa.

**It is built for exactly one television and one set-top box.** Please read
[Will it work on your device?](#will-it-work-on-your-device) before installing — the answer may
well be no, and the reasons are specific.

---

## Contents

- [What it does](#what-it-does)
- [Will it work on your device?](#will-it-work-on-your-device)
- [What you need](#what-you-need)
- [Installing it](#installing-it)
- [First run](#first-run)
- [Songs, and where they come from](#songs-and-where-they-come-from)
- [Legal notice](#legal-notice)
- [Settings worth knowing about](#settings-worth-knowing-about)
- [Building from source](#building-from-source)
- [Feedback and bug reports](#feedback-and-bug-reports)
- [Credits and licence](#credits-and-licence)

---

## What it does

- **Real-time pitch scoring** for one or two singers, using the YIN pitch-detection algorithm.
- **A scrolling note track** in the Karaoke Revolution style — notes flow right to left past a
  fixed sing line, with your pitch shown as an arrow that tilts when you are flat or sharp, and
  sparks where the arrow meets the note.
- **Solo, duet and versus.** A duet song shows both parts on their own tracks; versus puts two
  singers on the same part and keeps two scores.
- **Music video playback** where a song has one, and an original full-screen audio visualiser
  where it does not.
- **Stars, praise and high scores** — results are stars rather than a percentage, records are kept
  per song, and the name that set one is shown on the song's card.
- **Songs are levelled to one loudness**, so nobody reaches for the remote between tracks.
- **A song's timing is checked against its own recording** when it is downloaded, and a chart that
  is simply out of time is corrected automatically. One that was written against a different
  recording is left alone and reported, because no offset can fix it.
- **In-app song search and download** from [USDB](https://usdb.animux.de/), using your own account.
- **Your LG television is switched into game mode** while the app is open, and put back when it
  closes, if you let it. This cuts the display lag that makes karaoke feel late.
- **Settings persist.** Sounds obvious. It is the reason this project exists.

Everything runs on the device. Nothing is uploaded, nothing phones home, there is no account with
us because there is no us, and the only network traffic is fetching songs you asked for.

## Will it work on your device?

**It has been tested on exactly one device: an Nvidia Shield TV Pro (2019, `mdarcy`), running
Android 11 (API 30), Shield software 9.2.4.** That is the machine it was written for, on the
television it was written for, and that is the whole of the test matrix.

If you have a **64-bit Android TV device running Android 11 or newer, you are very welcome to try
it** — and please [tell us how it went](#feedback-and-bug-reports) either way. But go in expecting
these specific limits:

| | |
|---|---|
| **CPU** | `arm64-v8a` **only**. The app ships one ABI. On a 32-bit device (including 2015-era Shields) it fails to install with `INSTALL_FAILED_NO_MATCHING_ABIS`, which looks like a broken download and is not. |
| **Android** | 11 (API 30) or newer. |
| **Microphones** | **Wired USB microphones only** — see below. Bluetooth mics, 3.5 mm mics, headset mics and the mic in your remote will not work, and this is not a bug that can be fixed with a setting. |
| **Television** | Any. The LG game-mode feature is LG-only and entirely optional; everything else is brand-agnostic. |

### Why USB microphones only

This app does not use Android's audio recording API at all. It talks to microphones directly over
the USB Host API — claiming the audio interface and pumping isochronous transfers itself.

That is not cleverness for its own sake. The Shield's USB audio driver is broken: it cannot open
*any* USB audio device, in either direction, so no microphone ever appears to Android's normal
recording path on this machine. Bypassing the audio stack entirely was the only way to get sound
in, and it works — two microphones at once, verified on hardware.

The side effect is that **the only microphones this app can hear are class-compliant USB audio
devices plugged in by cable.** In practice that means the cheap wired USB mics sold with karaoke
games. It was developed against a pair from *Let's Sing 2024* (PS5 edition, `046d:0a03`).

It also expects a microphone that offers **mono, 16-bit audio at 48 kHz** on an isochronous input
endpoint, which is what nearly every one of these devices does. A stereo-only or 24-bit microphone
will be found and then misread. If you have one, please say so in an issue — the descriptor
parsing is already there, and supporting more formats is a small change made worthwhile by knowing
which formats to support.

On a device whose USB audio driver *does* work, this bypass should still be fine: the app detaches
the kernel driver and takes over the interface, which is exactly what a USB audio application is
supposed to be able to do. It has never been tried, though. That is what feedback is for.

## What you need

1. **An Android TV device** — see above.
2. **One or two wired USB microphones.** Two, through a powered hub, if you want duets. Also see
   [a warning about rebooting](#a-warning-about-rebooting) if you have a Shield.
3. **Somewhere to keep songs** — internal storage, a USB stick, or an SD card. The library used to
   develop this lives on a 1 TB microSD card in a USB adapter.
4. **Songs in UltraStar format** — a folder per song, holding a `.txt` chart and its audio, and
   optionally a video and cover art. If you have none, the app can download them; read the
   [legal notice](#legal-notice) first.
5. Optionally, **a free [USDB](https://usdb.animux.de/) account**, if you want to use the in-app
   song search.

## Installing it

The app is not on the Play Store and probably never will be. You install it yourself, which on
Android TV is called sideloading and is a completely normal thing to do.

### The easy way: the Downloader app

**Downloader** by AFTVnews is a free app on the Play Store that fetches a file from a URL and
offers to install it. It is the standard tool for this.

1. **Install Downloader.** On your TV, open the Play Store, search for **Downloader**, install it.
   (Its icon is an orange circle with a white down-arrow.)

2. **Allow it to install apps.** Go to
   **Settings → Device Preferences → Security & restrictions → Unknown sources**, find
   **Downloader** in the list, and switch it on. Android will not let one app install another
   without this, and if you skip it the install simply fails without saying why.

   > On some devices this lives at **Settings → Apps → Special app access → Install unknown apps**.

3. **Open Downloader**, put the cursor in the URL box, and type:

   ```
   github.com/MichaelMarcialis/UltraStar-Android-TV/releases/latest/download/ultrastar-android-tv.apk
   ```

   It is a long thing to type with a remote, and you only ever type it once. That address always
   points at the newest release, so it works for updates too.

4. **Press Go.** It downloads, then offers to install. Say **Install**.

5. When it finishes, choose **Done**, and Downloader will offer to delete the APK. Say yes — it is
   installed by then, and the file is 32 MB of nothing.

6. **The game is now on your home screen.** Look for the star-and-microphone banner.

### If you would rather use a browser or a file manager

Download the APK from the
[Releases page](https://github.com/MichaelMarcialis/UltraStar-Android-TV/releases/latest) onto a
USB stick from a computer, plug the stick into the TV, and open the file with any file manager
that can install packages (X-plore, Solid Explorer, FX). You will still need to allow that file
manager to install unknown apps, as in step 2 above.

### If you have adb set up

```sh
adb connect <your-tv-ip>:5555
adb install -r ultrastar-android-tv.apk
```

`-r` reinstalls over an existing copy and keeps its data. That only works if the new APK is signed
with the same key as the installed one — every release here is, so updates keep your settings,
your singers and your high scores.

### Updating later

Repeat whichever method you used. The Downloader URL above always fetches the newest release. Your
song folder, profiles, settings and high scores survive an update.

### A warning about rebooting

**On the Nvidia Shield, unplug USB peripherals before rebooting.** With a hub, microphones or a
card reader attached at power-on, the Shield hangs on its boot logo — reproduced three times,
resolved every time by unplugging. Plug the peripherals back in once it reaches the home screen.
This is a Shield firmware quirk and nothing to do with this app, but it will happen to you.

## First run

1. **Plug in your microphones.** Android asks permission for each one, one at a time. Say **OK** to
   each. You will be asked again after every reboot or replug, because Android ties USB permission
   to the physical port and hands out a new one each time.
2. **Press Play.** The first time, it asks where your songs are.
3. **Choose your song folder** with Android's folder picker. Pick the folder that *contains* your
   song folders, not one song.

   > **The picker is awkward with a remote, and the rule is simple:** `USE THIS FOLDER` is already
   > selected whenever a folder opens, so press it as soon as you are in the right place. If you
   > move down into the file list you cannot get back up to that button — press Back and open the
   > folder again.

4. **Say how many are singing**, then **claim a microphone**: sing into the one in your hand until
   its bar fills. That is how the game knows which microphone is yours, since no label on a screen
   can tell two identical microphones apart.
5. **Choose a name**, pick a song, sing.

## Songs, and where they come from

### Songs you already have

Put each song in its own folder, with the `.txt` chart and its audio file together. Point the app
at the folder holding all of them. Anything that is not an UltraStar song is simply ignored.

The **Songs** screen on the main menu shows everything the app found, including songs that are
incomplete — a chart with no audio, no video or no cover — and lets you fix or remove them.
**Repair** fetches what a song is missing without downloading the whole song again.

### Downloading songs in the app

**Songs → Add songs** searches [USDB](https://usdb.animux.de/), a free community database of
UltraStar charts. You sign in with your own USDB account; nothing is bundled and no account is
shared.

A chart carries notes and lyrics but no music, so the audio and video come from the YouTube upload
the chart was written against. That is what makes the timing work, and it is also why the
[legal notice](#legal-notice) below matters.

**Downloads are deliberately slow.** USDB asks for about 24 seconds between chart downloads to
protect its bandwidth, and this app waits every time and says so out loud. Please do not try to
work around it: you are signed in as yourself, so an app that hammered USDB would get *your*
account blocked. Queue up several songs and go and do something else — the queue keeps running
while you use the rest of the app.

## Legal notice

**Only download songs you already own a lawful copy of.**

The download feature fetches a commercial recording — audio and video — and saves it to your
device. Whether making that copy is lawful depends entirely on what you own and where you live,
and this software can see neither. That judgement is yours, and so is the responsibility for it.

This project provides a tool. It hosts no music, no video, no lyrics and no charts; it is not
affiliated with USDB, YouTube, Google, Nvidia, LG, or the UltraStar or UltraStar Play projects; it
has no relationship with any rights holder; and it makes no claim that any particular download is
lawful for you to make. The song files that arrive on your storage remain subject to the copyright
of whoever owns them, exactly as they were before.

Nothing you download is uploaded, shared, republished or transmitted anywhere by this app. It goes
to your storage and stays there.

The software is provided as-is, with no warranty, under the [MIT licence](LICENSE).

The app says the same thing, once, the first time anybody opens **Add songs**, and keeps the first
line of it on screen while songs are being chosen.

## Settings worth knowing about

- **Difficulty** — Easy, Normal or Hard. It changes how far off pitch you may be and still be
  credited, and a note is drawn exactly as tall as the window that scores it, so an easier setting
  has visibly fatter notes. What you see is what is scored.
- **Microphone sensitivity** — how loudly a phrase must *begin* before the game hears it. This is
  the only defence against crosstalk: with two singers in a room, each microphone also hears the
  other person and the television. Solo and duet keep separate values, because on your own the
  only competition is the song itself.
- **Display lead** — how far *ahead* the notes are drawn, to compensate for your television's own
  picture delay. Raise it if the lyrics feel like they arrive after you have sung them. Zero is
  right for a set in game mode; a set doing heavy picture processing may want 40 ms or more.
- **Fill the screen with video** — on by default, which crops a song's video to fill the screen.
  Turn it off to see the whole picture, letterboxed, exactly as it was framed.
- **Game mode on the television** — LG sets only. Turn it on once, accept the prompt that appears
  *on the television*, and from then on the set switches to Game Optimizer whenever the app opens
  and back to your usual picture mode when it closes. It pairs over your local network; the app
  never talks to anything outside your house. None of this is required, and everything else works
  without it.

## Building from source

You need Android Studio with the Android SDK (platform 30 or newer), the NDK (`30.0.15729638`) and
CMake — Android Studio installs the last one for you.

```sh
git clone https://github.com/MichaelMarcialis/UltraStar-Android-TV.git
cd UltraStar-Android-TV
./gradlew assembleDebug          # a debug APK you can install straight away
./gradlew testDebugUnitTest      # the unit tests, all of them on the JVM
```

The debug APK lands in `app/build/outputs/apk/debug/`.

`assembleRelease` produces an **unsigned** APK unless you supply your own signing key, which is
correct: the release key for this project is not in the repository and never will be. For your own
signed build, create a keystore and a `keystore.properties` beside `settings.gradle.kts`:

```properties
storeFile=my-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Both files are gitignored.

### How the code is laid out

| | |
|---|---|
| `song/` | Parsing UltraStar `.txt` charts, and beat ↔ time conversion. |
| `mic/`, `cpp/` | USB microphone capture — the Host API bypass, and the C that pumps the transfers. |
| `pitch/` | The YIN pitch detector and the tracker that feeds it. |
| `score/` | Scoring a performance against a chart, beat by beat. |
| `playback/` | ExoPlayer, the song clock, and the latency calibration. |
| `game/` | The game screen: note track, lyrics, arrow, sparks, stars, results. |
| `library/` | Finding and scanning songs on storage. |
| `usdb/`, `download/`, `net/` | Searching USDB and assembling a downloaded song. |
| `audio/`, `visual/` | Loudness, FFT, and the full-screen visualiser. |
| `tv/` | Talking to an LG television over its own network protocol. |
| `ui/` | Every screen that is not the game. |
| `tools/` | Python helpers for the workstation, not shipped in the app. |

`CLAUDE.md` in the repository root is the project's full engineering log — every decision, every
measurement and every mistake, in far more detail than this README. If you are going to change
something, read the part of it that covers what you are changing first. Most of the things in this
codebase that look wrong are load-bearing, and it says which.

## Feedback and bug reports

Please open an [issue](https://github.com/MichaelMarcialis/UltraStar-Android-TV/issues). Reports
from devices other than a 2019 Shield TV Pro are especially welcome, whether it worked or not.

Useful things to include:

- Your device, and its Android version.
- Which microphones you are using, and whether the app found them.
- For a crash or a hang, the output of `adb logcat` if you can get it.

There is no telemetry in this app, so an issue really is the only way anybody finds out.

## Credits and licence

This app's own code is under the [MIT licence](LICENSE).

It deliberately shares **no code** with any other karaoke project. What it borrows is ideas and
formats, all of which are freely documented:

- **The UltraStar song format**, from the UltraStar community — a plain-text format that this
  project's parser was written against from scratch.
- **YIN** — *A fundamental frequency estimator for speech and music*, de Cheveigné & Kawahara,
  JASA 111(4), 2002. Implemented here from the paper.
- **[USDB](https://usdb.animux.de/)**, the community chart database the download feature searches.
  Please respect their bandwidth; this app is written to.
- **UltraStar Play**, the official Android TV port, which this project was built as an alternative
  to after it did not work on this hardware. No code is shared with it.

Every runtime dependency is permissively licensed and comes from Maven Central or Google's Maven:
AndroidX, Jetpack Compose, Compose for TV, and Media3/ExoPlayer.

Not affiliated with or endorsed by Nvidia, LG, Google, YouTube, USDB, or the UltraStar or UltraStar
Play projects. All trademarks belong to their owners.
