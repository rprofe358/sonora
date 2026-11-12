@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import uniffi.sonora_mobile.Prompt

/** Google rechaza el inicio de sesión en vistas web que se presentan como tales. */
private const val CHROME =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/** Lo que se muestra mientras un proveedor pide algo durante el inicio de sesión. */
@Composable
fun PromptDialog(pending: PendingPrompt) {
    when (val prompt = pending.prompt) {
        is Prompt.OpenUrl -> WebDialog("Iniciar sesión", prompt.url, agent = null, external = true) { web ->
            web.loadUrl(prompt.url)
        }
        is Prompt.WebCookies -> CookieDialog(pending.id, prompt)
        is Prompt.ShowCode -> {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Escribe este código") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(prompt.code, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { open(context, prompt.url) }) {
                            Text("Abrir ${Uri.parse(prompt.url).host ?: prompt.url}")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { Store.cancelSignIn() }) { Text("Cancelar") } },
            )
        }
        is Prompt.ChooseAccount -> AlertDialog(
            onDismissRequest = { Store.cancelSignIn() },
            title = { Text("Elige la cuenta") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(prompt.options, key = { it.id }) { option ->
                        val detail = option.detail
                        ListItem(
                            headlineContent = { Text(option.name) },
                            supportingContent = if (detail != null) { { Text(detail) } } else null,
                            modifier = Modifier.focusRing().clickable { Store.answer(option.id) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Store.cancelSignIn() }) { Text("Cancelar") } },
        )
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Una vista web a pantalla completa, con cancelar y, si se quiere, abrir en el navegador. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebDialog(
    title: String,
    url: String,
    agent: String?,
    external: Boolean,
    setup: (WebView) -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = { Store.cancelSignIn() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { Store.cancelSignIn() }) { Icon(Icons.Filled.Close, "Cancelar") }
                    },
                    actions = {
                        if (external) {
                            IconButton(onClick = { open(context, url) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Abrir en el navegador")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = agent ?: CHROME
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        setup(this)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/**
 * Inicia sesión en una vista web limpia y, cuando aparece la cookie que prueba la sesión, envía
 * todas las del dominio como una cabecera `Cookie`.
 */
@Composable
private fun CookieDialog(id: Long, prompt: Prompt.WebCookies) {
    val cookies = CookieManager.getInstance()
    // como una sesión privada: sin cookies de antes
    remember(id) {
        cookies.removeAllCookies(null)
        cookies.flush()
        true
    }
    LaunchedEffect(id) {
        val hosts = listOfNotNull(
            "https://${prompt.domain}",
            "https://www.${prompt.domain}",
            "https://music.${prompt.domain}",
            Uri.parse(prompt.landing).host?.let { "https://$it" },
        ).distinct()
        while (true) {
            delay(500)
            val found = linkedMapOf<String, String>()
            for (host in hosts) {
                cookies.getCookie(host)?.split(";")?.forEach { pair ->
                    val clean = pair.trim()
                    val name = clean.substringBefore("=", "")
                    if (name.isNotEmpty() && name !in found) found[name] = clean.substringAfter("=", "")
                }
            }
            val proven = prompt.proof.any { found[it]?.isNotEmpty() == true }
            if (proven) {
                Store.answer(found.entries.joinToString("; ") { "${it.key}=${it.value}" })
                break
            }
        }
    }
    WebDialog("Iniciar sesión", prompt.url, agent = prompt.agent, external = false) { web ->
        web.loadUrl(prompt.url)
    }
}

