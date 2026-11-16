# Movie playback repair, 2026-11-16

Packages: HDFilmCehennemi 99, FullHDFilmizlesene 74, HDFilmDelisi 20,
KraptorPlus 89. Existing plugin identities and repository URLs stay intact.

## Causes and repair

- FullHDFilmizlesene decoded current `rapidvid.org` embeds, but its registered
  extractor matched only `rapidvid.net`. The existing decoder still works.
- HDFilmCehennemi returns `.nl/rplayer/` URLs; RapidRame matched `.ws`.
  Its initial player request also returned 403 without the explicit desktop
  User-Agent. The extractor now reads the same cached domain as the provider.
- The HDFilm player packs a pure URL decoder, with either an array or a
  string-split argument. `HdfilmConfig` executes only that decoder in the
  installed Rhino interpreter, with Java access disabled and a two-second
  instruction budget. It preserves captions and keeps the original WebView
  fallback. Both the standalone and KraptorPlus extractors receive this repair.
- HDFilmDelisi replaced its VidMody URL with a Vixolity embed in VideoObject
  metadata. `DelisiNative` reads its public player configuration and emits the
  extensionless HLS URL explicitly as M3U8. The legacy path remains available.

## Rebuild and regression checks

The mirror contains recovered binaries rather than the original Kotlin sources.
The builder takes unmodified packages from commit `8b9db13`, applies checked
patch sites, injects only helper classes, and updates package/catalog versions,
sizes and SHA-256 together. Compile-only API stubs are never packaged.

```sh
python3 patches/movie-playback/build.py /tmp/kraptor-movie-release
adb -s 192.168.1.6:5555 push /tmp/kraptor-movie-release/helpers.dex /tmp/kraptor-movie-release/player-checks.dex /data/local/tmp/
adb -s 192.168.1.6:5555 shell "su -c 'CLASSPATH=/system/priv-app/CloudStream/CloudStream.apk:/data/local/tmp/helpers.dex:/data/local/tmp/player-checks.dex app_process /system/bin com.kraptor.PlayerChecks'"
```

Build tools used: Java 8 bytecode, Android 23 API jar, dx 29.0.3, and
smali/baksmali 2.5.2. `PlayerChecks` verifies both player argument formats,
captions, legacy fallback, Java isolation, the script deadline, Vixolity
decoding and rejected source schemes/hosts in the actual Android runtime.

`MovieProbe.java` is the separate-process live harness. Compile with
`javac --release 8` against the Android jar, then dex with installed SDK d8
(`--min-api 23`). Its five arguments are provider class, current origin,
mode (`search`, `load`, `all`, `links`, or `extract`), query/page/embed,
and the device-side plugin path. Put the APK, tested plugin and probe DEX
in CLASSPATH and start `MovieProbe` through root `app_process`.

## Device evidence and limits

Z1Mini, serial ZMMTV23345752, Android 10, CloudStream 4.8.0 (68), Wi-Fi
`192.168.1.6:5555`. Recheck the endpoint with `tvctl status` before reuse.
Tests run separately from the installed player; the app was not restarted
and no installed plugins were replaced during verification.

- HDFilmCehennemi: The Brink of War and The Hunt for Red October.
- FullHDFilmizlesene: The Matrix and The Matrix Reloaded.
- HDFilmDelisi: The Matrix and The Matrix Reloaded.
- KraptorPlus: the shared RapidRame extractor on The Brink of War.

Each case extracted media and fetched the HLS master, a variant and its first
segment successfully (HTTP 200/206). Catalog identities, versions, sizes and
hashes matched the rebuilt packages. This is transport/extraction evidence,
not acceptance of picture/audio in the CloudStream player or the entire catalog.

Separate upstream failures were not masked: FilmModu returned eight masters
whose child host `sx1.canvopics.life` was NXDOMAIN in both Cloudflare and Google
DNS; HDFilmizle's `p1.photofunny.org` and UgurFilm's `ugurfilm3.xyz` were also
NXDOMAIN. WFilmizle's configured host resolved but refused HTTPS. FilmMakinesi
answered HTTP 403. A reachable homepage or master alone is not playback proof.
