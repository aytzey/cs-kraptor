package com.kraptor;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.lagradost.cloudstream3.CommonActivity;
import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.utils.ExtractorApiKt;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * HDFilmCehennemi's "Close" player (hdfilmcehennemi.mobi/video/embed/...) no longer embeds its stream URL: the
 * page posts a fingerprint to .../kn/ and decodes the answer in obfuscated JavaScript, then calls
 * window.knKur(url, tracks). The page runs in an off-screen WebView and the result is read from window.knAdres.
 * FilmMakinesi's closeload.<site> embeds go to the same id on hdfilmcehennemi.mobi (see mirror()) and never to
 * its bundled CloseLoadExtractor, which only finds the page's 90 s decoy ("videosamplemp4") that CloudStream
 * then plays first. Every other iframe still goes to CloudStream's loadExtractor.
 */
public final class HdfPlayer {
    private static final String TAG = "KraptorHdf";
    private static final String HDF = "hdfilmcehennemi.mobi";
    private static final String HDF_SITE = "https://www.hdfilmcehennemi.nl/";
    private static final String CLOSELOAD = "closeload.";
    private static final String EMBED = "/video/embed/";
    private static final long TIMEOUT_MS = 25_000L;
    // Some pages define window.knAdres empty up front and fill it once the /kn/ answer is decoded.
    private static final String READ = "(function(){return window.knAdres?"
        + "JSON.stringify({u:String(window.knAdres),t:window.knT||[]}):null;})()";

    private HdfPlayer() { }

    /** Same signature as ExtractorApiKt.loadExtractor, which the plugins' loadLinks called here. */
    public static Object load(String url, String referer, Function1 subtitles, Function1 callback, Continuation completion) {
        String embed = url == null ? null : url.replace("{rapidrame_id}", "");
        String mirror = mirror(embed);
        if (mirror != null) {
            try {
                if (Boolean.TRUE.equals(extract(mirror, "FilmMakinesi", subtitles, callback))) return Boolean.TRUE;
            } catch (Throwable error) {
                Log.w(TAG, "player failed: " + error);
            }
            return Boolean.FALSE;
        }
        try {
            Boolean handled = isClose(embed, HDF) ? extract(embed, "HDFilmCehennemi", subtitles, callback) : null;
            if (handled != null) return handled;
        } catch (Throwable error) {
            Log.w(TAG, "player failed: " + error);
        }
        return ExtractorApiKt.loadExtractor(url, referer, subtitles, callback, completion);
    }

    /**
     * FilmMakinesi's closeload.<site>/video/embed/<id>/ as the same id on HDFilmCehennemi's Close host, else null.
     * closeload answered a plain fetch with 404 and its page never produced a stream in WebView; both sites embed
     * the same ids (Fjord: A6HYLfcXm6a6 on each). shortcut: relies on that shared backend (Nov 2026); an id HDF
     * does not know just skips the Close source, the film's Rapid source still plays.
     */
    static String mirror(String url) {
        if (url == null || !isClose(url, CLOSELOAD)) return null;
        return "https://" + HDF + URI.create(url).getPath();
    }

    /** An https Close embed whose host is {@code host}, or starts with it when it ends in a dot. */
    static boolean isClose(String url, String host) {
        try {
            URI uri = URI.create(url);
            String name = uri.getHost();
            String path = uri.getPath();
            if (!"https".equals(uri.getScheme()) || name == null || path == null || !path.startsWith(EMBED)) return false;
            return host.endsWith(".") ? name.startsWith(host) && name.length() > host.length() : name.equals(host);
        } catch (IllegalArgumentException | NullPointerException notUrl) {
            return false;
        }
    }

    /** TRUE/FALSE: a Close page, with or without a stream. null: another player format, for loadExtractor. */
    private static Boolean extract(final String url, String source, Function1 subtitles, Function1 callback) throws Exception {
        final Activity activity = CommonActivity.INSTANCE.getActivity();
        if (activity == null) return null;
        final String agent = WebSettings.getDefaultUserAgent(activity);
        final String html = fetch(url, agent);
        if (!html.contains("knKur")) return null;  // older player format: let the bundled extractor handle it

        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final AtomicReference<String> result = new AtomicReference<>();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    WebView view = new WebView(activity);
                    view.getSettings().setJavaScriptEnabled(true);
                    view.getSettings().setDomStorageEnabled(true);
                    view.getSettings().setMediaPlaybackRequiresUserGesture(true);
                    view.setWebViewClient(new WebViewClient());
                    holder[0] = view;
                    // Same origin as the embed, so the page's own XHR to .../kn/ is a same-origin request.
                    view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
                } catch (Throwable error) {
                    Log.w(TAG, "webview: " + error);
                }
            }
        });
        long started = SystemClock.elapsedRealtime();
        while (result.get() == null && SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
            SystemClock.sleep(500);
            main.post(new Runnable() {
                @Override public void run() {
                    WebView view = holder[0];
                    if (view == null) return;
                    view.evaluateJavascript(READ, new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            if (value != null && !"null".equals(value)) result.compareAndSet(null, value);
                        }
                    });
                }
            });
        }
        main.post(new Runnable() {
            @Override public void run() {
                WebView view = holder[0];
                if (view == null) return;
                try { view.stopLoading(); view.destroy(); } catch (Throwable ignored) { }
            }
        });
        String raw = result.get();
        if (raw == null) { Log.d(TAG, "no answer from player in " + TIMEOUT_MS + " ms"); return Boolean.FALSE; }
        // evaluateJavascript returns a JSON-encoded string.
        JSONObject answer = new JSONObject(new JSONArray("[" + raw + "]").getString(0));
        String stream = answer.optString("u", "");
        if (stream.isEmpty()) { Log.d(TAG, "player answered without a stream"); return Boolean.FALSE; }
        URI uri = URI.create(url).resolve(stream);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return Boolean.FALSE;

        String origin = "https://" + URI.create(url).getHost();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", agent);
        headers.put("Origin", origin);
        callback.invoke(new ExtractorLink(source, source + " - Close", uri.toString(),
            origin + "/", 0, ExtractorLinkType.M3U8, headers, null));
        JSONArray tracks = answer.optJSONArray("t");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            String file = track.optString("file", "");
            String kind = track.optString("kind", "captions");
            if (file.isEmpty() || !(kind.equals("captions") || kind.equals("subtitles"))) continue;
            subtitles.invoke(new SubtitleFile(track.optString("label", "Türkçe"), URI.create(url).resolve(file).toString()));
        }
        Log.d(TAG, "stream from " + uri.getHost() + " in " + (SystemClock.elapsedRealtime() - started) + " ms");
        return Boolean.TRUE;
    }

    private static String fetch(String url, String agent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", agent);
        connection.setRequestProperty("Referer", HDF_SITE);
        // Without a browser-like Accept header the player answers 404.
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0; ) {
                if (out.size() + count > 2097152) throw new IllegalStateException("player page too large");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }
}
