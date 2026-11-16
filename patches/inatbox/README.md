# InatBox 60

The reference app is the local İnat BOX APK, package `com.bp.box`, version 16.0 (code 16), SHA-256 `b59d3e0925b498df32eae9288216bf6efc2eb0c4a3a2cb63caac9211015a346e`. Its `d73.onResponse` callback uses `Pattern.MULTILINE`, capture group 1 and escaped-slash replacement for plain regex streams. Its live SDK/config menu no longer advertises the old 4K endpoint.

The v59 regex resolver treated every `Regex1` value as an AES key. Plain Now TV responses therefore produced no link. ATV's token endpoint also contained `.m3u8` in its query, so the old whole-string check emitted the JSON endpoint as a video before trying the resolver. The shared decoder now accepts the app's first URL capture before its existing AES path; direct-stream detection checks the URI path. Headers, signatures, server-time retry and encrypted decoding stay in the original binary. The unavailable 4K menu entry is removed.

`build.py` patches the exact v59 artifact from commit `4d894906dadc289801aa34697e24f15da9dd6e89`, preserving the installed `InatBox` manifest identity. It adds only `InatRegex` to the package. Tests, the reference app and compile tools are not shipped.

```sh
python3 patches/inatbox/build.py /tmp/inatbox-60
```

The build requires Java 8-compatible `javac`, smali/baksmali 2.5.2 and the installed Android `dx` path in the script. It runs `InatRegexChecks` locally and emits `checks.dex` for both that suite and `InatDirectChecks`. The latter reflects the actual provider's private classifier and fails against v59's token-endpoint behavior.

`InatProbe.java` reuses `patches/movie-playback/MovieProbe.java` to check the real Android API without installing or opening anything. Compile both against an Android platform JAR, convert their class files with D8, then run with the CloudStream APK, plugin and probe DEX on the Android classpath. Arguments are category index and optional title substring; `pages` lists the configured categories. No signed URLs or header values are printed.

API checks cover catalog, detail/episode resolution, HLS master, variant and a media segment. They do not establish on-screen video, audible sound, casting or every channel's availability. The validation runtime is CloudStream 4.8.0 on Android 10.

Release validation passed 13 native checks: both regression suites, the 18-category inventory, eight live streams, one series episode and one movie. The live sample includes Now TV A/B, ATV, ATV Avrupa, A2, TRT 1 A/C and beIN Sports 1 A. Two clean builds produced identical package/catalog bytes; all other 67 catalog entries and all other original DEX classes were preserved.
