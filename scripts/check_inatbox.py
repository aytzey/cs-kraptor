#!/usr/bin/env python3
import hashlib
import hmac
import secrets
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

URL = "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php"
HMK = b"x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"


def post(timestamp, body):
    nonce = secrets.token_hex(16)
    payload = f"POST\n{urlsplit(URL).path}\n{timestamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
    signature = hmac.new(HMK, payload.encode(), hashlib.sha256).hexdigest()
    request = urllib.request.Request(URL, body, {
        "User-Agent": "speedrestapi",
        "X-Requested-With": "com.bp.box",
        "Referer": "https://speedrestapi.com/",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Ts": str(timestamp),
        "X-Nc": nonce,
        "X-Sg": signature,
    })
    return urllib.request.urlopen(request, timeout=20)


key = secrets.token_urlsafe(12)[:16]
body = f"1={key}&0={key}".encode()
try:
    post(int(time.time()) - 604800, body)
    raise AssertionError("stale timestamp was unexpectedly accepted")
except urllib.error.HTTPError as error:
    assert error.code == 403 and error.headers.get("X-St"), "server time was not returned"
    server_time = int(error.headers["X-St"])

with post(server_time, body) as response:
    assert response.status == 200 and len(response.read()) > 100, "signed retry failed"
print("InatBox signed retry: OK")
