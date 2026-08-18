"""Checks whether an UltraStar chart actually matches the recording it ships with.

A chart can be out of time with its audio for a reason no amount of app-side work will fix: it
was written against a different recording. This tells the two apart, and it does it by pitch
rather than by rhythm.

    python tools/check_song_sync.py song.txt song.mp3      # one song
    python tools/check_song_sync.py --card                 # every song on the Shield's card

**Read this before trusting a number.** The method correlates the chart's melody against a
chroma profile of the audio, sweeping the offset. A chart written for its own recording peaks
within a fraction of a second of zero, at roughly 1.5-2.4x the mean score across all offsets. A
chart written for a different recording has no peak worth the name — around 1.1x, landing
somewhere arbitrary. **The gap between those two bands is the whole test**, so run a song you
know is fine before believing a verdict about one you do not.

Rhythm does not work for this, which is worth knowing before reaching for it again: note onsets
correlated against an energy envelope score nearly the same at every bar line of a 4/4 song, and
that approach confidently reported a +34 s offset for a song that is perfectly in sync. The
sequence of pitch classes a song moves through is close to unique; its onsets are not.

Needs numpy and ffmpeg on PATH. `--card` also needs adb with the Shield connected.
"""
import argparse
import os
import subprocess
import sys

import numpy as np

RATE = 11025
WINDOW = 4096
HOP = 512
HOP_SECONDS = HOP / RATE
SEARCH_SECONDS = 45.0

# Where a voice and its first few harmonics live. Below this is bass guitar and kick drum, which
# follow the chords rather than the tune and would drown the melody the chart is describing.
LOW_HZ, HIGH_HZ = 150.0, 2000.0

# A chart aligned to its own audio lands this close to zero; further out means it is describing
# something else. Generous next to the ~0.09 s the controls actually reach.
ALIGNED_SECONDS = 0.6

CARD = "/storage/5002-E7C7/UltraStar"
AUDIO_SUFFIXES = (".mp3", ".m4a", ".ogg", ".opus", ".wav")


def read_chart(path):
    """Headers, plus (start, end, pitch class) in seconds for every *pitched* note.

    Freestyle notes are dropped: they carry a pitch column but are unpitched by definition, so
    including them adds noise to the one signal this depends on.
    """
    headers, raw = {}, []
    with open(path, encoding="utf-8-sig", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\r\n")
            if line.startswith("#"):
                key, _, value = line[1:].partition(":")
                headers[key.upper()] = value
            elif line and line[0] in ":*RG":
                parts = line.split(" ")
                if len(parts) >= 4:
                    raw.append((int(parts[1]), int(parts[2]), int(parts[3])))

    bpm = float(headers["BPM"].replace(",", "."))
    gap = float(headers.get("GAP", "0").replace(",", ".")) / 1000.0

    # An UltraStar beat is a quarter of a BPM beat. #GAP is milliseconds, unlike #VIDEOGAP.
    beats_per_second = bpm * 4 / 60.0
    notes = [
        (gap + beat / beats_per_second, gap + (beat + length) / beats_per_second, pitch % 12)
        for beat, length, pitch in raw
    ]
    return headers, notes


def chroma(media):
    """How much energy sits in each of the twelve pitch classes, per analysis frame."""
    raw = subprocess.run(
        ["ffmpeg", "-hide_banner", "-v", "error", "-i", media,
         "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
        stdout=subprocess.PIPE, check=True).stdout
    samples = np.frombuffer(raw, dtype=np.float32)
    if len(samples) < WINDOW * 4:
        raise ValueError("no usable audio")

    frames = 1 + (len(samples) - WINDOW) // HOP
    index = np.arange(WINDOW)[None, :] + HOP * np.arange(frames)[:, None]
    spectrum = np.abs(np.fft.rfft(samples[index] * np.hanning(WINDOW).astype(np.float32), axis=1))

    freqs = np.fft.rfftfreq(WINDOW, 1.0 / RATE)
    keep = (freqs >= LOW_HZ) & (freqs <= HIGH_HZ)
    classes = np.round(12 * np.log2(freqs[keep] / 440.0) + 69).astype(int) % 12

    band = spectrum[:, keep]
    out = np.zeros((frames, 12), dtype=np.float32)
    for pitch_class in range(12):
        out[:, pitch_class] = band[:, classes == pitch_class].sum(axis=1)

    # Normalised per frame: which pitch class dominates is the signal, how loud it is is not.
    return out / (out.sum(axis=1, keepdims=True) + 1e-9)


def align(chart_path, media_path):
    """Returns (peak ratio, offset seconds, ratio as written), or None if unscoreable."""
    _, notes = read_chart(chart_path)
    if len(notes) < 20:
        return None

    profile = chroma(media_path)
    frames = len(profile)

    at, want = [], []
    for start, end, pitch_class in notes:
        first = int(start / HOP_SECONDS)
        last = max(int(end / HOP_SECONDS), first + 1)
        at.extend(range(first, last))
        want.extend([pitch_class] * (last - first))
    at, want = np.array(at), np.array(want)

    span = int(SEARCH_SECONDS / HOP_SECONDS)
    scores, at_zero, best = [], None, (0.0, 0.0)
    for shift in range(-span, span + 1):
        moved = at + shift
        inside = (moved >= 0) & (moved < frames)
        if inside.sum() < len(at) * 0.5:
            continue
        score = float(profile[moved[inside], want[inside]].mean())
        scores.append(score)
        if shift == 0:
            at_zero = score
        if score > best[0]:
            best = (score, shift * HOP_SECONDS)

    if not scores or at_zero is None:
        return None
    mean = sum(scores) / len(scores)
    return best[0] / mean, best[1], at_zero / mean


def verdict(peak, offset):
    if abs(offset) <= ALIGNED_SECONDS:
        return "ok"
    return "SHIFTED" if peak >= 1.45 else "MISMATCH"


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, encoding="utf-8")


def sweep_card(work):
    os.makedirs(work, exist_ok=True)
    folders = [f for f in adb("shell", f"ls '{CARD}'").stdout.replace("\r", "").split("\n") if f.strip()]
    print(f"{len(folders)} folders on the card\n")

    for folder in folders:
        files = adb("shell", f"ls '{CARD}/{folder}'").stdout.replace("\r", "").split("\n")
        charts = [f for f in files if f.lower().endswith(".txt")]
        audio = [f for f in files if f.lower().endswith(AUDIO_SUFFIXES)]
        if not charts or not audio:
            print(f"NO AUDIO {folder}")
            continue

        local_chart = os.path.join(work, "song.txt")
        local_audio = os.path.join(work, "song.snd")
        adb("pull", f"{CARD}/{folder}/{charts[0]}", local_chart)
        adb("pull", f"{CARD}/{folder}/{audio[0]}", local_audio)
        try:
            result = align(local_chart, local_audio)
            if result is None:
                print(f"SKIP     {folder}")
            else:
                peak, offset, written = result
                print(f"{verdict(peak, offset):8s} peak {peak:.2f}x at {offset:+7.2f}s "
                      f"| as written {written:.2f}x | {folder}")
        except Exception as error:
            print(f"ERROR    {folder}: {error}")
        for path in (local_chart, local_audio):
            if os.path.exists(path):
                os.remove(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chart", nargs="?", help="path to a .txt")
    parser.add_argument("media", nargs="?", help="path to its audio")
    parser.add_argument("--card", action="store_true", help="sweep every song on the Shield")
    parser.add_argument("--work", default="song_sync_work", help="scratch directory for --card")
    args = parser.parse_args()

    if args.card:
        sweep_card(args.work)
        return
    if not args.chart or not args.media:
        parser.error("give a chart and its audio, or --card")

    result = align(args.chart, args.media)
    if result is None:
        print("not enough pitched notes to score")
        sys.exit(1)
    peak, offset, written = result
    print(f"{verdict(peak, offset)}: peak {peak:.2f}x mean at {offset:+.2f}s "
          f"(as written {written:.2f}x)")


if __name__ == "__main__":
    main()
