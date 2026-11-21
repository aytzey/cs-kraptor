"""Use DiziMom's ordinary HTTPS document path for its public catalog and title pages."""
import json
import runpy
import subprocess
import tempfile
import zipfile
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
base = "d478aaf87aa9df44ceeec98aa13f53fefd68ca7e"
package = "DiziMom.cs3"
tools = runpy.run_path(str(repo / "patches/series-playback/build.py"))


def published(name):
    return subprocess.check_output(["git", "-C", repo, "show", base + ":" + name])


with tempfile.TemporaryDirectory() as temp:
    work = Path(temp)
    (work / package).write_bytes(published(package))
    with zipfile.ZipFile(work / package) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    dex = work / "classes.dex"
    dex.write_bytes(entries["classes.dex"])
    smali = work / "smali"
    tools["run"]("baksmali", "d", str(dex), "-o", str(smali))
    provider = smali / "com/byayzen/DiziMom.smali"
    text = provider.read_text()
    # Null is the provider's existing branch for an ordinary request.
    acquire = "    invoke-direct {v1, v3}, Lcom/byayzen/DiziMom;->getCfCookie(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;\n\n    move-result-object v8"
    assert text.count(acquire) == 1
    provider.write_text(text.replace(acquire, "    const/4 v8, 0x0"))
    tools["run"]("smali", "a", "-j", "1", str(smali), "-o", str(dex))
    entries["classes.dex"] = dex.read_bytes()
    catalog = json.loads(published("plugins.json"))
    tools["write_package"](entries, repo / package, catalog, "DiziMom", 62)
    (repo / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
