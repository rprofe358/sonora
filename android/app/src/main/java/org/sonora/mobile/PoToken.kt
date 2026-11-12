package org.sonora.mobile

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import uniffi.sonora_mobile.PoTokenReply
import uniffi.sonora_mobile.potokenClosed
import uniffi.sonora_mobile.potokenCookie
import uniffi.sonora_mobile.potokenFailed
import uniffi.sonora_mobile.potokenGive
import uniffi.sonora_mobile.potokenPage
import uniffi.sonora_mobile.potokenReply
import uniffi.sonora_mobile.potokenScript
import uniffi.sonora_mobile.potokenWanted

/**
 * Acuña los tokens de origen que YouTube pide para algunos streams, en una vista web oculta,
 * igual que la app de escritorio. La vista se monta (invisible) en la pantalla principal para
 * que Android no frene su JavaScript.
 */
class PoTokenMinter {
    var view by mutableStateOf<WebView?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val cookie = potokenCookie()
    private var warm = false
    private var asked: Pair<String, Long>? = null
    private var used = 0L

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 250)
        }
    }

    fun start() {
        handler.post(tick)
    }

    private fun now() = System.currentTimeMillis()

    private fun step() {
        val web = view
        if (web == null) {
            potokenWanted()?.let { open(it) }
            return
        }
        val pending = asked
        when {
            pending != null -> poll(pending)
            now() - used > LINGER -> close()
            else -> potokenWanted()?.let { binding ->
                web.evaluateJavascript("window.sonoraPoToken && window.sonoraPoToken(${JSONObject.quote(binding)})", null)
                asked = binding to now()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(binding: String) {
        val web = WebView(Store.app)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        val script = potokenScript()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        } else {
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(script, null)
                }
            }
        }
        web.loadUrl(potokenPage(binding))
        view = web
        warm = false
        asked = binding to now()
        used = now()
    }

    private fun poll(pending: Pair<String, Long>) {
        val (binding, since) = pending
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com/") ?: ""
        val value = cookies.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$cookie=") }
            ?.substringAfter("=")
        when (val reply = value?.let { potokenReply(it, binding) }) {
            is PoTokenReply.Minted -> {
                potokenGive(binding, reply.token)
                warm = true
                asked = null
                used = now()
            }
            is PoTokenReply.Failed -> fail()
            null -> {
                val patience = if (warm) ANSWER else PATIENCE
                if (now() - since > patience) fail()
            }
        }
    }

    private fun fail() {
        close()
        potokenFailed()
    }

    private fun close() {
        val old = view
        view = null
        // se destruye después de que la pantalla la suelte
        handler.postDelayed({
            old?.stopLoading()
            old?.destroy()
        }, 500)
        asked = null
        warm = false
        potokenClosed()
    }

    private companion object {
        /** Cuánto tiene una página nueva para pasar la verificación de Google y responder. */
        const val PATIENCE = 45_000L
        /** Cuánto tiene una página ya verificada para responder otra petición. */
        const val ANSWER = 10_000L
        /** Cuánto queda abierta la página tras su última respuesta. */
        const val LINGER = 20_000L
    }
}
