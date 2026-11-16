package com.kraptor;

import android.util.Base64;
import android.util.Log;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.jvm.functions.Function1;
import org.json.JSONObject;
import org.json.JSONTokener;

/** HDFilmDelisi's public Vixolity player, replacing the former VidMody embed. */
public final class DelisiNative {
    private static final Pattern EMBED = Pattern.compile("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern WRAPPER = Pattern.compile("\\}\\)\\('([a-fA-F0-9]+)',\\s*(\\d+)\\)");
    private static final Pattern CONFIG = Pattern.compile("var\\s+AYAR\\s*=\\s*(\\{[^\\r\\n]+\\});");
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0";

    public static boolean load(String data, Function1 callback) {
        try {
            String page = data.startsWith("{") ? new JSONObject(data).getString("url") : data;
            URI uri = URI.create(page);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return false;
            String html = request(page, "");
            Matcher embed = EMBED.matcher(html);
            if (!embed.find()) return false;
            String player = (String)new JSONTokener("\"" + embed.group(1) + "\"").nextValue();
            if (!"pl.vixolity.com".equals(URI.create(player).getHost())) return false;
            String master = master(request(player, page));
            if (master == null) return false;
            callback.invoke(new ExtractorLink("HDFilmDelisi", "HDFilmDelisi - Delicore", master,
                player, 400, ExtractorLinkType.M3U8, Collections.singletonMap("User-Agent", UA), null));
            return true;
        } catch (Exception e) {
            Log.w("HDFilmDelisi", "Vixolity player failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    static String master(String html) throws Exception {
        Matcher wrapper = WRAPPER.matcher(html);
        if (!wrapper.find()) return null;
        String hex = wrapper.group(1);
        if ((hex.length() & 1) != 0 || hex.length() > 262144) return null;
        StringBuilder reversed = new StringBuilder();
        for (int i=0; i<hex.length(); i+=2) reversed.append((char)Integer.parseInt(hex.substring(i,i+2),16));
        byte[] shifted = Base64.decode(reversed.reverse().toString(), Base64.DEFAULT);
        int shift = Integer.parseInt(wrapper.group(2));
        for (int i=0; i<shifted.length; i++) shifted[i] = (byte)((shifted[i] & 255) - shift);
        String script = new String(Base64.decode(shifted, Base64.DEFAULT), "UTF-8");
        Matcher config = CONFIG.matcher(script);
        if (!config.find()) return null;
        String master = new JSONObject(config.group(1)).getString("kaynak");
        URI uri = URI.create(master);
        return "https".equals(uri.getScheme()) && "ha.vixolity.com".equals(uri.getHost())
            && uri.getUserInfo() == null ? master : null;
    }

    private static String request(String url, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", UA);
        if (!referer.isEmpty()) connection.setRequestProperty("Referer", referer);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0;) {
                if (out.size() + count > 2097152) throw new IllegalStateException("player response too large");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }
}
