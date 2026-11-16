#!/usr/bin/env python3
"""Rebuild the four movie packages from the last published builds revision."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BASE = "8b9db13f972e46bc8570fab0cec7b704c6495d1f"
ANDROID = "/usr/share/java/com.android.android-23.jar"
DX = "/usr/lib/android-sdk/build-tools/29.0.3/dx"
PACKAGES = ("FullHDFilmizlesene", "HDFilmCehennemi", "HDFilmDelisi", "KraptorPlus")


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def replace(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise ValueError(f"Expected exactly one patch site in {path.name}: {old!r}")
    path.write_text(text.replace(old, new))


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kraptor-movies-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        stubs = REPO / "patches/animeler/stubs"
        run("javac", "--release", "8", "-nowarn", "-cp", ANDROID, "-d", str(classes),
            *map(str, (HERE / "stubs").rglob("*.java")),
            str(stubs / "kotlin/jvm/functions/Function1.java"),
            str(stubs / "com/lagradost/cloudstream3/utils/ExtractorLink.java"),
            str(stubs / "com/lagradost/cloudstream3/utils/ExtractorLinkType.java"),
            str(HERE / "HdfilmConfig.java"), str(HERE / "DelisiNative.java"))
        run(DX, "--dex", "--output=" + str(output / "helpers.dex"),
            *[str(p.relative_to(classes)) for p in sorted((classes / "com/kraptor").glob("*.class"))], cwd=classes)
        helpers = work / "helpers"
        run("baksmali", "d", str(output / "helpers.dex"), "-o", str(helpers))
        run("javac", "--release", "8", "-cp", str(classes) + ":" + ANDROID, "-d", str(classes), str(HERE / "PlayerChecks.java"))
        run(DX, "--dex", "--output=" + str(output / "player-checks.dex"), "com/kraptor/PlayerChecks.class", cwd=classes)

        catalog = json.loads(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":plugins.json"]))
        for name in PACKAGES:
            directory = work / name
            directory.mkdir()
            original = directory / "original.cs3"
            original.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":" + name + ".cs3"]))
            with zipfile.ZipFile(original) as archive:
                entries = {n: archive.read(n) for n in archive.namelist()}
            (directory / "classes.dex").write_bytes(entries["classes.dex"])
            smali = directory / "smali"
            run("baksmali", "d", str(directory / "classes.dex"), "-o", str(smali))
            package = smali / "com/kraptor"
            if name == "FullHDFilmizlesene":
                replace(package / "RapidVid.smali", "https://rapidvid.net", "https://rapidvid.org")
            if name in ("HDFilmCehennemi", "KraptorPlus"):
                for helper in (helpers / "com/kraptor").glob("HdfilmConfig*.smali"):
                    shutil.copyfile(helper, package / helper.name)
                replace(package / "HdFilmCehennemiRapid.smali",
                    '    const-string v0, "https://www.hdfilmcehennemi.ws"',
                    '    invoke-static {p1}, Lcom/kraptor/HdfilmConfig;->origin(Landroid/content/Context;)Ljava/lang/String;\n    move-result-object v0')
                replace(package / "HDFilmCehennemiExtractor$getUrl$2.smali", "    const/16 v24, 0x0\n",
                    '    const-string v11, "User-Agent"\n'
                    '    const-string v12, "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0"\n'
                    '    invoke-static {v11, v12}, Ljava/util/Collections;->singletonMap(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;\n'
                    '    move-result-object v11\n    move-object/from16 v24, v11\n')
                replace(package / "HDFilmCehennemiExtractor$getUrl$2.smali", "const/16 v37, 0xffa", "const/16 v37, 0xff8")
                replace(package / "HDFilmCehennemiExtractor.smali", '    const-string v0, "[WEBVIEW_CREATE] ========================================"',
                    '    invoke-static/range {p3 .. p3}, Lcom/kraptor/HdfilmConfig;->parse(Ljava/lang/String;)Ljava/lang/String;\n'
                    '    move-result-object v0\n    if-eqz v0, :legacy_webview\n    move-object/from16 v1, p5\n'
                    '    invoke-interface {v1, v0}, Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;\n'
                    '    const/4 v0, 0x0\n    return-object v0\n    :legacy_webview\n\n'
                    '    const-string v0, "[WEBVIEW_CREATE] ========================================"')
            if name == "HDFilmDelisi":
                package.mkdir(exist_ok=True)
                shutil.copyfile(helpers / "com/kraptor/DelisiNative.smali", package / "DelisiNative.smali")
                replace(smali / "com/byayzen/HDFilmDelisi.smali", "    :cond_239\n    :try_start_239\n",
                    '    :cond_239\n    move-object/from16 v5, p1\n    move-object/from16 v6, p4\n'
                    '    invoke-static {v5, v6}, Lcom/kraptor/DelisiNative;->load(Ljava/lang/String;Lkotlin/jvm/functions/Function1;)Z\n'
                    '    move-result v0\n    if-eqz v0, :legacy_delisi\n'
                    '    sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\n    return-object v0\n'
                    '    :legacy_delisi\n    :try_start_239\n')
            run("smali", "a", "-j", "1", str(smali), "-o", str(directory / "fixed.dex"))
            manifest = json.loads(entries["manifest.json"])
            item = next(p for p in catalog if p["internalName"] == name)
            assert manifest["version"] == item["version"], name
            manifest["version"] += 1
            entries["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
            entries["classes.dex"] = (directory / "fixed.dex").read_bytes()
            target = output / (name + ".cs3")
            with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
                for filename, data in sorted(entries.items()):
                    entry = zipfile.ZipInfo(filename, (1980, 2, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, data)
            item.update(version=manifest["version"], fileSize=target.stat().st_size,
                fileHash="sha256-" + hashlib.sha256(target.read_bytes()).hexdigest())
            print(f"{name}: version {item['version']}, {item['fileSize']} bytes")
        (output / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: build.py OUTPUT_DIRECTORY")
    build(Path(sys.argv[1]).resolve())
