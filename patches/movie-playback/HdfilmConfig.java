package com.kraptor;

import android.util.Log;
import java.net.URI;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

/** Decode the player's packed URL without loading its UI and third-party scripts. */
public final class HdfilmConfig {
    public static String origin(android.content.Context context) {
        String origin = context.getSharedPreferences("DomainListesi", 0)
            .getString("HDFilmCehennemi", "https://www.hdfilmcehennemi.nl");
        return origin != null && origin.startsWith("https://") ? origin : "https://www.hdfilmcehennemi.nl";
    }

    // shortcut: array and string-split player forms; extend from a captured page if the format changes.
    private static final Pattern SOURCE = Pattern.compile("return\\s+([\\w$]+)\\s*\\((\\[[^;]+?\\]|\"[^\"\\r\\n]*\"\\.split\\(\"[^\"\\r\\n]*\"\\))\\)\\s*;\\s*\\}\\)");
    private static final Pattern SPLIT = Pattern.compile("(\"[^\"]*\")\\.split\\((\"[^\"]*\")\\)");
    private static final Pattern TRACKS = Pattern.compile("\\btracks\\s*:\\s*(\\[[^\\r\\n]*\\])");
    private static final String BASE64 = "function atob(s){var a='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',o='',v=0,b=0;"
        + "s=String(s).replace(/\\s/g,'').replace(/=+$/,'');for(var i=0;i<s.length;i++){var n=a.indexOf(s.charAt(i));"
        + "if(n<0)throw Error('base64');v=(v<<6)|n;b+=6;if(b>=8){b-=8;o+=String.fromCharCode((v>>b)&255);}}return o;}";

    private static final class BoundedContext extends ContextFactory {
        private long deadline;
        void start() { deadline = System.nanoTime() + 5_000_000_000L; }
        @Override protected void observeInstructionCount(Context context, int count) {
            if (deadline != 0 && System.nanoTime() > deadline) throw new IllegalStateException("player decode timeout");
        }
    }

    public static String parse(String html) {
        if (html.length() > 262144) return null;
        int start = html.indexOf("eval(function(p,a,c,k,e,d)");
        int end = html.indexOf("var configs", start < 0 ? 0 : start);
        Matcher source = SOURCE.matcher(html);
        if (start < 0 || end < start || !source.find()) return null;
        BoundedContext factory = new BoundedContext();
        Context context = factory.enterContext();
        try {
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(10000);
            context.setClassShutter(new ClassShutter() {
                @Override public boolean visibleToScripts(String name) { return false; }
            });
            Scriptable scope = context.initSafeStandardObjects();
            // Only the packed decoder and its literal string array run; DOM and Java APIs are absent.
            String argument = source.group(2);
            Matcher split = SPLIT.matcher(argument);
            JSONArray parts;
            if (split.matches()) {
                String text = (String)new JSONTokener(split.group(1)).nextValue();
                String separator = (String)new JSONTokener(split.group(2)).nextValue();
                if (separator.isEmpty()) return null;
                parts = new JSONArray(Arrays.asList(text.split(Pattern.quote(separator), -1)));
            } else {
                parts = new JSONArray(argument);
            }
            String code = BASE64 + html.substring(start, end) + "\n" + source.group(1) + "(" + parts + ");";
            factory.start();
            String url = String.valueOf(context.evaluateString(scope, code, "hdfilm-player", 1, null));
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            Matcher tracks = TRACKS.matcher(html);
            return new JSONObject().put("sources", new JSONArray().put(new JSONObject().put("file", url)))
                .put("tracks", tracks.find() ? new JSONArray(tracks.group(1)) : new JSONArray()).toString();
        } catch (Exception e) {
            Log.w("HDFC", "Native player decode failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            Context.exit();
        }
    }
}
