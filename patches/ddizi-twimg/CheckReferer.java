import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/** Runs against the installed CloudStream API and patched DEX without opening the app. */
public class CheckReferer {
    public static void main(String[] args) throws Exception {
        Class<?> linkClass = Class.forName("com.lagradost.cloudstream3.utils.ExtractorLink");
        Constructor<?> constructor = linkClass.getConstructor(String.class, String.class,
            String.class, String.class, int.class, boolean.class, Map.class, String.class);
        Method apply = Class.forName("com.kraptor.Ddizi")
            .getMethod("setPlayerReferer", linkClass, String.class);
        String referer = "https://example.invalid/player";
        String[][] cases = {
            {"https://video.twimg.com/test.m3u8", ""},
            {"https://VIDEO.TWIMG.COM:443/test.m3u8", ""},
            {"https://video.twimg.com/test.mp4?tag=1", ""},
            {"https://video.twimg.com.example.invalid/test.m3u8", referer},
            {"https://video.twimg.com@example.invalid/test.m3u8", referer},
            {"https://example.invalid/?url=https://video.twimg.com/a", referer},
            {"/relative.m3u8", referer},
            {"https://vmcdn.ciner.com.tr/test.m3u8", referer}
        };
        Map<String, String> headers = Collections.singletonMap("User-Agent", "test");
        for (String[] test : cases) {
            Object link = constructor.newInstance("test", "test", test[0], referer,
                0, true, headers, null);
            apply.invoke(null, link, referer);
            if (!test[1].equals(linkClass.getMethod("getReferer").invoke(link)))
                throw new AssertionError("Unexpected Referer for " + test[0]);
            if (!headers.equals(linkClass.getMethod("getHeaders").invoke(link)))
                throw new AssertionError("Unrelated headers changed");
        }
        System.out.println("Android runtime: " + cases.length + " Referer cases passed; headers preserved");
        System.exit(0);
    }
}
