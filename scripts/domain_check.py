#!/usr/bin/env python3
"""Follow each provider's domain; if the site redirected to a new host, rewrite doms/eklenti_domainleri.txt.
ponytail: runs from GitHub's vantage, so a domain that is alive abroad but ISP-blocked in TR is kept;
fix those by hand (or add a TR proxy) when a provider stays empty."""
import re, sys, urllib.request, urllib.error
from urllib.parse import urlparse

PATH = "doms/eklenti_domainleri.txt"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"

def final_url(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.geturl(), r.status
    except urllib.error.HTTPError as e:
        return e.geturl(), e.code          # 403 (Cloudflare) etc. still tells us the final host
    except Exception:
        return None, None

def base(u):
    p = urlparse(u)
    return f"{p.scheme}://{p.netloc}"

text = open(PATH, encoding="utf-8").read()
out, changes = [], []
for line in text.splitlines():
    m = re.match(r"^\|([^:]+):(https?://\S+)$", line.strip())
    if not m:
        out.append(line); continue
    name, cur = m.group(1), m.group(2)
    fin, code = final_url(cur)
    if fin and code and code < 500 and base(fin).lower() != base(cur).lower() and "google.com" not in fin:
        changes.append(f"{name}: {cur} -> {base(fin)} ({code})")
        line = f"|{name}:{base(fin)}"
    out.append(line)
if changes:
    open(PATH, "w", encoding="utf-8").write("\n".join(out) + "\n")
print("\n".join(changes) if changes else "no changes")
