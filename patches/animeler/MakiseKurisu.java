package com.kraptor;

import com.lagradost.cloudstream3.utils.ExtractorApi;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

/** Animeler's own HLS player (/embed/plyr/{id}); the master playlist is served with a .css name. */
public final class MakiseKurisu extends ExtractorApi {
    // shortcut: matched by this one host; add the new domain here if Animeler rotates its player host.
    private static final String MAIN = "https://makisekurisu.site";
    private static final Pattern MASTER = Pattern.compile("\"master\"\\s*:\\s*\"(https?:[^\"]+)\"");

    @Override public String getName() { return "Animeler"; }
    @Override public String getMainUrl() { return MAIN; }
    @Override public boolean getRequiresReferer() { return false; }

    @Override
    public Object getUrl(String url, String referer, Function1 subtitleCallback, Function1 callback,
                         Continuation continuation) {
        String master = master(url, referer);
        if (master != null)
            callback.invoke(new ExtractorLink(getName(), getName(), master, url, 400,
                ExtractorLinkType.M3U8, new HashMap<String, String>(), null));
        return Unit.INSTANCE;
    }

    /** The player page answers 403 unless it is requested as an iframe of an Animeler page. */
    static String master(String url, String referer) {
        try {
            AnimelerHttp.Resp r = AnimelerHttp.request(url, "GET", null, new String[] {
                "Referer", referer != null && !referer.isEmpty() ? referer : "https://animeler.pw/",
                "Sec-Fetch-Dest", "iframe"});
            if (r.code != 200) return null;
            Matcher m = MASTER.matcher(r.body);
            if (!m.find()) return null;
            String master = m.group(1).replace("\\/", "/");
            return AnimelerHttp.isWebUrl(master) ? master : null;
        } catch (Exception e) {
            return null;
        }
    }
}
