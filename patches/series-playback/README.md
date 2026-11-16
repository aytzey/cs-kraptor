# Series and original FilmMakinesi repair, 2026-11-17

This binary mirror is rebuilt from `82086009b90fdbd7b65dd2fa8006858a81ca1ad1`.
Plugin identities, repository URLs and unrelated packages remain intact.

## Changes

- DiziBox 39: use the working page User-Agent, synchronize the shared episode
  collection written by concurrent season requests, and add the required
  User-Agent to Moly HLS links while retaining their own Referer and metadata.
- FilmMakinesi 73: restore its original archive with the current archive path
  and page headers. Decode the current CloseLoad/Rapid source function without
  loading its UI. Keep the existing extractors and explicit backup fallback.
- DiziPal 91: parse the new article cards, episode links, pagination JSON and
  player configuration. Preserve the anonymous HTTP session for configuration
  requests. Automatic browser verification uses the existing SDK WebViewResolver
  with its genuine WebView User-Agent. Read the current embed's literal HLS
  configuration before its player waits for a user gesture.
- Start the shared decoder budget immediately before script evaluation, after
  cold engine setup. Allow five seconds for older Android TV processors;
  instruction checks and denied Java access still bound untrusted scripts.
  Update every published copy: FilmMakinesi 73, FilmModu 44, HDFilmCehennemi 100,
  HDFilmizle 28, KraptorPlus 90, UgurFilm 38 and WFilmizle 26.

No new runtime dependencies are bundled. The API stubs are compile-only and
must never be included in a plugin.

## Rebuild and check

Use Java 8 bytecode, the Android 23 API jar, dx 29.0.3 and smali/baksmali 2.5.2.
`JARS_DIRECTORY` contains OkHttp 4.12.0 (`okhttp.jar`) and Jsoup 1.18.3
(`jsoup.jar`); the builder checks their SHA-256 before compiling.

```sh
python3 patches/series-playback/build.py OUTPUT_DIRECTORY JARS_DIRECTORY
python3 patches/series-playback/verify.py OUTPUT_DIRECTORY
```

Build twice in different output directories and compare every output byte.
The verifier checks catalog hashes/sizes, unchanged identity and metadata,
excluded SDK stubs, and the exact set of modified classes against the base.

Run `SeriesHeadersChecks`, `DiziPalHtmlChecks`, `CloseLoadChecks` and the existing
`PlayerChecks` through a separate `app_process`, with the official CloudStream
APK, `helpers.dex` and `checks.dex` in CLASSPATH. Only `PageHeadersChecks` uses
the JVM; the others require the real SDK, JSON and Rhino implementation.

The reusable `patches/movie-playback/MovieProbe.java` accepts provider class,
verified origin, mode, query/page URL and device-side plugin path. `home` also
accepts an optional page number. Its in-memory domain fixture initializes
constructor-built category URLs without writing app preferences. `first` checks
the first emitted HLS source, including its child playlist and first segment.
These checks fetch media bytes; they do not display video or verify codecs,
decryption keys, picture or audio.

## Evidence and open items

The separate Z1Mini test device uses Android 10. CloudStream 4.9.1 was staged as
an isolated API runtime; it was not installed over the user's app. No installed
plugins or foreground playback were changed. The father's TV is a different
device and has not received a physical acceptance test.

- DiziBox: catalog pages, Breaking Bad (63 entries including the site's bonus
  season) and Dexter (96 entries); emitted HLS reaches HTTP 200/206 media.
- FilmMakinesi: its own first/second archive pages and Matrix search return
  original FilmMakinesi URLs, without backup labels. Matrix and 2m² reach media
  segments with the final package.
- DiziPal: first/second catalog pages, search and all 73 Game of Thrones
  episodes parse with the real API. The automatic page script reaches the new
  embed in managed Firefox, whose HLS chain reaches a media segment. The complete
  Android WebView-to-player flow still needs verification on the target TV.
- DiziYou 50: all three Breaking Bad language sources reached media segments on
  both 4.8.0 and 4.9.1 API runtimes. The reported target-device failure remains
  unreproduced; the unchanged package was not version-bumped.
- DiziMom 62: the configured domains are parked, invalid or unavailable; no
  verified replacement address was found. Do not disable TLS checks or substitute
  an unrelated archive under the same name.
- InatBox 60: the sports API and its own SDK currently return Cloudflare 403/1006
  on the test network. This does not establish the father's IP is banned.
  Preserve the existing signing/crypto and report this external limitation.
