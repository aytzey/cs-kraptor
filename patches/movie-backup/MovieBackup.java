package com.kraptor;

import android.util.Base64;
import android.util.Log;
import com.lagradost.cloudstream3.MainActivityKt;
import com.lagradost.cloudstream3.MovieLoadResponse;
import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.MovieSearchResponse;
import com.lagradost.cloudstream3.SearchResponse;
import com.lagradost.cloudstream3.TvType;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.JobKt;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Keep working original links; resolve an identified movie elsewhere when none remain. */
public final class MovieBackup {
    private static final String MARKER = "kraptorBackup=";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0";
    private static final Pattern IMDB = Pattern.compile("tt[0-9]{7,10}");
    private static final Pattern RAPID = Pattern.compile("(?:rapidrame_id=|/rplayer/)([a-z0-9]{12})(?:[&/#?]|$)");
    private static volatile String origin = "https://www.hdfilmcehennemi.nl";
    private static OkHttpClient client;

    public static void setup(android.content.Context context) {
        try {
            URI uri=URI.create(HdfilmConfig.origin(context));
            if ("https".equals(uri.getScheme()) && uri.getUserInfo()==null && uri.getPort()==-1
                    && uri.getHost()!=null && uri.getHost().matches("(?:www\\.)?hdfilmcehennemi\\.[a-z.]+"))
                origin="https://"+uri.getHost();
        } catch (IllegalArgumentException e) { Log.w("MovieBackup","Invalid configured movie origin"); }
    }

    public static Object remember(Object value) {
        if (value instanceof MovieLoadResponse) {
            MovieLoadResponse movie = (MovieLoadResponse)value;
            Map<String,String> sync = movie.getSyncData();
            movie.setDataUrl(tag(movie.getDataUrl(), movie.getName(), movie.getYear()==null ? 0 : movie.getYear(),
                sync==null ? "" : sync.get("imdb")));
        }
        return value;
    }

    static String tag(String url, String title, int year, String imdb) {
        try {
            if (!web(url) || (URI.create(url).getFragment()!=null && metadata(url)==null)) return url;
            JSONObject data = new JSONObject().put("title", title).put("year", year)
                .put("imdb", imdb!=null && IMDB.matcher(imdb).matches() ? imdb : "");
            return url.split("#", 2)[0] + "#" + MARKER + Base64.encodeToString(data.toString().getBytes("UTF-8"),
                Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        } catch (Exception ignored) { return url; }
    }

    static JSONObject metadata(String url) {
        try {
            String fragment = URI.create(url).getRawFragment();
            if (fragment==null || !fragment.startsWith(MARKER) || fragment.length()>8192) return null;
            return new JSONObject(new String(Base64.decode(fragment.substring(MARKER.length()), Base64.URL_SAFE), "UTF-8"));
        } catch (Exception ignored) { return null; }
    }

    public static Function1 wrap(String data, Function1 subtitles, Function1 callback) {
        return wrap(data,subtitles,callback,null);
    }

    public static Function1 wrap(String data, Function1 subtitles, Function1 callback, Continuation completion) {
        return callback instanceof CheckedLinks ? callback : new CheckedLinks(metadata(data), subtitles, callback,
            completion==null ? null : completion.getContext());
    }

    public static boolean finish(Function1 callback) {
        if (!(callback instanceof CheckedLinks)) return false;
        CheckedLinks links = (CheckedLinks)callback;
        links.active();
        if (links.hasLinks) return true;
        try { return links.backup(); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) {
            Log.w("MovieBackup", "Backup failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static final class CheckedLinks implements Function1 {
        private final JSONObject metadata;
        private final Function1 subtitles, callback;
        private final CoroutineContext context;
        private volatile boolean hasLinks;

        CheckedLinks(JSONObject data, Function1 subtitles, Function1 callback, CoroutineContext context) {
            this.metadata=data; this.subtitles=subtitles; this.callback=callback;
            this.context=context;
        }

        void active() { if (context!=null) JobKt.ensureActive(context); }

        @Override public Object invoke(Object value) {
            ExtractorLink link = (ExtractorLink)value;
            active();
            if (link.getType()!=ExtractorLinkType.M3U8 || usable(link.getUrl(), link.getReferer(), link.getHeaders(), 0)) {
                active();
                callback.invoke(value);
                hasLinks=true;
            }
            return Unit.INSTANCE;
        }

        boolean backup() throws Exception {
            if (metadata==null) return false;
            String imdb = metadata.optString("imdb");
            String title = metadata.optString("title").split(" - ", 2)[0];
            if (imdb.isEmpty() && (title.isEmpty() || metadata.optInt("year")<1878)) return false;
            active();
            Map<String,Element> candidates = new LinkedHashMap<>();
            JSONArray rows = json("/search?q="+URLEncoder.encode(imdb.isEmpty()?title:imdb,"UTF-8")).getJSONArray("results");
            for (int i=0; i<rows.length(); i++) {
                Document row = Jsoup.parse(rows.getString(i), origin);
                Element a = row.selectFirst("a[href]");
                if (a==null || !sameOrigin(a.absUrl("href"))) continue;
                Element name = row.selectFirst("h4.title, h3");
                Element year = row.selectFirst(".year");
                int y = year!=null && year.text().matches("[0-9]{4}") ? Integer.parseInt(year.text()) : 0;
                if (!imdb.isEmpty() || (name!=null && matches(metadata,name.text(),y,""))) candidates.put(a.absUrl("href"),row);
            }
            if (candidates.size()!=1) return false;
            String page = candidates.keySet().iterator().next();
            active();
            Document film = Jsoup.parse(text(page,origin+"/"), page);
            Element idLink = film.selectFirst("a[href*=imdb.com/title/]");
            Matcher found = IMDB.matcher(idLink==null ? "" : idLink.attr("href"));
            if (!found.find() || (!imdb.isEmpty() && !imdb.equals(found.group()))) return false;
            Element iframe = film.selectFirst("iframe[data-src]");
            if (iframe==null) return false;
            Matcher rapid = RAPID.matcher(iframe.attr("data-src"));
            if (!rapid.find()) return false;
            String player = origin+"/rplayer/"+rapid.group(1)+"/";
            active();
            String parsed = HdfilmConfig.parse(text(player,origin+"/"));
            if (parsed==null) return false;
            JSONObject config = new JSONObject(parsed);
            String media = config.getJSONArray("sources").getJSONObject(0).getString("file");
            Map<String,String> headers = Collections.singletonMap("User-Agent",UA);
            if (!usable(media,player,headers,0)) return false;
            active();
            JSONArray tracks = config.optJSONArray("tracks");
            if (tracks!=null) for (int i=0; i<tracks.length(); i++) {
                JSONObject track=tracks.getJSONObject(i);
                String url=URI.create(player).resolve(track.optString("file")).toString();
                if ("captions".equals(track.optString("kind")) && web(url))
                    subtitles.invoke(new SubtitleFile(track.optString("label","Türkçe"),url));
            }
            callback.invoke(new ExtractorLink("HDFilmCehennemi", "Yedek · HDFilmCehennemi - RapidRame", media,
                player, 400, ExtractorLinkType.M3U8, headers, null));
            hasLinks=true;
            return true;
        }
    }

    static boolean matches(JSONObject data, String title, int year, String imdb) {
        String wanted=data.optString("imdb");
        if (!wanted.isEmpty()) return wanted.equals(imdb);
        if (data.optInt("year")!=year || year<1878) return false;
        for (String name:data.optString("title").split(" - ")) {
            String normalized=normalize(name);
            if (normalized.isEmpty()) continue;
            for (String candidate:title.split(" - ")) if (normalized.equals(normalize(candidate))) return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static boolean sameOrigin(String url) {
        if (!web(url)) return false;
        URI uri=URI.create(url);
        return origin.equals("https://"+uri.getHost()) && "https".equals(uri.getScheme())
            && (uri.getPort()==-1 || uri.getPort()==443);
    }

    private static boolean web(String url) {
        try {
            URI uri=URI.create(url);
            return url.length()<8192 && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getHost()!=null && uri.getUserInfo()==null;
        } catch (Exception ignored) { return false; }
    }

    private static synchronized OkHttpClient client() {
        if (client==null) client=MainActivityKt.getApp().getBaseClient().newBuilder()
            .connectTimeout(4,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build();
        return client;
    }

    private static byte[] request(String url, String referer, Map<String,String> headers, int limit, boolean range) throws Exception {
        if (!web(url)) throw new IllegalArgumentException("invalid movie URL");
        Request.Builder request=new Request.Builder().url(url).header("User-Agent",UA);
        for (Map.Entry<String,String> h:headers.entrySet()) request.header(h.getKey(),h.getValue());
        if (!referer.isEmpty()) request.header("Referer",referer);
        if (range) request.header("Range","bytes=0-65535");
        try (Response response=client().newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body()==null) throw new java.io.IOException("movie HTTP "+response.code());
            try (InputStream in=response.body().byteStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];
                for (int n; out.size()<limit && (n=in.read(buffer,0,Math.min(buffer.length,limit-out.size())))>0;) out.write(buffer,0,n);
                return out.toByteArray();
            }
        }
    }

    static String text(String url, String referer) throws Exception {
        return new String(request(url,referer,Collections.emptyMap(),2097152,false),"UTF-8");
    }

    private static JSONObject json(String path) throws Exception {
        return new JSONObject(new String(request(origin+path,origin+"/",Collections.singletonMap("X-Requested-With","fetch"),
            2097152,false),"UTF-8"));
    }

    static List<SearchResponse> search(MainAPI provider, String query) throws Exception {
        JSONArray rows = json("/search?q="+URLEncoder.encode(query,"UTF-8")).getJSONArray("results");
        List<SearchResponse> results = new ArrayList<>();
        for (int i=0;i<rows.length();i++) {
            Document row = Jsoup.parse(rows.getString(i),origin);
            Element type = row.selectFirst(".type");
            if (type!=null && !type.text().equalsIgnoreCase("Film")) continue;
            SearchResponse item = entry(provider,row);
            if (item!=null) results.add(item);
        }
        return results;
    }

    static List<SearchResponse> home(MainAPI provider) throws Exception {
        Document doc = Jsoup.parse(text(origin+"/",origin+"/"),origin);
        List<SearchResponse> results = new ArrayList<>();
        for (Element poster:doc.select("a.poster")) {
            SearchResponse item=entry(provider,poster);
            if (item!=null) results.add(item);
            if (results.size()==24) break;
        }
        return results;
    }

    private static SearchResponse entry(MainAPI provider, Element row) {
        Element a = row.tagName().equals("a") ? row : row.selectFirst("a[href]");
        Element name = row.selectFirst("h4.title, h3, .poster-title");
        if (a==null || name==null || !sameOrigin(a.absUrl("href"))) return null;
        Element image=row.selectFirst("img");
        final String poster=image==null ? null : !image.absUrl("data-src").isEmpty() ? image.absUrl("data-src") : image.absUrl("src");
        Element year=row.selectFirst(".year, .poster-meta > span:first-child");
        final Integer y=year!=null && year.text().matches("[0-9]{4}") ? Integer.valueOf(year.text()) : null;
        return MainAPIKt.newMovieSearchResponse$default(provider,name.text()+" · Yedek",a.absUrl("href"),TvType.Movie,false,
            new Function1() { public Object invoke(Object value) {
                MovieSearchResponse movie=(MovieSearchResponse)value;
                movie.setPosterUrl(poster); movie.setYear(y); return Unit.INSTANCE;
            }},8,null);
    }

    static MovieLoadResponse details(MainAPI provider, String url) throws Exception {
        Document doc=Jsoup.parse(text(url,origin+"/"),url);
        Element title=doc.selectFirst("h1");
        Element id=doc.selectFirst("a[href*=imdb.com/title/]");
        Matcher imdb=IMDB.matcher(id==null?"":id.attr("href"));
        if (title==null || !imdb.find()) throw new java.io.IOException("backup movie identity missing");
        String name=title.text().replaceFirst("\\s+izle(?:\\s.*)?$","");
        // Keep the SDK defaults for the optional fields after dataUrl.
        MovieLoadResponse movie=new MovieLoadResponse(name,url,provider.getName(),TvType.Movie,url,
            null,null,null,null,null,null,null,null,null,false,null,null,null,null,null,null,0x1fffe0,null);
        movie.setSyncData(new LinkedHashMap<String,String>());
        movie.getSyncData().put("imdb",imdb.group());
        Element poster=doc.selectFirst("meta[property=og:image]");
        if (poster!=null) movie.setPosterUrl(poster.attr("content"));
        Element year=doc.selectFirst("a[href*=/yil/]");
        if (year!=null && year.text().matches("[0-9]{4}")) movie.setYear(Integer.valueOf(year.text()));
        movie.setPlot("Yedek kaynak: HDFilmCehennemi.");
        remember(movie);
        return movie;
    }

    private static boolean usable(String url, String referer, Map<String,String> headers, int depth) {
        try {
            byte[] bytes=request(url,referer,headers,65536,depth>0);
            String body=new String(bytes,"UTF-8").trim();
            if (body.startsWith("#EXTM3U")) {
                if (depth>=3) return false;
                for (String line:body.split("\\r?\\n")) if (!line.trim().isEmpty() && !line.startsWith("#"))
                    return usable(URI.create(url).resolve(line.trim()).toString(),referer,headers,depth+1);
                return false;
            }
            return bytes.length>=188 && !body.startsWith("<") && (depth>0 || !url.contains(".m3u8"));
        } catch (Exception ignored) { return false; }
    }
}
