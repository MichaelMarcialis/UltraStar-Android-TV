#!/usr/bin/env bash
#
# Back up the release signing key, encrypted, to cloud storage — and prove the backup works.
#
# ## Why this exists
#
# Android identifies an app by *who signed it*, not by its name. An update only installs over an
# existing copy when the signature matches, so losing `release.jks` means every person who
# installed the app is stranded: the next release is, to Android, a different app from a stranger,
# and they have to uninstall first — losing their song folder grant, their profiles and their high
# scores. There is no recovery. No reset, no support ticket, no override from Google.
#
# And the usual safety net does not apply here. `release.jks` and `keystore.properties` are
# gitignored *on purpose*, so the thing that catches every other mistake in this repository —
# git, and a copy on GitHub — deliberately does not catch these two. Without a backup they exist
# in exactly one place on earth.
#
# ## Why it is not on a schedule
#
# Because a signing key never changes. A monthly job that re-encrypts and re-uploads identical
# bytes is motion rather than safety, and this project has form for deleting exactly that shape of
# feature — the library-wide timing sweep went the same way, for the same reason: its cost grew and
# its value did not.
#
# So this is run by hand, at the two moments that matter: when a key is created, and if one is ever
# replaced. What *is* worth repeating is the verifying, which is why it happens automatically at
# the end of every run rather than being a separate thing to remember. A backup nobody has ever
# restored from is a rumour.
#
# ## What it does not protect against
#
# The passphrase. It is asked for interactively and never stored, which is what keeps it out of
# this repository and off the disk — so put it somewhere designed to remember things. A password
# manager is the right place, and it is also the right place for a second copy of the two files
# themselves. Two independent copies, one of which is not a script.
#
# ## Two traps in gpg that this had to be rewritten around
#
# **Without a terminal, `gpg --symmetric` does not fail — it encrypts anyway, exit 0, with a
# passphrase nobody knows.** Measured: the resulting file will not open with the empty passphrase,
# or with any other. So this refuses to run without a TTY rather than producing a convincing
# archive that can never be decrypted.
#
# **And gpg-agent caches the symmetric passphrase for the session**, so a `--decrypt` immediately
# after a `--symmetric` succeeds without asking for anything. The first version of the verify step
# did exactly that, which made it worthless: it proved the file was well formed and said nothing
# about whether the passphrase was the one that had been typed. Both halves now pass
# `--no-symkey-cache` and supply the passphrase explicitly, so the check really does re-derive the
# key.
#
# The passphrase is asked for **twice and compared**, because a typo is the realistic way to lose a
# backup — likelier by far than gpg or Drive failing.
#
# ## Usage
#
#     rclone config          # once: create a remote pointing at your Drive
#     tools/backup_signing_key.sh
#
# Override the defaults with environment variables if your setup differs:
#
#     RCLONE=/path/to/rclone.exe REMOTE=mydrive: DEST_DIR=Backups/keys \
#       tools/backup_signing_key.sh

set -euo pipefail

RCLONE="${RCLONE:-$HOME/bin/rclone.exe}"
REMOTE="${REMOTE:-gdrive:}"
DEST_DIR="${DEST_DIR:-Backups/ultrastar-android-tv}"

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
stamp="$(date +%Y%m%d)"
archive="ultrastar-signing-key-${stamp}.tar.gpg"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

say() { printf '%s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Both files, or nothing. A backup holding the keystore without the password that opens it is a
# locked box, and one holding the password without the keystore is worse than useless.
for f in release.jks keystore.properties; do
    [ -f "$repo/$f" ] || die "$repo/$f is missing — nothing to back up."
done

command -v gpg >/dev/null 2>&1 || die "gpg not found (it ships with Git for Windows)."
[ -x "$RCLONE" ] || die "rclone not found at $RCLONE — set RCLONE=/path/to/rclone.exe"

"$RCLONE" listremotes | grep -qx "$REMOTE" \
    || die "no rclone remote called '$REMOTE'. Run: $RCLONE config"

# Refuse to run without a terminal. See the note above: gpg would otherwise encrypt happily and
# hand back an archive whose passphrase nobody knows.
[ -t 0 ] || die "no terminal — run this from an interactive shell (Git Bash), not from a script."

# Asked twice and compared. A mistyped passphrase is the realistic way to lose a backup, and it is
# the one failure that looks exactly like success right up until the day it matters.
printf 'Passphrase for the backup (keep it in your password manager): ' >&2
IFS= read -rs pass
printf '\n' >&2
printf 'Again: ' >&2
IFS= read -rs again
printf '\n' >&2
[ -n "$pass" ] || die "empty passphrase."
[ "$pass" = "$again" ] || die "the two passphrases do not match."
unset again

say "Encrypting release.jks and keystore.properties…"
tar -C "$repo" -cf - release.jks keystore.properties \
    | gpg --batch --yes --no-symkey-cache --passphrase-fd 3 \
          --symmetric --cipher-algo AES256 -o "$work/$archive" 3<<<"$pass"

# Verify against the *encrypted file*, before it is uploaded and before anything is trusted — and
# with the cache off and the passphrase supplied explicitly, so this re-derives the key rather than
# reading one gpg-agent is still holding from the line above.
say "Verifying the archive decrypts with that passphrase and holds both files…"
listing="$(gpg --batch --quiet --no-symkey-cache --passphrase-fd 3 \
    --decrypt "$work/$archive" 3<<<"$pass" 2>/dev/null | tar -tf -)"
for f in release.jks keystore.properties; do
    printf '%s\n' "$listing" | grep -qx "$f" || die "verify failed: $f missing from the archive."
done

say "Uploading to ${REMOTE}${DEST_DIR}/ …"
"$RCLONE" copyto "$work/$archive" "${REMOTE}${DEST_DIR}/${archive}"

# Read it back from the remote rather than trusting that the upload said nothing. This is the step
# that distinguishes "a file was sent" from "a file is there and is the file we meant".
say "Reading it back from the remote…"
"$RCLONE" copyto "${REMOTE}${DEST_DIR}/${archive}" "$work/roundtrip.tar.gpg"
cmp -s "$work/$archive" "$work/roundtrip.tar.gpg" \
    || die "the copy on the remote does not match what was uploaded."

say ""
say "Done. ${REMOTE}${DEST_DIR}/${archive}"
say "It decrypts with the passphrase you typed, it holds both files, and the copy on Drive is"
say "byte-identical to the one that was uploaded."
say ""
say "Two things left that this script cannot do for you:"
say "  1. Put the passphrase in your password manager."
say "  2. Put a second copy of release.jks and keystore.properties somewhere that is not"
say "     this disk and not that Drive account."
