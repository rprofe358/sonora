package org.sonora.mobile

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.sonora_mobile.SessionInfo

/**
 * Sonora Session: escuchar lo mismo a la vez que otras apps de Sonora, como la del PC, o
 * controlarlas desde aquí sin que suene el teléfono.
 */
@Composable
fun SessionScreen() {
    Column(Modifier.fillMaxSize()) {
        Bar("Sesión")
        SessionContent(Modifier.fillMaxSize())
    }
}

/** Unirse, iniciar o ver la sesión en curso. También se muestra en el reproductor. */
@Composable
fun SessionContent(modifier: Modifier, slug: String? = null) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val session = Store.session
        if (session != null) InSession(session) else Outside(slug)
    }
}

@Composable
private fun Outside(slug: String?) {
    var code by rememberSaveable { mutableStateOf("") }
    var remote by rememberSaveable { mutableStateOf(false) }
    Text(
        "Escucha lo mismo a la vez que otras apps de Sonora, como la del PC. Una persona inicia la sesión y comparte su código de 6 dígitos.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Unirse con un código", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text("Código de 6 dígitos") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Solo controlar (no suena en este teléfono)", Modifier.weight(1f))
        Switch(checked = remote, onCheckedChange = { remote = it })
    }
    Text(
        "Con «Solo controlar» el teléfono es un control remoto: lo que pongas, pauses o adelantes aquí suena en el PC.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { Store.joinSession(code, remote) }, enabled = code.length == 6 && !Store.sessionBusy) { Text("Unirse") }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Iniciar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (slug == null) ProviderPicker()
    val from = slug ?: Store.selected
    OutlinedButton(
        onClick = { Store.startSession(from) },
        enabled = Store.signedIn.any { it.slug == from } && !Store.sessionBusy,
    ) { Text("Iniciar una sesión en ${providerName(from)}") }
    Text(
        "La sesión suena desde ese servicio; los demás necesitan haber iniciado sesión en él.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (Store.sessionBusy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Conectando…")
        }
    }
}

@Composable
private fun InSession(session: SessionInfo) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Código", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(spaced(session.code), fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Únete a mi Sonora Session con el código ${session.code}")
            context.startActivity(Intent.createChooser(share, "Compartir código"))
        }) {
            Icon(Icons.Filled.Share, null)
            Spacer(Modifier.size(6.dp))
            Text("Compartir código")
        }
    }
    if (session.reconnecting) Text("Reconectando…", color = MaterialTheme.colorScheme.error)
    Text("Servicio: ${providerName(session.provider)}")
    if (session.remote) {
        Text("Solo controlas: la música suena en los otros dispositivos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    Text("Personas (${session.members.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    session.members.forEach { member ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(member.image, 36.dp, round = true)
            Text(if (member.you) "${member.name} (tú)" else member.name, Modifier.weight(1f))
            if (member.host) Text("Anfitrión", style = MaterialTheme.typography.labelSmall)
            if (session.hosting && !member.you) {
                TextButton(onClick = { Store.engine.sessionKick(member.id) }) { Text("Sacar") }
            }
        }
    }
    HorizontalDivider()
    Button(onClick = { Store.engine.sessionLeave() }) {
        Text(if (session.hosting) "Terminar la sesión" else "Salir de la sesión")
    }
}

private fun providerName(slug: String) = Store.providers.firstOrNull { it.slug == slug }?.name ?: slug

/** `123 456`, más fácil de dictar. */
private fun spaced(code: String) = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code
