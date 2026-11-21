"""Rebuild the provider against the published base. Pass the Gradle lib directory and Android build-tools directory."""
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
BASE = "4dd3805a5a189cbcc73b96302c455ff8e573992e"
RELEASE_BASE = "f8c55e0fb8a3b3630fc4406391f8e64be9d86950"
NAME = "HDFilmCehennemi"
OLD = runpy.run_path(str(HERE.parent / "series-playback/build.py"))


def once(text, anchor, insert):
    assert text.count(anchor) == 1, anchor
    return text.replace(anchor, insert + anchor)


def hooks(text):
    owner = "Lcom/kraptor/PeerPlayback;->"
    detail = text.index(".method public load(")
    links = text.index(".method public loadLinks(", detail)
    end = text.index(".end method", links)
    method = once(text[detail:links], "    invoke-virtual {v11}, Lcom/lagradost/nicehttp/NiceResponse;->getDocument()", f"""
    invoke-virtual {{v11}}, Lcom/lagradost/nicehttp/NiceResponse;->getCode()I
    move-result v12
    const/16 v13, 403
    if-ne v12, v13, :read_original_detail
    move-object/from16 v12, p0
    invoke-virtual {{v9}}, Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;->getCompletion()Lkotlin/coroutines/Continuation;
    move-result-object v13
    invoke-static {{v12, v10, v13}}, {owner}blocked(Lcom/lagradost/cloudstream3/MainAPI;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
    move-result-object v12
    return-object v12
    :read_original_detail
""")
    anchor = "    move-object/from16 v1, p5\n\n    instance-of"
    dispatch = once(text[links:end], anchor, f"""
    invoke-static/range {{p1 .. p1}}, {owner}owns(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :original_links
    sget-object v0, Lcom/kraptor/HDFilmCehennemiHelper;->INSTANCE:Lcom/kraptor/HDFilmCehennemiHelper;
    invoke-virtual {{v0}}, Lcom/kraptor/HDFilmCehennemiHelper;->isAllowedVersion()Z
    move-result v0
    if-nez v0, :peer_links
    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    return-object v0
    :peer_links
    invoke-static/range {{p1 .. p5}}, {owner}links(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
    move-result-object v0
    return-object v0
    :original_links
""")
    before = once(text[:detail], '    .line 170\n    const-string v1, "img"', f"""
    const-string v1, ".poster-meta > span:first-child"
    invoke-virtual {{p1, v1}}, Lorg/jsoup/nodes/Element;->selectFirst(Ljava/lang/String;)Lorg/jsoup/nodes/Element;
    move-result-object v5
    if-eqz v5, :no_card_year
    invoke-virtual {{v5}}, Lorg/jsoup/nodes/Element;->text()Ljava/lang/String;
    move-result-object v5
    invoke-static {{v7, v6, v5}}, {owner}remember(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    :no_card_year
""")
    return before + method + dispatch + text[end:]


def build(output, lib, android):
    run = OLD["run"]
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        repo = HERE.parents[1]
        android_jar = android.parents[1] / "platforms/android-33/android.jar"
        def published(file, revision=BASE):
            return subprocess.check_output(["git", "-C", repo, "show", revision + ":" + file])
        archive = work / "base.cs3"
        archive.write_bytes(published(NAME + ".cs3"))
        with zipfile.ZipFile(archive) as z:
            entries = {name: z.read(name) for name in z.namelist()}
        dex = work / "base.dex"
        dex.write_bytes(entries["classes.dex"])
        original = work / "original"
        run("baksmali", "d", str(dex), "-o", str(original))
        source = original / "com/kraptor/HDFilmCehennemi.smali"
        source.write_text(hooks(source.read_text()))
        classes = work / "classes"
        stdlib, = lib.glob("kotlin-stdlib-[0-9]*.jar")
        run("java", "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-language-version", "1.9", "-api-version", "1.9", "-no-reflect", "-no-stdlib",
            "-classpath", str(stdlib) + ":" + str(android_jar), "-d", str(classes), *map(str, sorted(HERE.glob("*.kt"))))
        checks = work / "checks"
        run("java", "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-language-version", "1.9", "-no-reflect", "-no-stdlib", "-classpath",
            ":".join(map(str, (classes, stdlib, android_jar))), "-d", str(checks), str(HERE / "checks/PeerChecks.kt"))
        run("java", "-cp", ":".join(map(str, (classes, stdlib, checks, android_jar))), "com.kraptor.PeerChecksKt")
        own = sorted((classes / "com/kraptor").glob("*.class"))
        assert own
        out = work / "helper"
        out.mkdir()
        run(str(android / "d8"), "--min-api", "23", "--classpath", str(stdlib),
            "--lib", str(android_jar), "--output", str(out), *map(str, own))
        run("baksmali", "d", str(out / "classes.dex"), "-o", str(out / "smali"))
        files = list((out / "smali").rglob("*.smali"))
        assert all(f.parent.relative_to(out / "smali").as_posix() == "com/kraptor" and f.name.startswith(("PeerPlayback", "ManualVerification")) for f in files)
        for f in files:
            shutil.copyfile(f, source.parent / f.name)
        run("smali", "a", "-j", "1", str(original), "-o", str(dex))
        entries["classes.dex"] = dex.read_bytes()
        catalog = json.loads(published("plugins.json", RELEASE_BASE))
        prior = work / "previous.cs3"
        prior.write_bytes(published(NAME + ".cs3", RELEASE_BASE))
        with zipfile.ZipFile(prior) as z:
            entries["manifest.json"] = z.read("manifest.json")
        OLD["write_package"](entries, output / (NAME + ".cs3"), catalog, NAME, 102)
        (output / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    build(*map(Path, sys.argv[1:]))
