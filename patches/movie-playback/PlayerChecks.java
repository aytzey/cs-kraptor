package com.kraptor;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run with the installed CloudStream APK on Android, so Rhino and JSON use the real runtime. */
public final class PlayerChecks {
    private static final String URL = "https://media.example.test/movie.m3u8";
    private static String page(String argument, String decoder) throws Exception {
        return "eval(function(p,a,c,k,e,d){return p}(" + JSONObject.quote(decoder) + "))\n"
            + "var configs={sources:[{file:(function(){return stream(" + argument + "); })()}],"
            + "tracks: [{\"file\":\"/tr.vtt\",\"kind\":\"captions\",\"label\":\"Türkçe\"}]\n};";
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String wrapper(String url) throws Exception {
        String script = "var AYAR=" + new JSONObject().put("kaynak", url) + ";";
        byte[] data = Base64.encode(script.getBytes("UTF-8"), Base64.NO_WRAP);
        for (int i=0;i<data.length;i++) data[i] += 3;
        String text = new StringBuilder(Base64.encodeToString(data, Base64.NO_WRAP)).reverse().toString();
        StringBuilder hex = new StringBuilder();
        for (char c:text.toCharArray()) hex.append(String.format(java.util.Locale.ROOT, "%02x", (int)c));
        return "})('" + hex + "',3)";
    }
    public static void main(String[] args) throws Exception {
        String encoded = Base64.encodeToString(URL.getBytes("UTF-8"), Base64.NO_WRAP);
        String decoder = "function stream(parts){return atob(parts.join(''));}";
        for (String argument : new String[]{new JSONArray().put(encoded).toString(),
                JSONObject.quote(encoded.substring(0,8)+"~"+encoded.substring(8))+".split(\"~\")"}) {
            String result = HdfilmConfig.parse(page(argument,decoder));
            check(result != null, "packed player must decode array and split arguments");
            JSONObject config = new JSONObject(result);
            check(URL.equals(config.getJSONArray("sources").getJSONObject(0).getString("file")), "media URL changed");
            check(config.getJSONArray("tracks").length()==1, "subtitles lost");
        }
        check(HdfilmConfig.parse("<html>upstream error</html>")==null, "legacy fallback lost");
        check(HdfilmConfig.parse(page("[]", "function stream(parts){return java.lang.System.getProperty('user.home');}"))==null,
            "player Java access must be denied");
        check(HdfilmConfig.parse(page("[]", "function stream(parts){while(true){}}"))==null, "unbounded script execution");
        check("https://ha.vixolity.com/vs/fixture".equals(DelisiNative.master(wrapper("https://ha.vixolity.com/vs/fixture"))),
            "Vixolity wrapper not decoded");
        check(DelisiNative.master(wrapper("file:///etc/passwd"))==null, "non-web source accepted");
        check(DelisiNative.master(wrapper("https://other.example/vs/fixture"))==null, "unexpected Vixolity source host");
        System.out.println("PlayerChecks: passed");
        System.exit(0);
    }
}
