package com.kraptor;

import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.SearchResponse;
import com.lagradost.cloudstream3.SearchResponseList;
import com.lagradost.cloudstream3.TvType;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;

public final class AdapterChecks {
    public static final class ProviderHelper {
        public static final ProviderHelper INSTANCE=new ProviderHelper();
        static boolean allowed=true;
        public boolean isAllowedVersion() { return allowed; }
    }

    public static final class Provider extends MainAPI {
        Object response;
        public String getName() { return "Adapter fixture"; }
        public String getMainUrl() { return "https://example.test"; }
        public Object searchOriginal(String query, int page, final Continuation completion) {
            if (query.equals("cancel")) throw new CancellationException("fixture cancellation");
            if (!query.equals("async")) return response;
            new Thread(new Runnable() {
                public void run() { completion.resumeWith(response); }
            }).start();
            return IntrinsicsKt.getCOROUTINE_SUSPENDED();
        }
    }

    private static final class Completion implements Continuation<Object> {
        final CountDownLatch done=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        Object result;
        public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
        public void resumeWith(Object result) { this.result=result; calls.incrementAndGet(); done.countDown(); }
    }

    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        Provider provider=new Provider();
        SearchResponse movie=MainAPIKt.newMovieSearchResponse$default(provider,"Fixture","https://example.test/film",TvType.Movie,
            false,new Function1() { public Object invoke(Object value) { return Unit.INSTANCE; }},8,null);
        provider.response=new SearchResponseList(Collections.singletonList(movie),false);
        Completion sync=new Completion();
        check(BackupAdapter.search(provider,"sync",1,sync)==provider.response,"working synchronous results must be preserved");
        check(sync.calls.get()==0,"synchronous results must not also resume the completion");
        Completion async=new Completion();
        check(BackupAdapter.search(provider,"async",1,async)==IntrinsicsKt.getCOROUTINE_SUSPENDED(),"suspension must be preserved");
        check(async.done.await(3,TimeUnit.SECONDS),"asynchronous results must resume");
        check(async.calls.get()==1 && async.result==provider.response,"asynchronous completion must happen exactly once");
        try { BackupAdapter.search(provider,"cancel",1,new Completion()); throw new AssertionError("cancellation swallowed"); }
        catch (CancellationException expected) {}
        ProviderHelper.allowed=false;
        provider.response=new SearchResponseList(Collections.<SearchResponse>emptyList(),false);
        check(BackupAdapter.search(provider,"sync",1,new Completion())==provider.response,"backup must respect the original package check");
        System.out.println("AdapterChecks: passed");
        System.exit(0);
    }
}
