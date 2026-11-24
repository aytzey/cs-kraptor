#!/usr/bin/env python3
"""Make Cloudflare-protected plugins pass the browser challenge.

The published HDFilmCehennemi 105 shipped a Kotlin `CfSolver` that never ran (detail requests passed
`interceptor = null`), recursed into itself through its wrapper (StackOverflowError, which put
CloudStream into safe mode), and read the WebView User-Agent off the UI thread (always failed).
This rebuild replaces it with `CfSolver.java`, routes the challenged call sites through the plugin's
interceptor, bumps the plugin version and refreshes the catalog entry.

Usage: build.py OUTPUT_DIRECTORY
"""
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
BASE_REF = "3e4fbf2"  # last published builds revision
# Requests.get and the PageHeaders wrapper share the same 17-register parameter layout.
CALLS = ("Lcom/lagradost/nicehttp/Requests;->get$default(", "Lcom/kraptor/PageHeaders;->get$default(")
INTERCEPTOR_BIT = 0x200  # 10th optional parameter of Requests.get
ANDROID = Path("/home/aytzey/dev-tools/android-sdk/platforms/android-33/android.jar")
D8 = Path("/home/aytzey/dev-tools/android-sdk/build-tools/36.0.0/lib/d8.jar")
OKHTTP = Path("/home/aytzey/.agent-reach/kraptor-movie-fallback-qa-20261116/okhttp.jar")
OKHTTP_SHA256 = "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0"

HDF_INTERCEPTOR = ("invoke-direct/range {{p0 .. p0}}, Lcom/kraptor/HDFilmCehennemi;->getInterceptor()"
                   "Lcom/kraptor/HDFilmCehennemi$CloudflareInterceptor;\n    move-result-object v{reg}")
FM_PACED = "sget-object v{reg}, Lcom/kraptor/CfSolver;->%s:Lokhttp3/Interceptor;"

# plugin -> (smali class, {method whose page requests get an interceptor: smali that stores it in register {reg}})
TARGETS = {
    "HDFilmCehennemi": (
        "com/kraptor/HDFilmCehennemi.smali",
        {"load(": HDF_INTERCEPTOR, "loadLinks(": HDF_INTERCEPTOR},
    ),
    "FilmMakinesi": (
        "com/byayzen/FilmMakinesi.smali",
        {"homeOriginal(": FM_PACED % "PACED_HOME", "searchOriginal(": FM_PACED % "PACED",
         "loadOriginal(": FM_PACED % "PACED", "linksOriginal(": FM_PACED % "PACED"},
    ),
}


LOAD_EXTRACTOR = ("Lcom/lagradost/cloudstream3/utils/ExtractorApiKt;->loadExtractor(Ljava/lang/String;Ljava/lang/String;"
                  "Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")
CLOSE_PLAYER = LOAD_EXTRACTOR.replace("Lcom/lagradost/cloudstream3/utils/ExtractorApiKt;->loadExtractor",
                                      "Lcom/kraptor/HdfPlayer;->load")

# Exact smali edits applied after routing: (plugin, file, old, new[, occurrences]). Old text must occur
# exactly `occurrences` times (default once).
FIXES = [
    # HDFilmCehennemi's "Close" player now hides its stream behind a scripted POST; HdfPlayer runs it.
    ("HDFilmCehennemi", "com/kraptor/HDFilmCehennemi.smali", LOAD_EXTRACTOR, CLOSE_PLAYER),
    # FilmMakinesi's closeload.<site> runs the same Close player; its bundled CloseLoadExtractor still waits
    # for jwplayer().setup(configs), which the page no longer calls. Both iframe loops go through HdfPlayer.
    ("FilmMakinesi", "com/byayzen/FilmMakinesi.smali", LOAD_EXTRACTOR, CLOSE_PLAYER, 2),
    # FilmMakinesi answers the app with a ten-minute block above about a dozen page requests a minute, so its
    # requests are paced (CfSolver.PACED). CloudStream shows the home page only once every section has loaded:
    # 14 sections took over two minutes on the Z1Mini, so the home page keeps the first five (latest films,
    # Netflix, Disney, Amazon, "Ölmeden İzle"); genres are still reachable through search.
    ("FilmMakinesi", "com/byayzen/FilmMakinesi.smali",
     ".method public getMainPage()Ljava/util/List;\n    .registers 2\n",
     ".method public getMainPage()Ljava/util/List;\n    .registers 4\n"),
    ("FilmMakinesi", "com/byayzen/FilmMakinesi.smali",
     "    iget-object v0, p0, Lcom/byayzen/FilmMakinesi;->mainPage:Ljava/util/List;\n\n    return-object v0\n",
     "    iget-object v0, p0, Lcom/byayzen/FilmMakinesi;->mainPage:Ljava/util/List;\n\n"
     "    const/4 v1, 0x0\n\n    const/4 v2, 0x5\n\n"
     "    invoke-interface {v0, v1, v2}, Ljava/util/List;->subList(II)Ljava/util/List;\n\n"
     "    move-result-object v0\n\n    return-object v0\n"),
    # The 105 PeerPlayback hook runs on every entry of the loadLinks state machine. Kotlin re-enters
    # suspended methods with null arguments, so owns(null) threw after the first network call.
    ("HDFilmCehennemi", "com/kraptor/HDFilmCehennemi.smali",
     "    invoke-static/range {p1 .. p1}, Lcom/kraptor/PeerPlayback;->owns(Ljava/lang/String;)Z\n\n    move-result v0\n\n    if-eqz v0, :cond_16\n",
     "    if-eqz p1, :cond_16\n\n    invoke-static/range {p1 .. p1}, Lcom/kraptor/PeerPlayback;->owns(Ljava/lang/String;)Z\n\n    move-result v0\n\n    if-eqz v0, :cond_16\n"),
    # MovieBackup's own client: solve challenges too, and let a solve (5-10 s) fit in the call budget.
    ("FilmMakinesi", "com/kraptor/MovieBackup.smali",
     "    invoke-virtual {v0}, Lokhttp3/OkHttpClient;->newBuilder()Lokhttp3/OkHttpClient$Builder;\n\n    move-result-object v0\n",
     "    invoke-virtual {v0}, Lokhttp3/OkHttpClient;->newBuilder()Lokhttp3/OkHttpClient$Builder;\n\n    move-result-object v0\n\n"
     "    sget-object v2, Lcom/kraptor/CfSolver;->INTERCEPTOR:Lokhttp3/Interceptor;\n\n"
     "    invoke-virtual {v0, v2}, Lokhttp3/OkHttpClient$Builder;->addInterceptor(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient$Builder;\n\n"
     "    move-result-object v0\n"),
    ("FilmMakinesi", "com/kraptor/MovieBackup.smali", "    const-wide/16 v2, 0x8\n", "    const-wide/16 v2, 0x2d\n"),
    # Plugins load before CloudStream first creates a WebView: the only moment the kernel can be swapped.
    ("HDFilmCehennemi", "com/kraptor/HDFilmCehennemiPlugin.smali",
     "    .line 26\n    sget-object v0, Lcom/kraptor/GlobalPluginChecker;",
     "    invoke-static {p1}, Lcom/kraptor/WebViewKernel;->prepare(Landroid/content/Context;)V\n\n"
     "    .line 26\n    sget-object v0, Lcom/kraptor/GlobalPluginChecker;"),
    ("FilmMakinesi", "com/byayzen/FilmMakinesiPlugin.smali",
     "    .line 26\n    sget-object v0, Lcom/byayzen/GlobalPluginChecker;",
     "    invoke-static {p1}, Lcom/kraptor/WebViewKernel;->prepare(Landroid/content/Context;)V\n\n"
     "    .line 26\n    sget-object v0, Lcom/byayzen/GlobalPluginChecker;"),
]


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def route(text, methods):
    """Give every Requests.get call in each method its interceptor; return (text, patched call count)."""
    count = 0
    for name, getter in methods.items():
        if ".method public " + name not in text:
            continue
        start = text.index(".method public " + name)
        end = text.index(".end method", start)
        body = text[start:end]
        out, last = [], 0
        calls = "|".join(map(re.escape, CALLS))
        for call in re.finditer(r"^    invoke-static/range \{v(\d+) \.\. v(\d+)\}, (?:" + calls + ")", body, re.M):
            first = int(call.group(1))
            interceptor, mask = first + 11, first + 15
            masks = re.findall(r"const(?:/4|/16)? v%d, (0x[0-9a-f]+)\n" % mask, body[:call.start()])
            assert masks, f"no mask constant for {name} v{mask}"
            value = int(masks[-1], 16)
            assert value & INTERCEPTOR_BIT, f"{name}: interceptor already passed"
            out.append(body[last:call.start()])
            out.append("    " + getter.format(reg=interceptor) + "\n"
                       f"    const/16 v{mask}, {hex(value & ~INTERCEPTOR_BIT)}\n\n")
            last = call.start()
            count += 1
        out.append(body[last:])
        text = text[:start] + "".join(out) + text[end:]
    return text, count


def solver_smali(work):
    """Compile CfSolver.java (stubs stay out of the dex) and return its smali directory."""
    assert hashlib.sha256(OKHTTP.read_bytes()).hexdigest() == OKHTTP_SHA256
    classes = work / "solver-classes"
    run("javac", "--release", "8", "-nowarn", "-cp", f"{ANDROID}:{OKHTTP}", "-d", str(classes),
        str(HERE / "CfSolver.java"), str(HERE / "WebViewKernel.java"), str(HERE / "HdfPlayer.java"), *map(str, (HERE / "stubs").rglob("*.java")))
    stubs = work / "stubs.jar"
    run("jar", "cf", str(stubs), "-C", str(classes), "com/lagradost", "-C", str(classes), "kotlin")
    run("java", "-cp", str(D8), "com.android.tools.r8.D8", "--release", "--min-api", "23",
        "--lib", str(ANDROID), "--classpath", str(OKHTTP), "--classpath", str(stubs),
        "--output", str(work), *map(str, sorted((classes / "com/kraptor").glob("*.class"))))
    smali = work / "solver-smali"
    run("baksmali", "d", str(work / "classes.dex"), "-o", str(smali))
    (work / "classes.dex").unlink()
    return smali / "com/kraptor"


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    base = subprocess.check_output(["git", "-C", str(REPO), "rev-parse", BASE_REF + "^{commit}"], text=True).strip()
    base_catalog = {p["internalName"]: p for p in
                    json.loads(subprocess.check_output(["git", "-C", str(REPO), "show", base + ":plugins.json"]))}
    # Versions continue from the published catalog, so each rebuild reaches devices as an update.
    catalog = json.loads((REPO / "plugins.json").read_text())
    with tempfile.TemporaryDirectory(prefix="kraptor-cf-route-") as temporary:
        work = Path(temporary)
        solver = solver_smali(work)
        for name, (path, methods) in TARGETS.items():
            archive = work / (name + ".cs3")
            archive.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", f"{base}:{name}.cs3"]))
            with zipfile.ZipFile(archive) as z:
                entries = {n: z.read(n) for n in z.namelist()}
            (work / "plugin.dex").write_bytes(entries["classes.dex"])
            smali = work / (name + "-smali")
            run("baksmali", "d", str(work / "plugin.dex"), "-o", str(smali))
            source = smali / path
            patched, count = route(source.read_text(), methods)
            assert count >= 1, f"{name}: no call sites patched"
            source.write_text(patched)
            for plugin, file, before, after, *occurrences in FIXES:
                if plugin == name:
                    target_file = smali / file
                    text = target_file.read_text()
                    expected = occurrences[0] if occurrences else 1
                    assert text.count(before) == expected, f"{name}: expected {expected} fix sites in {file}"
                    target_file.write_text(text.replace(before, after))
            package = smali / "com/kraptor"
            for old in package.glob("CfSolver*.smali"):
                old.unlink()
            for new in solver.glob("*.smali"):
                shutil.copyfile(new, package / new.name)
            run("smali", "a", "-j", "1", str(smali), "-o", str(work / "fixed.dex"))
            manifest = json.loads(entries["manifest.json"])
            item = next(p for p in catalog if p["internalName"] == name)
            assert manifest["version"] == base_catalog[name]["version"], name
            manifest["version"] = item["version"] + 1
            entries["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
            entries["classes.dex"] = (work / "fixed.dex").read_bytes()
            target = output / (name + ".cs3")
            with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
                for filename, data in sorted(entries.items()):
                    entry = zipfile.ZipInfo(filename, (1980, 2, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    z.writestr(entry, data)
            item.update(version=manifest["version"], fileSize=target.stat().st_size,
                        fileHash="sha256-" + hashlib.sha256(target.read_bytes()).hexdigest())
            print(f"{name}: {count} call sites, version {item['version']}, {item['fileSize']} bytes")
    (output / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: build.py OUTPUT_DIRECTORY")
    build(Path(sys.argv[1]).resolve())
