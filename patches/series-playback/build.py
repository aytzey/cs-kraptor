#!/usr/bin/env python3
"""Rebuild series repairs against the pinned published binary, without shipping SDK stubs."""
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
BASE = "82086009b90fdbd7b65dd2fa8006858a81ca1ad1"
DX = "/usr/lib/android-sdk/build-tools/29.0.3/dx"
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0"
GET = "get$default(Lcom/lagradost/nicehttp/Requests;Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;ZILjava/util/concurrent/TimeUnit;JLokhttp3/Interceptor;ZLcom/lagradost/nicehttp/ResponseParser;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;"
BROWSER_GET = GET.replace("get$default", "browserGet$default", 1)
ANDROID = "/usr/share/java/com.android.android-23.jar"
JARS = {"okhttp.jar": "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0",
        "jsoup.jar": "5be1ccd3228ae5fd6eed1bd6d827bac2bc65b91c20e9957d16ea65f739f15302"}


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def forwarding_headers(helper):
    text = helper.read_text()
    for method, signature in (("headers", GET), ("browserHeaders", BROWSER_GET)):
        text += (
            "\n.method public static " + signature + "\n"
            "    .registers 17\n"
            f"    invoke-static {{p2}}, Lcom/kraptor/PageHeaders;->{method}(Ljava/util/Map;)Ljava/util/Map;\n"
            "    move-result-object p2\n"
            "    and-int/lit8 p15, p15, -0x3\n"
            "    invoke-static/range {p0 .. p16}, Lcom/lagradost/nicehttp/Requests;->" + GET + "\n"
            "    move-result-object p0\n    return-object p0\n.end method\n")
    return text


def write_package(entries, target, catalog, name, old_version):
    manifest = json.loads(entries["manifest.json"])
    assert manifest["name"] == name and manifest["version"] == old_version
    manifest["version"] += 1
    entries["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for filename, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(filename, (1980, 2, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    item = next(row for row in catalog if row["internalName"] == name)
    assert item["version"] == old_version
    item.update(version=manifest["version"], fileSize=target.stat().st_size,
                fileHash="sha256-" + hashlib.sha256(target.read_bytes()).hexdigest())
    print(name, item["version"], item["fileSize"], item["fileHash"])


def copy_decoder(helpers, smali):
    destination = smali / "com/kraptor"
    assert (destination / "HdfilmConfig.smali").exists()
    for helper in (helpers / "com/kraptor").glob("HdfilmConfig*.smali"):
        shutil.copyfile(helper, destination / helper.name)


def shared_decoder(output, work, helpers, catalog):
    for name, version in (("FilmModu", 43), ("HDFilmCehennemi", 99), ("HDFilmizle", 27),
                          ("KraptorPlus", 89), ("UgurFilm", 37), ("WFilmizle", 25)):
        original = work / (name + ".cs3")
        original.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":" + name + ".cs3"]))
        with zipfile.ZipFile(original) as archive:
            entries = {filename: archive.read(filename) for filename in archive.namelist()}
        dex = work / (name + ".dex")
        dex.write_bytes(entries["classes.dex"])
        smali = work / (name + "-smali")
        run("baksmali", "d", str(dex), "-o", str(smali))
        copy_decoder(helpers, smali)
        run("smali", "a", "-j", "1", str(smali), "-o", str(dex))
        entries["classes.dex"] = dex.read_bytes()
        write_package(entries, output / (name + ".cs3"), catalog, name, version)


def pal(output, work, helpers, catalog):
    original = work / "pal.cs3"
    original.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":DiziPal.cs3"]))
    with zipfile.ZipFile(original) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    (work / "pal.dex").write_bytes(entries["classes.dex"])
    smali = work / "pal-smali"
    run("baksmali", "d", str(work / "pal.dex"), "-o", str(smali))
    provider = smali / "com/kraptor/DiziPal.smali"
    text = provider.read_text()
    text = text.replace('"div.post-item"', '".post-item"')
    for signature in ("getMainPage(IL", "load(Ljava", "loadLinks(Ljava", "search(Ljava/lang/String;I"):
        first = text.index(".method public " + signature)
        last = text.index(".end method", first)
        method = text[first:last]
        assert "Lcom/lagradost/nicehttp/Requests;->" + GET in method
        method = method.replace("Lcom/lagradost/nicehttp/Requests;->" + GET, "Lcom/kraptor/PageHeaders;->" + BROWSER_GET, 1)
        text = text[:first] + method + text[last:]
    for old, new in {"/animeler/": "/anime", "/platform/disney": "/platform/disney-plus",
                     "/platform/hbomax": "/platform/max", "/dizi-kategori/": "/kategori/"}.items():
        assert old in text
        text = text.replace(old, new)
    text = text.replace("meta[property=\\'og:image\\']binary", "meta[property=\\'og:image\\']")
    start = text.index(".method public getMainPage(IL")
    anchor = '    .local v7, "url":Ljava/lang/String;'
    point = text.index(anchor, start) + len(anchor)
    text = text[:point] + ("\n    invoke-static {v11, v1}, Lcom/kraptor/DiziPalHtml;->homeUrl(Ljava/lang/String;I)Ljava/lang/String;\n"
                           "    move-result-object v7\n") + text[point:]
    start = text.index(".method public search(Ljava/lang/String;I")
    point = text.index(anchor, start) + len(anchor)
    text = text[:point] + ("\n    invoke-static {v0, v1, v2}, Lcom/kraptor/DiziPalHtml;->searchUrl(Lcom/lagradost/cloudstream3/MainAPI;Ljava/lang/String;I)Ljava/lang/String;\n"
                           "    move-result-object v7\n") + text[point:]
    start = text.index(".method private static final load$parseEpisodes(")
    point = text.index('    const-string v0, "div.episode-item"', start)
    text = text[:point] + (
        "    invoke-static/range {p0 .. p1}, Lcom/kraptor/DiziPalHtml;->episodes(Lcom/lagradost/cloudstream3/MainAPI;Lorg/jsoup/nodes/Element;)Ljava/util/List;\n"
        "    move-result-object v0\n    if-eqz v0, :pal_legacy_episodes\n    return-object v0\n    :pal_legacy_episodes\n") + text[point:]
    pattern = re.compile(r"(    invoke-virtual(?:/range)? \{[^}]+\}, Lcom/lagradost/nicehttp/NiceResponse;->)getDocument\(\)Lorg/jsoup/nodes/Document;\s+move-result-object (v\d+)")
    assert len(pattern.findall(text)) == 4
    start = text.index(".method public loadLinks(")
    def document(match):
        register = match[2]
        read = match[1] + "getText()Ljava/lang/String;\n    move-result-object " + register + "\n"
        if match.start() >= start and match.start() < text.index(".end method", start):
            assert register == "v6"
            return (
                "    move-object/from16 v6, v23\n"
                "    move-object/from16 v7, p0\n"
                "    invoke-static {v6, v7, v5, v9}, Lcom/kraptor/DiziPalHtml;->play(Lcom/lagradost/nicehttp/NiceResponse;Lcom/lagradost/cloudstream3/MainAPI;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;\n"
                "    move-result-object v6\n"
                "    instance-of v7, v6, Ljava/lang/Boolean;\n"
                "    if-eqz v7, :pal_embed_document\n    return-object v6\n"
                "    :pal_embed_document\n    check-cast v6, Lorg/jsoup/nodes/Document;")
        return read + (f"    invoke-static {{{register}}}, Lcom/kraptor/DiziPalHtml;->document(Ljava/lang/String;)Lorg/jsoup/nodes/Document;\n"
                       f"    move-result-object {register}")
    text = pattern.sub(document, text)
    assert text.count("    const/16 v66, 0x76") == 1
    text = text.replace("    const/16 v66, 0x76", "    const/16 v66, 0x62")
    assert text.count("    const/16 v62, 0x0") == 1
    text = text.replace("    const/16 v62, 0x0",
                        "    invoke-static {}, Lcom/kraptor/DiziPalHtml;->browserScript()Ljava/lang/String;\n"
                        "    move-result-object v6\n    move-object/from16 v62, v6")
    assert text.count('    const-string v6, ".*\\\\.m3u8.*"') == 1
    text = text.replace('    const-string v6, ".*\\\\.m3u8.*"',
                        '    const-string v6, ".*\\\\.m3u8.*|https?://formationfeed\\\\.net/i\\\\?.*"')
    anchor = '    .local v5, "mainRequest":Lokhttp3/Request;'
    assert text.count(anchor) == 1
    text = text.replace(anchor, anchor + (
        "\n    move-object/from16 v6, p0\n"
        "    move-object/from16 v8, v19\n"
        "    invoke-static {v5, v6, v8, v13, v9}, Lcom/kraptor/DiziPalHtml;->resolved(Lokhttp3/Request;Lcom/lagradost/cloudstream3/MainAPI;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Boolean;\n"
        "    move-result-object v6\n"
        "    if-eqz v6, :pal_original_media\n    return-object v6\n    :pal_original_media\n"))
    provider.write_text(text)
    for helper in (helpers / "com/kraptor").glob("DiziPalHtml*.smali"):
        shutil.copyfile(helper, smali / "com/kraptor" / helper.name)
    (smali / "com/kraptor/PageHeaders.smali").write_text(forwarding_headers(helpers / "com/kraptor/PageHeaders.smali"))
    run("smali", "a", "-j", "1", str(smali), "-o", str(work / "pal-fixed.dex"))
    entries["classes.dex"] = (work / "pal-fixed.dex").read_bytes()
    write_package(entries, output / "DiziPal.cs3", catalog, "DiziPal", 90)


def film(output, work, helpers, catalog):
    original = work / "film.cs3"
    original.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":FilmMakinesi.cs3"]))
    with zipfile.ZipFile(original) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    (work / "film.dex").write_bytes(entries["classes.dex"])
    smali = work / "film-smali"
    run("baksmali", "d", str(work / "film.dex"), "-o", str(smali))
    provider = smali / "com/byayzen/FilmMakinesi.smali"
    text = provider.read_text()
    assert text.count("Lcom/lagradost/nicehttp/Requests;->" + GET) == 5
    text = text.replace("Lcom/lagradost/nicehttp/Requests;->" + GET, "Lcom/kraptor/PageHeaders;->" + GET)
    assert text.count('"/filmler/"') == 1
    text = text.replace('"/filmler/"', '"/filmler-fmuyqb/"')
    provider.write_text(text)
    for name in ("CloseLoadExtractor", "RapidRameExtractor"):
        target = smali / "com/byayzen" / (name + "$getUrl$2.smali")
        content = target.read_text()
        start = content.index(".method public final invokeSuspend(")
        point = content.index("    move-object/from16 v1, p0", start)
        descriptor = "Lcom/byayzen/" + name + "$getUrl$2;"
        hook = (
            "    move-object/from16 v0, p0\n"
            f"    iget v1, v0, {descriptor}->label:I\n"
            "    if-nez v1, :native_player_legacy\n"
            f"    iget-object v1, v0, {descriptor}->$url:Ljava/lang/String;\n"
            f"    iget-object v2, v0, {descriptor}->$referer:Ljava/lang/String;\n"
            f"    iget-object v3, v0, {descriptor}->$subtitleCallback:Lkotlin/jvm/functions/Function1;\n"
            f"    iget-object v4, v0, {descriptor}->$callback:Lkotlin/jvm/functions/Function1;\n"
            "    invoke-static {v1, v2, v3, v4, v0}, Lcom/kraptor/CloseLoadNative;->load(Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Z\n"
            "    move-result v1\n"
            "    if-eqz v1, :native_player_legacy\n"
            "    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;\n"
            "    return-object v0\n"
            "    :native_player_legacy\n")
        target.write_text(content[:point] + hook + content[point:])
    for helper in (helpers / "com/kraptor").glob("CloseLoadNative*.smali"):
        shutil.copyfile(helper, smali / "com/kraptor" / helper.name)
    destination = smali / "com/kraptor/PageHeaders.smali"
    destination.write_text(forwarding_headers(helpers / "com/kraptor/PageHeaders.smali"))
    copy_decoder(helpers, smali)
    run("smali", "a", "-j", "1", str(smali), "-o", str(work / "film-fixed.dex"))
    entries["classes.dex"] = (work / "film-fixed.dex").read_bytes()
    write_package(entries, output / "FilmMakinesi.cs3", catalog, "FilmMakinesi", 72)


def build(output, jars):
    for name, digest in JARS.items():
        assert hashlib.sha256((jars / name).read_bytes()).hexdigest() == digest, name
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="series-playback-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        stubs = REPO / "patches"
        run("javac", "--release", "8", "-nowarn", "-Xmaxwarns", "1", "-cp",
            ":".join((ANDROID, str(jars / "okhttp.jar"), str(jars / "jsoup.jar"))), "-d", str(classes),
            *map(str, (stubs / "movie-backup/stubs").rglob("*.java")),
            *map(str, (stubs / "movie-playback/stubs").rglob("*.java")),
            str(stubs / "animeler/stubs/com/lagradost/cloudstream3/utils/ExtractorLinkType.java"),
            str(stubs / "animeler/stubs/kotlin/jvm/functions/Function1.java"),
            str(HERE / "SeriesHeaders.java"), str(HERE / "SeriesHeadersChecks.java"),
            str(HERE / "PageHeaders.java"), str(HERE / "PageHeadersChecks.java"),
            str(HERE / "DiziPalHtml.java"), str(HERE / "DiziPalHtmlChecks.java"),
            str(HERE / "CloseLoadNative.java"), str(HERE / "CloseLoadChecks.java"),
            str(stubs / "movie-playback/HdfilmConfig.java"),
            str(stubs / "movie-playback/DelisiNative.java"), str(stubs / "movie-playback/PlayerChecks.java"))
        run("java", "-cp", str(classes), "com.kraptor.PageHeadersChecks")
        run(DX, "--dex", "--output=" + str(output / "helpers.dex"),
            *[str(p.relative_to(classes)) for p in sorted((classes / "com/kraptor").glob("*.class")) if "Checks" not in p.name], cwd=classes)
        run(DX, "--dex", "--output=" + str(output / "checks.dex"),
            *[str(p.relative_to(classes)) for p in sorted((classes / "com/kraptor").glob("*Checks*.class"))], cwd=classes)
        helpers = work / "helpers"
        run("baksmali", "d", str(output / "helpers.dex"), "-o", str(helpers))
        original = subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":DiziBox.cs3"])
        original_path = work / "original.cs3"
        original_path.write_bytes(original)
        with zipfile.ZipFile(original_path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        (work / "original.dex").write_bytes(entries["classes.dex"])
        smali = work / "smali"
        run("baksmali", "d", str(work / "original.dex"), "-o", str(smali))
        interceptor = smali / "com/byayzen/DiziBox$CloudflareInterceptor.smali"
        text = interceptor.read_text()
        anchor = "    invoke-interface {p1, v0}, Lokhttp3/Interceptor$Chain;->proceed(Lokhttp3/Request;)Lokhttp3/Response;"
        assert text.count(anchor) == 1
        text = text.replace(anchor, (
            "    invoke-virtual {v0}, Lokhttp3/Request;->newBuilder()Lokhttp3/Request$Builder;\n"
            "    move-result-object v1\n"
            '    const-string v2, "User-Agent"\n'
            f'    const-string v3, "{USER_AGENT}"\n'
            "    invoke-virtual {v1, v2, v3}, Lokhttp3/Request$Builder;->header(Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Request$Builder;\n"
            "    move-result-object v1\n"
            "    invoke-virtual {v1}, Lokhttp3/Request$Builder;->build()Lokhttp3/Request;\n"
            "    move-result-object v0\n" + anchor))
        interceptor.write_text(text)
        provider = smali / "com/byayzen/DiziBox.smali"
        text = provider.read_text()
        anchor = '    .local v0, "episodeList":Ljava/util/List;'
        assert text.count(anchor) == 1
        text = text.replace(anchor, (
            "    invoke-static {v0}, Ljava/util/Collections;->synchronizedList(Ljava/util/List;)Ljava/util/List;\n"
            "    move-result-object v0\n" + anchor))
        start = text.index(".method public loadLinks(")
        anchor = "    move-object/from16 v2, p1"
        point = text.index(anchor, start)
        text = text[:point] + (
            "    invoke-static/range {p4 .. p4}, Lcom/kraptor/SeriesHeaders;->wrap(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;\n"
            "    move-result-object p4\n\n") + text[point:]
        provider.write_text(text)
        destination = smali / "com/kraptor"
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(helpers / "com/kraptor/SeriesHeaders.smali", destination / "SeriesHeaders.smali")
        run("smali", "a", "-j", "1", str(smali), "-o", str(work / "fixed.dex"))
        entries["classes.dex"] = (work / "fixed.dex").read_bytes()
        catalog = json.loads(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":plugins.json"]))
        write_package(entries, output / "DiziBox.cs3", catalog, "DiziBox", 38)
        film(output, work, helpers, catalog)
        pal(output, work, helpers, catalog)
        shared_decoder(output, work, helpers, catalog)
        (output / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: build.py OUTPUT_DIRECTORY JARS_DIRECTORY")
    build(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
