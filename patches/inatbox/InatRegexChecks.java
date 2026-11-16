package com.kraptor;

public final class InatRegexChecks {
    static void equal(String actual, String expected, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String url = "https://media.example.test/live.m3u8?one=1&two=2";
        String pattern = "\"chUrl\":\"(.*?)\"";
        equal(InatRegex.extract("{\"chUrl\":\"" + url + "\"}", pattern), url,
            "plain JSON stream replies must use the first regex capture");
        equal(InatRegex.extract("{\"Url\":\"" + url + "\"}", "Url\":\"(.*?[^&\">]+)\""), url,
            "ATV's regex capture must preserve the complete signed query");
        equal(InatRegex.extract("{\"chUrl\":\"https:\\/\\/media.example.test\\/live.m3u8\"}", pattern),
            "https://media.example.test/live.m3u8", "JSON-escaped slashes must become a playback URL");
        equal(InatRegex.extract("heading\nurl=" + url + "\nfooter", "^url=(https?://[^\\r\\n]+)$"), url,
            "regex flags must match the app's multiline mode");
        equal(InatRegex.extract("missing", pattern), null, "unmatched replies must keep the old decoder path");
        equal(InatRegex.extract("ciphertext:iv", "abcdefghijklmnop"), null,
            "AES keys must keep the existing encrypted decoder path");
        equal(InatRegex.extract("anything", "("), null, "invalid upstream regexes must fail closed");
        equal(InatRegex.extract("{\"chUrl\":\"javascript:alert(1)\"}", pattern), null,
            "only HTTP playback URLs may be emitted");
        equal(InatRegex.extract("{\"chUrl\":\"https://user@media.example.test/live.m3u8\"}", pattern), null,
            "captured URLs must not contain credentials");
        equal(InatRegex.extract(null, pattern), null, "missing replies must not throw");
        if (InatRegex.isDirect("https://token.example.test/secure?url=" + url))
            throw new AssertionError("query parameters must not make token endpoints look like streams");
        if (!InatRegex.isDirect(url) || !InatRegex.isDirect("https://media.example.test/live.M3U8?token=fixture"))
            throw new AssertionError("signed and uppercase playlist paths must remain direct");
        if (InatRegex.isDirect("https://example.test/page") || InatRegex.isDirect(null))
            throw new AssertionError("non-stream and missing paths must not be direct");
        System.out.println("InatRegexChecks: passed");
    }
}
