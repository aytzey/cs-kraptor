#!/usr/bin/env python3
"""Build movie backups. JARS contains compile-only okhttp.jar (4.12.0), jsoup.jar (1.18.3)."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BASE = "1bf7209ae6b28141291752cc880843ec31ae21fa"
ANDROID = "/usr/share/java/com.android.android-23.jar"
DX = "/usr/lib/android-sdk/build-tools/29.0.3/dx"
D8 = "/home/aytzey/dev-tools/android-sdk/build-tools/36.0.0/d8"
PACKAGES = {"FilmModu": "com/kraptor", "HDFilmizle": "com/byayzen",
    "FilmMakinesi": "com/byayzen", "WFilmizle": "com/byayzen", "UgurFilm": "com/kraptor"}
JARS = {"okhttp.jar": "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0",
    "jsoup.jar": "5be1ccd3228ae5fd6eed1bd6d827bac2bc65b91c20e9957d16ea65f739f15302"}


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def change_method(path, signature, edit):
    text = path.read_text()
    start = text.index(".method public " + signature)
    end = text.index(".end method", start)
    path.write_text(text[:start] + edit(text[start:end]) + text[end:])


def remember(method):
    return re.sub(r"^    return-object ([vp][0-9]+)$", lambda m:
        f"    invoke-static/range {{{m[1]} .. {m[1]}}}, Lcom/kraptor/MovieBackup;->remember(Ljava/lang/Object;)Ljava/lang/Object;\n"
        f"    move-result-object {m[1]}\n" + m[0], method, flags=re.M)


def adapter(path, directory, package, name):
    targets = {
        "search": ("searchOriginal", "search", "Ljava/lang/String;ILkotlin/coroutines/Continuation;", 4),
        "getMainPage": ("homeOriginal", "home", "ILcom/lagradost/cloudstream3/MainPageRequest;Lkotlin/coroutines/Continuation;", 4),
        "load": ("loadOriginal", "load", "Ljava/lang/String;Lkotlin/coroutines/Continuation;", 3),
        "loadLinks": ("linksOriginal", "links", "Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;", 6),
    }
    if name=="HDFilmizle":
        targets["search"]=("searchOriginal","searchLegacy","Ljava/lang/String;Lkotlin/coroutines/Continuation;",3)
    text = path.read_text()
    for method, (original, helper, signature, count) in targets.items():
        header = f".method public {method}({signature})Ljava/lang/Object;"
        assert text.count(header)==1, (name,method)
        text = text.replace(header, header.replace(method+"(",original+"(",1))
        text += (f"\n{header}\n    .registers {count+1}\n"
            f"    invoke-static/range {{p0 .. p{count-1}}}, Lcom/kraptor/BackupAdapter;->{helper}(Lcom/lagradost/cloudstream3/MainAPI;{signature})Ljava/lang/Object;\n"
            "    move-result-object v0\n    return-object v0\n.end method\n")
        continuation = directory/(name+"$"+method+"$1.smali")
        old = f"L{package}/{name};->{method}("
        content = continuation.read_text()
        assert content.count(old)==2, continuation.name
        continuation.write_text(content.replace(old,f"L{package}/{name};->{original}("))
    path.write_text(text)


def build(output, jars):
    for name, digest in JARS.items():
        if hashlib.sha256((jars/name).read_bytes()).hexdigest()!=digest:
            raise ValueError("Unexpected compile-only library: "+name)
    output.mkdir(parents=True, exist_ok=True)
    cp = ":".join((ANDROID, str(jars/"okhttp.jar"), str(jars/"jsoup.jar")))
    with tempfile.TemporaryDirectory(prefix="kraptor-backup-") as temporary:
        work = Path(temporary)
        classes = work/"classes"
        classes.mkdir()
        old = REPO/"patches/animeler/stubs"
        run("javac", "--release", "8", "-nowarn", "-Xmaxwarns", "1", "-cp", cp, "-d", str(classes),
            *map(str, (HERE/"stubs").rglob("*.java")),
            *map(str, (REPO/"patches/movie-playback/stubs").rglob("*.java")),
            str(old/"kotlin/jvm/functions/Function1.java"),
            str(old/"com/lagradost/cloudstream3/utils/ExtractorLinkType.java"),
            str(REPO/"patches/movie-playback/HdfilmConfig.java"), str(HERE/"MovieBackup.java"), str(HERE/"BackupAdapter.java"))
        run(DX, "--dex", "--output="+str(output/"helpers.dex"),
            *[str(p.relative_to(classes)) for p in sorted((classes/"com/kraptor").glob("*.class"))], cwd=classes)
        helpers = work/"helpers"
        run("baksmali", "d", str(output/"helpers.dex"), "-o", str(helpers))
        run("javac", "--release", "8", "-nowarn", "-cp", str(classes)+":"+cp, "-d", str(classes), str(HERE/"BackupChecks.java"), str(HERE/"AdapterChecks.java"))
        tests = work/"tests"
        tests.mkdir()
        run(D8, "--min-api", "23", "--lib", ANDROID, "--classpath", str(classes), "--output", str(tests),
            *map(str,sorted((classes/"com/kraptor").glob("*Checks*.class"))))
        shutil.copyfile(tests/"classes.dex", output/"backup-checks.dex")

        catalog = json.loads(subprocess.check_output(["git", "-C", str(REPO), "show", BASE+":plugins.json"]))
        for name, package in PACKAGES.items():
            directory = work/name
            directory.mkdir()
            original = directory/"original.cs3"
            original.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", BASE+":"+name+".cs3"]))
            with zipfile.ZipFile(original) as archive:
                entries = {n:archive.read(n) for n in archive.namelist()}
            dex = directory/"classes.dex"
            dex.write_bytes(entries["classes.dex"])
            smali = directory/"smali"
            run("baksmali", "d", str(dex), "-o", str(smali))
            target = smali/package/(name+".smali")
            change_method(target, "load(Ljava/lang/String;Lkotlin/coroutines/Continuation;)", remember)
            adapter(target,smali/package,package,name)
            plugin = smali/package/(name+"Plugin.smali")
            text = plugin.read_text()
            setup = f"    invoke-virtual {{v0, p1}}, L{package}/{name}Helper;->setup(Landroid/content/Context;)V"
            assert text.count(setup)==1, name
            plugin.write_text(text.replace(setup, setup+"\n    invoke-static {p1}, Lcom/kraptor/MovieBackup;->setup(Landroid/content/Context;)V"))
            destination = smali/"com/kraptor"
            destination.mkdir(parents=True, exist_ok=True)
            for helper in (helpers/"com/kraptor").glob("*.smali"):
                shutil.copyfile(helper,destination/helper.name)
            run("smali", "a", "-j", "1", str(smali), "-o", str(directory/"fixed.dex"))
            manifest = json.loads(entries["manifest.json"])
            item = next((p for p in catalog if p["internalName"]==name),None)
            if item is None:
                assert name=="UgurFilm"
                item = dict(url="https://raw.githubusercontent.com/aytzey/cs-kraptor/builds/UgurFilm.cs3",
                    status=1,version=manifest["version"],name=name,internalName=name,authors=["kraptor"],
                    description="UğurFilm arşivi.",
                    repositoryUrl="https://github.com/aytzey/cs-kraptor",language="tr",tvTypes=["Movie"],apiVersion=1)
                catalog.append(item)
            assert manifest["version"]==item["version"], name
            manifest["version"]+=1
            entries["manifest.json"] = json.dumps(manifest,separators=(",",":")).encode()
            entries["classes.dex"] = (directory/"fixed.dex").read_bytes()
            target = output/(name+".cs3")
            with zipfile.ZipFile(target,"w",zipfile.ZIP_DEFLATED) as archive:
                for filename,data in sorted(entries.items()):
                    entry = zipfile.ZipInfo(filename,(1980,2,1,0,0,0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry,data)
            item.update(version=manifest["version"],fileSize=target.stat().st_size,
                fileHash="sha256-"+hashlib.sha256(target.read_bytes()).hexdigest())
            item["description"] += " Kaynak açılamazsa eşleşen film için HDFilmCehennemi yedeğini kullanır."
            print(name, item["version"], item["fileSize"])
        (output/"plugins.json").write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+"\n")


if __name__ == "__main__":
    if len(sys.argv)!=3:
        raise SystemExit("usage: build.py OUTPUT JARS_DIRECTORY")
    build(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
