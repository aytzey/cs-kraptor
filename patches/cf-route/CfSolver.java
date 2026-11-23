package com.kraptor;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.lagradost.cloudstream3.CommonActivity;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Passes Cloudflare's browser challenge the way a browser does: the page is opened once in a WebView,
 * and the resulting cf_clearance cookie is sent, with the same User-Agent, on the plugin's own requests.
 *
 * Needs a current system WebView; Cloudflare keeps WebView 101 (Android 10 TV boxes) in a reload loop.
 * The WebView is shown over the app while the challenge runs (a few seconds) so it renders like a
 * normal page; it never takes focus, so the remote keeps controlling CloudStream.
 */
public final class CfSolver {
    private static final String TAG = "KraptorCf";
    private static final long TIMEOUT_MS = 40_000L;
    private static final long POLL_MS = 400L;
    private static final Map<String, Object> LOCKS = new HashMap<>();
    /** User-Agent of the WebView that earned each host's clearance; Cloudflare binds the cookie to it. */
    private static final Map<String, String> AGENTS = new ConcurrentHashMap<>();
    /** Earliest time each host may open a WebView again: one minute after a solve, ten after a failure. */
    private static final Map<String, Long> NEXT_ATTEMPT = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 60_000L;
    private static final long RETRY_AFTER_FAILURE_MS = 600_000L;
    /** Outdated WebViews (e.g. 101 on Android 10 boxes) reload the interstitial forever; stop after this many. */
    private static final int MAX_CHALLENGE_PAGES = 3;

    private CfSolver() { }

    /** Ready-made interceptor for plugins without their own CloudflareInterceptor class. */
    public static final Interceptor INTERCEPTOR = new Interceptor() {
        @Override public Response intercept(Chain chain) throws IOException { return CfSolver.intercept(chain, null); }
    };

    /** Called by each plugin's CloudflareInterceptor; {@code original} is kept for signature compatibility. */
    public static Response intercept(Interceptor.Chain chain, Interceptor original) throws IOException {
        Request request = chain.request();
        String host = request.url().isHttps() ? request.url().host() : null;
        if (host == null) return chain.proceed(request);

        String before = clearance(host);
        Response response = chain.proceed(withClearance(request, host));
        if (!isChallenge(response)) return response;
        response.close();
        Log.d(TAG, "challenge " + host + " " + response.code() + (before == null ? "" : " (with clearance)"));

        synchronized (lock(host)) {
            String current = clearance(host);
            if (current != null && !current.equals(before)) {  // solved by a parallel request meanwhile
                Response retried = chain.proceed(withClearance(request, host));
                if (!isChallenge(retried)) return retried;
                retried.close();
            }
            Long next = NEXT_ATTEMPT.get(host);
            if (next != null && SystemClock.elapsedRealtime() < next) return chain.proceed(withClearance(request, host));
            if (solve(request.url().toString(), host)) {
                NEXT_ATTEMPT.put(host, SystemClock.elapsedRealtime() + RETRY_AFTER_MS);
                Response solved = chain.proceed(withClearance(request, host));
                Log.d(TAG, "after solve " + host + " " + solved.code());
                return solved;
            }
            NEXT_ATTEMPT.put(host, SystemClock.elapsedRealtime() + RETRY_AFTER_FAILURE_MS);
            return chain.proceed(request);
        }
    }

    private static Object lock(String host) {
        synchronized (LOCKS) {
            Object lock = LOCKS.get(host);
            if (lock == null) { lock = new Object(); LOCKS.put(host, lock); }
            return lock;
        }
    }

    private static String cookies(String host) {
        try { return CookieManager.getInstance().getCookie("https://" + host + "/"); }
        catch (Throwable ignored) { return null; }
    }

    private static String clearance(String host) {
        String all = cookies(host);
        if (all == null) return null;
        for (String part : all.split(";")) {
            String cookie = part.trim();
            if (cookie.startsWith("cf_clearance=")) return cookie;
        }
        return null;
    }

    static Request withClearance(Request request, String host) {
        if (clearance(host) == null) return request;
        String agent = AGENTS.get(host);
        if (agent == null) agent = defaultAgent();
        if (agent == null) return request;
        String own = request.header("Cookie");
        String jar = cookies(host);
        return request.newBuilder()
            .header("Cookie", own == null || own.isEmpty() ? jar : own + "; " + jar)
            .header("User-Agent", agent)
            .build();
    }

    private static String defaultAgent() {
        Activity activity = CommonActivity.INSTANCE.getActivity();
        if (activity == null) return null;
        try { return WebSettings.getDefaultUserAgent(activity); } catch (Throwable ignored) { return null; }
    }

    static boolean isChallenge(Response response) {
        int code = response.code();
        // 429 is a site rate limit, not a challenge; a WebView cannot clear it.
        if (code != 403 && code != 503) return false;
        if ("challenge".equalsIgnoreCase(response.header("cf-mitigated"))) return true;
        try {
            String body = response.peekBody(65536).string();
            // Cloudflare injects challenge-platform into ordinary pages too, so only the interstitial counts.
            return body.contains("Just a moment") || body.contains("cf_chl_opt");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean solve(final String url, final String host) {
        final Activity activity = CommonActivity.INSTANCE.getActivity();
        if (activity == null || activity.isFinishing()) { Log.d(TAG, "solve " + host + " -> no activity"); return false; }
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final long started = SystemClock.elapsedRealtime();
        final String stale = clearance(host);  // rejected cookie from an earlier visit; wait for a new one
        final AtomicInteger challengePages = new AtomicInteger();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (activity.isFinishing()) return;
                    ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
                    WebView view = new WebView(activity);
                    WebSettings settings = view.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    AGENTS.put(host, settings.getUserAgentString());
                    CookieManager.getInstance().setAcceptCookie(true);
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
                    view.setFocusable(false);
                    view.setFocusableInTouchMode(false);
                    view.setWebViewClient(new WebViewClient() {
                        @Override public void onPageFinished(WebView page, String loaded) {
                            String title = page.getTitle();
                            if (title != null && title.contains("Just a moment")) challengePages.incrementAndGet();
                        }
                    });
                    root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    holder[0] = view;
                    // WebView adds "X-Requested-With: <app package>"; some sites answer that with an
                    // "app not supported" page instead of the challenge, so the main request sends it empty.
                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-Requested-With", "");
                    view.loadUrl(url, headers);
                } catch (Throwable error) {
                    Log.w(TAG, "webview " + host + ": " + error);
                }
            }
        });
        boolean solved = false;
        while (SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
            String fresh = clearance(host);
            if (fresh != null && !fresh.equals(stale) && AGENTS.containsKey(host)) { solved = true; break; }
            if (challengePages.get() >= MAX_CHALLENGE_PAGES) { Log.d(TAG, "challenge keeps reloading; WebView too old?"); break; }
            SystemClock.sleep(POLL_MS);
        }
        main.post(new Runnable() {
            @Override public void run() {
                WebView view = holder[0];
                if (view == null) return;
                try {
                    ViewGroup parent = (ViewGroup) view.getParent();
                    if (parent != null) parent.removeView(view);
                    view.stopLoading();
                    view.destroy();
                } catch (Throwable ignored) { }
            }
        });
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        Log.d(TAG, "solve " + host + " -> " + (solved ? "ok" : "fail") + " in "
            + (SystemClock.elapsedRealtime() - started) + " ms");
        return solved;
    }
}
