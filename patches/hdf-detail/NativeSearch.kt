package com.kraptor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import java.net.URI
import java.net.URLEncoder

object NativeSearch {
    const val message = "HDFilmCehennemi sayfaya erişim izni vermedi (403)."
    private const val buttonTag = "com.kraptor.hdf.nativeSearch"

    data class Suggestion(val originalUrl: String, val provider: String, val url: String, val title: String, val year: Int) {
        val label get() = "$provider · $title ($year)"
    }
    @Volatile private var lastSuggestion: Suggestion? = null

    private fun allowed(url: String, origin: String): Boolean = try {
        val page = URI(url)
        val site = URI(origin)
        page.scheme == "https" && site.scheme == "https" && site.host != null &&
            page.host.equals(site.host, ignoreCase = true) && page.rawUserInfo == null &&
            site.rawUserInfo == null && page.port == site.port
    } catch (_: Exception) { false }

    fun target(text: String, origin: String): String? {
        val url = text.substringBefore('\n')
        if (!text.substringAfter('\n', "").contains(message) || !allowed(url, origin)) return null
        return PeerPlayback.searchTitle(url)
    }

    fun unavailable(origin: String, suggestion: Suggestion? = null): ErrorLoadingException {
        if (suggestion != null) lastSuggestion = suggestion
        CommonActivity.activity?.let { activity ->
            activity.runOnUiThread { attach(activity, origin) }
        }
        return ErrorLoadingException(if (suggestion == null) message else "$message\n${suggestion.label}")
    }

    private fun attach(activity: Activity, origin: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        fun id(name: String) = activity.resources.getIdentifier(name, "id", activity.packageName)
        val error = activity.findViewById<TextView>(id("result_error_text")) ?: return
        val panel = error.parent as? LinearLayout ?: return
        if (panel.findViewById<View>(id("result_reload_connectionerror")) == null) return
        if (panel.findViewWithTag<View>(buttonTag) != null) return

        val button = Button(activity).apply {
            tag = buttonTag
            text = "CloudStream’de başka kaynak aç"
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                val shown = error.text.toString()
                target(shown, origin)?.let { title ->
                    val peer = lastSuggestion?.takeIf { it.originalUrl == shown.substringBefore('\n') && shown.endsWith(it.label) }
                    open(activity, title, peer)
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

    private fun open(activity: Activity, title: String, peer: Suggestion?) {
        fun encode(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val uri = if (peer == null) Uri.parse("cloudstreamsearch://" + URLEncoder.encode(title, "UTF-8"))
                  else Uri.parse("csshare:" + encode(peer.provider) + "?" + encode(peer.url))
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(activity.packageName))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "CloudStream aramasına şu adı yaz: $title", Toast.LENGTH_LONG).show()
        }
    }
}
