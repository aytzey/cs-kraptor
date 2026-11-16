package com.kraptor;

import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class SeriesHeaders implements Function1<ExtractorLink, Unit> {
    public static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0";
    private final Function1<ExtractorLink, Unit> callback;

    private SeriesHeaders(Function1<ExtractorLink, Unit> callback) { this.callback = callback; }

    public static Function1<ExtractorLink, Unit> wrap(Function1<ExtractorLink, Unit> callback) {
        return callback == null || callback instanceof SeriesHeaders ? callback : new SeriesHeaders(callback);
    }

    public Unit invoke(ExtractorLink link) {
        if (link != null && link.getClass() == ExtractorLink.class && link.getType() == ExtractorLinkType.M3U8
                && moly(link.getUrl())) {
            Map<String, String> headers = link.getHeaders();
            boolean hasAgent = false;
            for (String name : headers.keySet()) if ("User-Agent".equalsIgnoreCase(name)) hasAgent = true;
            if (!hasAgent) {
                headers = new LinkedHashMap<>(headers);
                // The Moly child playlist rejects the player's default user agent; retain its own Referer.
                headers.put("User-Agent", USER_AGENT);
                link = new ExtractorLink(link.getSource(), link.getName(), link.getUrl(), link.getReferer(),
                        link.getQuality(), link.getType(), headers, link.getExtractorData());
            }
        }
        return callback.invoke(link);
    }

    private static boolean moly(String url) {
        try { return "dbx.molystream.org".equalsIgnoreCase(URI.create(url).getHost()); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
