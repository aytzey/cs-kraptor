package com.kraptor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PageHeaders {
    public static Map<String, String> headers(Map<String, String> original) {
        Map<String, String> result = new LinkedHashMap<>();
        if (original != null) for (Map.Entry<String, String> entry : original.entrySet()) {
            if (!"User-Agent".equalsIgnoreCase(entry.getKey()) && !"Accept".equalsIgnoreCase(entry.getKey()))
                result.put(entry.getKey(), entry.getValue());
        }
        result.put("User-Agent", SeriesHeaders.USER_AGENT);
        result.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return result;
    }

    public static Map<String, String> browserHeaders(Map<String, String> original) {
        Map<String, String> result = headers(original);
        result.put("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6");
        result.put("Sec-Fetch-Dest", "document");
        result.put("Sec-Fetch-Mode", "navigate");
        result.put("Sec-Fetch-Site", "none");
        result.put("Upgrade-Insecure-Requests", "1");
        return result;
    }
}
