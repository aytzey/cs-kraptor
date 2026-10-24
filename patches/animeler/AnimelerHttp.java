package com.kraptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Blocking HTTP for Animeler's session and embed handshakes. CloudStream runs loadLinks off the main thread. */
public final class AnimelerHttp {
    static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Pattern CSRF = Pattern.compile("<meta\\s+name=\"csrf-token\"\\s+content=\"([^\"]+)\"");
    private static final Pattern D = Pattern.compile("\"d\"\\s*:\\s*\"([A-Za-z0-9+/=_-]+)\"");
    private static final Pattern K = Pattern.compile("\"k\"\\s*:\\s*\"([A-Za-z0-9+/=_-]+)\"");

    static final class Resp {
        int code;
        String body = "";
        String session;
    }

    static boolean isWebUrl(String url) {
        try {
            URI u = URI.create(url);
            return ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
                && u.getHost() != null && u.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static Resp request(String url, String method, String body, String[] headers) throws Exception {
        if (!isWebUrl(url)) throw new IllegalArgumentException("Animeler: invalid URL");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            for (int i = 0; i + 1 < headers.length; i += 2)
                if (headers[i + 1] != null) c.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                OutputStream o = c.getOutputStream();
                o.write(b);
                o.close();
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() == null || !"set-cookie".equalsIgnoreCase(h.getKey())) continue;
                for (String v : h.getValue()) {
                    if (v.startsWith("animeler_session=")) {
                        int end = v.indexOf(';');
                        r.session = v.substring("animeler_session=".length(), end < 0 ? v.length() : end);
                    }
                }
            }
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < MAX_BODY) out.write(buf, 0, n);
                in.close();
                r.body = out.toString("UTF-8");
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /**
     * The episode HTML embeds a CSRF token from an unsaved session and sets no cookie.
     * A first POST starts the session; the page fetched with that cookie carries the matching token.
     * Returns {token, session}; empty strings when the handshake fails.
     */
    public static String[] session(String pageUrl) {
        try {
            URI page = URI.create(pageUrl);
            String origin = page.getScheme() + "://" + page.getRawAuthority();
            Resp boot = request(origin + "/ajax/get-source-url", "POST", "",
                new String[] {"X-Requested-With", "XMLHttpRequest", "Referer", pageUrl});
            if (boot.session == null) return new String[] {"", ""};
            Resp r = request(pageUrl, "GET", null, new String[] {"Cookie", "animeler_session=" + boot.session});
            String session = r.session != null ? r.session : boot.session;
            Matcher m = CSRF.matcher(r.body);
            return m.find() ? new String[] {m.group(1), session} : new String[] {"", ""};
        } catch (Exception e) {
            return new String[] {"", ""};
        }
    }

    /** /embed/{id}/{hash} now loads its player via an XOR-encoded JSON reply instead of iframe src. */
    public static String playerUrl(String embedUrl, String session) {
        try {
            Resp r = request(embedUrl, "GET", null, new String[] {
                "X-Embed-Resolve", "1", "Accept", "application/json", "Referer", embedUrl,
                "Cookie", session == null || session.isEmpty() ? null : "animeler_session=" + session});
            String url = decode(r.body);
            return url != null && isWebUrl(url) ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String decode(String json) {
        Matcher d = D.matcher(json), k = K.matcher(json);
        if (!d.find() || !k.find()) return null;
        byte[] data = b64(d.group(1)), key = b64(k.group(1));
        if (key.length == 0) return null;
        for (int i = 0; i < data.length; i++) data[i] ^= key[i % key.length];
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] b64(String s) {
        s = s.replace('-', '+').replace('_', '/');
        while (s.length() % 4 != 0) s += "=";
        return Base64.getDecoder().decode(s);
    }
}
