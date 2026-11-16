#!/usr/bin/env python3
"""Patch the published InatBox binary; uses javac, dx, smali and baksmali."""
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
BASE = "4d894906dadc289801aa34697e24f15da9dd6e89"
DX = "/usr/lib/android-sdk/build-tools/29.0.3/dx"
OLD_4K = "https://4k.filmizleeeee.cfd/4k/01/public/catalog-exo.php"


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="inatbox-regex-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        run("javac", "--release", "8", "-d", str(classes), str(HERE / "InatRegex.java"),
            str(HERE / "InatRegexChecks.java"), str(HERE / "InatDirectChecks.java"))
        run("java", "-cp", str(classes), "com.kraptor.InatRegexChecks")
        run(DX, "--dex", "--output=" + str(output / "helpers.dex"), "com/kraptor/InatRegex.class", cwd=classes)
        run(DX, "--dex", "--output=" + str(output / "checks.dex"), "com/kraptor/InatRegexChecks.class",
            "com/kraptor/InatDirectChecks.class", cwd=classes)
        helpers = work / "helpers"
        run("baksmali", "d", str(output / "helpers.dex"), "-o", str(helpers))
        original = work / "original.cs3"
        original.write_bytes(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":InatBox.cs3"]))
        with zipfile.ZipFile(original) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        (work / "classes.dex").write_bytes(entries["classes.dex"])
        smali = work / "smali"
        run("baksmali", "d", str(work / "classes.dex"), "-o", str(smali))
        provider = smali / "com/keyiflerolsun/InatBoxBC.smali"
        text = provider.read_text()
        start = text.index(".method private final resolveTekliRegexStream(")
        end = text.index(".end method", start)
        method = text[start:end]
        anchor = '    .local v0, "raw":Ljava/lang/String;\n'
        assert method.count(anchor) == 1
        hook = ("    invoke-static {v0, v11}, Lcom/kraptor/InatRegex;->extract(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n"
                "    move-result-object v2\n"
                "    if-eqz v2, :inat_encrypted_reply\n"
                "    return-object v2\n"
                "    :inat_encrypted_reply\n")
        method = method.replace(anchor, anchor + hook)
        text = text[:start] + method + text[end:]

        start = text.index(".method private final isDirectStream(Ljava/lang/String;)Z")
        end = text.index(".end method", start) + len(".end method")
        text = text[:start] + (
            ".method private final isDirectStream(Ljava/lang/String;)Z\n"
            "    .registers 3\n"
            "    invoke-static {p1}, Lcom/kraptor/InatRegex;->isDirect(Ljava/lang/String;)Z\n"
            "    move-result v0\n"
            "    return v0\n"
            ".end method") + text[end:]

        start = text.index(".method public constructor <init>()V")
        end = text.index(".end method", start)
        constructor = text[start:end]
        assert constructor.count("    const/16 v2, 0x13\n") == 1
        constructor = constructor.replace("    const/16 v2, 0x13\n", "    const/16 v2, 0x12\n")
        first = constructor.index('    const-string v0, "' + OLD_4K + '"')
        last = constructor.index("    aput-object v0, v2, v1\n", first) + len("    aput-object v0, v2, v1\n")
        assert 'const-string v1, "4K Filmler"' in constructor[first:last]
        constructor = constructor[:first] + constructor[last:]
        provider.write_text(text[:start] + constructor + text[end:])
        assert OLD_4K not in provider.read_text()
        destination = smali / "com/kraptor"
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(helpers / "com/kraptor/InatRegex.smali", destination / "InatRegex.smali")
        run("smali", "a", "-j", "1", str(smali), "-o", str(work / "fixed.dex"))
        entries["classes.dex"] = (work / "fixed.dex").read_bytes()
        manifest = json.loads(entries["manifest.json"])
        assert manifest["name"] == "InatBox" and manifest["version"] == 59
        manifest["version"] = 60
        entries["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
        package = output / "InatBox.cs3"
        with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(entries.items()):
                entry = zipfile.ZipInfo(name, (1980, 2, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, data)
        catalog = json.loads(subprocess.check_output(["git", "-C", str(REPO), "show", BASE + ":plugins.json"]))
        item = next(row for row in catalog if row["internalName"] == "InatBox")
        item.update(version=60, fileSize=package.stat().st_size,
                    fileHash="sha256-" + hashlib.sha256(package.read_bytes()).hexdigest())
        item["description"] = "Güncel sunucu protokolü, saat eşitleme ve uygulamayla uyumlu yayın adresi çözümleme desteği."
        (output / "plugins.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
        print("InatBox", item["version"], item["fileSize"], item["fileHash"])


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: build.py OUTPUT_DIRECTORY")
    build(Path(sys.argv[1]).resolve())
