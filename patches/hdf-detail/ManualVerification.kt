package com.kraptor

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
        if (panel.findViewById<View>(id("result_reload_connectionerror")) == null) return
        if (panel.findViewWithTag<View>(buttonTag) != null) return

        val button = Button(activity).apply {
            tag = buttonTag
            text = "Sitede doğrula"
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                target(error.text.toString(), origin)?.let { open(activity, it) }
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

    private fun open(activity: Activity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(activity)
                .setTitle("Tarayıcı gerekli")
                .setMessage("Bu sayfayı açmak için bir tarayıcı gerekiyor. TV Bro’yu kurduktan sonra buraya dönüp Sitede doğrula’ya basabilirsin.")
                .setNegativeButton("Kapat", null)
                .setPositiveButton("Google Play’de aç") { _, _ ->
                    val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.phlox.tvwebbrowser"))
                        .setPackage("com.android.vending")
                    try {
                        activity.startActivity(store)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(activity, "Uygulama mağazandan bir tarayıcı kurabilirsin.", Toast.LENGTH_LONG).show()
                    }
                }.show()
        }
    }
}
