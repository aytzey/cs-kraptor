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
the challenge. A host gets at most one solve attempt per minute. 429 rate-limit pages are
not treated as challenges.

- HDFilmCehennemi 106: `load` and `loadLinks` requests use the plugin interceptor. The
  `PeerPlayback.owns` hook now skips the null arguments Kotlin passes when the suspended
  `loadLinks` resumes. In 105 that hook threw after the first network call.
- FilmMakinesi 74: page requests through `PageHeaders.get` use `CfSolver.INTERCEPTOR`.
  The `MovieBackup` client also uses it, with a 45 s call budget (connect and read
  timeouts are unchanged).

Requirement: a current system WebView. Cloudflare keeps WebView 101 (the Z1Mini's Android
10 image) in a reload loop. The Z1Mini now bind-mounts Chromium SystemWebView 160 from
`/data/local/webview` through `/vendor/etc/init/z1mini-webview.rc`.

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
- FilmMakinesi: `solve filmmakinesi.to -> ok` in 4.1–4.6 s and the home page loaded. Its
  own search could not be confirmed: the site rate-limited the test IP ("Biraz
  yavaşlayalım") after repeated testing, and results came from the HDFilmCehennemi backup.
