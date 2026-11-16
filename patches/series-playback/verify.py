#!/usr/bin/env python3
"""Check binary patch scope, identity and catalog integrity against the pinned base."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location("series_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def original(path):
    return subprocess.check_output(["git", "-C", str(build.REPO), "show", build.BASE + ":" + path])


def verify(output):
    old_catalog = json.loads(original("plugins.json"))
    catalog = json.loads((output / "plugins.json").read_text())
    old_rows = {row["internalName"]: row for row in old_catalog}
    rows = {row["internalName"]: row for row in catalog}
    assert len(rows) == len(catalog) == len(old_rows)
    changed = {name for name in rows if rows[name] != old_rows[name]}
    expected = {"DiziBox", "DiziPal", "FilmMakinesi", "FilmModu", "HDFilmCehennemi",
                "HDFilmizle", "KraptorPlus", "UgurFilm", "WFilmizle"}
    assert changed == expected, changed
    with tempfile.TemporaryDirectory(prefix="series-verify-") as temporary:
        work = Path(temporary)
        for name in sorted(changed):
            old_row, row = old_rows[name], rows[name]
            assert {k: v for k, v in old_row.items() if k not in ("version", "fileSize", "fileHash")} == {
                k: v for k, v in row.items() if k not in ("version", "fileSize", "fileHash")}, name
            package = output / (name + ".cs3")
            assert row["fileSize"] == package.stat().st_size, name
            assert row["fileHash"] == "sha256-" + build.hashlib.sha256(package.read_bytes()).hexdigest(), name
            with zipfile.ZipFile(io.BytesIO(original(name + ".cs3"))) as old, zipfile.ZipFile(package) as new:
                assert old.namelist() == new.namelist() or set(old.namelist()) == set(new.namelist()), name
                before, after = json.loads(old.read("manifest.json")), json.loads(new.read("manifest.json"))
                assert after.pop("version") == row["version"] == before.pop("version") + 1, name
                assert before == after, name
                for entry in old.namelist():
                    if entry not in ("manifest.json", "classes.dex"):
                        assert old.read(entry) == new.read(entry), (name, entry)
                dirs = []
                for label, archive in (("old", old), ("new", new)):
                    dex = work / (name + "-" + label + ".dex")
                    dex.write_bytes(archive.read("classes.dex"))
                    directory = work / (name + "-" + label)
                    build.run("baksmali", "d", str(dex), "-o", str(directory))
                    dirs.append({str(p.relative_to(directory)): p.read_text() for p in directory.rglob("*.smali")})
            old_classes, new_classes = dirs
            assert old_classes.keys() <= new_classes.keys(), name
            touched = {path for path in old_classes.keys() | new_classes.keys()
                       if old_classes.get(path) != new_classes.get(path)}
            allowed = {path for path in touched if path.startswith("com/kraptor/HdfilmConfig")}
            if name == "DiziBox":
                allowed = {"com/byayzen/DiziBox.smali", "com/byayzen/DiziBox$CloudflareInterceptor.smali",
                           "com/kraptor/SeriesHeaders.smali"}
            if name == "FilmMakinesi":
                allowed |= {"com/byayzen/FilmMakinesi.smali", "com/byayzen/CloseLoadExtractor$getUrl$2.smali",
                            "com/byayzen/RapidRameExtractor$getUrl$2.smali", "com/kraptor/PageHeaders.smali"}
                allowed |= {path for path in touched if path.startswith("com/kraptor/CloseLoadNative")}
            if name == "DiziPal":
                allowed = {"com/kraptor/DiziPal.smali", "com/kraptor/PageHeaders.smali"}
                allowed |= {path for path in touched if path.startswith("com/kraptor/DiziPalHtml")}
            assert touched <= allowed, (name, touched - allowed)
            assert all(path.startswith(("com/kraptor/", "com/byayzen/")) for path in new_classes.keys() - old_classes.keys()), name
            print(name, "identity/catalog/patch scope passed;", len(touched), "classes changed")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify.py OUTPUT_DIRECTORY")
    verify(Path(sys.argv[1]).resolve())
