package com.kraptor;

import java.util.HashMap;
import java.util.Map;

public final class PageHeadersChecks {
    public static void main(String[] args) {
        Map<String, String> original = new HashMap<>();
        original.put("Referer", "https://example.test");
        original.put("accept", "*/*");
        Map<String, String> fixed = PageHeaders.headers(original);
        if (original.size() != 2 || !"*/*".equals(original.get("accept")) || fixed.containsKey("accept")
                || !original.get("Referer").equals(fixed.get("Referer"))
                || !fixed.get("Accept").contains("text/html") || PageHeaders.headers(null).size() != 2)
            throw new AssertionError("replace page negotiation headers without changing caller headers");
        System.out.println("PageHeadersChecks: passed");
    }
}
