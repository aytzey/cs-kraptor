package com.kraptor;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public final class AnimelerPage {
    private static final Pattern EPISODES = Pattern.compile(
        "\\bvar\\s+allEpisodesData\\s*=\\s*(\\[.*?\\])\\s*;", Pattern.DOTALL);

    public static Elements episodes(Document page) {
        Elements fallback = page.select(".episode-grid a.ep-item");
        for (Element script : page.select("script:not([src])")) {
            Matcher match = EPISODES.matcher(script.data());
            if (!match.find()) continue;
            try {
                JSONArray rows = new JSONArray(match.group(1));
                LinkedHashMap<String, Element> episodes = new LinkedHashMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    String url = row.optString("url", "");
                    URI uri = URI.create(url);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) ||
                            uri.getHost() == null || uri.getUserInfo() != null) continue;
                    String number = row.optString("start", "");
                    if (number.isEmpty()) continue;
                    Element item = new Element("a").attr("href", url).attr("class", "ep-item")
                        .attr("data-ep-num", number)
                        .attr("data-episode-title", row.optString("title", ""));
                    episodes.put(url, item);
                }
                if (episodes.size() >= fallback.size() && !episodes.isEmpty())
                    return new Elements(episodes.values());
            } catch (JSONException | IllegalArgumentException ignored) {
                // Keep the existing HTML list when the site's embedded JSON is malformed.
            }
        }
        return fallback;
    }
}
