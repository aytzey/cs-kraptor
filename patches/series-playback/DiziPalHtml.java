package com.kraptor;

import com.lagradost.cloudstream3.Episode;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.MainActivityKt;
import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import com.lagradost.nicehttp.NiceResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.JobKt;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class DiziPalHtml {
    private static final Pattern EPISODE = Pattern.compile("-(\\d+)-sezon-(\\d+)-bolum(?:/|$)");
    private static final Pattern SOURCE = Pattern.compile("\\bsources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");
    private static final Pattern TRACKS = Pattern.compile("\\btracks\\s*:\\s*(\\[[^\\r\\n]*\\])");

    public static String homeUrl(String url, int page) {
        if (page <= 1) return url;
        URI uri = URI.create(url);
        String path = uri.getPath().replaceAll("/+$", "");
        String route = path.equals("/filmler") ? "filmler-load" : path.equals("/diziler") ? "diziler-load"
                : path.equals("/anime") ? "anime-load" : null;
        return route == null ? url + (url.contains("?") ? "&" : "?") + "page=" + page
                : uri.getScheme() + "://" + uri.getAuthority() + "/" + route + "?page=" + page + "&filters=%7B%7D";
    }

    public static String searchUrl(MainAPI api, String query, int page) throws Exception {
        return api.getMainUrl() + "/arama?q=" + URLEncoder.encode(query, "UTF-8") + "&page=" + page;
    }

    public static Document document(String text) throws Exception {
        boolean more = false;
        if (text.trim().startsWith("{")) {
            JSONObject result = new JSONObject(text);
            text = result.optString("html", "");
            more = result.optBoolean("has_more", result.optBoolean("hasMore", false));
        }
        Document doc = Jsoup.parse(text);
        for (Element card : doc.select(".content-card")) {
            card.addClass("post-item");
            Element link = card.selectFirst("a.card-link"), title = card.selectFirst(".card-title");
            if (link != null && title != null) link.attr("title", title.text());
        }
        doc.select("h1.series-title, h1.film-title").addClass("text-white");
        doc.select("p.series-description, .film-description").addClass("summary-text");
        if (more || doc.selectFirst("#infiniteLoader") != null)
            doc.body().appendElement("a").addClass("next").attr("href", "?page=next").text("Sonraki");
        return doc;
    }

    public static List<Episode> episodes(MainAPI api, Element root) {
        if (root.selectFirst("a.detail-episode-item") == null) return null;
        List<Episode> episodes = new ArrayList<>();
        for (Element link : root.select("a.detail-episode-item")) {
            String url = link.attr("href");
            Matcher numbers = EPISODE.matcher(url);
            if (!numbers.find()) continue;
            final int season = Integer.parseInt(numbers.group(1)), number = Integer.parseInt(numbers.group(2));
            Element label = link.selectFirst(".detail-episode-subtitle");
            final String title = label == null || label.text().isEmpty() ? link.text() : label.text();
            episodes.add(MainAPIKt.newEpisode(api, url, new Function1<Episode, Unit>() {
                public Unit invoke(Episode episode) {
                    episode.setSeason(season); episode.setEpisode(number); episode.setName(title);
                    return Unit.INSTANCE;
                }
            }));
        }
        return episodes;
    }

    public static String browserScript() {
        return "(function(){if(window.__kraptorPlayer)return;window.__kraptorPlayer=true;var started=false;var timer=setInterval(function(){"
                + "document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;});"
                + "if(!started&&typeof window.startPlayer==='function'){started=true;window.startPlayer();}"
                + "var skip=document.getElementById('skipBtn');if(skip&&!skip.disabled&&skip.offsetParent!==null){skip.click();}"
                + "},350);setTimeout(function(){clearInterval(timer);},25000);})();";
    }

    static JSONObject embedConfig(String html) throws Exception {
        if (html.length() > 1048576) return null;
        Matcher source = SOURCE.matcher(html);
        if (!source.find()) return null;
        String value = (String)new JSONTokener(source.group(1)).nextValue();
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { return null; }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || !uri.getPath().contains(".m3u8")) return null;
        Matcher tracks = TRACKS.matcher(html);
        return new JSONObject().put("file", value)
                .put("tracks", tracks.find() ? new JSONArray(tracks.group(1)) : new JSONArray());
    }

    public static Boolean resolved(Request request, MainAPI api, Function1 subtitles, Function1 callback,
                                   Continuation completion) throws Exception {
        if (request == null || !request.url().host().equals("formationfeed.net")) return null;
        JobKt.ensureActive(completion.getContext());
        // This embed waits for a user gesture; read its literal HLS configuration before WebView waits for media.
        try (Response reply = MainActivityKt.getApp().getBaseClient().newBuilder()
                .callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute()) {
            if (!reply.isSuccessful() || reply.body() == null) return Boolean.FALSE;
            JSONObject config = embedConfig(reply.body().string());
            if (config == null) return Boolean.FALSE;
            JobKt.ensureActive(completion.getContext());
            String agent = request.header("User-Agent");
            String page = request.url().toString();
            JSONArray tracks = config.getJSONArray("tracks");
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.getJSONObject(i);
                String kind = track.optString("kind");
                if (!kind.equals("captions") && !kind.equals("subtitles")) continue;
                URI url = URI.create(page).resolve(track.optString("file"));
                if ("https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null)
                    subtitles.invoke(new SubtitleFile(track.optString("label", "Altyazı"), url.toString()));
            }
            callback.invoke(new ExtractorLink(api.getName(), api.getName(), config.getString("file"), page, 400,
                    ExtractorLinkType.M3U8, Collections.singletonMap("User-Agent", agent == null ? SeriesHeaders.USER_AGENT : agent), null));
            return Boolean.TRUE;
        } catch (IOException | org.json.JSONException unavailable) {
            JobKt.ensureActive(completion.getContext());
            return Boolean.FALSE;
        }
    }

    static Document browserPage(Document doc, MainAPI api, String page) throws Exception {
        URI origin = URI.create(api.getMainUrl()), target = origin.resolve(page);
        if (page.isEmpty() || !origin.getScheme().equals(target.getScheme()) || !origin.getHost().equals(target.getHost())
                || origin.getPort() != target.getPort() || target.getUserInfo() != null)
            throw new IOException("invalid DiziPal player page");
        doc.body().appendElement("div").addClass("responsive-player").appendElement("iframe").attr("src", target.toString());
        return doc;
    }

    static Request configRequest(String origin, String page, String cfg, Map<String, String> cookies) {
        Request.Builder request = new Request.Builder().url(origin + "/ajax-player-config")
                .header("User-Agent", SeriesHeaders.USER_AGENT).header("Referer", page)
                .header("Origin", origin).header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(new FormBody.Builder().add("cfg", cfg).build());
        if (cookies != null && !cookies.isEmpty()) {
            StringBuilder value = new StringBuilder();
            for (Map.Entry<String, String> cookie : cookies.entrySet()) {
                if (value.length() > 0) value.append("; ");
                value.append(cookie.getKey()).append('=').append(cookie.getValue());
            }
            request.header("Cookie", value.toString());
        }
        return request.build();
    }

    public static Object play(NiceResponse response, MainAPI api, Function1 callback, Continuation completion) throws Exception {
        JobKt.ensureActive(completion.getContext());
        Document doc = document(response.getText());
        String page = response.getUrl();
        if (doc.selectFirst("meta[name=_bc]") != null) return browserPage(doc, api, page);
        Element container = doc.selectFirst("#videoContainer[data-cfg]");
        if (container == null) return doc;
        String origin = api.getMainUrl();
        Request request = configRequest(origin, page, container.attr("data-cfg"), response.getCookies());
        JSONObject result;
        try (Response reply = MainActivityKt.getApp().getBaseClient().newBuilder()
                .callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute()) {
            if (!reply.isSuccessful() || reply.body() == null) {
                JobKt.ensureActive(completion.getContext());
                return browserPage(doc, api, page);
            }
            result = new JSONObject(reply.body().string());
        } catch (IOException | org.json.JSONException unavailable) {
            JobKt.ensureActive(completion.getContext());
            return browserPage(doc, api, page);
        }
        JobKt.ensureActive(completion.getContext());
        JSONObject config = result.optJSONObject("config");
        if (!result.optBoolean("success") || config == null) return browserPage(doc, api, page);
        return player(doc, api, callback, config);
    }

    static Object player(Document doc, MainAPI api, Function1 callback, JSONObject config) throws Exception {
        String origin = api.getMainUrl();
        String value = config.optString("v"), type = config.optString("t", "embed");
        if (type.equals("iframe")) {
            Element iframe = Jsoup.parse(value).selectFirst("iframe[src]");
            value = iframe == null ? "" : iframe.attr("src");
        }
        URI video = URI.create(value);
        if (!("https".equals(video.getScheme()) || "http".equals(video.getScheme()))
                || video.getHost() == null || video.getUserInfo() != null) throw new IOException("invalid player URL");
        if (type.equals("m3u8") || type.equals("mp4")) {
            callback.invoke(new ExtractorLink(api.getName(), api.getName(), value, origin + "/", 400,
                    type.equals("m3u8") ? ExtractorLinkType.M3U8 : ExtractorLinkType.VIDEO,
                    Collections.singletonMap("User-Agent", SeriesHeaders.USER_AGENT), null));
            return Boolean.TRUE;
        }
        doc.body().appendElement("div").addClass("responsive-player").appendElement("iframe").attr("src", value);
        return doc;
    }
}
