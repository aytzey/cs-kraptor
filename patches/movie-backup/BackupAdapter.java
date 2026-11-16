package com.kraptor;

import com.lagradost.cloudstream3.HomePageList;
import com.lagradost.cloudstream3.HomePageResponse;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.MainPageRequest;
import com.lagradost.cloudstream3.SearchResponse;
import com.lagradost.cloudstream3.SearchResponseList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.JobKt;

/** Recover empty/unavailable public catalogs without changing their installed API names. */
public final class BackupAdapter {
    private interface Recovery { Object apply(Object result) throws Exception; }

    private static boolean allowed(MainAPI provider) {
        try {
            Class<?> helper=Class.forName(provider.getClass().getName()+"Helper",true,provider.getClass().getClassLoader());
            Object instance=helper.getField("INSTANCE").get(null);
            return Boolean.TRUE.equals(helper.getMethod("isAllowedVersion").invoke(instance));
        } catch (Exception e) { return false; }
    }

    private static Object original(MainAPI provider, String name, Object[] arguments) throws Exception {
        Method found=null;
        for (Method method:provider.getClass().getMethods()) if (method.getName().equals(name)) {
            if (found!=null) throw new IllegalStateException("ambiguous original provider method");
            found=method;
        }
        if (found==null) throw new NoSuchMethodException(name);
        try { return found.invoke(provider,arguments); }
        catch (InvocationTargetException e) {
            Throwable cause=e.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw new RuntimeException(cause);
        }
    }

    private static Object recover(Object result, Continuation completion, Recovery recovery) throws Exception {
        try { ResultKt.throwOnFailure(result); }
        catch (CancellationException e) { throw e; }
        catch (Exception e) { result=null; }
        JobKt.ensureActive(completion.getContext());
        return recovery.apply(result);
    }

    private static Object call(MainAPI provider, String name, Object[] arguments,
            final Continuation completion, final Recovery recovery) throws Exception {
        if (!allowed(provider)) return original(provider,name,arguments);
        arguments[arguments.length-1]=new Continuation<Object>() {
            public CoroutineContext getContext() { return completion.getContext(); }
            public void resumeWith(Object result) {
                Object completed;
                try { completed=recover(result,completion,recovery); }
                catch (Throwable e) { completed=ResultKt.createFailure(e); }
                completion.resumeWith(completed);
            }
        };
        Object result;
        try { result=original(provider,name,arguments); }
        catch (CancellationException e) { throw e; }
        catch (Exception e) { result=null; }
        if (result==IntrinsicsKt.getCOROUTINE_SUSPENDED()) return result;
        return recover(result,completion,recovery);
    }

    public static Object search(final MainAPI provider, final String query, final int page, Continuation completion) throws Exception {
        return call(provider,"searchOriginal",new Object[]{query,page,completion},completion,new Recovery() {
            public Object apply(Object result) throws Exception {
                if (result instanceof SearchResponseList && !((SearchResponseList)result).getItems().isEmpty()) return result;
                return new SearchResponseList(page==1 ? MovieBackup.search(provider,query) : Collections.<SearchResponse>emptyList(),false);
            }
        });
    }

    public static Object searchLegacy(final MainAPI provider, final String query, Continuation completion) throws Exception {
        return call(provider,"searchOriginal",new Object[]{query,completion},completion,new Recovery() {
            public Object apply(Object result) throws Exception {
                if (result instanceof java.util.List && !((java.util.List)result).isEmpty()) return result;
                return MovieBackup.search(provider,query);
            }
        });
    }

    public static Object home(final MainAPI provider, final int page, final MainPageRequest request, Continuation completion) throws Exception {
        return call(provider,"homeOriginal",new Object[]{page,request,completion},completion,new Recovery() {
            public Object apply(Object result) throws Exception {
                if (result instanceof HomePageResponse) for (HomePageList section:((HomePageResponse)result).getItems())
                    if (!section.getList().isEmpty()) return result;
                if (page==1 && request!=null && !provider.getMainPage().isEmpty()
                        && request.getName().equals(provider.getMainPage().get(0).getName())) {
                    HomePageList section=new HomePageList("Yedek · HDFilmCehennemi",MovieBackup.home(provider),request.getHorizontalImages());
                    return new HomePageResponse(Collections.singletonList(section),false);
                }
                return result==null ? new HomePageResponse(Collections.emptyList(),false) : result;
            }
        });
    }

    public static Object load(MainAPI provider, String url, Continuation completion) throws Exception {
        if (allowed(provider) && MovieBackup.sameOrigin(url)) {
            JobKt.ensureActive(completion.getContext());
            return MovieBackup.details(provider,url);
        }
        return original(provider,"loadOriginal",new Object[]{url,completion});
    }

    public static Object links(MainAPI provider, String data, boolean casting, Function1 subtitles,
            Function1 callback, Continuation completion) throws Exception {
        if (!allowed(provider)) return original(provider,"linksOriginal",new Object[]{data,casting,subtitles,callback,completion});
        final Function1 checked=MovieBackup.wrap(data,subtitles,callback,completion);
        if (MovieBackup.sameOrigin(data)) return MovieBackup.finish(checked);
        return call(provider,"linksOriginal",new Object[]{data,casting,subtitles,checked,completion},completion,new Recovery() {
            public Object apply(Object result) { return MovieBackup.finish(checked); }
        });
    }
}
