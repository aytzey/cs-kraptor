#!/usr/bin/env python3
"""Apply the Animeler 43 bytecode edits to `baksmali d` output of version 42. Usage: patch_smali.py <smali-dir>"""
import pathlib
import sys

root = pathlib.Path(sys.argv[1]) / "com/kraptor"


def edit(name, old, new):
    p = root / name
    s = p.read_text()
    if s.count(old) != 1:
        sys.exit(f"{name}: expected exactly one match for patch anchor")
    p.write_text(s.replace(old, new))


# 1. Episode list: the HTML grid stops at 100; allEpisodesData JSON has every episode.
edit("Animeler.smali",
     '    const-string v6, ".episode-grid a.ep-item"\n\n'
     '    invoke-virtual {v2, v6}, Lorg/jsoup/nodes/Document;->select(Ljava/lang/String;)Lorg/jsoup/select/Elements;\n',
     '    invoke-static {v2}, Lcom/kraptor/AnimelerPage;->episodes(Lorg/jsoup/nodes/Document;)Lorg/jsoup/select/Elements;\n')

# 2. Source requests: replace the never-set XSRF-TOKEN/session cookies with a real session + matching CSRF token.
p = root / "Animeler$loadLinks$2.smali"
s = p.read_text()
start = s.index('    invoke-virtual {v1}, Lcom/lagradost/nicehttp/NiceResponse;->getCookies()Ljava/util/Map;\n\n    move-result-object v3\n\n    const-string v4, "XSRF-TOKEN"')
end = s.index('    :goto_84\n') + len('    :goto_84\n')
p.write_text(s[:start] +
             '    iget-object v3, v0, Lcom/kraptor/Animeler$loadLinks$2;->$data:Ljava/lang/String;\n\n'
             '    invoke-static {v3}, Lcom/kraptor/AnimelerHttp;->session(Ljava/lang/String;)[Ljava/lang/String;\n\n'
             '    move-result-object v3\n\n'
             '    const/4 v4, 0x0\n\n    aget-object v13, v3, v4\n\n'
             '    const/4 v4, 0x1\n\n    aget-object v14, v3, v4\n\n' + s[end:])

# 3. Embed page: resolve the player URL from the X-Embed-Resolve JSON; keep the old iframe lookup as fallback.
edit("Animeler$loadLinks$2$1$1.smali",
     '    move-object v11, v3\n\n    .line 402\n    .local v11, "embedResponse":Lcom/lagradost/nicehttp/NiceResponse;\n',
     '    move-object v11, v3\n\n    .line 402\n    .local v11, "embedResponse":Lcom/lagradost/nicehttp/NiceResponse;\n'
     '    invoke-virtual {v11}, Lcom/lagradost/nicehttp/NiceResponse;->getUrl()Ljava/lang/String;\n\n'
     '    move-result-object v0\n\n'
     '    iget-object v12, v5, Lcom/kraptor/Animeler$loadLinks$2$1$1;->$session:Ljava/lang/String;\n\n'
     '    invoke-static {v0, v12}, Lcom/kraptor/AnimelerHttp;->playerUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n\n'
     '    move-result-object v0\n\n'
     '    if-nez v0, :cond_203\n\n')

# 4. Register the extractor for Animeler's own HLS player host.
edit("AnimelerPlugin.smali",
     '    invoke-virtual {p0, v1}, Lcom/kraptor/AnimelerPlugin;->registerMainAPI(Lcom/lagradost/cloudstream3/MainAPI;)V\n',
     '    invoke-virtual {p0, v1}, Lcom/kraptor/AnimelerPlugin;->registerMainAPI(Lcom/lagradost/cloudstream3/MainAPI;)V\n\n'
     '    new-instance v1, Lcom/kraptor/MakiseKurisu;\n\n'
     '    invoke-direct {v1}, Lcom/kraptor/MakiseKurisu;-><init>()V\n\n'
     '    check-cast v1, Lcom/lagradost/cloudstream3/utils/ExtractorApi;\n\n'
     '    invoke-virtual {p0, v1}, Lcom/kraptor/AnimelerPlugin;->registerExtractorAPI(Lcom/lagradost/cloudstream3/utils/ExtractorApi;)V\n')
print("patched")
