"""Turn a solo UltraStar song into a duet, for testing the split gameplay layout.

The song library on the card is all solo songs, so the two-track layout has nothing real to
exercise it. This takes any song's `.txt` and deals its lyric lines alternately to P1 and P2,
writing a new `.txt` beside it. The two parts then genuinely differ — different notes, different
lyrics, different beat counts — which is the thing worth testing; singing along to the result is
not the point.

The new file goes in the same folder and keeps the original `#MP3` header, so it shares the
original's audio without copying it.

Usage:
    python tools/make_duet_test.py <song.txt> [output.txt]

To put one on the Shield's card:
    adb push DuetTest.txt "/storage/<VOL>/UltraStar/<Song Folder>/DuetTest.txt"
"""

import sys
from pathlib import Path


def decode(raw: bytes) -> str:
    """BOM, then strict UTF-8, then Windows-1252 — the same order the app's decoder uses."""
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw[3:].decode("utf-8")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("windows-1252")


def make_duet(text: str) -> str:
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    headers = [l for l in lines if l.startswith("#")]
    body = [l for l in lines if l and not l.startswith("#") and l.strip() != "E"]

    # A lyric line is a run of note lines closed by a '-' line-break marker.
    groups, current = [], []
    for line in body:
        current.append(line)
        if line.startswith("-"):
            groups.append(current)
            current = []
    if current:
        groups.append(current)

    out = []
    for header in headers:
        if header.startswith("#TITLE:"):
            out.append(header.rstrip() + " [Duet Test]")
        else:
            out.append(header)
    out.append("#DUETSINGERP1:Player 1")
    out.append("#DUETSINGERP2:Player 2")

    out.append("P1")
    for group in groups[0::2]:
        out.extend(group)
    out.append("P2")
    for group in groups[1::2]:
        out.extend(group)
    out.append("E")

    return "\r\n".join(out) + "\r\n"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    source = Path(sys.argv[1])
    target = Path(sys.argv[2]) if len(sys.argv) > 2 else source.with_name("DuetTest.txt")

    duet = make_duet(decode(source.read_bytes()))
    target.write_bytes(duet.encode("utf-8"))

    parts = duet.count("\r\nP1\r\n") + duet.count("\r\nP2\r\n")
    print(f"wrote {target} ({len(duet.splitlines())} lines, {parts} voice parts)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
