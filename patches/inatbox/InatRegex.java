package com.kraptor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InatRegex {
    public static boolean isDirect(String url) {
        if (url == null) return false;
        try {
            String path = new URI(url).getPath();
            if (path == null) return false;
            path = path.toLowerCase(Locale.ROOT);
            return path.contains(".m3u8") || path.contains(".mpd") || path.contains(".mp4") || path.contains(".webm");
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    public static String extract(String response, String pattern) {
        if (response == null || pattern == null) return null;
        try {
            Matcher match = Pattern.compile(pattern, Pattern.MULTILINE).matcher(response);
            if (match.groupCount() == 0 || !match.find() || match.group(1) == null) return null;
            String url = match.group(1).trim().replace("\\/", "/");
            URI parsed = new URI(url);
            return ("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                && parsed.getHost() != null && parsed.getUserInfo() == null ? url : null;
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            return null;
        }
    }
}
