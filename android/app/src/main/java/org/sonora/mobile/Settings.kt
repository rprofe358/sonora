@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sonora_mobile.AudioQuality
import uniffi.sonora_mobile.AudioSettings
import uniffi.sonora_mobile.EqualizerPreset
import uniffi.sonora_mobile.ProviderInfo
import uniffi.sonora_mobile.ScrobbleLink
import uniffi.sonora_mobile.ScrobbleService
import uniffi.sonora_mobile.SignInMethod
import uniffi.sonora_mobile.audioOutputs
import uniffi.sonora_mobile.engineVersion
import kotlin.math.abs

// ── Ajustes ────────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    var outputs by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { outputs = withContext(Dispatchers.IO) { runCatching { audioOutputs() }.getOrDefault(emptyList()) } }
    Column(Modifier.fillMaxSize()) {
        Bar("Ajustes")
        LazyColumn(Modifier.fillMaxSize()) {
            items(Store.providers, key = { it.slug }) { provider -> ProviderSection(provider) }
            item { SectionTitle("Ajustes") }
            item {
                ListItem(
                    headlineContent = { Text("Audio y ecualizador") },
                    leadingContent = { Icon(Icons.Filled.Tune, null) },
                    modifier = Modifier.focusRing().clickable { nav.navigate("settings/audio") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Scrobbling") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ShowChart, null) },
                    modifier = Modifier.focusRing().clickable { nav.navigate("settings/scrobble") },
                )
            }
            item { SectionTitle("Diagnóstico") }
            item { ListItem(headlineContent = { Text("Motor (Sonora)") }, trailingContent = { Text(engineVersion()) }) }
            items(outputs) { output ->
                ListItem(headlineContent = { Text(output) }, leadingContent = { Icon(Icons.Filled.Speaker, null) })
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun ProviderSection(provider: ProviderInfo) {
    if (provider.slug == "local") {
        LocalSection(provider)
        return
    }
    SectionTitle(provider.name)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val account = if (provider.signedIn) remember(provider) { Store.engine.account(provider.slug) } else null
        when {
            account != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (account.avatar != null) Cover(account.avatar, 40.dp, round = true)
                    else Icon(Icons.Filled.AccountCircle, null, Modifier.size(40.dp))
                    Text(account.name.ifEmpty { if (account.authenticated) "Sesión iniciada" else "Invitado" }, Modifier.weight(1f))
                }
                OutlinedButton(onClick = { Store.signOut(provider.slug) }) { Text("Cerrar sesión") }
            }
            Store.signingIn == provider.slug -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Iniciando sesión…", Modifier.weight(1f))
                    TextButton(onClick = { Store.cancelSignIn() }) { Text("Cancelar") }
                }
            }
            else -> provider.methods.forEach { method ->
                Button(
                    onClick = { Store.signIn(provider, method) },
                    enabled = Store.signingIn == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label(method, provider)) }
            }
        }
        val note = when (provider.slug) {
            "spotify" -> "Spotify normalmente pide una cuenta Premium para reproducir."
            "youtube" -> "Sin cuenta puedes buscar y escuchar; con tu cuenta de Google ves tu biblioteca y tus playlists."
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Las canciones guardadas en el teléfono: se buscan en Música, Descargas y otras carpetas. */
@Composable
private fun LocalSection(provider: ProviderInfo) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Store.openLocal()
        else Store.problem = "Sin el permiso de música, TK-Music no puede ver las canciones del teléfono."
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { folderPath(it) }
        if (uri != null && path == null) Store.problem = "No pude abrir esa carpeta. Prueba con una de la memoria del teléfono."
        path?.let { Store.addLocalFolder(it) }
    }
    fun scan() {
        if (Store.canReadMusic()) Store.openLocal() else permission.launch(Store.musicPermission)
    }

    SectionTitle("Música del teléfono")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Las canciones descargadas en el teléfono, para escucharlas aquí y ver su letra. Aparecen como «${provider.name}» en Inicio, Buscar y Biblioteca.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            Store.localBusy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("Buscando canciones…")
            }
            provider.signedIn -> {
                Text("Listo: tus canciones ya están en la app.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scan() }) { Text("Volver a buscar") }
                    OutlinedButton(onClick = { Store.closeLocal() }) { Text("Quitar") }
                }
            }
            else -> Button(onClick = { scan() }, modifier = Modifier.fillMaxWidth()) { Text("Buscar música en el teléfono") }
        }
        Text("Carpetas", style = MaterialTheme.typography.labelLarge)
        Store.localFolders.forEach { path ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    path.removePrefix("/storage/emulated/0/").ifEmpty { "Memoria interna" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { Store.removeLocalFolder(path) }) { Text("Quitar") }
            }
        }
        TextButton(onClick = { runCatching { folder.launch(null) } }) { Text("Agregar carpeta…") }
    }
}

/** La ruta de una carpeta elegida en el selector de Android (`primary:Music/Rock` → ruta). */
private fun folderPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(":")
    val inside = id.substringAfter(":", "")
    val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
    return if (inside.isEmpty()) root else "$root/$inside"
}

private fun label(method: SignInMethod, provider: ProviderInfo) = when (method) {
    SignInMethod.ANONYMOUS -> "Usar sin cuenta (invitado)"
    SignInMethod.SECRET -> "Iniciar sesión con Google"
    SignInMethod.DEFAULT -> "Iniciar sesión en ${provider.name}"
}

// ── Audio ──────────────────────────────────────────────────────────────────────────────────

private val frequencies = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

private val qualities = listOf(
    AudioQuality.LOW to "Baja",
    AudioQuality.NORMAL to "Normal",
    AudioQuality.HIGH to "Alta",
    AudioQuality.LOSSLESS to "Sin pérdida",
    AudioQuality.HI_RES to "Hi-Res",
)

@Composable
fun AudioScreen() {
    val settings = Store.audio ?: return
    val tv = LocalTv.current
    var presets by remember { mutableStateOf(listOf<EqualizerPreset>()) }
    var bands by remember(settings.bands) { mutableStateOf(settings.bands.padded()) }
    LaunchedEffect(Unit) { presets = Store.engine.equalizerPresets() }

    fun update(change: AudioSettings.() -> AudioSettings) = Store.applyAudio(settings.change())

    Column(Modifier.fillMaxSize()) {
        Bar("Audio", back = true)
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionTitle("Reproducción") }
            item {
                Choice("Calidad", qualities.first { it.first == settings.quality }.second, qualities.map { it.second }) { index ->
                    update { copy(quality = qualities[index].first) }
                }
            }
            item { Toggle("Igualar el volumen entre canciones", settings.normalisation) { update { copy(normalisation = it) } } }
            item { Toggle("Pasar sin silencios entre canciones", settings.gapless) { update { copy(gapless = it) } } }
            item {
                Note("Cada servicio da la calidad más cercana que tenga a la elegida. Cambiar estos ajustes reinicia la canción donde iba.")
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item { SectionTitle("Ecualizador") }
            item { Toggle("Ecualizador", settings.equalizer) { update { copy(equalizer = it) } } }
            item {
                val current = presets.firstOrNull { matches(it.gains, settings.bands) }?.name ?: "Personalizado"
                Choice("Ajuste predefinido", current, presets.map { it.name }) { index ->
                    update { copy(bands = presets[index].gains, equalizer = true) }
                }
            }
            items(10) { band ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(frequencies[band], style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
                    if (tv) {
                        // con el control, una barra atraparía las flechas: botones de medio decibelio
                        fun step(by: Double) {
                            bands = bands.toMutableList().also { it[band] = (it[band] + by).coerceIn(-12.0, 12.0) }
                            update { copy(bands = bands) }
                        }
                        IconButton(onClick = { step(-0.5) }, enabled = settings.equalizer) { Icon(Icons.Filled.Remove, "Bajar") }
                        LinearProgressIndicator(
                            progress = { ((bands[band] + 12) / 24).toFloat() },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { step(0.5) }, enabled = settings.equalizer) { Icon(Icons.Filled.Add, "Subir") }
                    } else {
                        Slider(
                            value = bands[band].toFloat(),
                            onValueChange = { value ->
                                bands = bands.toMutableList().also { it[band] = (Math.round(value * 2) / 2.0) }
                            },
                            onValueChangeFinished = { update { copy(bands = bands) } },
                            valueRange = -12f..12f,
                            enabled = settings.equalizer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text("%+.1f".format(bands[band]), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                }
            }
            item { Note("Diez bandas en decibelios, de graves (arriba) a agudos (abajo).") }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

private fun List<Double>.padded() = if (size >= 10) take(10) else this + List(10 - size) { 0.0 }

private fun matches(a: List<Double>, b: List<Double>) = a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) < 0.1 }

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.focusRing().clickable { onChange(!checked) },
    )
}

@Composable
private fun Choice(title: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.focusRing().clickable(enabled = options.isNotEmpty()) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    open = false
                    onPick(index)
                })
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// ── Scrobbling ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ScrobbleScreen() {
    val nav = LocalNav.current
    var services by remember { mutableStateOf(listOf<ScrobbleService>()) }
    val entry = nav.currentBackStackEntry
    LaunchedEffect(entry) { services = Store.engine.scrobbleServices() }
    Column(Modifier.fillMaxSize()) {
        Bar("Scrobbling", back = true)
        LazyColumn(Modifier.fillMaxSize()) {
            items(services, key = { it.id }) { service ->
                ListItem(
                    headlineContent = { Text(service.name) },
                    trailingContent = {
                        if (service.linked) {
                            Text(
                                if (!service.enabled) "Pausado" else service.accountName.ifEmpty { "Vinculado" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.focusRing().clickable { nav.navigate("settings/scrobble/${Uri.encode(service.id)}") },
                )
            }
            item {
                Note("Una canción cuenta al escucharla la mitad o cuatro minutos, y nunca si dura menos de treinta segundos. En modo «Solo controlar» anota el PC, no el teléfono.")
            }
        }
    }
}

@Composable
fun ScrobbleServiceScreen(id: String) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf(Store.engine.scrobbleServices().firstOrNull { it.id == Uri.decode(id) }) }
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }

    val current = service ?: return
    fun reload() {
        service = Store.engine.scrobbleServices().firstOrNull { it.id == current.id }
    }

    Column(Modifier.fillMaxSize()) {
        Bar(current.name, back = true)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (current.linked) {
                Text("Cuenta: ${current.accountName.ifEmpty { "vinculada" }}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Anotar lo que escucho", Modifier.weight(1f))
                    Switch(checked = current.enabled, onCheckedChange = {
                        Store.engine.scrobbleSetEnabled(current.id, it)
                        reload()
                    })
                }
                OutlinedButton(onClick = {
                    Store.engine.scrobbleDisconnect(current.id)
                    nav.popBackStack()
                }) { Text("Desvincular") }
                return@Column
            }
            val secretField = PasswordVisualTransformation()
            when (current.link) {
                ScrobbleLink.BROWSER -> Text("Pulsa «Vincular» y aprueba el acceso en la página que se abre.")
                ScrobbleLink.KEYS -> {
                    OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, label = { Text("Secreto compartido") }, singleLine = true,
                        visualTransformation = secretField, modifier = Modifier.fillMaxWidth())
                    Note("Después de pulsar «Vincular» se abre Last.fm para aprobar el acceso.")
                }
                ScrobbleLink.TOKEN -> OutlinedTextField(token, { token = it }, label = { Text("Token de usuario") }, singleLine = true,
                    visualTransformation = secretField, modifier = Modifier.fillMaxWidth())
                ScrobbleLink.SERVER -> {
                    OutlinedTextField(server, { server = it }, label = { Text("Dirección del servidor") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("API key del servidor") }, singleLine = true,
                        visualTransformation = secretField, modifier = Modifier.fillMaxWidth())
                }
            }
            current.signup?.let { signup ->
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(signup))) }) {
                    Text(if (current.link == ScrobbleLink.KEYS) "Crear una API key en Last.fm" else "Dónde consigo esto")
                }
            }
            val ready = when (current.link) {
                ScrobbleLink.BROWSER -> true
                ScrobbleLink.KEYS -> key.isNotEmpty() && secret.isNotEmpty()
                ScrobbleLink.TOKEN -> token.isNotEmpty()
                ScrobbleLink.SERVER -> server.isNotEmpty() && key.isNotEmpty()
            }
            Button(
                onClick = {
                    linking = true
                    scope.launch {
                        val done = Store.linkScrobble(current.id, key, secret, token, server)
                        linking = false
                        if (done) nav.popBackStack() else reload()
                    }
                },
                enabled = ready && !linking,
            ) {
                if (linking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (linking) "Vinculando…" else "Vincular")
            }
        }
    }
}
