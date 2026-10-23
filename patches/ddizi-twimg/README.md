# Ddizi 21: Twitter video Referer

The HTTP regression passes on the still-available episode 8 player. The
patched helper also passes eight hostname/header cases against CloudStream
4.8.0 on the physical Android 10 TV, in a separate `app_process` that does
not touch playback. The device's installed Ddizi package matches version 21.
Full Ddizi video playback has not been accepted: the user is watching InatBox,
and changing the current show was deliberately avoided.

Episode 1 was replaced upstream with `/player/telif/` and a Netflix link.
That unavailable episode is separate from this header defect; this patch
does not restore removed content.

The original Ddizi 20 package matches the deployed package (SHA-256
`9128e53d9be73af0d3a3708565f9c65a0c91f1dc50d00fc65546f357bb391b8a`).
Its player sends the Ddizi iframe URL as Referer to `video.twimg.com`.
On 2026-10-23 that request returns HTTP 403 `Unauthorized.`, while the same
manifest without Referer returns HTTP 200. The master playlist, variant
playlist and media segment must all remain accessible without Referer.

`referer.patch` adds an exact, case-insensitive hostname check shared by the
three built-in Ddizi player link builders. It clears Referer only for
`video.twimg.com`; other hosts retain their original Referer and headers,
including the existing Yandex behavior. The external-extractor forwarding
path is unchanged.

The matching Kotlin source for version 20 is unavailable in the recovered
history. Apply the patch to `baksmali disassemble` output from version 20,
then rebuild with `smali assemble` (2.5.2). Replace only `classes.dex` and
set the inner manifest version to 21; update the Ddizi entry in `plugins.json`
with the same version and the rebuilt package size/SHA-256.

Run `python3 patches/ddizi-twimg/check_stream.py` with `DDIZI_TEST_URL` and
`DDIZI_TEST_REFERER` set to a current failing Ddizi Twitter video URL and its
player Referer. The script does not print either value. Device acceptance
additionally requires playing the same episode in CloudStream and observing
advancing playback, because HTTP success alone does not prove decoding.

`CheckReferer.java` needs no Android SDK at compilation time. Compile it with
`javac --release 8`, then run `dx --dex --output=check-referer.dex CheckReferer.class`
from the class output directory. Run `app_process /system/bin CheckReferer`
with `CLASSPATH` containing that DEX, the installed CloudStream APK and this
patched plugin package. It verifies the actual DEX helper, exact hostname
matching, uppercase hosts, deceptive hosts/query strings and preservation
of unrelated headers. It does not start a player or make network requests.
