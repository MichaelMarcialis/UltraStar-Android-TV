"""Generates the sync-calibration song: a WAV of tones at exactly known song positions,
and the matching UltraStar .txt.

Held in front of the TV speaker, the mic hears the song itself, so the lag between "this note
starts at song time X" and "the pitch tracker reported it" is the whole round trip: HDMI output
latency plus USB capture plus the analysis window.

Uncompressed on purpose. An MP3 or AAC decoder adds its own priming delay, which would land
straight in the measurement and be indistinguishable from real output latency.
"""

import math
import struct
import wave

RATE = 48_000
BPM = 240.0                 # UltraStar quarter-beats: 0.0625 s per beat unit
GAP_MS = 1_000.0
BEAT_SECONDS = 60.0 / (BPM * 4.0)

NOTE_BEATS = 8              # 0.5 s of tone
REST_BEATS = 8              # 0.5 s of silence
NOTE_COUNT = 16
NOTES_PER_LINE = 4

# A4 and E4. High enough that a TV speaker reproduces them cleanly, and five semitones apart
# so a mix-up could never pass as a hit. UltraStar pitch 0 is C4 (MIDI 60).
PITCHES = [9, 4]
TAIL_SECONDS = 1.0

# A fast attack so the onset is sharp enough to time, but not so fast it clicks.
ATTACK_SECONDS = 0.004
RELEASE_SECONDS = 0.020

# Fundamental plus two harmonics: small speakers roll off badly, and a detector that can hear
# the missing fundamental does not care, but the RMS gate does.
HARMONICS = [(1, 1.0), (2, 0.5), (3, 0.25)]
AMPLITUDE = 0.6


def midi_to_hz(midi):
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def note_start_seconds(index):
    return GAP_MS / 1000.0 + index * (NOTE_BEATS + REST_BEATS) * BEAT_SECONDS


def render():
    total = note_start_seconds(NOTE_COUNT - 1) + NOTE_BEATS * BEAT_SECONDS + TAIL_SECONDS
    samples = [0.0] * int(total * RATE)
    duration = NOTE_BEATS * BEAT_SECONDS

    for index in range(NOTE_COUNT):
        pitch = PITCHES[index % len(PITCHES)]
        hz = midi_to_hz(pitch + 60)
        start = int(note_start_seconds(index) * RATE)
        length = int(duration * RATE)
        peak = sum(weight for _, weight in HARMONICS)

        for n in range(length):
            t = n / RATE
            if t < ATTACK_SECONDS:
                envelope = t / ATTACK_SECONDS
            elif t > duration - RELEASE_SECONDS:
                envelope = (duration - t) / RELEASE_SECONDS
            else:
                envelope = 1.0
            value = sum(
                weight * math.sin(2.0 * math.pi * hz * harmonic * t)
                for harmonic, weight in HARMONICS
            )
            samples[start + n] = AMPLITUDE * envelope * value / peak

    return samples


def write_wav(path, samples):
    with wave.open(path, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(
            struct.pack("<h", max(-32768, min(32767, int(s * 32767)))) for s in samples
        ))


def write_txt(path):
    lines = [
        "#TITLE:Sync Calibration",
        "#ARTIST:UltraStar Android TV",
        "#MP3:calibration.wav",
        "#BPM:%g" % BPM,
        "#GAP:%g" % GAP_MS,
    ]
    for index in range(NOTE_COUNT):
        start = index * (NOTE_BEATS + REST_BEATS)
        pitch = PITCHES[index % len(PITCHES)]
        lines.append(": %d %d %d tone" % (start, NOTE_BEATS, pitch))
        is_last = index == NOTE_COUNT - 1
        if not is_last and (index + 1) % NOTES_PER_LINE == 0:
            lines.append("- %d" % (start + NOTE_BEATS + REST_BEATS // 2))
    lines.append("E")
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    import sys

    target = sys.argv[1]
    samples = render()
    write_wav(target + "/calibration.wav", samples)
    write_txt(target + "/calibration.txt")
    print("%d notes, %.2f s, first onset at %.3f s" % (
        NOTE_COUNT, len(samples) / RATE, note_start_seconds(0),
    ))
