#!/usr/bin/env python3
"""Talk to an LG webOS television over SSAP, and switch its picture mode.

## Why this exists

The Shield cannot ask the television for game mode. HDMI's Auto Low Latency Mode is
the proper route and this device drops the request before it reaches the wire
(``allmSupported false``); CEC needs a signature permission; and driving the set at
120 Hz — which did work — was measured to cost more latency than it saved. What is
left is the television's own network API, which is what Home Assistant and
``bscpylgtv`` drive, and which does exactly the thing that was asked for: put the C1
into Game Optimizer while the game is on and put it back afterwards.

This script is the *measurement* before any of that goes into the app. The protocol
is well known but the exact call that moves the picture mode is the part worth
verifying against a real set rather than assuming, and having it as a tool means it
can be re-run whenever the television's firmware changes.

## Why there are no dependencies

``websockets`` and ``bscpylgtv`` would both do this in ten lines. Neither is
installed on this machine, this repo may be opened publicly, and the whole point is
to learn what the app has to send — which is easier to see written out than hidden
behind a library. The WebSocket client below is about sixty lines of framing and
nothing else. Python 3 standard library only.

## Usage

    python tools/lg_game_mode.py --host 192.168.1.234 pair     # once; accept on the TV
    python tools/lg_game_mode.py --host 192.168.1.234 info
    python tools/lg_game_mode.py --host 192.168.1.234 mode game
    python tools/lg_game_mode.py --host 192.168.1.234 mode standard

The pairing token is written to ``~/.lg_webos_client_key`` — deliberately outside
this repo, the same rule the USDB account follows.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import ssl
import struct
import sys
import time

KEY_FILE = os.path.join(os.path.expanduser("~"), ".lg_webos_client_key")

# LG's own test manifest, copied verbatim and deliberately unedited.
#
# The `signed` block carries a signature over its own contents, so changing anything inside it --
# the obvious first move being to put this app's name in `localizedAppNames` so the prompt says
# who is asking -- makes a manifest that no longer matches its own signature. This copy had been
# edited in exactly that way, and had a permission miscased into `READ_original_SETTINGS` with
# it. Pairing happened to work anyway, so the set evidently does not check; that is not a reason
# to ship a manifest that lies about itself, and it has to stay what `WebOsTv.kt` sends or the
# two will not behave the same way on a firmware that does check.
MANIFEST = {
    "manifestVersion": 1,
    "appVersion": "1.1",
    "signed": {
        "created": "20140509",
        "appId": "com.lge.test",
        "vendorId": "com.lge",
        "localizedAppNames": {"": "LG Remote App", "ko-KR": "\ub9ac\ubaa8\ucee8 \uc571", "zxx-XX": "\u041b\u0413 R\u044d\u043cot\u044d A\u041f\u041f"},
        "localizedVendorNames": {"": "LG Electronics"},
        "permissions": ["TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
                        "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS",
                        "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
                        "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS",
                        "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP",
                        "READ_ORIGINAL_SETTINGS", "CONTROL_DISPLAY"],
        "serial": "2f930e2d2cfe083771f68e4fe7bb07",
    },
    "permissions": [
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
        "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
        "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
        "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
        "READ_COUNTRY_INFO", "READ_SETTINGS", "CONTROL_TV_SCREEN",
        "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
    ],
    "signatures": [{
        "signatureVersion": 1,
        "signature": (
            "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsIn"
            "NpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pc"
            "egmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOH"
            "E73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu"
            "+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqa"
            "FM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="
        ),
    }],
}


class Ws:
    """A WebSocket client with nothing in it but what SSAP needs.

    Text frames out, text frames in, and a reply to the set's pings. No extensions,
    no continuation frames, no binary — the television never sends any of those.
    """

    def __init__(self, host: str, port: int, secure: bool, timeout: float = 8.0):
        raw = socket.create_connection((host, port), timeout=timeout)
        if secure:
            # The set signs with its own certificate. Verifying it would mean
            # shipping LG's root, and the connection is to an address on this LAN
            # that the user typed, so there is nothing a name check would add.
            context = ssl._create_unverified_context()
            raw = context.wrap_socket(raw, server_hostname=host)
        self.sock = raw
        self.buf = b""
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET / HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            # No Origin header. Sending one gets the connection closed with 1008
            # "invalid origin" before the set looks at the payload at all -- measured
            # on the C1. A browser has to send one; a native client must not.
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        head = self._read_until(b"\r\n\r\n")
        status = head.split(b"\r\n", 1)[0].decode("latin-1")
        if "101" not in status:
            raise RuntimeError(f"the set refused the upgrade: {status}")

    # -- plumbing ----------------------------------------------------------------

    def _read_until(self, marker: bytes) -> bytes:
        while marker not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("the set closed the connection during the handshake")
            self.buf += chunk
        head, self.buf = self.buf.split(marker, 1)
        return head

    def _read_exactly(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("the set closed the connection")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, obj: dict) -> None:
        data = json.dumps(obj).encode()
        header = bytearray([0x81])  # FIN + text
        mask = os.urandom(4)
        n = len(data)
        # A client MUST mask, and the length is written in one of three widths.
        if n < 126:
            header.append(0x80 | n)
        elif n < 1 << 16:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(bytes(header) + masked)

    def recv(self, timeout: float = 8.0) -> dict:
        self.sock.settimeout(timeout)
        while True:
            first, second = self._read_exactly(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read_exactly(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read_exactly(8))[0]
            payload = self._read_exactly(length) if length else b""
            if opcode == 0x9:            # ping -> pong, or the set hangs up on us
                self.sock.sendall(b"\x8a\x80" + os.urandom(4))
                continue
            if opcode == 0x8:
                raise RuntimeError("the set closed the connection")
            if opcode in (0x1, 0x2):
                return json.loads(payload.decode("utf-8"))

    def close(self) -> None:
        try:
            self.sock.sendall(b"\x88\x80" + os.urandom(4))
        except OSError:
            pass
        self.sock.close()


class Tv:
    def __init__(self, host: str, port: int | None = None):
        self.host = host
        self.counter = 0
        # **TLS first.** Both ports answer on a C1, and the saved pairing token goes out on
        # every connection — so preferring the plain one would make this diagnostic the unsafe
        # way to talk to the same television the app talks to securely. Plaintext stays as a
        # fallback for a set that will not answer on 3001, and can be asked for explicitly.
        attempts = [(port, port == 3001)] if port else [(3001, True), (3000, False)]
        last = None
        for p, secure in attempts:
            try:
                self.ws = Ws(host, p, secure)
                self.port = p
                break
            except Exception as error:  # noqa: BLE001 - reported below either way
                last = error
        else:
            raise SystemExit(
                f"could not reach {host} on 3000 or 3001 ({last}).\n"
                "A webOS set only opens these while it is switched on."
            )

    def _next_id(self, kind: str) -> str:
        self.counter += 1
        return f"{kind}_{self.counter}"

    def register(self, client_key: str | None) -> str:
        payload = {"forcePairing": False, "pairingType": "PROMPT", "manifest": MANIFEST}
        if client_key:
            payload["client-key"] = client_key
        self.ws.send({"type": "register", "id": self._next_id("register"), "payload": payload})
        while True:
            # With no key the set puts a prompt on screen first and only sends the
            # key once somebody accepts it, so this waits longer than anything else.
            message = self.ws.recv(timeout=60.0)
            if message.get("type") == "registered":
                return message["payload"]["client-key"]
            if message.get("type") == "error":
                raise SystemExit(f"the set refused to pair: {message.get('error')}")

    def request(self, uri: str, payload: dict | None = None) -> dict:
        self.ws.send({
            "type": "request",
            "id": self._next_id("req"),
            "uri": uri,
            "payload": payload or {},
        })
        return self.ws.recv()

    def close(self) -> None:
        self.ws.close()

    def luna(self, uri: str, params: dict) -> dict:
        """Invoke a `luna://` service, the long way round.

        webOS does not let SSAP call luna services directly. What it does have is
        `createAlert`, whose payload may carry an `onclose` handler naming any URI —
        so the way round is to raise an invisible alert and immediately close it
        again, and the set fires the handler on the way down. A workaround for the
        set's own restriction, not a way past any authentication: the pairing prompt
        still had to be accepted on the television.

        **It has to be an alert, and it has to be closed.** A `createToast` takes the
        same `onclose` and is accepted with a `toastId` and a cheerful
        `returnValue: true` — and nothing happens, because nothing ever closes it.
        Measured on the C1: the toast reported success and the picture mode did not
        move.
        """
        full = f"luna://{uri}"
        raised = self.request("ssap://system.notifications/createAlert", {
            "message": " ",
            "buttons": [{"label": "", "onClick": full, "params": params}],
            "onclose": {"uri": full, "params": params},
            "onfail": {"uri": full, "params": params},
        })
        alert = raised.get("payload", {}).get("alertId")
        if not alert:
            return raised
        return self.request("ssap://system.notifications/closeAlert", {"alertId": alert})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", required=True, help="the television's IP address")
    parser.add_argument("--port", type=int, default=None, help="3000 (ws) or 3001 (wss)")
    parser.add_argument("--key", default=None, help="pairing token, if not the saved one")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("pair", help="pair with the set; accept the prompt on the television")
    sub.add_parser("info", help="what the set says about itself, to prove the channel works")
    mode = sub.add_parser("mode", help="set the picture mode")
    mode.add_argument("name", help='e.g. "game", "standard", "cinema", "filmMaker"')
    args = parser.parse_args()

    saved = None
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE, encoding="utf-8") as handle:
            saved = handle.read().strip() or None
    client_key = args.key or saved

    if args.command == "pair":
        client_key = None  # deliberately: pairing means asking the set again

    tv = Tv(args.host, args.port)
    print(f"connected on port {tv.port}")
    if client_key is None:
        print("look at the television and accept the pairing prompt...")
    key = tv.register(client_key)
    if key != saved:
        with open(KEY_FILE, "w", encoding="utf-8") as handle:
            handle.write(key)
        print(f"paired; token saved to {KEY_FILE}")
    else:
        print("paired with the saved token")

    if args.command == "info":
        for uri in ("ssap://system/getSystemInfo",
                    "ssap://com.webos.service.update/getCurrentSWInformation",
                    "ssap://com.webos.applicationManager/getForegroundAppInfo"):
            print(f"\n{uri}\n  {json.dumps(tv.request(uri))[:400]}")

    if args.command == "mode":
        reply = tv.luna("com.webos.settingsservice/setSystemSettings", {
            "category": "picture",
            "settings": {"pictureMode": args.name},
        })
        print(f"asked for picture mode {args.name!r}; the set said {json.dumps(reply)[:300]}")
        time.sleep(1.0)
        print("look at the television: the picture mode banner should say so.")

    tv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
