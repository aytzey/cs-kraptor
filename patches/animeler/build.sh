#!/bin/sh
# Rebuild Animeler 43 from the version 42 package in git. Usage: build.sh <work-dir> <stub-src-dir> <jsoup.jar>
set -eu
here=$(cd "$(dirname "$0")" && pwd); repo=$(cd "$here/../.." && pwd); w=$1; stubs=$2; jsoup=$3
rm -rf "$w"; mkdir -p "$w/classes" "$w/helper" "$w/pkg"
git -C "$repo" show 5bd771e:Animeler.cs3 > "$w/v42.cs3"
unzip -q -o "$w/v42.cs3" classes.dex -d "$w"
baksmali d "$w/classes.dex" -o "$w/smali"
python3 "$here/patch_smali.py" "$w/smali"
javac --release 8 -nowarn -d "$w/classes" -cp "/usr/share/java/com.android.android-23.jar:$jsoup" \
  $(find "$stubs" -name '*.java') "$here/AnimelerPage.java" "$here/AnimelerHttp.java" "$here/MakiseKurisu.java"
(cd "$w/classes" && /usr/lib/android-sdk/build-tools/29.0.3/dx --dex --output="$w/helper.dex" com/kraptor/*.class)
baksmali d "$w/helper.dex" -o "$w/helper"
cp "$w"/helper/com/kraptor/*.smali "$w/smali/com/kraptor/"
smali a -j 1 "$w/smali" -o "$w/pkg/classes.dex"
printf '%s' '{"pluginClassName":"com.kraptor.AnimelerPlugin","name":"Animeler","version":43,"requiresResources":false}' > "$w/pkg/manifest.json"
(cd "$w/pkg" && rm -f ../Animeler.cs3 && TZ=UTC touch -t 198002010000 manifest.json classes.dex && zip -X -q ../Animeler.cs3 manifest.json classes.dex)
echo "$w/Animeler.cs3"
