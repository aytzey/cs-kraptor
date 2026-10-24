import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

public class ProviderProbe {
    static Class<?> continuation;
    static Object unit;
    static Object context;

    static Object call(Object provider, String name, Class<?>[] types, Object... values) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Object[] result = new Object[1];
        Object callback = Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation},
            new InvocationHandler() { public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getContext")) return context;
                if (m.getName().equals("resumeWith")) { result[0] = a[0]; done.countDown(); }
                return null;
            }});
        Class<?>[] allTypes = Arrays.copyOf(types, types.length + 1);
        allTypes[types.length] = continuation;
        Object[] allValues = Arrays.copyOf(values, values.length + 1);
        allValues[values.length] = callback;
        Object immediate = provider.getClass().getMethod(name, allTypes).invoke(provider, allValues);
        if (immediate == Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt")
                .getMethod("getCOROUTINE_SUSPENDED").invoke(null)) {
            if (!done.await(75, TimeUnit.SECONDS)) throw new TimeoutException(name);
            immediate = result[0];
        }
        Class.forName("kotlin.ResultKt").getMethod("throwOnFailure", Object.class).invoke(null, immediate);
        return immediate;
    }

    public static void main(String[] args) {
        try {
            Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
            continuation = Class.forName("kotlin.coroutines.Continuation");
            unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            context = Class.forName("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Object system = activityThread.getMethod("getSystemContext").invoke(thread);
            Class<?> androidContext = Class.forName("android.content.Context");
            Object appContext = androidContext.getMethod("createPackageContext", String.class, int.class)
                .invoke(system, "com.lagradost.cloudstream3", 0);
            Class<?> helper = Class.forName("com.kraptor.AnimelerHelper");
            Object helperInstance = helper.getField("INSTANCE").get(null);
            helper.getMethod("setup", androidContext).invoke(helperInstance, appContext);
            if (!Boolean.TRUE.equals(helper.getMethod("isAllowedVersion").invoke(helperInstance)))
                throw new IllegalStateException("Installed package rejected by plugin");
            Object provider = Class.forName("com.kraptor.Animeler").getConstructor().newInstance();
            provider.getClass().getMethod("setMainUrl", String.class).invoke(provider, "https://animeler.pw");
            System.out.println("PROBE provider-created");
            ((List<Object>)Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt").getMethod("getExtractorApis").invoke(null))
                .add(0, Class.forName("com.kraptor.MakiseKurisu").getConstructor().newInstance());
            if (args[0].equals("search")) {
                Object results = call(provider, "search", new Class<?>[]{String.class, int.class}, "Naruto", 1);
                System.out.println("PROBE search-result=" + results.getClass().getSimpleName());
                for (Method m : results.getClass().getMethods())
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == List.class)
                        System.out.println("PROBE " + m.getName() + "=" + ((List<?>)m.invoke(results)).size());
            } else if (args[0].equals("load")) {
                Object detail = call(provider, "load", new Class<?>[]{String.class}, "https://animeler.pw/naruto");
                Map<?,?> episodes = (Map<?,?>)detail.getClass().getMethod("getEpisodes").invoke(detail);
                int count = 0;
                for (Object list : episodes.values()) count += ((List<?>)list).size();
                System.out.println("PROBE episodes=" + count);
            } else {
                Class<?> function = Class.forName("kotlin.jvm.functions.Function1");
                Object subtitle = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
                    new InvocationHandler() { public Object invoke(Object p, Method m, Object[] a) { return unit; }});
                Object link = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
                    new InvocationHandler() { public Object invoke(Object p, Method m, Object[] a) throws Exception {
                    if (m.getName().equals("invoke")) {
                        String url = (String)a[0].getClass().getMethod("getUrl").invoke(a[0]);
                        Object type = a[0].getClass().getMethod("getType").invoke(a[0]);
                        String ref = (String)a[0].getClass().getMethod("getReferer").invoke(a[0]);
                        int code = -1;
                        try { java.net.HttpURLConnection c = (java.net.HttpURLConnection)new java.net.URL(url).openConnection();
                            c.setConnectTimeout(10000); c.setReadTimeout(10000); if (ref != null && !ref.isEmpty()) c.setRequestProperty("Referer", ref);
                            code = c.getResponseCode(); c.disconnect(); } catch (Exception e) { code = -2; }
                        System.out.println("PROBE link host=" + URI.create(url).getHost() + " type=" + type + " http=" + code);
                    }
                    return unit;
                }});
                Object outcome = call(provider, "loadLinks", new Class<?>[]{String.class, boolean.class, function, function},
                    args.length > 1 ? args[1] : "https://animeler.pw/naruto/bolum-1", false, subtitle, link);
                System.out.println("PROBE loadLinks=" + outcome);
            }
            System.exit(0);
        } catch (Throwable error) {
            while (error != null) {
                System.err.println("PROBE failure=" + error.getClass().getName());
                for (StackTraceElement frame : error.getStackTrace()) {
                    if (frame.getClassName().startsWith("ProviderProbe") || frame.getClassName().startsWith("com.kraptor"))
                        System.err.println("PROBE at=" + frame);
                }
                error = error.getCause();
            }
            System.exit(1);
        }
    }
}
