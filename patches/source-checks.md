# Source checks, 2026-10-23

## Confirmed and repaired

Ddizi 20 sends its player Referer to `video.twimg.com`, which rejects that
request with 403. Version 21 clears that Referer only for the exact CDN
hostname in all three built-in link builders. The same episode 8 master,
variant and media segment return 200 without it. Eight cases passed against
the installed CloudStream 4.8.0 API and patched DEX on Android 10. See
`ddizi-twimg/README.md` for reproduction and the remaining playback check.

## Current observations, not blanket playback acceptance

- 70 domain-list origins: 54 returned HTTP 200 from the home connection.
  A reachable homepage does not prove search, extraction or video playback.
- 66 available plugin packages matched their catalog size, SHA-256 and inner
  manifest version. The catalog additionally lists AsyaMinik as down
  (`status: 0`); its package is absent, so it was not restored with an older,
  incompatible version merely to satisfy a download URL.
- DiziMom's current Kizilcik Serbeti 144 / PeaceMakerst API returned a video
  source. Its master, variant and first segment returned 200 with the existing
  Referer. This case does not reproduce the earlier HDStreamAble 403, so no
  speculative DiziMom header change was made.
- TrAnimeIzle's configured `.io` homepage redirects to `.live` and responds
  200; this is not an episode playback test.
- Ddizi episode 1 was replaced by the site's removal page. This is separate
  from the CDN header defect and cannot be repaired by that patch.

The following origin failures need source-specific investigation; a single
403 can be a browser challenge and a timeout can be transient. They were not
disabled or redirected to guessed domains:

| Sources | Observation |
|---|---|
| Animely | HTTP 502 |
| AsyaMinik, DiziPal, DiziYo, SelcukFlix, UgurFilm, WFilmizle | URL/connection error |
| DiziBox, FilmMakinesi, FullHDFilmizlesene, HDFilmCehennemi, TrDiziIzle, YabanciDizi | HTTP 403 |
| Filmzal | Timeout |
| KoreanTurk | HTTP 522 |
| Teve2 | HTTP 400 |

TV playback was preserved during these source checks. No claim is made that
all episodes or all providers have been tested in the TV player.
