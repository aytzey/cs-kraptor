package com.kraptor

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import java.net.URI

object ManualVerification {
    const val message = "HDFilmCehennemi sayfaya erişim izni vermedi (403)."
    private const val buttonTag = "com.kraptor.hdf.manualVerification"

    private fun allowed(url: String, origin: String): Boolean = try {
        val page = URI(url)
        val site = URI(origin)
        page.scheme == "https" && site.scheme == "https" && site.host != null &&
            page.host.equals(site.host, ignoreCase = true) && page.rawUserInfo == null &&
            site.rawUserInfo == null && page.port == site.port
    } catch (_: Exception) { false }

    fun target(text: String, origin: String): String? {
        val url = text.substringBefore('\n')
        return url.takeIf { text.substringAfter('\n', "").contains(message) && allowed(it, origin) }
    }

    fun unavailable(origin: String): ErrorLoadingException {
        CommonActivity.activity?.let { activity ->
            activity.runOnUiThread { attach(activity, origin) }
        }
        return ErrorLoadingException(message)
    }

    private fun attach(activity: Activity, origin: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        fun id(name: String) = activity.resources.getIdentifier(name, "id", activity.packageName)
        val error = activity.findViewById<TextView>(id("result_error_text")) ?: return
        val panel = error.parent as? LinearLayout ?: return
        val retry = panel.findViewById<View>(id("result_reload_connectionerror")) ?: return
        if (panel.findViewWithTag<View>(buttonTag) != null) return

        val button = Button(activity).apply {
            tag = buttonTag
            text = "Sitede doğrula"
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                target(error.text.toString(), origin)?.let { url ->
                    open(activity, url, origin) {
                        if (retry.isShown && target(error.text.toString(), origin) == url) retry.performClick()
                    }
                }
            }
        }
        fun refresh() {
            button.visibility = if (target(error.text.toString(), origin) != null) View.VISIBLE else View.GONE
        }
        refresh()
        panel.addView(button, panel.indexOfChild(error), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })
        error.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun open(activity: Activity, url: String, origin: String, retry: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || !allowed(url, origin)) return
        val web = try { WebView(activity) } catch (_: RuntimeException) {
            Toast.makeText(activity, "Android WebView açılamadı.", Toast.LENGTH_LONG).show()
            return
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !allowed(request.url.toString(), origin)

            @Deprecated("Android 6 navigation callback")
            override fun shouldOverrideUrlLoading(view: WebView, next: String): Boolean = !allowed(next, origin)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(URI(url).host)
            .setView(web)
            .setNegativeButton("Kapat", null)
            .setPositiveButton("CloudStream’de yeniden dene") { _, _ -> retry() }
            .create()
        dialog.setOnDismissListener {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        web.requestFocus()
        web.loadUrl(url)
    }
}
