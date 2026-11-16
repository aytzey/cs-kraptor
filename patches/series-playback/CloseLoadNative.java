package com.kraptor;

import android.util.Log;
import com.lagradost.cloudstream3.MainActivityKt;
import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.JobKt;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

public final class CloseLoadNative {
    private static final Pattern FILE = Pattern.compile("sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*([\\w$]+)");
    private static final Pattern TRACKS = Pattern.compile("\\btracks\\s*:\\s*(\\[[^\\r\\n]*\\])");
    private static final String BASE64 = "function atob(s){var a='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',o='',v=0,b=0;"
        + "s=String(s).replace(/\\s/g,'').replace(/=+$/,'');for(var i=0;i<s.length;i++){var n=a.indexOf(s.charAt(i));"
        + "if(n<0)throw Error('base64');v=(v<<6)|n;b+=6;if(b>=8){b-=8;o+=String.fromCharCode((v>>b)&255);}}return o;}";

    private static final class BoundedContext extends ContextFactory {
        long deadline;
        void start() { deadline = System.nanoTime() + 5_000_000_000L; }
        protected void observeInstructionCount(Context context, int count) {
            if (deadline != 0 && System.nanoTime() > deadline) throw new IllegalStateException("player decode timeout");
        }
    }

    // shortcut: decode the standalone source function; retain WebView fallback for other player formats.
    static String parse(String html, String player) {
        if (html.length() > 262144) return null;
        Matcher file = FILE.matcher(html);
        if (!file.find()) return null;
        Matcher call = Pattern.compile("(?:var|let|const)\\s+" + Pattern.quote(file.group(1))
                + "\\s*=\\s*([\\w$]+)\\s*\\((\\[[^;]+?\\]|\"[^\"\\r\\n]*\"\\.split\\(\"[^\"\\r\\n]*\"\\))\\)").matcher(html);
        if (!call.find()) return null;
        Matcher function = Pattern.compile("(?ms)(?:var|let|const)\\s+" + Pattern.quote(call.group(1))
                + "\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{.*?^\\};").matcher(html);
        if (!function.find()) return null;
        BoundedContext factory = new BoundedContext();
        Context context = factory.enterContext();
        try {
            JSONObject attributes = new JSONObject();
            for (Element node : Jsoup.parse(html).select("[id]")) {
                JSONObject values = new JSONObject();
                for (org.jsoup.nodes.Attribute attribute : node.attributes()) values.put(attribute.getKey(), attribute.getValue());
                attributes.put(node.id(), values);
            }
            Matcher selectors = Pattern.compile("document\\.(?:querySelectorAll|querySelector|getElementsByTagName)\\((['\"])([^'\"\\r\\n]+)\\1\\)").matcher(function.group());
            org.jsoup.nodes.Document document = Jsoup.parse(html);
            while (selectors.find()) {
                Element node = document.selectFirst(selectors.group(2));
                if (node == null) continue;
                JSONObject values = new JSONObject();
                for (org.jsoup.nodes.Attribute attribute : node.attributes()) values.put(attribute.getKey(), attribute.getValue());
                attributes.put(selectors.group(2), values);
            }
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(10000);
            context.setClassShutter(new ClassShutter() { public boolean visibleToScripts(String name) { return false; } });
            Scriptable scope = context.initSafeStandardObjects();
            String dom = "var location={hostname:" + JSONObject.quote(URI.create(player).getHost()) + "};"
                    + "var window={},navigator={userAgent:'native'};"
                    + "var document=(function(attrs){function node(s){var a=attrs[String(s)]||attrs[String(s).replace(/^#/,'')];if(!a)return null;"
                    + "var names={};for(var k in a)names[k]={name:k,value:a[k],nodeValue:a[k]};"
                    + "names.getNamedItem=function(k){return names[k]||null;};"
                    + "return {attributes:names,getAttribute:function(k){return a[k]||'';}};}"
                    + "return {getElementById:node,querySelector:node,querySelectorAll:function(s){var a=node(s);return a?[a]:[];},"
                    + "createElement:function(n){return {tagName:String(n).toUpperCase()};},"
                    + "getElementsByTagName:function(n){var a=node(n);return n==='script'?[{}]:a?[a]:[];}};})(" + attributes + ");"
                    + "window.location=location;window.document=document;window.navigator=navigator;"
                    + "window.window=window;window.self=window;document.location=location;document.defaultView=window;var self=window;";
            factory.start();
            String url = String.valueOf(context.evaluateString(scope, BASE64 + dom + function.group() + "\n"
                    + call.group(1) + "(" + call.group(2) + ");", "closeload-source", 1, null));
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            Matcher tracks = TRACKS.matcher(html);
            return new JSONObject().put("sources", new JSONArray().put(new JSONObject().put("file", url)))
                    .put("tracks", tracks.find() ? new JSONArray(tracks.group(1)) : new JSONArray()).toString();
        } catch (Exception failure) {
            Log.w("CloseLoad", "Native decode failed: " + failure.getClass().getSimpleName());
            if ("player decode timeout".equals(failure.getMessage())) Log.w("CloseLoad", "Decode budget expired");
            return null;
        } finally { Context.exit(); }
    }

    public static boolean load(String url, String referer, Function1 subtitles, Function1 callback, Continuation completion) {
        try {
            JobKt.ensureActive(completion.getContext());
            Request.Builder builder = new Request.Builder().url(url)
                    .header("Referer", referer == null ? "https://filmmakinesi.to/" : referer);
            for (java.util.Map.Entry<String, String> header : PageHeaders.headers(null).entrySet())
                builder.header(header.getKey(), header.getValue());
            Request request = builder.build();
            String html;
            try (Response response = MainActivityKt.getApp().getBaseClient().newBuilder()
                    .callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null || response.body().contentLength() > 262144) {
                    Log.w("CloseLoad", "Player HTTP " + response.code()); return false;
                }
                html = response.body().string();
            }
            String decoded = parse(html, url);
            if (decoded == null) decoded = HdfilmConfig.parse(html);
            if (decoded == null) { Log.w("CloseLoad", "Unrecognized player source format"); return false; }
            JSONObject config = new JSONObject(decoded);
            JobKt.ensureActive(completion.getContext());
            JSONArray tracks = config.optJSONArray("tracks");
            if (tracks != null) for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.getJSONObject(i);
                String source = URI.create(url).resolve(track.optString("file")).toString();
                if ("captions".equals(track.optString("kind")) && "https".equals(URI.create(source).getScheme()))
                    subtitles.invoke(new SubtitleFile(track.optString("label", "Türkçe"), source));
            }
            String source = config.getJSONArray("sources").getJSONObject(0).getString("file");
            callback.invoke(new ExtractorLink("FilmMakinesi", "FilmMakinesi - CloseLoad", source, url, 400,
                    ExtractorLinkType.M3U8, Collections.singletonMap("User-Agent", SeriesHeaders.USER_AGENT), null));
            return true;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { Log.w("CloseLoad", "Native player failed: " + failure.getClass().getSimpleName()); return false; }
    }
}
