package com.kraptor;

import com.lagradost.cloudstream3.Episode;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.io.IOException;
import java.util.List;
import java.util.Collections;
import okhttp3.Request;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.json.JSONObject;
import org.jsoup.nodes.Document;

public final class DiziPalHtmlChecks {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static ExtractorLink emitted;
    static int calls;

    public static void main(String[] args) throws Exception {
        MainAPI api = new MainAPI() {
            public String getName() { return "DiziPal"; }
            public String getMainUrl() { return "https://dizipal.example"; }
        };
        String card = "<article class='content-card'><a class='card-link' href='/dizi/fixture'><h3 class='card-title'>A &amp; B</h3></a></article>";
        Document doc = DiziPalHtml.document(card);
        check(doc.selectFirst(".post-item a").attr("title").equals("A & B"), "new article cards retain their titles");
        doc = DiziPalHtml.document(new JSONObject().put("html", card).put("hasMore", true).toString());
        check(doc.select(".post-item").size() == 1 && doc.selectFirst("a.next") != null, "decode pagination JSON with its next page");
        check(DiziPalHtml.document(card).selectFirst("a.next") == null, "final page must stop pagination");
        JSONObject embedConfig = DiziPalHtml.embedConfig("jwplayer('v').setup({sources:[{file:\"https://media.example/master.m3u8\",type:\"hls\"}],tracks:[{\"file\":\"/tr.vtt\",\"kind\":\"captions\",\"label\":\"Türkçe\"}]});");
        check(embedConfig.getString("file").equals("https://media.example/master.m3u8")
                && embedConfig.getJSONArray("tracks").length() == 1, "read the literal embed HLS source and retain subtitles");
        check(DiziPalHtml.embedConfig("sources:[{file:'https://media.example/master.m3u8'}]") != null, "support single-quoted public player URLs");
        check(DiziPalHtml.embedConfig("sources:[{file:'file:///invalid.m3u8'}]") == null
                && DiziPalHtml.embedConfig("sources:[{file:getDynamicSource()}]") == null, "reject unsafe and executable player sources");
        check(DiziPalHtml.homeUrl("https://dizipal.example/diziler/", 2).contains("/diziler-load?page=2"), "use the new pagination endpoint");
        check(DiziPalHtml.searchUrl(api, "A & B", 1).contains("q=A+%26+B"), "encode search terms");
        Request config = DiziPalHtml.configRequest(api.getMainUrl(), api.getMainUrl()+"/bolum/fixture", "fixture",
                Collections.singletonMap("fixture_session", "fixture_value"));
        check(config.url().toString().endsWith("/ajax-player-config")
                && config.header("Cookie").equals("fixture_session=fixture_value")
                && config.header("Referer").endsWith("/bolum/fixture"), "preserve the anonymous player session and page referer");
        Document guard = DiziPalHtml.document("<meta name='_bc' content='fixture'>");
        DiziPalHtml.browserPage(guard, api, api.getMainUrl()+"/bolum/fixture");
        check(guard.selectFirst(".responsive-player iframe").attr("src").endsWith("/bolum/fixture"), "browser verification must open the full episode page");
        try { DiziPalHtml.browserPage(guard, api, "https://other.example/episode"); throw new AssertionError("cross-origin browser page accepted"); }
        catch (IOException expected) {}
        doc = DiziPalHtml.document("<a class='detail-episode-item' href='https://dizipal.example/bolum/a-1-sezon-2-bolum'><div class='detail-episode-subtitle'>Pilot</div></a>"
                + "<a class='detail-episode-item' href='https://dizipal.example/bolum/a-3-sezon-7-bolum'><div class='detail-episode-subtitle'>Final</div></a>");
        List<Episode> episodes = DiziPalHtml.episodes(api, doc);
        check(episodes.size() == 2 && episodes.get(0).getSeason() == 1 && episodes.get(0).getEpisode() == 2
                && episodes.get(1).getSeason() == 3 && episodes.get(1).getEpisode() == 7
                && episodes.get(0).getName().equals("Pilot"), "retain every season and episode number");
        check(DiziPalHtml.episodes(api, DiziPalHtml.document("<div class='episode-item'></div>")) == null, "legacy pages retain the original parser");
        Function1<ExtractorLink, Unit> callback = new Function1<ExtractorLink, Unit>() {
            public Unit invoke(ExtractorLink link) { emitted = link; calls++; return Unit.INSTANCE; }
        };
        Object direct = DiziPalHtml.player(doc, api, callback, new JSONObject().put("t", "m3u8").put("v", "https://media.example/master.m3u8"));
        check(Boolean.TRUE.equals(direct) && calls == 1 && emitted.getType() == ExtractorLinkType.M3U8
                && emitted.getReferer().equals(api.getMainUrl() + "/"), "direct public HLS emits one playable link");
        Object embed = DiziPalHtml.player(doc, api, callback, new JSONObject().put("t", "iframe")
                .put("v", "<iframe src='https://media.example/embed'></iframe>"));
        check(embed == doc && doc.selectFirst(".responsive-player iframe").attr("src").equals("https://media.example/embed")
                && calls == 1, "embed configuration reaches the original extractor");
        try {
            DiziPalHtml.player(doc, api, callback, new JSONObject().put("v", "file:///invalid"));
            throw new AssertionError("non-web player URL accepted");
        } catch (IOException expected) {}
        System.out.println("DiziPalHtmlChecks: passed");
        System.exit(0);
    }
}
