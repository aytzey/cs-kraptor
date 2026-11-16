import java.lang.reflect.*;
import java.net.URI;
import java.util.*;

public class InatProbe {
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> thread=Class.forName("android.app.ActivityThread");
        android.content.Context system=(android.content.Context)thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null));
        new Thread(() -> run(args)).start();
        android.os.Looper.loop();
    }
    static void run(String[] args) {
        try {
            MovieProbe.continuation=Class.forName("kotlin.coroutines.Continuation");
            MovieProbe.unit=Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            MovieProbe.coroutineContext=Class.forName("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
            Object provider=Class.forName("com.keyiflerolsun.InatBoxBC").getConstructor().newInstance();
            List<?> pages=(List<?>)MovieProbe.get(provider,"getMainPage");
            if(args[0].equals("pages")) {
                System.out.println("INAT pages="+pages.size());
                for(Object page:pages)System.out.println("INAT page="+MovieProbe.get(page,"getName"));
                System.exit(0);return;
            }
            int category=Integer.parseInt(args[0]);
            Object page=pages.get(category);
            Object request=Class.forName("com.lagradost.cloudstream3.MainPageRequest").getConstructor(String.class,String.class,boolean.class)
                .newInstance(MovieProbe.get(page,"getName"),MovieProbe.get(page,"getData"),false);
            Object response=MovieProbe.call(provider,"getMainPage",1,request);
            List<Object> items=new ArrayList<>();
            for(Object section:(List<?>)MovieProbe.get(response,"getItems"))items.addAll((List<?>)MovieProbe.get(section,"getList"));
            System.out.println("INAT category="+MovieProbe.get(page,"getName")+" count="+items.size());
            for(int i=0;i<Math.min(items.size(),10);i++) {
                Object item=items.get(i);
                org.json.JSONObject json=new org.json.JSONObject((String)MovieProbe.get(item,"getUrl"));
                String raw=json.optString("chUrl",json.optString("diziUrl"));
                System.out.println("INAT item="+MovieProbe.get(item,"getName")+" type="+json.optString("chType",json.optString("diziType"))+" host="+URI.create(raw).getHost());
            }
            if(items.isEmpty())throw new AssertionError("empty category");
            if(args.length<2) {System.exit(0);return;}
            Object chosen=null;
            for(Object item:items)if(((String)MovieProbe.get(item,"getName")).toLowerCase(Locale.ROOT).contains(args[1].toLowerCase(Locale.ROOT))) {chosen=item;break;}
            if(chosen==null)throw new AssertionError("requested item missing");
            System.out.println("INAT selected="+MovieProbe.get(chosen,"getName"));
            Object detail=MovieProbe.call(provider,"load",MovieProbe.get(chosen,"getUrl"));
            if(detail==null)throw new AssertionError("no detail");
            String data;
            try {data=(String)MovieProbe.get(detail,"getDataUrl");}
            catch(NoSuchMethodException absent) {
                Map<?,?> episodes=(Map<?,?>)MovieProbe.get(detail,"getEpisodes");
                List<?> first=(List<?>)episodes.values().iterator().next();
                System.out.println("INAT episodes="+first.size());
                data=(String)MovieProbe.get(first.get(0),"getData");
            }
            Class<?> fn=Class.forName("kotlin.jvm.functions.Function1");
            int[] links={0},usable={0};
            Object subtitles=Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},(p,m,a)->MovieProbe.unit);
            Object callback=Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},(p,m,a)->{
                if(m.getName().equals("invoke")) {
                    Object link=a[0];links[0]++;
                    String url=(String)MovieProbe.get(link,"getUrl");
                    Map<String,String> headers=new HashMap<>((Map<String,String>)MovieProbe.get(link,"getHeaders"));
                    String ref=(String)MovieProbe.get(link,"getReferer");
                    if(ref!=null&&!ref.isEmpty())headers.put("Referer",ref);
                    System.out.println("INAT link-host="+URI.create(url).getHost()+" type="+MovieProbe.get(link,"getType")+" headers="+headers.keySet());
                    try {MovieProbe.media(url,headers,0);usable[0]++;}
                    catch(Exception|AssertionError failure) {String msg=failure.getMessage();System.out.println("INAT media-failure="+failure.getClass().getSimpleName()+(msg!=null&&msg.matches("HTTP [0-9]+")?" "+msg:""));}
                }
                return MovieProbe.unit;
            });
            Object outcome=MovieProbe.call(provider,"loadLinks",data,false,subtitles,callback);
            System.out.println("INAT outcome="+outcome+" links="+links[0]+" usable="+usable[0]);
            if(usable[0]==0)throw new AssertionError("no playable media");
            System.exit(0);
        } catch(Throwable failure) {
            for(Throwable error=failure;error!=null;error=error.getCause()) {
                System.out.println("INAT failure="+error.getClass().getName());
                for(StackTraceElement frame:error.getStackTrace())if(frame.getClassName().startsWith("InatProbe")||frame.getClassName().startsWith("com.keyiflerolsun"))System.out.println("INAT at="+frame);
            }
            System.exit(1);
        }
    }
}
