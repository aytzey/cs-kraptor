# Movie source recovery

FilmModu 43, HDFilmizle 27, FilmMakinesi 72, WFilmizle 25 and UgurFilm 37 keep their installed plugin identities. Working original catalogs and non-HLS streams pass through. HLS links are checked through a media segment; an unavailable source falls back to HDFilmCehennemi. Search results, details and player links identify the backup source.

Playback matches the supplied IMDb ID. When the original provider has no ID, it requires one exact title/year result and verifies that the destination has an IMDb ID. Ambiguous matches fail without choosing a film. Package signature checks and coroutine cancellation remain active.

UgurFilm's package was present but missing from the update catalog; this release restores its catalog entry. A bookmark containing only an unreachable old site URL may require searching for the film again. These changes recover access through a backup; they do not reopen the closed upstream sites. TV series are not supplied by the movie backup.

## Rebuild

Run from this checkout with Java 8-compatible `javac`, smali/baksmali and the Android tools configured in `build.py`. The script rebuilds from commit `1bf7209ae6b28141291752cc880843ec31ae21fa` and verifies its compile-only libraries. The stubs and libraries are not included in the plugins; CloudStream supplies those APIs.

```sh
mkdir -p /tmp/movie-backup-jars
curl -fL https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar -o /tmp/movie-backup-jars/okhttp.jar
curl -fL https://repo.maven.apache.org/maven2/org/jsoup/jsoup/1.18.3/jsoup-1.18.3.jar -o /tmp/movie-backup-jars/jsoup.jar
python3 patches/movie-backup/build.py /tmp/movie-backup-build /tmp/movie-backup-jars
```

Run `com.kraptor.BackupChecks` and `com.kraptor.AdapterChecks` using the installed CloudStream APK, a rebuilt plugin and `backup-checks.dex` on the Android classpath. They cover identity matching, media-format preservation, page/media request headers, original-stream preservation, package checks, synchronous/asynchronous completion and cancellation.

`patches/movie-playback/MovieProbe.java` verifies home, search and movie resolution against the real Android API. Its `all` mode fetches the HLS master, variant and first media segment. These are API/HTTP checks, not an on-screen video or audio acceptance test. The installed runtime used for validation is CloudStream 4.8.0 on Android 10.

Release validation passed 27 native checks: both regression suites, home and search for all five providers, and The Matrix (`tt0133093`) plus Inception (`tt1375666`) through movie details, playback links and a media segment in each provider. Two clean builds produced identical plugin and catalog bytes. This sample does not establish availability of every film in the backup catalog.
