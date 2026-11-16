package com.kraptor;

import org.json.JSONObject;

public final class CloseLoadChecks {
    static String fixture(String body) {
        return "<i id='guard' data-c='fixture'></i><script>\nvar decode = function(parts) {\n" + body
                + "\n};\nvar media = decode([\"/master.m3u8\"]);\n"
                + "var player=jwplayer('videoplayer').setup({sources: [{file: media, type: 'hls'}],tracks: []});</script>";
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            String captured = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), "UTF-8");
            String parsed = CloseLoadNative.parse(captured, args[1]);
            check(parsed != null, "captured player must decode");
            System.out.println("CloseLoadChecks: captured player passed");
            System.exit(0);
        }
        String html = fixture("return 'https://' + location.hostname + '/' + document.querySelectorAll('#guard')[0].getAttribute('data-c') + parts.join('');");
        JSONObject decoded = new JSONObject(CloseLoadNative.parse(html, "https://closeload.example/embed/1"));
        check(decoded.getJSONArray("sources").getJSONObject(0).getString("file").equals("https://closeload.example/fixture/master.m3u8"), "decode the selected source with its own host and DOM attributes");
        html = html.replace("[\"/master.m3u8\"]", "\"/master.m3u8\".split(\"@\")");
        check(CloseLoadNative.parse(html, "https://closeload.example/embed/1") != null, "support split literal player sources");
        String alternative = fixture("return 'https://' + self.location.hostname + '/' + document.querySelector('[data-c]').getAttribute('data-c') + parts.join('');");
        check(CloseLoadNative.parse(alternative, "https://closeload.example/embed/1") != null, "support the alternate player host and attribute selectors");
        String documentHost = fixture("return 'https://' + document.location.hostname + '/' + document.defaultView.location.hostname + parts.join('');");
        check(CloseLoadNative.parse(documentHost, "https://closeload.example/embed/1") != null, "document and its own window expose the same player location");
        String namedAttribute = fixture("return 'https://' + self.location.hostname + '/' + document.querySelector('[data-c]').attributes['data-c'].value + parts.join('');");
        check(CloseLoadNative.parse(namedAttribute, "https://closeload.example/embed/1") != null, "read player attributes through their named Attr nodes");
        check(CloseLoadNative.parse(fixture("return 'file:///invalid';"), "https://closeload.example") == null, "reject non-web decoded sources");
        check(CloseLoadNative.parse(fixture("return java.lang.System.getProperty('user.home');"), "https://closeload.example") == null, "decoder cannot access Java");
        check(CloseLoadNative.parse(fixture("while (true) {}"), "https://closeload.example") == null, "bound untrusted decoder execution");
        check(CloseLoadNative.parse("<html>unrelated page</html>", "https://closeload.example") == null, "retain fallback for unknown formats");
        System.out.println("CloseLoadChecks: passed");
        System.exit(0);
    }
}
