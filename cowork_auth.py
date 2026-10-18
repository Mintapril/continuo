"""Direct loopback Cowork challenge/HMAC client; no bearer, proxy, redirect or retry.

Phone-local callers default to port 3091. PC callers must use their explicit ADB
forward (normally port 13091). The helper does not create or manage that forward.
"""
import hashlib
import hmac
import http.client
import json
import math
import re
import secrets
import socket
import threading
import time

_MAX_RESPONSE_BYTES = 12 * 1024 * 1024
_AUTH_MAX_BYTES = 1024
_HEX64 = re.compile(r"[0-9a-f]{64}\Z")


def _unavailable():
    return RuntimeError("Native authentication unavailable")


def _mac(secret, text):
    return hmac.new(secret.encode("utf-8"), text.encode("utf-8"), hashlib.sha256).hexdigest()


def _proof(actual, expected):
    return isinstance(actual, str) and _HEX64.fullmatch(actual) is not None and hmac.compare_digest(actual, expected)


def _json(raw):
    def exact_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise _unavailable()
            result[key] = value
        return result
    return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=exact_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(_unavailable()))


def _exchange(method, path, body, authorization, port, deadline, max_bytes):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise _unavailable()
    connection = http.client.HTTPConnection("127.0.0.1", port=port, timeout=remaining)
    expired = threading.Event()
    active_socket = None
    response = None

    def expire():
        expired.set()
        sock = active_socket or connection.sock
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

    timer = threading.Timer(remaining, expire)
    timer.daemon = True
    timer.start()
    try:
        # Connect explicitly so a deadline during connect cannot leak a subsequent request.
        connection.connect()
        active_socket = connection.sock
        if expired.is_set() or time.monotonic() >= deadline:
            raise _unavailable()
        headers = {"Accept": "application/json"}
        if authorization is not None:
            headers["Authorization"] = authorization
        if method != "GET":
            headers.update({"Content-Type": "application/json", "Content-Length": str(len(body))})
        connection.request(method, path, body=None if method == "GET" else body, headers=headers)
        response = connection.getresponse()
        if response.status < 200 or response.status >= 600 or 300 <= response.status < 400:
            raise _unavailable()
        lengths = response.headers.get_all("Content-Length", [])
        if len(lengths) > 1 or (lengths and (re.fullmatch(r"[0-9]+", lengths[0]) is None or int(lengths[0]) > max_bytes)):
            raise _unavailable()
        # Keep the connected socket reference even when http.client detaches it
        # for Connection: close, so the total deadline also interrupts body reads.
        chunks = []
        size = 0
        while True:
            if expired.is_set() or time.monotonic() >= deadline:
                raise _unavailable()
            if active_socket is not None:
                active_socket.settimeout(deadline - time.monotonic())
            chunk = response.read1(min(65536, max_bytes + 1 - size))
            if not chunk:
                break
            size += len(chunk)
            if size > max_bytes:
                raise _unavailable()
            chunks.append(chunk)
            if response.isclosed():
                break
        if expired.is_set() or time.monotonic() >= deadline or (lengths and int(lengths[0]) != size):
            raise _unavailable()
        return response.status, response.headers, b"".join(chunks)
    finally:
        timer.cancel()
        if response is not None:
            response.close()
        connection.close()


def request(path, secret, value=None, *, role="agent", port=3091, timeout=12,
            max_response_bytes=_MAX_RESPONSE_BYTES):
    """Return verified JSON for one GET (value=None) or POST, with one total deadline.

    Secret and remote/socket error details are never included in raised errors.
    Both success and error response bodies require a valid response MAC. Non-2xx
    responses fail closed; the caller remains responsible for its result schema.
    """
    try:
        if (not isinstance(secret, str) or re.fullmatch(r"[\x21-\x7e]{32,512}", secret) is None
                or role not in ("agent", "controller") or type(port) is not int or not 1 <= port <= 65535
                or not isinstance(path, str) or not path.startswith("/") or "#" in path
                or re.fullmatch(r"[\x21-\x7e]+", path) is None
                or type(timeout) not in (int, float) or not math.isfinite(timeout) or timeout <= 0
                or type(max_response_bytes) is not int or max_response_bytes < 1):
            raise _unavailable()
        deadline = time.monotonic() + timeout
        method = "GET" if value is None else "POST"
        body = b"" if value is None else json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(body) > _MAX_RESPONSE_BYTES:
            raise _unavailable()
        digest = hashlib.sha256((method + "\n" + path + "\n").encode("utf-8") + body).hexdigest()
        client_nonce = secrets.token_hex(32)
        challenge_body = json.dumps({"role": role, "clientNonce": client_nonce, "digest": digest}, separators=(",", ":")).encode("utf-8")
        status, headers, raw = _exchange("POST", "/v1/auth", challenge_body, None, port, deadline, _AUTH_MAX_BYTES)
        if status != 200 or re.match(r"application/json(?:\s*;|$)", headers.get("Content-Type", ""), re.I) is None:
            raise _unavailable()
        challenge = _json(raw)
        if (type(challenge) is not dict or set(challenge) != {"serverNonce", "proof"}
                or not isinstance(challenge["serverNonce"], str) or _HEX64.fullmatch(challenge["serverNonce"]) is None):
            raise _unavailable()
        fields = "\n".join((role, client_nonce, challenge["serverNonce"], digest))
        if not _proof(challenge["proof"], _mac(secret, "server\n" + fields)):
            raise _unavailable()
        authorization = "Cowork-HMAC " + ":".join((role, client_nonce, challenge["serverNonce"], _mac(secret, "client\n" + fields)))
        status, headers, raw = _exchange(method, path, body, authorization, port, deadline, max_response_bytes)
        proofs = headers.get_all("X-Cowork-Proof", [])
        expected = _mac(secret, "response\n" + fields + "\n" + str(status) + "\n" + hashlib.sha256(raw).hexdigest())
        if len(proofs) != 1 or not _proof(proofs[0], expected):
            raise _unavailable()
        if not 200 <= status < 300 or re.match(r"application/json(?:\s*;|$)", headers.get("Content-Type", ""), re.I) is None:
            raise _unavailable()
        result = _json(raw)
        if time.monotonic() >= deadline:
            raise _unavailable()
        return result
    except Exception:
        raise _unavailable() from None
