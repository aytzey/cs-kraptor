package com.kraptor

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.CommonActivity
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Solves the Cloudflare inline challenge with an offscreen WebView, the way a
 * real browser would, and keeps the clearance cookie for later requests.
 * Everything is best effort: when the WebView cannot run, the original
 * interceptor keeps its previous behaviour.
 */
object CfSolver {
    private const val TAG = "KraptorCf"
    private const val COOKIE_PREFIX = "cf_"
    private const val PREFS = "kraptor_cloudflare"
    private const val MAX_AGE_MS = 20 * 60 * 1000L
    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 400L

    private class Session(val cookies: String, val userAgent: String, val savedAt: Long)

    private val sessions = HashMap<String, Session>()
    private val locks = HashMap<String, Any>()

    private fun lock(host: String): Any = synchronized(locks) { locks.getOrPut(host) { Any() } }

    private fun prefs(): android.content.SharedPreferences? = try {
        CommonActivity.activity?.getSharedPreferences(PREFS, 0)
    } catch (_: Exception) { null }

    private fun read(host: String): Session? {
        synchronized(sessions) { sessions[host] }?.let {
            return it.takeIf { SystemClock.elapsedRealtime() - it.savedAt < MAX_AGE_MS }
        }
        val raw = prefs()?.getString(COOKIE_PREFIX + host, null) ?: return null
        val parts = raw.split('\u0000')
        if (parts.size != 3) return null
        val saved = Session(parts[0], parts[1], parts[2].toLongOrNull() ?: return null)
        if (SystemClock.elapsedRealtime() - saved.savedAt >= MAX_AGE_MS) return null
        synchronized(sessions) { sessions[host] = saved }
        return saved
    }

    private fun store(host: String, session: Session) {
        synchronized(sessions) { sessions[host] = session }
        prefs()?.edit()?.putString(
            COOKIE_PREFIX + host,
            session.cookies + '\u0000' + session.userAgent + '\u0000' + session.savedAt
        )?.apply()
    }

    private fun forget(host: String) {
        synchronized(sessions) { sessions.remove(host) }
        prefs()?.edit()?.remove(COOKIE_PREFIX + host)?.apply()
    }

    private fun hostOf(request: Request): String? = try {
        request.url.host.takeIf { it.isNotBlank() && request.url.isHttps }
    } catch (_: Exception) { null }

    private fun inject(request: Request, session: Session): Request {
        val builder = request.newBuilder()
        if (request.header("Cookie").isNullOrBlank()) builder.header("Cookie", session.cookies)
        if (session.userAgent.isNotBlank()) builder.header("User-Agent", session.userAgent)
        return builder.build()
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        if (response.header("cf-mitigated")?.equals("challenge", true) == true) return true
        return try {
            val body = response.peekBody(300_000).string()
            body.contains("Just a moment") || body.contains("cf_chl_opt") ||
                body.contains("challenge-platform") || body.contains("_cf_chl")
        } catch (_: Exception) { false }
    }

    @JvmStatic
    fun intercept(chain: Interceptor.Chain, original: Interceptor): Response {
        val request = chain.request()
        val host = hostOf(request)
        if (host == null) return original.intercept(chain)

        read(host)?.let { session ->
            val response = chain.proceed(inject(request, session))
            if (!isChallenge(response)) return response
            response.close()
            forget(host)
        }

        val response = original.intercept(chain)
        if (!isChallenge(response)) return response
        response.close()

        synchronized(lock(host)) {
            read(host)?.let { return chain.proceed(inject(request, it)) }
            val solved = solve(request.url.toString(), host)
            if (solved != null) {
                store(host, solved)
                val retried = chain.proceed(inject(request, solved))
                if (!isChallenge(retried)) return retried
                retried.close()
                forget(host)
            }
            return chain.proceed(request)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solve(url: String, host: String): Session? {
        val activity = CommonActivity.activity ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val latch = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null
        var session: Session? = null
        var destroyed = false

        fun finish() {
            if (destroyed) return
            destroyed = true
            latch.countDown()
        }

        fun cookieHeader(): String? = try {
            CookieManager.getInstance().getCookie("https://$host/")
        } catch (_: Exception) { null }

        fun tapCenter(view: WebView) {
            try {
                val now = SystemClock.uptimeMillis()
                val x = view.width / 2f
                val y = view.height / 2f
                view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0))
            } catch (_: Exception) { }
        }

        main.post {
            if (activity.isFinishing || activity.isDestroyed) { finish(); return@post }
            val view = try { WebView(activity) } catch (_: Exception) { finish(); return@post }
            web = view
            view.setBackgroundColor(Color.TRANSPARENT)
            view.alpha = 0.02f
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                @Suppress("DEPRECATION")
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) { pageFinished(host, ::finish) }
            }
            val params = FrameLayout.LayoutParams(480, 720).apply { gravity = Gravity.TOP or Gravity.START }
            root.addView(view, params)
            view.requestFocus()
            view.loadUrl(url)
        }

        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var tick = 0
        while (SystemClock.elapsedRealtime() < deadline && !latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            val cookies = cookieHeader()
            val view = web
            val ua = try { view?.settings?.userAgentString } catch (_: Exception) { null }
            if (cookies != null && cookies.contains("cf_clearance") && ua != null) {
                session = Session(cookies, ua, SystemClock.elapsedRealtime())
                val current = latch
                main.post {
                    web?.stopLoading()
                    finish()
                }
                current.await(2, TimeUnit.SECONDS)
                break
            }
            tick++
            if (tick % 6 == 0) {
                main.post {
                    val v = web ?: return@post
                    tapCenter(v)
                    v.evaluateJavascript(
                        "(function(){var e=document.querySelector('input[type=checkbox]');" +
                            "if(e){e.click();}var s=document.querySelector('#challenge-stage');" +
                            "if(s){s.click();}})()", null
                    )
                }
            }
        }

        val cookies = cookieHeader()
        val view = web
        val ua = try { view?.settings?.userAgentString } catch (_: Exception) { null }
        if (cookies != null && cookies.contains("cf_clearance") && ua != null) {
            session = Session(cookies, ua, SystemClock.elapsedRealtime())
        }

        main.post {
            val v = web ?: return@post
            try {
                (v.parent as? ViewGroup)?.removeView(v)
                v.stopLoading()
                v.destroy()
            } catch (_: Exception) { }
        }
        Log.d(TAG, "solve $host -> ${if (session == null) "fail" else "ok"}")
        return session
    }

    private fun pageFinished(host: String, finish: () -> Unit) {
        try {
            val cookies = CookieManager.getInstance().getCookie("https://$host/")
            if (cookies != null && cookies.contains("cf_clearance")) finish()
        } catch (_: Exception) { }
    }

    fun clear() {
        val hosts = synchronized(sessions) { sessions.keys.toList() }
        hosts.forEach(::forget)
        try { CookieManager.getInstance().removeAllCookies(null) } catch (_: Exception) { }
    }

    fun origin(url: String): String? = try { URI(url).host } catch (_: Exception) { null }
}
