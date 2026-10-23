#!/usr/bin/env python3
"""Live regression: DDIZI_TEST_URL and DDIZI_TEST_REFERER stay out of logs."""
import os
import urllib.error
import urllib.request
from urllib.parse import urljoin, urlsplit


def get(url, referer=None):
    assert urlsplit(url).scheme == "https"
    assert urlsplit(url).hostname == "video.twimg.com"
    headers = {"User-Agent": "Mozilla/5.0", "Accept": "*/*"}
    if referer:
        headers["Referer"] = referer
    request = urllib.request.Request(url, headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read(65536)


url = os.environ["DDIZI_TEST_URL"]
referer = os.environ["DDIZI_TEST_REFERER"]
assert get(url, referer)[0] == 403, "Original header no longer reproduces 403"
status, master = get(url)
assert status == 200 and master.startswith(b"#EXTM3U"), "Master playlist failed"
variant = next(line for line in master.decode().splitlines() if line and not line.startswith("#"))
variant_url = urljoin(url, variant)
status, media = get(variant_url)
assert status == 200 and media.startswith(b"#EXTM3U"), "Variant playlist failed"
segment = next(line for line in media.decode().splitlines() if line and not line.startswith("#"))
status, data = get(urljoin(variant_url, segment))
assert status == 200 and data, "Media segment failed"
print("Ddizi regression: original=403; no Referer: master, variant, segment=200")
