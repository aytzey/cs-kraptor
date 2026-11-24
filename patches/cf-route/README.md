# Cloudflare challenge and dead-source cleanup, 2026-11-23

Rebuilt from builds `3e4fbf2`. Plugin identities and repository URLs are unchanged.

## Cloudflare

HDFilmCehennemi 105 shipped a Kotlin `CfSolver` that never worked:

- detail and link requests passed `interceptor = null`, so the solver never ran;
- once wired, it called the wrapper interceptor, which called the solver again
  (`StackOverflowError`); CloudStream then starts in safe mode with every plugin disabled;
- it read the WebView User-Agent from the OkHttp thread, which WebView rejects, so no
  session was ever stored.

`CfSolver.java` replaces it. On a 403/503 Cloudflare interstitial (`cf-mitigated: challenge`,
"Just a moment" or `cf_chl_opt`) it opens the page once in a WebView over the app, waits
for a new `cf_clearance` cookie, and repeats the request with that cookie and the WebView
User-Agent. The main request sends `X-Requested-With` empty: HDFilmCehennemi answers
`X-Requested-With: com.lagradost.cloudstream3` with an "app not supported" page instead of
the challenge. After a solve the host may open a WebView again after one minute, after a
failure after ten. 429 rate-limit pages are not treated as challenges.

Outdated WebViews keep reloading the interstitial instead of passing it. After three
"Just a moment" page loads the solver gives up (about 15 s on WebView 101, instead of the
40 s timeout), and the plugin falls back exactly as 105 did. Devices that update "Android
System WebView" from Play Store pass the challenge; boxes with a frozen old WebView do not.

- HDFilmCehennemi 106: `load` and `loadLinks` requests use the plugin interceptor. The
  `PeerPlayback.owns` hook now skips the null arguments Kotlin passes when the suspended
  `loadLinks` resumes. In 105 that hook threw after the first network call.
- FilmMakinesi 74: page requests through `PageHeaders.get` use `CfSolver.INTERCEPTOR`.
  The `MovieBackup` client also uses it, with a 45 s call budget (connect and read
  timeouts are unchanged).

Requirement: a current system WebView. Cloudflare keeps WebView 101 (the Z1Mini's Android
10 image) in a reload loop. The Z1Mini now bind-mounts Chromium SystemWebView 160 from
`/data/local/webview` through `/vendor/etc/init/z1mini-webview.rc`.

## HDFilmCehennemi "Close" player

The hdfilmcehennemi.mobi embed no longer contains its stream URL. The page posts a fingerprint to
`/video/embed/<id>/kn/` with jQuery (the server answers only `X-Requested-With: XMLHttpRequest`),
decodes the reply in obfuscated JavaScript and calls `window.knKur(url, tracks)`. The bundled
extractors (HdfilmConfig and its WebView fallback) returned nothing for these titles, so films
offering only "Close" sources showed "Bağlantı bulunamadı".

`HdfPlayer.java` replaces the `loadExtractor` call in `loadLinks` (same signature): embed URLs
are fetched with a browser-like `Accept` header (the server answers 404 without one), run in an
off-screen WebView with the embed URL as base, and `window.knAdres` / `window.knT` become an M3U8
link and subtitles. Other iframes still go to CloudStream's `loadExtractor`. The server's broken
`{rapidrame_id}` URL template is stripped. HDFilmCehennemi 109.

## Bundled WebView kernel (Android 10+)

`WebViewKernel.java` gives devices with an outdated WebView (major < 130, or after the
challenge kept reloading) Chromium's own SystemWebView 160.0.8181.0 without root or a server:

- `prepare()` runs from each plugin's `load()`, before CloudStream creates its first WebView.
  It downloads only the `apks/SystemWebView.apk` entry of the Chromium snapshot zip (HTTP Range,
  inflated in-stream, SHA-256 pinned: arm 66 MB, arm64 159 MB, x86_64 192 MB) into
  `files/kraptor-webview/`, read-only. From the next app start the process uses it.
- Hooks (after JonaNorman/WebViewUpgrade, Apache-2.0): the WebView update service (sCache and
  Android 15+ WebViewUpdateManager) and the package manager answer with the downloaded APK;
  its own components report disabled. Non-SDK access uses android.util.Property (LSPosed LSPass,
  Apache-2.0), with Android 10 double reflection as fallback.
- Plugins cannot declare renderer services, so the renderer runs in-process: the update service
  answers `isMultiProcessEnabled=false`, and because Android 15/16 force multiprocess, the
  provider is created right away and Chromium's `webview-sandboxed-renderer` switch is removed
  before the browser starts (`CommandLine.d` / `g(String)`, R8 names checked in all three builds).

Verified with HDF's challenge page: Z1Mini Android 10 (system 101, also inside CloudStream with a
film playing), emulators Android 11 (system 83), 12 (91), 13 (109), 14 (113), 15 (124) and
16 (133, also inside CloudStream: solve in 2.4 s).

Close player (HDFilmCehennemi 109, Z1Mini): "Karlar Düşerken Aşk" and "Merry Christmas Mr.
Lawrence" (Close sources only, previously no links) and "Russell Brand: Open Secret" (Rapidrame and
Close) played with advancing position and active audio output; extraction took 1.0–8.0 s per source.

## FilmMakinesi, 2026-11-25

A second TV showed HDFilmCehennemi working and FilmMakinesi not opening. Three causes, all fixed in
FilmMakinesi 78 / HDFilmCehennemi 110:

- **Rate limit.** filmmakinesi.to answers the app with a ten-minute IP block (429 "Biraz yavaşlayalım",
  `Retry-After: 600`) once it has made about a dozen page requests within a minute. With 2 s, 3 s and 5 s
  gaps the block came at the 12th request after the challenge every time. The home page fired its 14
  sections in 2–3 s, so every film page afterwards failed. A desktop browser on the same IP made 147
  requests at one per 2 s without a block, so the stricter limit applies to app traffic. Same-site
  requests from a hidden WebView's `fetch()` were blocked the same way, and with WebView's
  `X-Requested-With` header the firewall answered "Attention Required". Player hosts (closeload., rapid.)
  are not counted.
  FilmMakinesi's page requests now go through `CfSolver.PACED` (search, details, links) and `PACED_HOME`
  (home sections): one request per 8 s per host, and home sections wait while a user request is queued.
  CloudStream shows the home page only after every section has loaded, so the home page keeps its first
  five sections (latest films, Netflix, Disney, Amazon, "Ölmeden İzle"); genres remain reachable through
  search.
- **Decoy Close stream.** closeload.filmmakinesi.to runs the same Close player as HDFilmCehennemi. Its
  `jwplayer().setup()` config, which the bundled CloseLoadExtractor still reads, now holds a 90 s sample
  video (`videosamplemp4`), and CloudStream listed and played it first. In WebView the page never produced
  a stream, and a plain fetch answered 404. Both sites embed the same ids (Fjord: `A6HYLfcXm6a6` on each),
  so `HdfPlayer.mirror()` resolves a closeload id on hdfilmcehennemi.mobi. Close embeds never fall back to
  the bundled extractor; when the id is unknown there, the film's Rapid source remains.
- **Kernel install race.** Each plugin loads its own copy of `WebViewKernel`, so `synchronized` did not
  keep HDFilmCehennemi and FilmMakinesi apart. On an outdated-WebView device both installed the kernel at
  once ("cannot create kernel-libs"). The lock is now a string literal (interned VM-wide), and directory
  creation tolerates a concurrent `mkdirs`.

The solver's WebView no longer loads the site's own images and scripts (only the main frame, `/cdn-cgi/`
and other hosts), and 429 answers are logged (`KraptorCf: rate limited <host>, retry after N s`).

Evidence:

- Emulator, Android 11 (system WebView 83, bundled kernel 160 installed once): FilmMakinesi home loaded
  after `solve filmmakinesi.to -> ok`; HDFilmCehennemi solved in 1.6 s and Fjord played through Close
  (`stream from srv12.cdnimages3899.cyou in 1004 ms`) and RapidRame.
- Z1Mini, CloudStream 4.9.1: a cold FilmMakinesi session (solve, home, search, details, links) with no 429.
  "Sosyal Hesaplaşma" listed `FilmMakinesi - Close` (`thesocialreckoning-2026-…-Ju5Pr9UYItQ`, 3.2 s) and
  RapidRame, without the decoy, and played. The five-section home page appeared 65 s after a cold app
  start (over two minutes with 14 sections).

## Removed from the catalog

Home and search both failed on the Z1Mini (Android 10, TR connection) with the current
domain list, and no working replacement was found:

| Plugin | Reason |
| --- | --- |
| AnimPow | site under maintenance, Cloudflare 522 |
| DiziYo | NXDOMAIN, no new domain found |
| FilmHane | domain now serves an unrelated chat site |
| Filmzal | Cloudflare 522/525 on every known domain |
| WFilmizle | domain parked; the new `.my` domain is ISP-blocked (TLS reset) |
| AsyaFanatiklerim | no domain in the list, `asyafanatiklerim.com` does not resolve |
| SelcukFlix, Dizilla, DiziPalOrijinal | sites moved to a Next.js app; `/api/bg/*` returns 404 |
| DiziAsya, AsyaWatch | API returns HTML/404 instead of JSON |
| Animeler, CizgiveDizi, DramaDizilerim, OpenAnime, SinemaCX | pages load, no results parse (markup changed) |
| TurkAnime | no list key; `turkanime.tv` returns no results |
| SetFilmIzle, Anizium, SineWix | API/routes no longer exist (400/404) |

## Rebuild

```sh
python3 patches/cf-route/build.py OUTPUT_DIRECTORY
```

Uses javac (release 8), d8 from build-tools 36 and android-33, smali/baksmali 2.5.2 and
the checked OkHttp 4.12.0 jar. The output is byte-identical across builds. The
CommonActivity stub is compile-only and is not packaged.

## Evidence (Z1Mini, CloudStream 4.9.1, WebView 160)

- HDFilmCehennemi: `solve www.hdfilmcehennemi.nl -> ok` in 3.7–5.3 s. The detail page
  loaded and a film played (`tvctl verify`: position advanced 17 → 29 s, video frames,
  audio output active).
- HDFilmCehennemi 107 with the box temporarily back on WebView 101: "challenge keeps
  reloading", `solve -> fail in 14664 ms`, home filled from the FullHDFilmizlesene backup,
  and parallel requests did not open another WebView.
- FilmMakinesi: `solve filmmakinesi.to -> ok` in 4.1–4.6 s and the home page loaded. Its
  own search could not be confirmed: the site rate-limited the test IP ("Biraz
  yavaşlayalım") after repeated testing, and results came from the HDFilmCehennemi backup.
