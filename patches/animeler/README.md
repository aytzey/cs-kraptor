# Animeler 43: sessions, embed resolver, full episode list

Version 42 found no playable link: every `/ajax/get-source-url` request
returned HTTP 419, and series longer than 100 episodes were truncated.

Causes (checked 2026-10-24 against animeler.pw):

- The episode HTML sets no cookie and embeds a CSRF token from an unsaved
  session. Version 42 sent the never-present `XSRF-TOKEN` cookie as `_token`.
  `AnimelerHttp.session` starts the session with one POST (the 419 reply sets
  `animeler_session`), refetches the page with that cookie and reads the
  matching `meta[name=csrf-token]`.
- `/embed/{id}/{hash}` no longer has an iframe `src`. Its script fetches the
  same URL with `X-Embed-Resolve: 1` and XORs the JSON `d` with `k`.
  `AnimelerHttp.playerUrl` does that (session cookie and Referer required);
  the old `iframe#innerPlayer` lookup remains the fallback.
- Animeler's own player (`makisekurisu.site/embed/plyr/...`) answers 403
  unless requested with an Animeler Referer and `Sec-Fetch-Dest: iframe`.
  Its `__PLAYER_BOOT__.master` is an HLS master named `.css`. The new
  `MakiseKurisu` extractor emits it as M3U8. mail.ru players go to
  CloudStream's built-in MailRu extractor.
- The episode grid stops at 100; `allEpisodesData` JSON lists all
  (`AnimelerPage.episodes`, HTML fallback kept).

`play.animeler.pw/fireplayer` sources fail server-side (ionCube loader
error); this package cannot repair them.

Build: `build.sh <work-dir> patches/animeler/stubs <jsoup-1.17.2.jar>` takes
version 42 from commit 5bd771e, applies `patch_smali.py`, compiles the three
helpers against the Android 23 jar, jsoup and the compile-only stubs, and
injects only `com/kraptor` classes. smali/baksmali 2.5.2, dx 29.0.3.

Note: Kotlin's `nullOutSpilledVariable` returns null in this runtime, so
patched code reads the embed URL from `NiceResponse.getUrl()`, never from
spilled coroutine locals.

Device check (CloudStream 4.8.0, Android 10 TV, separate root `app_process`,
live player untouched): `ProviderProbe` registers `MakiseKurisu`, then
Naruto load = 220 episodes, search = 21, Steins;Gate ep. 1 = 5 links and
Naruto ep. 200 = 4 links, every link's URL answering HTTP 200. JDK
`HttpURLConnection` drops `Sec-Fetch-Dest`; Android's keeps it, so desktop
checks need `-Dsun.net.http.allowRestrictedHeaders=true`. Playback in the
CloudStream player itself is not yet accepted.
