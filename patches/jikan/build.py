#!/usr/bin/env python3
"""Animely without Jikan: the Jikan API shut down in September 2026.

Animely's detail page looked up the MAL id with api.jikan.moe, outside any try block, so every
detail page failed with a connect timeout. The lookup now reads doms/jikan-empty.json
({"data":[]}), so the plugin skips MAL/AniList metadata and uses the site's own data.
Usage: build.py OUTPUT_DIRECTORY
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

REPO = Path(__file__).resolve().parents[2]
NAME = "Animely"
OLD = '"https://api.jikan.moe/v4/anime?q="'
NEW = '"https://raw.githubusercontent.com/aytzey/cs-kraptor/refs/heads/master/doms/jikan-empty.json?q="'


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    catalog = json.loads((REPO / "plugins.json").read_text())
    item = next(p for p in catalog if p["internalName"] == NAME)
    with tempfile.TemporaryDirectory(prefix="kraptor-jikan-") as temporary:
        work = Path(temporary)
        with zipfile.ZipFile(REPO / (NAME + ".cs3")) as z:
            entries = {n: z.read(n) for n in z.namelist()}
        (work / "classes.dex").write_bytes(entries["classes.dex"])
        run("baksmali", "d", str(work / "classes.dex"), "-o", str(work / "smali"))
        source = work / "smali/com/kraptor/AnimeElysium.smali"
        text = source.read_text()
        assert text.count(OLD) == 1, "Jikan URL not found exactly once"
        source.write_text(text.replace(OLD, NEW))
        run("smali", "a", "-j", "1", str(work / "smali"), "-o", str(work / "fixed.dex"))
        manifest = json.loads(entries["manifest.json"])
        assert manifest["version"] == item["version"], NAME
        manifest["version"] += 1
        entries["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
        entries["classes.dex"] = (work / "fixed.dex").read_bytes()
        target = output / (NAME + ".cs3")
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
            for filename, data in sorted(entries.items()):
                entry = zipfile.ZipInfo(filename, (1980, 2, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(entry, data)
    item.update(version=manifest["version"], fileSize=target.stat().st_size,
                fileHash="sha256-" + hashlib.sha256(target.read_bytes()).hexdigest())
    (output / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    print(f"{NAME}: version {item['version']}, {item['fileSize']} bytes")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: build.py OUTPUT_DIRECTORY")
    build(Path(sys.argv[1]).resolve())
