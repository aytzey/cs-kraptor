import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MovieProbe {
    static Class<?> continuation;
    static Object unit, coroutineContext;
    static Object call(Object target, String name, Object... args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Object[] result = new Object[1];
        Object completion = java.lang.reflect.Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation}, (p,m,a) -> {
            if (m.getName().equals("getContext")) return coroutineContext;
            if (m.getName().equals("resumeWith")) { result[0] = a[0]; done.countDown(); }
            return null;
        });
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length + 1) continue;
            Object[] values = Arrays.copyOf(args, args.length + 1); values[args.length] = completion;
            Object value = method.invoke(target, values);
            if (value == Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt").getMethod("getCOROUTINE_SUSPENDED").invoke(null)) {
                if (!done.await(100, TimeUnit.SECONDS)) throw new TimeoutException(name);
                value = result[0];
            }
            Class.forName("kotlin.ResultKt").getMethod("throwOnFailure",Object.class).invoke(null,value);
            return value;
        }
        throw new NoSuchMethodException(name);
    }
    static Object get(Object target, String name) throws Exception { return target.getClass().getMethod(name).invoke(target); }
    static void media(String url, Map<String,String> headers, int depth) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(10000);
        for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
        if(depth>0)c.setRequestProperty("Range","bytes=0-65535");
        try {
            int code=c.getResponseCode();
            if(code!=200 && code!=206)throw new java.io.IOException("HTTP "+code);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream in=c.getInputStream()) {
                byte[] b=new byte[4096];
                for(int n;bytes.size()<65536 && (n=in.read(b))>0;)bytes.write(b,0,n);
            }
            String body=new String(bytes.toByteArray(),"UTF-8");
            if(body.startsWith("#EXTM3U")) {
                if(depth>=3)throw new AssertionError("playlist recursion");
                String next=null;
                for(String line:body.split("\\r?\\n"))if(!line.trim().isEmpty() && !line.startsWith("#")){next=line.trim();break;}
                if(next==null)throw new AssertionError("empty HLS playlist");
                System.out.println("PROBE playlist="+depth+" http="+code);
                System.out.println("PROBE next-host="+new URI(url).resolve(next).getHost());
                media(new URI(url).resolve(next).toString(),headers,depth+1);
            } else {
                if(depth==0 || bytes.size()<188 || body.trim().startsWith("<"))throw new AssertionError("not HLS/media");
                System.out.println("PROBE segment http="+code+" bytes="+bytes.size());
            }
        } finally { c.disconnect(); }
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> thread=Class.forName("android.app.ActivityThread");
        android.content.Context system=(android.content.Context)thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null));
        android.content.Context ctx=system.createPackageContext("com.lagradost.cloudstream3",0);
        new Thread(() -> run(args,ctx)).start();
        android.os.Looper.loop();
    }
    static void run(String[] args, android.content.Context ctx) {
        try {
            continuation=Class.forName("kotlin.coroutines.Continuation");
            unit=Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            coroutineContext=Class.forName("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
            Class<?> type=Class.forName(args[0]);
            try {
                Class<?> helper=Class.forName(args[0]+"Helper");
                Object instance=helper.getField("INSTANCE").get(null);
                helper.getMethod("setup",android.content.Context.class).invoke(instance,ctx);
                if (!Boolean.TRUE.equals(helper.getMethod("isAllowedVersion").invoke(instance))) throw new IllegalStateException("package rejected");
            } catch (ClassNotFoundException|NoSuchMethodException ignored) {}
            Object provider;
            try { provider=type.getConstructor(android.content.SharedPreferences.class).newInstance(ctx.getSharedPreferences("DomainListesi",0)); }
            catch (NoSuchMethodException ignored) {
                try { provider=type.getConstructor().newInstance(); }
                catch(NoSuchMethodException ignoredAgain) { provider=type.getConstructor(android.content.Context.class).newInstance(ctx); }
            }
            if(!args[2].equals("extract"))type.getMethod("setMainUrl",String.class).invoke(provider,args[1]);
            System.out.println("PROBE provider="+args[0]+" main="+get(provider,"getMainUrl"));
            Class<?> extractor=Class.forName("com.lagradost.cloudstream3.utils.ExtractorApi");
            List<Object> extractors=(List<Object>)Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt").getMethod("getExtractorApis").invoke(null);
            Enumeration<String> classes=new dalvik.system.DexFile(args[4]).entries();
            int registered=0;
            while(classes.hasMoreElements()) {
                String name=classes.nextElement();
                if (name.contains("$") || !name.startsWith("com.")) continue;
                Class<?> c=Class.forName(name,false,type.getClassLoader());
                if (!extractor.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers())) continue;
                Object e;
                try { e=c.getConstructor(android.content.Context.class).newInstance(ctx); }
                catch (NoSuchMethodException ignored) { e=c.getConstructor().newInstance(); }
                extractors.add(0,e); registered++;
            }
            System.out.println("PROBE extractors="+registered);
            if (args[2].equals("search")) {
                Object response;
                try { response=call(provider,"search",args[3],1); }
                catch (NoSuchMethodException ignored) { response=call(provider,"search",args[3]); }
                List<?> results;
                if (response instanceof List) results=(List<?>)response;
                else results=(List<?>)get(response,"getItems");
                System.out.println("PROBE results="+results.size());
                for (int i=0;i<Math.min(3,results.size());i++) System.out.println("PROBE result="+get(results.get(i),"getName")+" url="+get(results.get(i),"getUrl"));
                if (results.isEmpty()) throw new AssertionError("empty search");
            } else {
                String data=args[3];
                if (!args[2].equals("links") && !args[2].equals("extract")) {
                    Object detail=call(provider,"load",data);
                    if(detail==null) throw new AssertionError("null detail");
                    System.out.println("PROBE title="+get(detail,"getName"));
                    try { data=(String)get(detail,"getDataUrl"); }
                    catch(NoSuchMethodException ignored) { System.out.println("PROBE detail="+detail.getClass().getSimpleName()); }
                }
                if (!args[2].equals("load")) {
                    Class<?> fn=Class.forName("kotlin.jvm.functions.Function1");
                    int[] links={0}, usable={0};
                    Object sub=java.lang.reflect.Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},(p,m,a)->unit);
                    Object link=java.lang.reflect.Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},(p,m,a)->{
                        if(m.getName().equals("invoke")) {
                            Object item=a[0]; String url=(String)get(item,"getUrl");
                            links[0]++;
                            System.out.println("PROBE link="+get(item,"getName")+" host="+URI.create(url).getHost()+" type="+get(item,"getType"));
                            Map<String,String> headers=new HashMap<>((Map<String,String>)get(item,"getHeaders"));
                            String ref=(String)get(item,"getReferer"); if(ref!=null&&!ref.isEmpty())headers.put("Referer",ref);
                            try {media(url,headers,0);usable[0]++;}
                            catch(Exception|AssertionError failure){System.out.println("PROBE media-failure="+failure.getClass().getSimpleName());}
                        }
                        return unit;
                    });
                    Object outcome=args[2].equals("extract") ? call(provider,"getUrl",data,args[1],sub,link)
                        : call(provider,"loadLinks",data,false,sub,link);
                    System.out.println("PROBE outcome="+outcome+" links="+links[0]+" usable="+usable[0]);
                    if(usable[0]==0)throw new AssertionError("no playable media");
                }
            }
            System.exit(0);
        } catch(Throwable e) {
            while(e!=null) {
                System.err.println("PROBE failure="+e.getClass().getName());
                for(StackTraceElement f:e.getStackTrace())if(f.getClassName().startsWith("MovieProbe")||f.getClassName().startsWith("com.kraptor")||f.getClassName().startsWith("com.byayzen"))System.err.println("PROBE at="+f);
                e=e.getCause();
            }
            System.exit(1);
        }
    }
}
