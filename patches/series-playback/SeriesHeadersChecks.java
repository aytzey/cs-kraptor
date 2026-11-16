package com.kraptor;

import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class SeriesHeadersChecks {
    static ExtractorLink received;
    static int calls;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        Function1<ExtractorLink, Unit> callback = new Function1<ExtractorLink, Unit>() {
            public Unit invoke(ExtractorLink value) { received = value; calls++; return Unit.INSTANCE; }
        };
        Function1<ExtractorLink, Unit> wrapped = SeriesHeaders.wrap(callback);
        check(SeriesHeaders.wrap(wrapped) == wrapped && SeriesHeaders.wrap(null) == null, "wrap once, including coroutine resume");
        Map<String, String> original = Collections.singletonMap("Referer", "https://dbx.molystream.org/frame");
        ExtractorLink link = new ExtractorLink("source", "name", "https://dbx.molystream.org/master.m3u8?q=1",
                "https://example.test/ref", 1080, ExtractorLinkType.M3U8, original, "metadata");
        wrapped.invoke(link);
        check(calls == 1 && received != link, "one callback per source");
        check(SeriesHeaders.USER_AGENT.equals(received.getHeaders().get("User-Agent")), "Moly user agent");
        check(original.size() == 1 && original.get("Referer").equals(received.getHeaders().get("Referer")), "retain Referer without mutating extractor headers");
        check(received.getSource().equals(link.getSource()) && received.getName().equals(link.getName())
                && received.getUrl().equals(link.getUrl()) && received.getReferer().equals(link.getReferer())
                && received.getQuality() == link.getQuality() && received.getType() == link.getType()
                && received.getExtractorData().equals(link.getExtractorData()), "retain link metadata");
        ExtractorLink other = new ExtractorLink("s", "n", "https://other.test/master.m3u8", "", 720,
                ExtractorLinkType.M3U8, original, "");
        wrapped.invoke(other);
        check(received == other, "unrelated sources unchanged");
        Map<String, String> explicit = new HashMap<>(original);
        explicit.put("user-agent", "explicit");
        ExtractorLink configured = new ExtractorLink("s", "n", link.getUrl(), "", 720,
                ExtractorLinkType.M3U8, explicit, "");
        wrapped.invoke(configured);
        check(received == configured && calls == 3, "retain explicitly configured user agent");
        ExtractorLink malformed = new ExtractorLink("s", "n", "not a URL", "", 720,
                ExtractorLinkType.M3U8, original, "");
        wrapped.invoke(malformed);
        check(received == malformed && calls == 4, "unrelated malformed URL must not interrupt the callback");
        System.out.println("SeriesHeadersChecks: passed");
        System.exit(0);
    }
}
